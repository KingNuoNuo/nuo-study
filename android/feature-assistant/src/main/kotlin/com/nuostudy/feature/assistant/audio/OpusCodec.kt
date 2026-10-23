package com.nuostudy.feature.assistant.audio

// ⚠️ mavenCentral 上的 Concentus 发行版（io.github.jaredmdobson:concentus）重新打包过：
//    包名是 io.github.jaredmdobson 而不是原版的 org.concentus —— 别「顺手改回来」。
//    另外 decode() 比原版多一个 decodeFec 布尔参数（我们不做丢包隐藏，恒传 false）。
import io.github.jaredmdobson.OpusApplication
import io.github.jaredmdobson.OpusDecoder
import io.github.jaredmdobson.OpusEncoder

/**
 * opus 编解码封装 —— 小智协议要求 16kHz 单声道上行 / 服务端声明采样率下行。
 *
 * 为什么用 Concentus（纯 Java 移植）而不是 MediaCodec：
 *   小智协议按 **60ms 定长帧** 交换音频（ESP32 端如此，云端也按这个节奏走）。
 *   MediaCodec 的 opus 输出帧长由编解码器自己决定（通常 20ms），凑不成 60ms ——
 *   要么攒帧（引入额外延迟和拼接复杂度），要么协议对不上。
 *   Concentus 完全自己控制帧长：每次喂 960 个样本（60ms@16k），出一帧。
 *
 * 线程模型：encode 只会在**录音线程**被调，decode 只会在**播放线程**被调 ——
 *   各自独占一个方向，Concentus 的 Encoder/Decoder 都不是线程安全的，别跨线程共享。
 */
class OpusCodec {

    private val encoder = OpusEncoder(
        SAMPLE_RATE_UP, CHANNELS, OpusApplication.OPUS_APPLICATION_VOIP
    )

    init {
        // ⚠️⚠️ complexity 必须压在 2 附近，**别按「音质直觉」往上调**（2026-10-10 实测）。
        //
        // Concentus 是**纯 Java** 的 opus 移植，而 libopus 的 complexity 直接决定 SILK
        // 分析路径的规模 —— 关键那个 `nStatesDelayedDecision`：
        //     complexity ≥ 8 → 4 个候选状态；5~7 → 2 个；≤ 4 → 1 个（最优路径，最省）。
        // 原来这里写的是 8（注释还写着「平板 CPU 完全扛得住」），于是语音噪声整形
        // 量化器跑满 4 状态搜索。真机 simpleperf 采样（8 秒 / 2 万样本）结果：
        //
        //     3.02%  SilkNSQState.silk_noise_shape_quantizer_del_dec   ← 除 GPU 外最大的单项
        //     2.80%  Inlines.silk_SMLAWB
        //     1.64%  Inlines.silk_SMULWB
        //     1.33%  Inlines.op_assert / silk_SUB_SAT32
        //     1.01%  NLSF.silk_NLSF_del_dec_quant
        //     0.88%  Autocorrelation.silk_warped_autocorrelation
        //     0.32%  BurgModified.silk_burg_modified
        //     ────────────────────────────────────────────────
        //     Concentus 合计 = 21.13%   （对比：Mali GPU 驱动 4.43%）
        //
        // 代价不是"费电"，而是**画面卡**：通话中整机 CPU 只剩 14~37% 空闲，
        // Live2D 的画面从 50fps 掉到 30~38fps —— 就是靖哥哥说的「动画不连贯」。
        // ⚠️ 排查时注意：改画布分辨率 / 抗锯齿 / 物理**都不会动这个帧率**，因为瓶颈
        //    根本不在渲染侧（当时把三条路都试过才定位到这里）。
        //
        // 16kHz 单声道语音在 complexity 2 下音质没有可听差别（libopus 的低功耗档
        // 就在这一段；ESP32 那类设备还要再低），但 SILK 的分析开销约降 3 倍。
        encoder.setComplexity(2)
        // 32kbps 对 16k 语音足够清晰（小智默认上行也在这个量级）。
        // 注意：降码率对 CPU 的影响远小于降 complexity，别指望靠它省 CPU。
        encoder.setBitrate(UP_BITRATE_BPS)
    }

    /**
     * 编码输出缓冲，**复用**（每秒 16.7 次，没必要每次都新建 1KB）。
     *
     * ⚠️ 只在录音线程调用 [encode]，独占，所以成员缓冲不需要加锁 ——
     *    这也和类注释里「encode 只会在录音线程被调」那条约定一致。
     */
    private val encodeBuf = ByteArray(MAX_FRAME_BYTES)

    /**
     * 编码一整帧上行音频。
     * @param pcm 长度必须恰为 [FRAME_SAMPLES_UP]（60ms@16k）
     * @return opus 字节帧（长度随语音内容浮动，静音时可能只有几十字节）。
     *   **每次都是新数组**（`copyOf`），调用方可以随便留着/交给别的线程。
     */
    fun encode(pcm: ShortArray): ByteArray {
        val written = encoder.encode(pcm, 0, FRAME_SAMPLES_UP, encodeBuf, 0, MAX_FRAME_BYTES)
        return encodeBuf.copyOf(written)
    }

    /**
     * 解码一帧下行 TTS 音频。
     * @param sampleRate 服务端 hello 里声明的采样率（通常 24000）
     * @param frameSize  对应帧长的每通道样本数（60ms@24k = 1440）
     * @return PCM（长度 = 实际解码样本数；丢包时 Concentus 会做 PLC 补帧，长度仍为 frameSize）
     */
    fun decode(opus: ByteArray, sampleRate: Int, frameSize: Int): ShortArray {
        val decoder = decoderFor(sampleRate)
        val pcm = ShortArray(frameSize)
        val samples = decoder.decode(opus, 0, opus.size, pcm, 0, frameSize, false)
        return if (samples == pcm.size) pcm else pcm.copyOf(samples)
    }

    @Synchronized
    private fun decoderFor(sampleRate: Int): OpusDecoder {
        // 下行采样率要等服务端 hello 才知道，惰性创建；变了一次就重建（每会话最多一次）
        var d = decoder
        if (d == null || decoderRate != sampleRate) {
            d = OpusDecoder(sampleRate, CHANNELS)
            decoder = d
            decoderRate = sampleRate
        }
        return d
    }

    private var decoder: OpusDecoder? = null

    @Volatile
    private var decoderRate: Int = 0

    companion object {
        const val SAMPLE_RATE_UP = 16_000
        const val CHANNELS = 1
        const val FRAME_MS = 60

        /** 60ms @ 16kHz 单声道 = 960 样本 = 1920 字节 PCM */
        const val FRAME_SAMPLES_UP = SAMPLE_RATE_UP * FRAME_MS / 1000

        /** 60ms 帧 opus 上限 510 字节（协议硬上限），给 1024 富余 */
        private const val MAX_FRAME_BYTES = 1024

        private const val UP_BITRATE_BPS = 32_000

        fun frameSamplesFor(sampleRate: Int): Int = sampleRate * FRAME_MS / 1000
    }
}
