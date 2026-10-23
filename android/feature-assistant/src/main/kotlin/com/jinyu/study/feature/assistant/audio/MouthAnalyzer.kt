package com.jinyu.study.feature.assistant.audio

import java.util.Arrays
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 一帧口型。
 *
 * @property open 开合度 0~1（0 = 闭嘴，1 = 张到最大）——「嘴张多大」
 * @property form 圆扁度 -1~+1（-1 = 扁/嘴角向两边拉，+1 = 圆/嘴唇收拢嘟起）——「嘴是什么形状」
 *
 * 为什么是**两个**数而不是一个：只靠音量（开合）的话，说「啊」和说「衣」都是"张一下"，
 * 嘴形完全一样 —— 这正是靖哥哥说的「只有开合、没有元音」。真人说话时，
 * 决定「像不像在说人话」的其实是第二个维度（圆扁），它由**共振峰**决定，跟音量无关。
 *
 * 值域对齐 Cubism 的标准参数：`ParamMouthOpenY` 收 [open]，`ParamMouthForm` 收 [form]。
 */
data class MouthShape(val open: Float, val form: Float) {
    companion object {
        /** 闭嘴 + 中性嘴形。会话收尾 / 打断时推它。 */
        val CLOSED = MouthShape(0f, 0f)
    }
}

/**
 * 从 TTS 解码后的 PCM 里估出「这一帧的嘴形」。
 *
 * ## 它到底在算什么
 *
 * 圆扁这一个维度，**只由一个量决定：第二共振峰 F2 落在哪**。
 * 这是语音学里定义元音最朴素的方式（F1-F2 平面），也是唯一能站得住的判据：
 *
 * | 元音 | F1 | F2 | 嘴形 |
 * |---|---|---|---|
 * | 衣 i | 300 | **2600** | 嘴角拉到最开（最扁） |
 * | 诶 e | 500 | **1900** | 扁 |
 * | 啊 a | 800 | **1200** | 中性（最自然的张口） |
 * | 哦 o | 500 | **900**  | 圆 |
 * | 乌 u | 350 | **700**  | 嘴唇收拢嘟起（最圆） |
 *
 * F2 高低 = 舌位前后 = 唇形圆扁。**不用识别元音**（那要模型、要延迟），
 * 只要连续地量出 F2 的位置，就能连续地驱动 Live2D 那个 -1~1 的浮点参数。
 *
 * ## ⚠️ 预加重是这条路能不能通的**唯一关键**（别删）
 *
 * 声带脉冲的谱天然是 **-12dB/oct** 下滑的（唇辐射补回 +6dB，净 -6dB/oct）。
 * 后果：**F1 的峰在功率谱上几乎总是全局最大**，F2 只是一个不起眼的小鼓包。
 * 想直接"找 F2 的峰"，找到的永远是 F1。
 *
 * 时域一阶差分 `y[n] = x[n] - 0.97·x[n-1]` 相当于 +6dB/oct 的高频提升，
 * 正好抵消掉那个倾斜，F1 和 F2 的峰才变得可比。
 *
 * **实测（5 个中文元音 × 5 种语速，取中位数）**：
 * ```
 *   预加重 0.97 + 平滑 250Hz + 找 600~3200Hz 峰位：
 *       衣 3040 → 诶 2278 → 啊 1097 → 哦 835 → 乌 623      单调 ✓
 *   同样的代码，把预加重去掉：
 *       衣  557 → 诶  609 → 啊 1084 → 哦 827 → 乌 611      全乱 ✗
 * ```
 * 去掉预加重后"衣"直接掉到 557Hz —— 因为找到的是它的 F1(300Hz) 的裙摆。
 * 所以下面 [PREEMPH] 那行看着像个可以忽略的系数，其实是整个算法的地基。
 *
 * ## 这条路上踩过的坑（都验证过，别再回头试了）
 *
 * - **谱重心**：≈ 反映 F1 的位置（= 张嘴大小），不是圆扁。衣的 F1=300 会把重心拉到最低，
 *   跟"扁"完全反相关。
 * - **三带能量占比**（低/中/高）：F2 的能量被源谱倾斜压死。实测「衣」的高频占比 0.044
 *   反而**小于**「诶」的 0.115 —— 单调性直接不成立。
 * - **dB 域减线性趋势白化**：高频的噪声底被抬成了假峰，后元音直接跑到搜索边界。
 * - **LPC 谱包络**：对阶数敏感，且「啊」的 F1/F2 挨得近、合并后峰会滑到左边界。
 *
 * ## 线程模型
 *
 * **非线程安全**：内部有跨帧状态（[open]/[form]/[prevSample]），只允许从
 * **播放线程**单线程调用（[AudioEngine] 的播放循环）。加锁只会给每帧白添开销。
 */
class MouthAnalyzer {

