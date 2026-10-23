package com.jinyu.study.feature.english.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * 麦克风录音 → 16k / 单声道 / 16bit WAV。
 *
 * ## 为什么不用 MediaRecorder
 *
 * MediaRecorder 输出的是容器格式（m4a/webm），还要服务端用 ffmpeg 解一遍。
 * 而**腾讯智聆要的就是 16k s16le PCM**，AudioRecord 天生给的就是它 ——
 * 直接录 PCM、自己套个 44 字节 WAV 头，服务端连转码都省了。
 *
 * ## 为什么用裸线程而不是协程
 *
 * `AudioRecord.read()` 是**阻塞调用**，拿不到数据就一直挂着。协程里放这种调用
 * 要么占着线程不放（等于没用协程），要么得自己写一套取消逻辑。
 * 一个 `while (running)` 的线程反而最清楚。
 *
 * ## 生命周期
 *
 * 一次录音 = [start] → [stop]（或 [cancel]）。**必须成对调用**，
 * 否则麦克风会一直被占着 —— 安卓上表现为「别的 App 录音没声音」，
 * 而且没有任何报错。UI 层用 pointerInput 的 press/release 保证这一点。
 */
class VoiceRecorder {

    companion object {
        const val SAMPLE_RATE = 16000

        /**
         * 最长录音。跟读句子最多 30 个词，正常 3~8 秒。
         *
         * 20 秒是「孩子忘了松手」的兜底：16k 单声道每秒 32KB，20 秒 = 640KB，
         * base64 之后 850KB —— 还能在一个请求体里传完。
         * 不设上限的话，一次误触就能把 2MB 的数据 POST 出去。
         */
        const val MAX_SECONDS = 20

        private const val BYTES_PER_SECOND = SAMPLE_RATE * 2
        private const val MAX_BYTES = BYTES_PER_SECOND * MAX_SECONDS
    }

    private var record: AudioRecord? = null
    private var worker: Thread? = null
    private val pcm = ByteArrayOutputStream(BYTES_PER_SECOND * 4)

    @Volatile
    private var running = false

    /** 是否正在录。UI 用它切换按钮态。 */
    val isRecording: Boolean get() = running

    /**
     * 开始录音。
     *
     * @return 成功返回 true；麦克风被占用 / 没有权限 / 设备不支持时返回 false
     *         （**不抛异常**：这条路径上失败太常见了，用返回值逼调用方处理）
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.w(TAG, "getMinBufferSize 失败：$minBuf（设备不支持 16k 单声道？）")
            return false
        }

        val rec = try {
            AudioRecord(
                // VOICE_RECOGNITION 而不是 MIC：它会走系统的语音增强（降噪/AGC），
                // 对跟读评分明显更友好 —— 用 MIC 时空调声、电扇声都会被当成发音。
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord 构造失败", e)
            return false
        }

        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "AudioRecord 未初始化（多半是没有 RECORD_AUDIO 权限或麦克风被占用）")
            rec.release()
            return false
        }

        pcm.reset()
        record = rec
        running = true
        try {
            rec.startRecording()
        } catch (e: Exception) {
            Log.w(TAG, "startRecording 失败", e)
            running = false
            rec.release()
            record = null
            return false
        }

        worker = Thread({
            Log.d(TAG, "录音线程启动")
            val buf = ByteArray(minBuf)
            while (running) {
                val n = try {
                    rec.read(buf, 0, buf.size)
                } catch (e: Exception) {
                    Log.w(TAG, "read 异常", e)
                    -1
                }
                if (n > 0) {
                    synchronized(pcm) {
                        if (pcm.size() + n <= MAX_BYTES) {
                            pcm.write(buf, 0, n)
                        } else {
                            // 到上限就停：超出的部分丢掉，但保留已经录到的
                            running = false
                        }
                    }
                } else if (n < 0) {
                    // ERROR_INVALID_OPERATION / ERROR_BAD_VALUE / ERROR_DEAD_OBJECT
                    running = false
                }
            }
        }, "voice-recorder").apply {
            isDaemon = true
            start()
        }
        Log.d(TAG, "开始录音：$SAMPLE_RATE Hz / 单声道 / 16bit")
        return true
    }

    /**
     * 停止并取回 WAV。
     *
     * @return 有效录音返回 WAV 字节；没录到东西（<0.2 秒）返回 null ——
     *         太短的片段送去评测只会得到 0 分，不如让 UI 直接提示「再按住说一次」。
     */
    fun stop(): ByteArray? {
        if (!running && worker == null) return null
        running = false
        worker?.let { runCatching { it.join(1500) } }
        worker = null

        record?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        record = null

        val data = synchronized(pcm) { pcm.toByteArray() }
        pcm.reset()
        Log.d(TAG, "停止录音：${data.size} 字节（约 ${data.size * 1000 / BYTES_PER_SECOND} ms）")
        // 0.2 秒 = 6400 字节。低于它基本就是误触（按下即松开）。
        if (data.size < BYTES_PER_SECOND / 5) return null
        return wav(data)
    }

    /** 放弃这一条（不返回数据）。 */
    fun cancel() {
        running = false
        worker?.let { runCatching { it.join(800) } }
        worker = null
        record?.let { rec ->
            runCatching { rec.stop() }
            runCatching { rec.release() }
        }
        record = null
        synchronized(pcm) { pcm.reset() }
    }

    /** 已经录了多久（毫秒）。UI 的计时器读它，不用再单独计数。 */
    fun durationMs(): Int = synchronized(pcm) { pcm.size() * 1000 / BYTES_PER_SECOND }

    // ---------------------------------------------------------------- WAV

    /**
     * 给裸 PCM 套一个 44 字节的 WAV 头（RIFF/WAVE，PCM 16bit 单声道）。
     *
     * 服务端用 ffmpeg 解，其实给裸 PCM 也行 —— 但 WAV 自带采样率，
     * 以后若改成 44.1k 或双声道，服务端不用跟着改。
     */
    private fun wav(pcmData: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(44 + pcmData.size)
        fun le32(v: Int) = byteArrayOf(
            v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()
        )
        fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

        out.write("RIFF".toByteArray())
        out.write(le32(36 + pcmData.size))
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        out.write(le32(16))                 // fmt 块长度
        out.write(le16(1))                  // PCM
        out.write(le16(1))                  // 单声道
        out.write(le32(SAMPLE_RATE))
        out.write(le32(BYTES_PER_SECOND))   // 字节率
        out.write(le16(2))                  // 块对齐
        out.write(le16(16))                 // 位深
        out.write("data".toByteArray())
        out.write(le32(pcmData.size))
        out.write(pcmData)
        return out.toByteArray()
    }
}

private const val TAG = "VoiceRecorder"
