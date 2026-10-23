package com.nuostudy.student.mirror

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.View
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.nuostudy.core.data.prefs.ServerConfigStore
import com.nuostudy.core.network.StudyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/**
 * 学生端「实时画面」镜像。
 *
 * 每 3 秒把平板**当前窗口**截一帧、压成 JPEG 传到家庭服务器；家长在手机或电脑的
 * 浏览器里打开 `http://<服务器>/debug/screen?token=<家庭令牌>` 就能实时看到。
 *
 * 为什么需要它：孩子拿着平板在客厅做题，家长在厨房 —— 想知道「他到底是在做题还是在
 * 刷别的」，让孩子念给你听不现实，让他自己截图也不会。这是测试期最省事的办法。
 *
 * ⚠️ 四个刻意的技术选择，动之前先读：
 *
 * 1. **不用 MediaProjection**。那需要家长在平板上点一次系统授权、常驻一条通知，
 *    Android 14+ 还要声明前台服务类型。而 [PixelCopy] 抓的是**自己 App 的窗口**，
 *    **不需要任何权限**。代价是抓不到系统弹窗、输入法候选框和桌面 —— 对「孩子在用
 *    App 干什么」这个需求已经够了。
 *
 * 2. **只在 App 前台时跑**（`repeatOnLifecycle(RESUMED)`）。孩子切到别的 App 或者
 *    锁屏，这里立刻停 —— 既是省电，也是不该越界：这个功能是「看 App」，
 *    不是「监控设备」。
 *
 * 3. **失败就丢帧，不重试、不退避**。丢一帧对「看画面」毫无影响，而重试队列会让
 *    这个小循环变得复杂，还可能把家里的小服务端堆满请求。
 *
 * 4. **降采样到 640 宽、JPEG q60**（约 30–60 KB）。原样 1280×800 的 PNG 有几百 KB，
 *    3 秒一帧在局域网上也能跑，但没必要 —— 看得清字就够了。
 *
 * 开关在学生端设置页（有家长口令挡着），存储见 [ServerConfigStore.mirrorEnabled]。
 */
@Singleton
class ScreenMirror @Inject constructor(
    private val api: StudyApi,
    private val store: ServerConfigStore,
) {

    /**
     * 挂到 Activity 上。**在 `onCreate` 里调用一次**，内部自己管生命周期：
     * 只有在 RESUMED 期间才抓帧，进后台自动停。
     */
    fun bind(activity: ComponentActivity) {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                // collectLatest：家长在设置页一关开关，正在跑的那个 while 立刻被取消。
                // 用 collect 的话它会把这轮 delay 睡完、再抓一帧，关不干净。
                store.mirrorEnabled.collectLatest { on ->
                    if (!on) return@collectLatest
                    while (true) {
                        // 单帧的任何异常都不该把这个循环打死 —— 它一死，
                        // 家长那边就是永远停在最后一帧，而且没有任何提示。
                        runCatching { oneFrame(activity) }
                        delay(INTERVAL_MS)
                    }
                }
            }
        }
    }

    private suspend fun oneFrame(activity: ComponentActivity) {
        val full = grab(activity) ?: return

        // ⚠️ recycle 必须放 finally。切后台 / 关开关时协程是在**挂起点**被取消的
        //    （withContext 切线程回来那一刻、或上传请求那一行），直接往下写
        //    `full.recycle()` 会在 CancellationException 抛出时被跳过 ——
        //    1280×800 ARGB_8888 的原图 ≈ 4MB，每漏一张就要等 GC 才回收，
        //    反复开关这个功能能明显看到内存曲线往上走。
        val jpeg: ByteArray? = try {
            withContext(Dispatchers.Default) { downscaleAndCompress(full) }
        } finally {
            if (!full.isRecycled) full.recycle()
        }

        if (jpeg == null) return

        val part = MultipartBody.Part.createFormData(
            "file", "screen.jpg", jpeg.toRequestBody(JPEG_MIME.toMediaType()),
        )
        // 服务端只写内存，不需要在这里等结果。返回体也没人看。
        api.uploadDebugFrame(part, label = LABEL)
    }

    /**
     * 抓一帧。**必须回到主线程** —— PixelCopy 要求在拥有该窗口的线程上发起，
     * `decorView.draw()` 更是只能在主线程画。
     */
    private suspend fun grab(activity: ComponentActivity): Bitmap? = withContext(Dispatchers.Main) {
        val window = activity.window
        val decor: View = window.decorView
        val w = decor.width
        val h = decor.height
        // 窗口还没量出来（首帧之前）或已经销毁。跳过，3 秒后再来。
        if (w <= 0 || h <= 0) return@withContext null

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

        val copied = suspendCancellableCoroutine<Boolean> { cont ->
            try {
                PixelCopy.request(window, bmp, { result ->
                    // ⚠️ 协程可能已经被取消（切后台 / 关开关），此时再 resume 会抛
                    //    「Already resumed」。isActive 这个判断不能省。
                    if (cont.isActive) cont.resume(result == PixelCopy.SUCCESS)
                }, Handler(Looper.getMainLooper()))
            } catch (e: Throwable) {
                if (cont.isActive) cont.resume(false)
            }
            // 取消时这里**故意不 recycle bmp**：PixelCopy.request 是异步的，
            // 取消的那一刻它可能还在往 bmp 里写，此刻 recycle 会让它的回调踩到
            // 已回收的像素缓冲（比多占 4MB 糟得多）。这一路丢掉的 bmp 交给 GC。
        }
        if (copied) return@withContext bmp

        // PixelCopy 偶发失败（窗口正在切换、被对话框遮挡）。
        // 退回「把 decorView 画进画布」：抓不到 SurfaceView 一类的东西，
        // 但学生端全是普通 Compose 内容，够用。
        bmp.eraseColor(Color.BLACK)
        decor.draw(Canvas(bmp))
        bmp
    }

    private fun downscaleAndCompress(full: Bitmap): ByteArray? {
        val scaled = if (full.width > TARGET_WIDTH) {
            val ratio = TARGET_WIDTH.toFloat() / full.width
            Bitmap.createScaledBitmap(
                full,
                TARGET_WIDTH,
                (full.height * ratio).roundToInt().coerceAtLeast(1),
                true,
            )
        } else {
            full
        }

        val out = ByteArrayOutputStream()
        val ok = scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        if (scaled !== full) scaled.recycle()
        return if (ok) out.toByteArray() else null
    }

    private companion object {
        const val INTERVAL_MS = 3_000L

        /** 目标宽度。1280 的平板缩到一半，作业本上的字仍然看得清。 */
        const val TARGET_WIDTH = 640

        /** 调试画面不需要画质，60 足够看清界面结构。 */
        const val QUALITY = 60

        const val JPEG_MIME = "image/jpeg"

        /**
         * 服务端网页上显示的设备名。
         *
         * 用 `Build.MODEL` 而不是写死「孩子端」：将来家里要是多一台设备，
         * 网页上分不清哪帧是哪台 —— 而这一切的起因就是「想看看那台平板上是什么」。
         */
        val LABEL = "孩子端 ${Build.MODEL}"
    }
}