    // ---- 跨帧状态 ----

    private var open = 0f
    private var form = 0f

    /**
     * 上一帧的**最后一个样本**（-1~1）。
     *
     * ⚠️ 预加重是一阶差分，需要"前一个样本"。如果每帧开头都当成 0 来算，
     * 帧边界会被伪造出一个巨大跳变，在频谱上就是一层宽带噪声 —— 表现为
     * 口型偶尔毫无理由地抖一下。存着上一帧的尾巴就干净了。
     */
    private var prevSample = 0f

    // ---- 上一帧的中间量（**只为诊断/标定**暴露）----

    /** 估出来的第二共振峰位置（Hz）。低于 [F2_REF] 走圆，高于走扁。 */
    @Volatile
    var lastF2Hz: Float = 0f
        private set

    /** 这一帧的原始 RMS（0~1），判断「有没有在发声」用的就是它。 */
    @Volatile
    var lastRms: Double = 0.0
        private set

    // ---- 复用缓冲（每帧都要用，避免在播放线程里反复分配）----

    private val fft = Fft(FFT_SIZE)
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)

    /** 对数功率谱（dB）与其平滑后的"包络" —— 找峰是在包络上找的 */
    private val spectrumDb = FloatArray(FFT_SIZE / 2 + 1)
    private val envelope = FloatArray(FFT_SIZE / 2 + 1)

    /** 前缀和，给移动平均用（O(n) 平滑，不用每点重算一遍窗口） */
    private val prefix = DoubleArray(FFT_SIZE / 2 + 2)

    private val window = FloatArray(FFT_SIZE) { i ->
        // 汉宁窗。不加窗的话，帧边界那一刀会在频谱上糊出一片旁瓣，
        // 把共振峰的对比度冲掉。
        0.5f * (1f - cos(2.0 * PI * i / FFT_SIZE)).toFloat()
    }

    /**
     * 分析一帧 PCM。
     *
     * @param pcm 解码后的单声道 16bit 样本（一帧，通常 60ms）
     * @param sampleRate pcm 的采样率（服务端 hello 声明，16k / 24k 都可能）
     */
    fun analyze(pcm: ShortArray, sampleRate: Int): MouthShape {
        if (pcm.isEmpty()) return MouthShape(open, form)

        // ---- 维度一：开合度。RMS + 快起慢落 ----
        var sum = 0.0
        for (s in pcm) {
            val v = s / 32768.0
            sum += v * v
        }
        val rms = sqrt(sum / pcm.size)
        lastRms = rms
        val raw = (rms * LEVEL_GAIN).toFloat().coerceIn(0f, 1f)
        val prev = open
        open = if (raw > prev) raw else prev + (raw - prev) * LEVEL_RELEASE

        // ---- 维度二：圆扁度。只有"确实在发声"时才更新 ----
        if (rms >= VOICED_RMS) {
            val f2 = estimateF2(pcm, sampleRate)
            if (f2 != null) {
                lastF2Hz = f2
                val target = formFromF2(f2)
                // 向目标靠拢而不是直接赋值：单个元音持续时 F2 的逐帧估计会抖，
                // 直接赋值会让嘴形跟着抖。0.45 ≈ 两帧（120ms）跟到位，
                // 比一个音节（150~250ms）略快，看着跟得住又不抽。
                form += (target - form) * FORM_SMOOTH
            }
        } else {
            // 静音段往中性收，但**很慢**。为什么不是立刻归零：句子中间的逗号停顿
            // 只有一两帧，立刻归零会让嘴形每个停顿都弹一次中性，比不动还假。
            form += (0f - form) * FORM_IDLE_DECAY
        }

        // 帧尾巴留好，下一帧预加重要用（见 [prevSample] 的注释）
        prevSample = pcm[pcm.size - 1] / 32768f

        return MouthShape(open, form)
    }

    /** 会话收尾 / 打断：全部归零。 */
    fun reset() {
        open = 0f
        form = 0f
        prevSample = 0f
    }

    // ---- 频谱 → F2 ----

    /**
     * 估第二共振峰的位置（Hz）。样本太短 / 全是噪声时返回 null（调用方保持上一帧）。
     *
     * 流程：预加重 → 分窗加窗 FFT → 功率平均 → 取 dB → 移动平均成包络 →
     * 在 [F2_SEARCH_LO]~[F2_SEARCH_HI] 找极大值 → 抛物线插值细化。
     */
    private fun estimateF2(pcm: ShortArray, sampleRate: Int): Float? {
        if (sampleRate <= 0 || pcm.size < FFT_SIZE) return null

        val binHz = sampleRate.toFloat() / FFT_SIZE
        val half = FFT_SIZE / 2
        val kLo = ceil(F2_SEARCH_LO / binHz).toInt().coerceAtLeast(1)
        val kHi = (F2_SEARCH_HI / binHz).toInt().coerceAtMost(half - 1)
        if (kHi <= kLo + 1) return null

        Arrays.fill(spectrumDb, 0f)
        val hop = FFT_SIZE / 2
        var windows = 0
        var off = 0
        while (off + FFT_SIZE <= pcm.size && windows < MAX_WINDOWS) {
            for (i in 0 until FFT_SIZE) {
                val idx = off + i
                val x = pcm[idx] / 32768f
                // 预加重（见类注释：这一步删了整条路就废）
                val xPrev = if (idx > 0) pcm[idx - 1] / 32768f else prevSample
                re[i] = (x - PREEMPH * xPrev) * window[i]
                im[i] = 0f
            }
            fft.forward(re, im)
            // 累加**功率**（不是幅度）：谱包络要的是能量分布
            for (k in 0..half) {
                spectrumDb[k] += re[k] * re[k] + im[k] * im[k]
            }
            windows++
            off += hop
        }
        if (windows == 0) return null

        // 转 dB 并在同一趟里平滑 —— 顺手把前缀和建好
        val invW = 1f / windows
        prefix[0] = 0.0
        for (k in 0..half) {
            // 平均之后取对数：dB 域平滑比线性域平滑更贴近听感，
            // 也不会让单个强谐波把邻近的弱谐波完全压掉
            val p = spectrumDb[k] * invW
            spectrumDb[k] = 10f * log10(p + 1e-12f)
            prefix[k + 1] = prefix[k] + spectrumDb[k]
        }

        // 包络宽度按**Hz**给，跟采样率无关：约 [SMOOTH_HZ] 宽
        val halfW = ((SMOOTH_HZ / binHz) / 2f).toInt().coerceAtLeast(1)
        for (k in 0..half) {
            val a = (k - halfW).coerceAtLeast(0)
            val b = (k + halfW).coerceAtMost(half)
            envelope[k] = ((prefix[b + 1] - prefix[a]) / (b - a + 1)).toFloat()
        }

        // 在搜索范围内找包络最高点
        var bestK = kLo
        for (k in kLo..kHi) {
            if (envelope[k] > envelope[bestK]) bestK = k
        }
        // 抛物线插值：真峰常落在两个 bin 之间，不插值的话同一个音的 F2
        // 会在相邻两档之间来回跳（bin 宽 47Hz，对应 form 差 0.03，肉眼能看出抖）
        var delta = 0f
        if (bestK > kLo && bestK < kHi) {
            val y0 = envelope[bestK - 1]
            val y1 = envelope[bestK]
            val y2 = envelope[bestK + 1]
            val denom = y0 - 2 * y1 + y2
            if (abs(denom) > 1e-6f) {
                delta = (0.5f * (y0 - y2) / denom).coerceIn(-0.5f, 0.5f)
            }
        }
        return (bestK + delta) * binHz
    }

    /**
     * F2 位置 → 圆扁值。
     *
     * 以 [F2_REF]（= "啊"的位置）为 0 点，往两边各线性铺到 [FORM_LIMIT]。
     * 为什么不用单调的对数映射：实测的分布是"扁侧铺得开、圆侧挤"，
     * 用 [F2_REF] 分两段线性反而落点更匀（见下表）。
     *
     * 代入实测的五个元音：
     *   衣 3040 → -0.83   诶 2278 → -0.50   啊 1097 → 0.00
     *   哦  835 → +0.45   乌  623 → +0.81
     */
    private fun formFromF2(f2: Float): Float {
        val v = if (f2 <= F2_REF) {
            (F2_REF - f2) / (F2_REF - F2_LOW) * FORM_LIMIT
        } else {
            -(f2 - F2_REF) / (F2_HIGH - F2_REF) * FORM_LIMIT
        }
        return v.coerceIn(-FORM_LIMIT, FORM_LIMIT)
    }

    companion object {

        /**
         * 分析窗长（样本），**调用方也要用** —— [AudioEngine] 靠它判断"这一帧够不够
         * 做频谱分析"。不够的话开合照常有（那只看 RMS），但圆扁会缺。
         *
         * 小智的 TTS 是 60ms 帧（16k → 960 样本 / 24k → 1440 样本），正常远够。
         *
         * ⚠️ 此处写**字面量**，不要改成 `= FFT_SIZE`。companion object 里的属性按
         *    **文本顺序**初始化，`= FFT_SIZE` 是对下方声明的前向引用，编译直接报
         *    `Variable 'FFT_SIZE' must be initialized`。引用方向只能是「后者指前者」。
         */
        const val WINDOW_SAMPLES = 512

        /**
         * 分析窗长（样本）。512 @24kHz ≈ 21ms，频率分辨率 46.9Hz。
         *
         * 为什么不是 256：F2 的搜索靠"包络上找峰"，而包络要先把谐波抹平 ——
         * 女声基频 200~350Hz，256 点（10.7ms / 93.75Hz 分辨率）下谐波间隔
         * 只有两三个 bin，抹不干净，峰位会跟着谐波漂。512 点稳得多。
         */
        private const val FFT_SIZE = WINDOW_SAMPLES

        /** 一帧里最多平均几个窗（24kHz 的 60ms 帧能滑出 4 个）。 */
        const val MAX_WINDOWS = 6

        /**
         * 预加重的差分系数。
         *
         * ⚠️ **这是整个算法的地基，不是可有可无的微调**（实测对比见类注释）。
         *    0.97 是语音处理里的常规取值：拐点在 ~800Hz，正好在 F1 和 F2 之间。
         */
        const val PREEMPH = 0.97f

        /** 谱包络的平滑宽度（Hz）。按 Hz 定，跟采样率无关。 */
        const val SMOOTH_HZ = 250f

        /** F2 的搜索范围。600Hz 起是避开 F1（最高也就 900Hz，取 600 留余量）。 */
        const val F2_SEARCH_LO = 600f
        const val F2_SEARCH_HI = 3200f

        /** 中性参考点：实测"啊"的 F2 落在这里，于是"啊"→ form 0。 */
        const val F2_REF = 1100f

        /** 两端的锚点（实测"乌"和"衣"的外沿）。 */
        const val F2_LOW = 600f
        const val F2_HIGH = 3100f

        /**
         * 输出上限（不是 1）。±1 在 Cubism 里是"嘴角拉到极限"的夸张表情，
         * 日常说话到那儿反而假。留 15% 余量。
         */
        const val FORM_LIMIT = 0.85f

        /** 圆扁度的帧间跟随系数（每帧向目标靠拢的比例）。 */
        const val FORM_SMOOTH = 0.45f

        /** 静音时圆扁度回中性的系数，故意很小（见 [analyze]）。 */
        const val FORM_IDLE_DECAY = 0.15f

        /**
         * 「这一帧算不算在发声」的 RMS 门限。
         *
         * 正常说话（约 -20dBFS）RMS ≈ 0.1；opus 静音段解出的舒适噪声在 0.005 以下。
         * 取 0.012（约 -38dBFS）：小声说话也照常分析，噪声又上不来。
         */
        const val VOICED_RMS = 0.012

        /** RMS → 开合的增益。正常说话乘 6 之后落在 0.6 附近，起伏正好铺满 0~1。 */
        const val LEVEL_GAIN = 6.0

        /** 闭嘴时的跟随系数。0.5 ≈ 两帧内收完，跟得住又不抽。 */
        const val LEVEL_RELEASE = 0.5f
    }
}

