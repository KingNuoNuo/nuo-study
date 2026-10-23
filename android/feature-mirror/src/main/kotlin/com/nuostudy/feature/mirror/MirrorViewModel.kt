package com.nuostudy.feature.mirror

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuostudy.core.data.prefs.ServerConfigStore
import com.nuostudy.core.network.FrameInfoDto
import com.nuostudy.core.network.MirrorSource
import com.nuostudy.core.network.StudyApi
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import javax.inject.Inject

/**
 * 家长端「实时画面」：把孩子端每 3 秒传上来的那帧画面拉下来显示。
 *
 * 数据流：孩子端 PixelCopy 抓自己的窗口 → POST 到家庭服务端 → 本 ViewModel
 * 每 [POLL_INTERVAL_MS] 毫秒 GET 最新一帧。**中间的服务器是必须的** ——
 * 两台设备之间没有直连通道，也因为有了它，家长端不用和孩子的平板在同一网段
 * 才能看（在家里同一个 WiFi 下即可）。
 *
 * ⚠️ 四个刻意的设计，动之前先读：
 *
 * 1. **轮询循环不在这里，在 UI 层**（见 [MirrorScreen] 的 `LaunchedEffect`）。
 *    这不是随手放错：本页是 `ParentRoot` 里的一个 Tab，切走时 composable 离开
 *    组合，`LaunchedEffect` 的协程**立刻被取消**，轮询自然就停了。
 *    如果循环写在 `viewModelScope` 里，`hiltViewModel()` 在没有导航回退栈的
 *    情况下会挂到 Activity 上 —— ViewModel 不会随 Tab 切换销毁，
 *    于是家长切到「设置」页后，这个循环会**一直在后台拉图**，谁也看不出来。
 *
 * 2. **暂停状态放在这里而不是 UI 的 `remember`**：暂停后切到别的 Tab 再回来，
 *    家长的意图应当保持（不要自作主张继续拉）。切 Tab 本身停轮询、回来接着暂停。
 *
 * 3. **一帧失败不弹错、不清空上一帧**。这是每 1.5 秒一次的循环：
 *    家里路由抖一下、服务端重启，都会让某一帧失败。若这时把画面清掉，
 *    家长看到的就是「一闪一闪的黑屏」，而不是「画面卡在最后一帧」。
 *    后者才是有信息量的：至少他知道孩子最后在干什么。
 *
 * 4. **图片字节在这里就解码成 [ImageBitmap]**，不留给 UI。BMP 解码是纯 CPU
 *    工作（640×400 约 1 MB），放在 UI 层做会让每一帧都掉一下帧。
 */
