package com.nuostudy.feature.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 麦克风录音 + TTS 播放的裸引擎。职责只有四件事：
 *
 *  1. **录音线程**：AudioRecord 16kHz → 攒满 60ms（960 样本）→ opus 编码 → 回调上行帧
 *  2. **播放线程**：下行 opus 帧 → 解码 → AudioTrack（采样率按服务端 hello 声明）
 *  3. **口型分析**：解码出来的 PCM 顺手算一帧「嘴形」（开合 + 圆扁）→ 回调给形象层。
 *     真正的信号处理在 [MouthAnalyzer] 里，这里只负责传对采样率。放这儿是因为
 *     PCM 此刻就在手上，另挂一路音频分析既要权限又跟播放对不齐。
 *  4. **打断与收尾**：flushPlayback() 丢掉没播的帧（孩子打断）；drain 模式把
 *     已入队的帧播完再回调（服务端说 stop 时音频可能还有一两秒在路上，不能掐尾）
 *
 * 线程模型刻意用裸线程而不是协程：AudioRecord.read / AudioTrack.write 都是
 * **阻塞调用**，天然就是线程的事；协程在这里只会多一层「不能真阻塞」的心智负担
 * （LiveSync 在协程里阻塞读时踩过这个坑）。
 *
 * 所有回调（[onFrame]/[onPlaybackShape]/[onPlaybackDrained]/[onError]）都来自**非主线程**。
 */
