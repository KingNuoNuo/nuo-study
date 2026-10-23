package com.jinyu.study.feature.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 上传前的图片规整。
 *
 * 为什么必须有这一步（不是「优化」，是**必须**）：
 *  1. 契约写死了长边 1600px（见 openapi.yaml 的 `/captures`）；
 *  2. 手机主摄拍一张作业，JPEG 通常 8–15MB，而服务端
 *     `MAX_UPLOAD_BYTES = 12MB` —— 不压就直接吃 400001「文件超过 12 MB」。
 *     这类失败在家长看来就是「传不上去」，而他不会知道要先去改相机设置。
 *
 * ⚠️ 两处容易做错的地方：
 *  - **不能直接解码整张图**。50MP 的位图约 200MB，`decodeStream` 一把梭会 OOM。
 *    必须先 `inJustDecodeBounds` 只读尺寸，算好采样率再真正解码。
 *  - **必须处理 EXIF 旋转**。相机直出的照片常常是「像素横着存 + EXIF 标记要转 90°」，
 *    而 `BitmapFactory` 完全无视 EXIF。不处理的话，传到服务端的是一张躺倒的图，
 *    识别质量直接崩。`ImageDecoder`（API 28+）会自动应用 EXIF，所以优先用它。
 *    两台目标设备（平板 API 30 / 手机 API 36）都走得到这条路。
 */
object ImagePreparer {

    /** 契约要求的长边上限。 */
    private const val MAX_LONG_SIDE = 1600

    /** 88 是「作业本上的小字还看得清」与「体积可接受」之间的经验值。 */
    private const val JPEG_QUALITY = 88

    const val FILE_NAME = "homework.jpg"
    const val MIME = "image/jpeg"

    /**
     * 压好的图。
     *
     * ⚠️ [bytes] 会被**留在内存里直到下一次选图**（见 CaptureViewModel 的重试逻辑）：
     *    上传失败时家长点「重试」，靠的就是这份字节，不必回相册重新翻那张图。
     *    一张长边 1600 的 JPEG 约 150–400 KB，留一个不会有事。
     */
    class Prepared(val bytes: ByteArray, val width: Int, val height: Int) {

        /** 给家长看的一句话：「739×1600 · 146 KB」。 */
        fun describe(): String = "$width×$height · ${bytes.size / 1024} KB"
    }

    fun prepare(context: Context, uri: Uri): Prepared {
        val resolver = context.contentResolver
            ?: error("拿不到 ContentResolver")

        val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(context, uri)
        } else {
            decodeWithBitmapFactory(context, uri)
        }

        val scaled = scaleToLongSide(bitmap, MAX_LONG_SIDE)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)

        val w = scaled.width
        val h = scaled.height
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()

        return Prepared(out.toByteArray(), w, h)
    }

    /**
     * API 28+ 的首选路径：`ImageDecoder` 会自动应用 EXIF 方向，
     * 而且能从解码器层面直接给目标采样率，不用先解出大图。
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.P)
    private fun decodeWithImageDecoder(context: Context, uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            // ⚠️ 必须是软件位图：HARDWARE 位图不能 compress()。
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val long = max(info.size.width, info.size.height)
            if (long > 0) {
                decoder.setTargetSampleSize(sampleSizeFor(long))
            }
        }
    }

    /** API 26–27 的兜底。**代价是忽略 EXIF**（那两个版本上只能这样）。 */
    private fun decodeWithBitmapFactory(context: Context, uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        } ?: error("打不开这张图")

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(max(bounds.outWidth, bounds.outHeight))
        }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        } ?: error("解码失败")
    }

    /**
     * 采样率只降不精 —— 目标是让结果略**大于** [MAX_LONG_SIDE]，
     * 剩下的交给 [scaleToLongSide] 精确缩放。反过来（先缩到比目标小再放大）
     * 会丢掉小字笔画，作业本上的「默写第 3 段」这类小字就糊了。
     *
     * 注意 `BitmapFactory` 会把非 2 的幂向下取整到 2 的幂，所以这里直接按 2 倍递增。
     */
    private fun sampleSizeFor(longSide: Int): Int {
        var s = 1
        while (longSide / s > MAX_LONG_SIDE * 2) s *= 2
        return s
    }

    private fun scaleToLongSide(src: Bitmap, maxSide: Int): Bitmap {
        val long = max(src.width, src.height)
        if (long <= maxSide) return src
        val ratio = maxSide.toFloat() / long
        val w = (src.width * ratio).roundToInt().coerceAtLeast(1)
        val h = (src.height * ratio).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }
}