@HiltViewModel
class MirrorViewModel @Inject constructor(
    private val api: StudyApi,
    private val store: ServerConfigStore,
) : ViewModel() {

    data class UiState(
        /** 最近成功拿到的那一帧。**失败时不清空**（见类注释第 3 条）。 */
        val image: ImageBitmap? = null,
        /** 学生端自填的设备名，形如「孩子端 GM260200701」。 */
        val label: String? = null,
        val deviceId: String? = null,
        /** 服务端自增帧号。**用它判断画面有没有在动** —— 比时间戳可靠。 */
        val seq: Int = 0,
        val kb: Double = 0.0,
        /** 本地拿到这一帧的时刻（`SystemClock` 不适用：要跨进程比较的是墙钟）。 */
        val receivedAtMs: Long = 0L,
        /** 服务端在**它收到这一帧时**算的帧龄（秒）。叠加本地停留时间才是真实滞后。 */
        val serverAgeSec: Double? = null,
        val error: String? = null,
        /** 连续失败次数。第一次失败不算数 —— 一次抖动没必要吓家长。 */
        val failures: Int = 0,
        val paused: Boolean = false,
        val polling: Boolean = false,
        /**
         * 服务端整屏抓取通道是否配置好了（来自 state 接口）。null ＝ 还没问到。
         *
         * 拿它是为了**在切换之前就能给提示**：否则家长切到「整屏抓取」只会看到
         * 一个 503 转述，而真正该做的是去 NAS 上配 ADB_TARGET。
         */
        val adbEnabled: Boolean? = null,
    ) {
        /** 从「服务端收到这一帧」到现在过了多久。用于状态点与「静止 X 秒」。 */
        fun ageSec(nowMs: Long): Double =
            (serverAgeSec ?: 0.0) + ((nowMs - receivedAtMs).coerceAtLeast(0L) / 1000.0)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * 当前正在看的采集通道。
     *
     * ⚠️ **刻意不放进 [UiState]**：它一变就要顺带做「把上一张图丢掉」这个动作，
     *    而 state 是个到处 `copy()` 的数据类，那个副作用迟早会被某次 copy 漏掉。
     *    单独一个 Flow，UI 各 collect 各的，Compose 会自己把两次重组合起来。
     */
    private val _source = MutableStateFlow(MirrorSource.APP)
    val source: StateFlow<String> = _source.asStateFlow()

    /**
     * 家长自己点过切换没有。
     *
     * ⚠️ 存在的理由是一个几毫秒的竞态：`init` 里的 collect 要等 DataStore
     *    第一次读盘完成才会 emit。如果家长赶在这之前就点了「整屏抓取」，
     *    随后到达的**存储里的旧值**会把他的选择盖回去 —— 现象是「点了没反应」，
     *    而且再点也一样，因为存储里从此一直是被盖回去的那个值。
     */
    private var sourceTouched = false

    init {
        viewModelScope.launch {
            store.mirrorSource.collect { saved -> if (!sourceTouched) _source.value = saved }
        }
    }

    fun setSource(source: String) {
        val s = MirrorSource.orDefault(source)
        if (_source.value == s) return
        sourceTouched = true
        _source.value = s
        // 换通道时必须**把上一张图丢掉**：两条通道的画面完全不同（一张是 App 的界面、
        // 一张是整个屏幕）。旧图留着，家长唯一的判断依据（屏幕上这张图）就会告诉他
        // 「切换没生效」—— 而实际上新通道只是还没出第一帧。
        _state.update {
            it.copy(image = null, label = null, deviceId = null, seq = 0, kb = 0.0,
                receivedAtMs = 0L, serverAgeSec = null, error = null, failures = 0)
        }
        viewModelScope.launch { store.setMirrorSource(s) }
    }

    /**
     * 串行闸门。手动「刷新」按钮与轮询循环是两个协程，可能同时进来 ——
     * 并发拉两帧除了浪费带宽，还会让「后到的旧帧」把新帧盖掉（肉眼是画面倒退）。
     */
    private val gate = Mutex()

    fun setPaused(paused: Boolean) {
        _state.update { it.copy(paused = paused) }
    }

    /**
     * 拉一帧 + 一次状态。**不抛异常**，所有失败都写进 [UiState.error]。
     *
     * 调用方只负责「隔一会儿再来一次」，不需要考虑错误分支 ——
     * 这类循环一旦在任何一层抛出，整个画面就永久停住且毫无提示。
     */
    suspend fun poll() = gate.withLock {
        val token = store.familyToken().orEmpty()
        if (token.isBlank()) {
            _state.update {
                it.copy(error = "还没有配置家庭令牌 —— 请到「设置」里填服务端地址与令牌。")
            }
            return@withLock
        }

        val src = _source.value
        _state.update { it.copy(polling = true) }

        // ---- 1) 元信息。拿不到不影响看图，所以单独 try 且不入错误态。
        var adbEnabled: Boolean? = null
        val meta: FrameInfoDto? = try {
            api.framesState(token).takeIf { it.isOk }?.data?.let { st ->
                adbEnabled = st.adb?.enabled
                // ⚠️ 必须按 source 挑，不能用 firstOrNull()：两条通道的帧混在**同一个
                //    倒序列表**里，取第一条会有一半概率拿到另一条通道的 label/seq。
                //    现象是「画面已经换了，但帧号和设备名没换」—— 看着像画面卡住。
                st.frames.firstOrNull { it.source == src }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }

        // ---- 2) 图片本体。
        //
        // ⚠️ **先去重，再下载**。孩子端 3 秒才出一帧，而这里 1.5 秒轮询一次 ——
        //    约一半的轮询拿到的是同一帧，却仍在走完整的 `bytes()` + 解 BMP（640×400
        //    ARGB ≈ 1MB）+ 造一个新 UiState（连带整屏重组）。
        //    帧号（seq）一样就说明画面没变：只更新「还在轮询」这个字段就返回。
        //    ⚠️ meta 为 null 时**不能**省 —— 没有帧号作依据，只能老实去取。
        val sameFrame = meta != null && meta.seq == _state.value.seq && _state.value.image != null
        if (sameFrame) {
            _state.update { it.copy(polling = false, adbEnabled = adbEnabled ?: it.adbEnabled) }
            return@withLock
        }

        try {
            val bytes = withContext(Dispatchers.IO) {
                // use{} 不能省：@Streaming 的响应体不缓冲，不关流会一直占着连接。
                api.latestFrame(token, deviceId = null, source = src).use { it.bytes() }
            }
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            // ⚠️ 这一帧是**上一个通道**的就丢掉。这一趟请求可能跑了 1 秒多，
            //    家长完全来得及在这中间点一下切换；不检查的话，setSource() 刚清空的
            //    画面会被这张旧图重新填上 —— 家长看到的正是「切换没生效」。
            if (_source.value != src) return@withLock

            if (bitmap == null) {
                _state.update {
                    it.copy(polling = false, failures = it.failures + 1,
                        error = "收到 ${bytes.size} 字节，但解不出图像。")
                }
            } else {
                _state.update {
                    it.copy(
                        image = bitmap.asImageBitmap(),
                        label = meta?.label,
                        deviceId = meta?.deviceId,
                        seq = meta?.seq ?: it.seq,
                        kb = meta?.kb ?: (bytes.size / 1024.0),
                        receivedAtMs = System.currentTimeMillis(),
                        serverAgeSec = meta?.ageSec,
                        error = null,
                        failures = 0,
                        polling = false,
                        adbEnabled = adbEnabled ?: it.adbEnabled,
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (_source.value != src) return@withLock
            _state.update {
                it.copy(polling = false, failures = it.failures + 1, error = describe(e),
                    adbEnabled = adbEnabled ?: it.adbEnabled)
            }
        }
    }

    /**
     * 把 HTTP 层的东西翻成家长能照着做的动作。
     *
     * ⚠️ 404 不是错误，是**还没开始**：服务端一帧都没收到过时返回它。
     *    这正是家长第一次打开这个页面最常见的情况（孩子端还没打开），
     *    所以文案必须指向「去检查什么」，而不是「请求失败」。
     */
    private fun describe(e: Throwable): String = when {
        // 503 只可能来自 adb 通道：服务端在「要 adb 帧，但压根没配」时回它。
        // 文案必须给出**下一步动作**，而不是复述服务端那句话。
        e is HttpException && e.code() == 503 ->
            "服务端的「整屏抓取」还没配置好：需要在 NAS 上放好 adb，并把 .env 里的 " +
                "ADB_TARGET 设成平板地址（形如 192.168.1.50:5555）。" +
                "现在先切回「App 内截图」就能正常看。"

        e is HttpException && e.code() == 404 && _source.value == MirrorSource.ADB ->
            "服务端还没抓到整屏。检查：平板是否还开着无线调试、NAS 能不能连上它" +
                "（平板重启后要重新执行一次 adb tcpip 5555）。"

        e is HttpException && e.code() == 404 ->
            "服务端还没有收到过画面。检查两件事：孩子端 App 是否正开着，以及孩子端「设置」里的「实时画面」是否打开。"

        e is HttpException && e.code() == 401 ->
            "家庭令牌不对（服务端返回 401）。两端的令牌要一致。"

        e is HttpException -> "服务端返回 HTTP ${e.code()}。"

        e is IOException -> "连不上服务端：${e.message ?: e.javaClass.simpleName}"

        else -> e.message ?: e.javaClass.simpleName
    }

    companion object {
        /**
         * 轮询间隔。孩子端 3 秒才出一帧，这里 1.5 秒取一次是**故意取密一点**的：
         * 间隔大于 3 秒时，一个帧的停留时间会随两个周期的相位漂移，
         * 表现为画面偶尔卡住两倍时长。密一点只是多几次请求（响应才几十 KB）。
         */
        const val POLL_INTERVAL_MS = 1_500L
    }
}