/**
 * 就地基-2 迭代 FFT（Cooley-Tukey）。
 *
 * 为什么自己写而不是拉个库：这是个 60 行的定长变换，只在播放线程每秒跑 16.7×4 次，
 * 而引入一个 FFT 依赖（外加它的构建配置、混淆规则）比这 60 行贵得多。
 *
 * 旋转因子在构造时预计算成表 —— 每帧现算 sin/cos 会白烧掉几万次三角函数调用。
 *
 * **非线程安全**：复用外部的 re/im 数组，只允许单线程调用。
 */
private class Fft(private val n: Int) {

    /** 位反转置换表 */
    private val rev = IntArray(n)

    /** 旋转因子 cos / sin，下标是"当前级里第几个蝶形" */
    private val cosT = FloatArray(n / 2)
    private val sinT = FloatArray(n / 2)

    init {
        var bits = 0
        while ((1 shl bits) < n) bits++
        for (i in 0 until n) {
            var r = 0
            for (b in 0 until bits) {
                if (i and (1 shl b) != 0) r = r or (1 shl (bits - 1 - b))
            }
            rev[i] = r
        }
        for (i in 0 until n / 2) {
            val a = -2.0 * PI * i / n
            cosT[i] = cos(a).toFloat()
            sinT[i] = sin(a).toFloat()
        }
    }

    /** 原地正向变换：输入 [re]/[im]，输出频域 [re]/[im]（不做归一化）。 */
    fun forward(re: FloatArray, im: FloatArray) {
        for (i in 0 until n) {
            val j = rev[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var i = 0
            while (i < n) {
                var j = i
                var k = 0
                while (j < i + half) {
                    val l = j + half
                    val tre = re[l] * cosT[k] - im[l] * sinT[k]
                    val tim = re[l] * sinT[k] + im[l] * cosT[k]
                    re[l] = re[j] - tre
                    im[l] = im[j] - tim
                    re[j] += tre
                    im[j] += tim
                    j++
                    k += step
                }
                i += size
            }
            size = size shl 1
        }
    }
}