class AudioEngine(
    private val codec: OpusCodec,
    private val onFrame: (ByteArray) -> Unit,
    private val onPlaybackDrained: () -> Unit,
    private val onError: (String) -> Unit,
    /**
     * 每解出一帧 TTS 音频就回调一次「这一帧的嘴形」（开合 + 圆扁）。
     *
     * 用途：驱动 Live2D 的口型。为什么不另外做音频分析 —— 解码后的 PCM
     * 此刻就在手上，顺手就能算；再挂一路 AudioRecord/VoiceAnalyser
     * 既要权限又跟播放对不齐。
     *
     * ⚠️ 回调来自**播放线程**，且频率 = 一帧一次（服务端 60ms 一帧 ≈ 16.7 次/秒）。
     *    这正是口型需要的更新率，不用再降频。
     */
    private val onPlaybackShape: (MouthShape) -> Unit = {},
) {
    /** 服务端 hello 声明的下行参数，连接握手后由会话层注入 */
    @Volatile
    var serverSampleRate: Int = DEFAULT_SAMPLE_RATE_DOWN

    // ---- 录音侧 ----

    @Volatile
    private var recording = false

    /**
     * 录音线程句柄。
     *
     * ⚠️ **只在 [startRecording] 里 join 并替换**，[stopRecording] 不碰它 —— 见那边的注释。
     */
    @Volatile
    private var recordThread: Thread? = null

    @SuppressLint("MissingPermission") // 权限是运行时申请的；没授权时 state 检查会兜住
    fun startRecording() {
        if (recording) return
        // ⚠️ 先把上一轮的录音线程收干净：它可能刚被 [stopRecording] 放下，
        //    还在跑完当前这一块并 release 掉 AudioRecord（最多 ≈60ms）。
        //    不等它就会有两个 AudioRecord 同时抢麦克风，新的那个 start 会失败 ——
        //    现象是「接上了，但对着麦克风说什么都没反应」。
        //    这个 join 放在 **start 侧**（而不是 stop 侧）是关键，理由见 [stopRecording]。
        recordThread?.let { runCatching { it.join(1000) } }
        recordThread = null
        val minBuf = AudioRecord.getMinBufferSize(
            OpusCodec.SAMPLE_RATE_UP, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                OpusCodec.SAMPLE_RATE_UP,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2,
            )
        } catch (e: Exception) {
            onError("无法打开麦克风：${e.message}")
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            onError("麦克风不可用（多半是没授权）")
            return
        }
        // VOICE_COMMUNICATION 自带回声消除：平板外放 TTS 时喇叭的声音
        // 会被麦再收一遍回给云端，不加 AEC 机器人会「自言自语」。
        recording = true
        record.startRecording()
        recordThread = Thread({
            val buf = ShortArray(OpusCodec.FRAME_SAMPLES_UP)
            var filled = 0
            try {
                while (recording) {
                    val n = record.read(buf, filled, buf.size - filled)
                    if (n <= 0) {
                        onError("录音读取失败 n=$n")
                        break
                    }
                    filled += n
                    if (filled == buf.size) {
                        onFrame(codec.encode(buf))
                        filled = 0
                    }
                }
            } catch (e: Exception) {
                if (recording) onError("录音线程异常：${e.message}")
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }, "assistant-record").also { it.start() }
    }

    /**
     * 停止收音。**O(1)，绝不阻塞**：只置标志位就返回；录音线程会在读完当前这一块
     * （最多 ≈60ms）后自行退出，并在 finally 里 stop/release 掉硬件。
     *
     * ⚠️ 这里**故意不 join 录音线程**（早先是 `join(1000)`）。两个理由：
     *  1. 它会被 **OkHttp 的 WS 读线程**调用（服务端 VAD 推 stt / listen stop 时）。
     *     join 最多堵 1 秒，会把紧随其后的下行消息（典型是 `tts start`）一起挡住，
     *     状态和口型都跟着晚一拍。
     *  2. 更隐蔽的：join 让「停麦」变成**耗时动作**，于是「停麦 → 用户立刻挂断 → 再接通」
     *     这种连续操作里，[startRecording] 可能因为 `recording` 还没翻成 false 而直接
     *     return，表现是「接通了、对着麦克风说什么都没反应」。
     *
     * 现在标志位**当场**翻转；[startRecording] 若被紧接着调用，会自己去 join 上一轮的
     * 线程 —— 顺序与硬件独占都保住了，而调用方一个都不会被堵。
     */
    fun stopRecording() {
        recording = false
    }

    // ---- 播放侧 ----

    /** 有界：60ms/帧 × 160 帧 ≈ 9.6 秒，见 [enqueuePlayback] 的丢弃策略 */
    private val playbackQueue = LinkedBlockingQueue<ByteArray>(PLAYBACK_QUEUE_FRAMES)

    @Volatile
    private var draining = false

    /**
     * 本轮收到过服务端的「tts stop」没有。
     *
     * ⚠️ 为什么必须跟 [draining] 分开记：`enqueuePlayback` 每来一帧都会把 [draining]
     *    清回 false，而服务端喊停之后**经常还跟着一两个尾帧** —— 于是 draining 被吃掉，
     *    「播完 → 回 Ready」这条正常路径**永远不触发**，状态就永久卡在 Speaking：
     *    孩子看到的是「小智早不说话了，按钮还红着」，而且不会自己恢复。
     *    这个标记是 **sticky** 的（迟到帧清不掉），只在真正收尾时复位。
     */
    @Volatile
    private var stopSeen = false

    @Volatile
    private var playing = false

    private var playThread: Thread? = null

    /** 会话建立后启动播放循环；重复调用无副作用 */
    fun startPlayback() {
        if (playing) return
        playing = true
        playThread = Thread({
            var track: AudioTrack? = null
            var trackRate = 0
            var frameSize = 0
            // 本轮解出过音频帧没有 —— 防「tts start 之后、首帧到达之前」那段空窗被误判成「说完」
            var produced = false
            // 最后一帧的播放时刻（elapsedRealtime），静默兜底用
            var lastAudioAt = 0L
            try {
                while (playing) {
                    val frame = playbackQueue.poll(100, TimeUnit.MILLISECONDS)
                    if (frame == null) {
                        // 队列空 = 没有待播音频。这时候该不该判定「这轮说完了」？三条路：
                        //  ① **正常**：服务端喊过停(draining) 且队列已空 → 立刻收尾（原逻辑）
                        //  ② **迟到帧兜底**：喊过停(stopSeen) 但 draining 被尾帧吃掉了
                        //     —— 等队列真正安静 TAIL_QUIET_MS 再收尾，别把还在路上的尾音掐掉
                        //  ③ **stop 丢失兜底**：连 stop 都没收到（漏推/丢包）→ 安静超过
                        //     SPEAK_IDLE_MS 就自己收尾。⚠️ 没有这条，一旦漏掉 stop 就永久卡
                        //     在 Speaking，按钮一直红、还不会自愈。
                        // ⚠️ 三条都以「队列此刻确实空」为前提：只要还有帧在队里，
                        //    就说明她还在说 —— 此时任何收尾都是错的。
                        if ((draining || stopSeen) && playbackQueue.isEmpty()) {
                            val quiet = if (produced) {
                                SystemClock.elapsedRealtime() - lastAudioAt
                            } else {
                                Long.MAX_VALUE
                            }
                            val done = draining ||
                                (stopSeen && (!produced || quiet >= TAIL_QUIET_MS)) ||
                                (produced && quiet >= SPEAK_IDLE_MS)
                            if (done) {
                                draining = false
                                stopSeen = false
                                produced = false
                                // 话说完 → 把嘴闭上。不清的话最后一帧的开合度会一直挂着，
                                // 表现是「小智说完了嘴还半张着」，很出戏。
                                resetShape()
                                onPlaybackDrained()
                            }
                        }
                        continue
                    }

                    val rate = serverSampleRate
                    if (track == null || trackRate != rate) {
                        runCatching { track?.release() }
                        track = buildTrack(rate)
                        trackRate = rate
                        frameSize = OpusCodec.frameSamplesFor(rate)
                    }
                    val pcm = codec.decode(frame, rate, frameSize)
                    produced = true
                    lastAudioAt = SystemClock.elapsedRealtime()
                    emitShape(pcm, rate)
                    // 阻塞写入，天然限速。
                    // ⚠️ 返回值必须检查：耳机插拔、音频设备切换等会让 AudioTrack 进入
                    //    ERROR_DEAD_OBJECT 一类状态，write 返回负值而**不抛异常**。
                    //    不检查的话，画面照常推进（状态、口型都在动），就是**没有声音** ——
                    //    这种「看着好好的但没声」最难查。这里丢掉坏 track，下一帧重建。
                    val written = track.write(pcm, 0, pcm.size)
                    if (written < 0) {
                        onError("音频输出异常（code=$written），已重建输出通道")
                        // track 此刻必非空（上一行刚 write 过），用非空调用避免
                        // 编译器报「不必要的安全调用」。
                        runCatching { track.release() }
                        track = null
                        trackRate = 0
                    }
                }
            } catch (e: Exception) {
                if (playing) onError("播放线程异常：${e.message}")
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
            }
        }, "assistant-play").also { it.start() }
    }

    fun enqueuePlayback(frame: ByteArray) {
        if (!playing) return
        // 队列满时**丢最旧的一帧**（队首），给新帧腾位置 —— 目的是让播放尽量贴近
        // 「正在说的那一句」。反过来保旧帧的话，网络卡一阵之后会积压出十几秒的
        // 陈旧语音，孩子听到的是「她还在念上一段」。
        while (!playbackQueue.offer(frame)) {
            if (playbackQueue.poll() == null) break
        }
        draining = false
    }

    /** 孩子打断（再按一次说话）：丢掉没播的帧，立即闭嘴 */
    fun flushPlayback() {
        playbackQueue.clear()
        draining = false
        stopSeen = false
        resetShape()
    }

    /**
     * 服务端 tts stop：不掐尾，把已入队的帧播完再回 Ready。
     *
     * ⚠️ 这里**不再直接回调** [onPlaybackDrained] —— 由播放线程统一收尾。
     *    直接回调要求「队列空了」这个判断和播放线程抢同一份状态，而且队列空 ≠ 音频到齐
     *    （尾帧可能还在网络上），会提前收尾。播放线程最多 100ms 就会醒来看这一次标记，
     *    延迟可以忽略，但少了一整类竞态。
     */
    fun markDraining() {
        stopSeen = true
        draining = true
    }

    // ---- 播放口型 ----

    /**
     * 口型分析器。**持有跨帧的平滑状态**（嘴形不能一帧一跳），
     * 所以必须是本引擎的一个实例，不能每次分析新建。
     *
     * ⚠️ 只被播放线程碰（见 [MouthAnalyzer] 的线程模型）。
     */
    private val mouth = MouthAnalyzer()

    /** 诊断日志的节流计数。纯日志用，不参与任何业务判断。 */
    private var shapeLogN = 0

    /**
     * 从解码后的 PCM 算这一帧的嘴形，并回调出去。
     *
     * 真正的分析在 [MouthAnalyzer] 里；这里只负责「传对采样率」和「把结果发出去」。
     * ⚠️ 采样率必须用**本轮实际用的** rate（服务端 hello 声明，16k/24k 都可能）——
     *    拿常量去算会让频带边界整体错位，症状是「口型有动静但一直很糊」。
     */
    private fun emitShape(pcm: ShortArray, sampleRate: Int) {
        // 防御：频谱分析要 [MouthAnalyzer.WINDOW_SAMPLES] 个样本才摆得下一个窗。
        // 小智的 TTS 是 60ms 帧，正常远够；真出现短帧就说明服务端改过参数 ——
        // 那时口型会"开合照常动、圆扁不动"，这条日志就是给那一刻准备的。
        if (pcm.size < MouthAnalyzer.WINDOW_SAMPLES && shapeLogN < 3) {
            Log.w(TAG, "下行帧只有 ${pcm.size} 个样本（< ${MouthAnalyzer.WINDOW_SAMPLES}）：圆扁维度不参与分析")
        }
        val shape = mouth.analyze(pcm, sampleRate)
        // 标定用诊断，节流到每 30 帧（约 0.5 秒）一条。
        // 为什么把 **F2 估计值**也打出来：只看 open/form 分不清是
        // 「映射系数没调好」还是「F2 压根估错了」—— 这两者的修法完全不同。
        // 真机上照着 f2 的实际分布去调 MouthAnalyzer 的 F2_REF / F2_LOW / F2_HIGH 即可。
        val n = shapeLogN++
        if (n < 3 || n % 30 == 0) {
            Log.d(
                TAG,
                "口型 #$n open=${"%.2f".format(shape.open)} form=${"%+.2f".format(shape.form)}" +
                    " f2=${mouth.lastF2Hz.toInt()}Hz" +
                    " rms=${"%.3f".format(mouth.lastRms)}",
            )
        }
        onPlaybackShape(shape)
    }

    /** 闭嘴 + 嘴形回中性。收尾、打断、停止播放时都要做，否则会「定格在半张嘴」。 */
    private fun resetShape() {
        mouth.reset()
        onPlaybackShape(MouthShape.CLOSED)
    }

    fun stopPlayback() {
        playing = false
        playbackQueue.clear()
        draining = false
        stopSeen = false
        resetShape()
        playThread?.let { runCatching { it.join(1000) } }
        playThread = null
    }

    private fun buildTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(minBuf * 2)
            .build()
            .also { it.play() }
    }

    // ---- 生命周期 ----

    /** 幂等全停：会话断开 / ViewModel 销毁时调用 */
    fun release() {
        stopRecording()
        stopPlayback()
    }

    companion object {
        const val DEFAULT_SAMPLE_RATE_DOWN = 24_000

        /** 口型诊断日志的 tag。跟 [XiaozhiClient] 那个分开，方便在 logcat 里按段过滤。 */
        private const val TAG = "AssistantAudio"

        /** 播放队列上限（帧）：160 帧 ≈ 9.6 秒 */
        private const val PLAYBACK_QUEUE_FRAMES = 160

        /**
         * 「喊过停之后，队列还要安静多久才算真的说完」。
         *
         * 服务端 `tts stop` 之后常有一两个尾帧还在路上；draining 会被尾帧清掉，
         * 所以要用「安静了一段时间」来兜。取 400ms：比一个 opus 帧(60ms)长得多，
         * 足够吃掉在途尾帧；又短到听感上「她说完 → 我能接着说」几乎无缝。
         */
        private const val TAIL_QUIET_MS = 400L

        /**
         * 连 `tts stop` 都没收到时的**自愈超时**。
         *
         * 正常 TTS 是连续流的，帧与帧之间不会空 3 秒。真空这么久只可能是
         * stop 漏推/丢包 —— 这时候必须自己收尾，否则状态永久卡在 Speaking，
         * 按钮一直红、麦克风也不会重开。
         */
        private const val SPEAK_IDLE_MS = 3_000L
    }
}
