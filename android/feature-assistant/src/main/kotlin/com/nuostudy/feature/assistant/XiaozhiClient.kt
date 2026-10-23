package com.nuostudy.feature.assistant

import android.os.SystemClock
import android.util.Log
import com.nuostudy.feature.assistant.audio.AudioEngine
import com.nuostudy.feature.assistant.audio.MouthShape
import com.nuostudy.feature.assistant.audio.OpusCodec
import com.nuostudy.feature.assistant.identity.XiaozhiIdentity
import com.nuostudy.feature.assistant.net.XiaozhiOta
import com.nuostudy.feature.assistant.net.XiaozhiSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.UnknownHostException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** 助手会话状态 —— UI 完全由它驱动，孩子看得懂哪个图标亮着就行 */
sealed interface AssistantState {
    /** 面板还没打开过 */
    data object Idle : AssistantState

    /** OTA / 激活轮询 / WS 握手 */
    data object Connecting : AssistantState

    /** 需要家长去 xiaozhi.me 输激活码（[code] 展示给家长） */
    data class AwaitingActivation(val code: String?) : AssistantState

    /** 就绪：可以点「说话」 */
    data object Ready : AssistantState

    /** 收音中 */
    data object Listening : AssistantState

    /** 说完了，等云端想 */
    data object Thinking : AssistantState

    /** TTS 播放中 */
    data object Speaking : AssistantState

    /**
     * 告别中 —— 挂断后小智正在说那句告别语（见 [HANGUP_FAREWELL]）。
     *
     * 为什么不直接复用 [Speaking]：按 Speaking 渲染时按钮是**红色挂断**态，于是孩子
     * 按下挂断后的三四秒里会看到 绿(Ready) → 红(Speaking) → 绿(Ready) 闪一下，
     * 看着像「又接通了」。单开一个状态，让这段收尾有稳定的视觉：蓝色、不可点、标「告别中」。
     *
     * 出口只有两个：TTS 播完（[AudioEngine] 的 onPlaybackDrained）或 [FAREWELL_TIMEOUT_MS]
     * 超时兜底，都回到 [Ready]。
     */
    data object Farewell : AssistantState

    /** 出错（会自动退避重试，不需要孩子做任何操作） */
    data class Error(val message: String) : AssistantState
}

/**
 * 小智会话编排：OTA → (激活) → WS 握手 → 对话 → 断线重连，全在这一层。
 *
 * 生命周期与面板绑定：面板打开 [startIfNeeded]，关闭 [shutdown]。
 * 会话循环内断线自动退避重连（1s → 30s）；取消（面板关闭）随时打断一切。
 *
 * 线程约定：所有回调（OkHttp WS 线程 / 录音 / 播放线程）只允许做两件事 ——
 * 更新 StateFlow（线程安全）或调用 socket.send（OkHttp 保证线程安全）。
 */
@Singleton
class XiaozhiClient @Inject constructor(
    private val ota: XiaozhiOta,
    private val identity: XiaozhiIdentity,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var sessionJob: Job? = null

    private val _state = MutableStateFlow<AssistantState>(AssistantState.Idle)
    val state: StateFlow<AssistantState> = _state.asStateFlow()

    /** 字幕：stt = 孩子说的话，tts sentence = 小姐姐说的话，括号 = 系统提示 */
    private val _subtitle = MutableStateFlow<String?>(null)
    val subtitle: StateFlow<String?> = _subtitle.asStateFlow()

    /**
     * 当前口型（开合度 0~1 + 圆扁度 -1~+1）—— 由 TTS 音频逐帧分析得来
     * （见 [AudioEngine.emitShape] 与 [MouthAnalyzer]）。
     *
     * 为什么要**经过 StateFlow 而不是让 UI 直接挂在 AudioEngine 上**：
     * [AudioEngine] 是每轮会话新建、断线即丢的，UI 不能持它的引用；
     * 而这个 StateFlow 挂在 @Singleton 的 client 上，生命周期跟 UI 一致。
     * 会话结束时 [shutdown] 把它归零，否则面板收起再打开会「嘴还张着」。
     *
     * ⚠️ StateFlow 会**丢弃与当前值相同的连续值**，这正好是我们想要的：
     *    静音段一整串「闭嘴」不用反复推给 WebView。
     *    （[MouthShape] 是 data class，相等性按两个分量比，不会误丢。）
     */
    private val _mouthShape = MutableStateFlow(MouthShape.CLOSED)
    val mouthShape: StateFlow<MouthShape> = _mouthShape.asStateFlow()

    /**
     * 「就绪即接通」意图 —— 平板物理键一键来电。
     *
     * 按键那一刻 OTA/WS 往往还在握手（[socket]、[engine] 都是 null），直接 [startListening]
     * 是空转。所以把意图记下来，等状态真正到 [AssistantState.Ready] 时由 [tryAutoTalk]
     * 兑现一次，孩子不用再点绿色按钮。
     *
     * 只消费一次：挂断后不会因为再次 Ready 又自己接通（那会变成「挂不掉」）。
     */
    @Volatile
    private var talkOnReady = false

    @Volatile
    private var socket: XiaozhiSocket? = null

    @Volatile
    private var engine: AudioEngine? = null

    /**
     * 会话代次。**每起一轮会话 +1，[shutdown] 也 +1**。
     *
     * 它挡的是「上一轮会话的残骸动到这一轮」这一类竞态，有两个具体来源：
     *  1. [runSession] 的 finally 会清 [socket]/[engine]。协程取消是**协作式**的 ——
     *     快速「关面板 → 立刻再开」时，旧协程的 finally 可能晚于新会话写入才执行，
     *     于是一把把**新会话**的 socket/engine 抹成 null：孩子按键全哑、推不了音频。
     *  2. 关面板之后，WS 回调 / 播放线程回调仍可能排在线程队列里继续跑，
     *     把已经落成 [AssistantState.Idle] 的状态又写回 `Speaking`
     *     （下次开面板，按钮直接是「挂断」的红色，而实际并没有连接）。
     *
     * 做法：所有来自网络/音频线程的回调都带上**自己那一轮的 epoch**，进来先比对，
     * 不一致就整条丢弃；finally 里的清理也只在「引用还是自己的」时才做。
     */
    private val sessionEpoch = AtomicInteger(0)

    /** 最近一次收到**任何**下行消息的时刻（elapsedRealtime）。假死看门狗用它判断链路还活着没有。 */
    private val lastDownlinkAt = AtomicLong(0L)

    private var sessionId: String = UUID.randomUUID().toString()

    /**
     * 连续对话（电话式）：AI 说完自动重开麦，孩子不用反复点「说」。
     * 点「说」开始对话时置 true；点「停」（= 挂断）或面板关闭时置 false。
     */
    @Volatile
    private var continuous = false

    /**
     * 告别超时兜底：挂断后等 TTS 回来的计时器（见 [hangUp]）。
     * 每次挂断/关闭面板都先 cancel 旧的，避免过期的计时器把新一轮的状态按回 Ready。
     */
    private var farewellJob: Job? = null

    // ---- 生命周期 ----

    /**
     * 面板打开时调用；重复调用无副作用。
     *
     * @param autoTalk 一键来电（平板物理键）：就绪后**直接接通**，省掉「再点绿色按钮」那一步。
     *   握手是异步的，所以意图先记进 [talkOnReady]，由 [tryAutoTalk] 在真正 Ready 时兑现；
     *   若调用时恰好已经 Ready（重复按键），[tryAutoTalk] 会当场兑现。
     */
    fun startIfNeeded(autoTalk: Boolean = false) {
        if (autoTalk) talkOnReady = true
        if (sessionJob?.isActive == true) {
            if (autoTalk) tryAutoTalk()
            return
        }
        _state.value = AssistantState.Connecting
        _subtitle.value = null
        sessionJob = scope.launch { sessionLoop() }
    }

    /** 面板关闭 / VM 销毁：无条件停掉一切，回到 Idle */
    fun shutdown() {
        // ⚠️ 代次**先** +1：让所有在途回调（WS 线程 / 播放线程）当场失效。
        //    否则它们会把状态重新写成 Speaking/Listening —— 面板都关了，状态还亮着。
        sessionEpoch.incrementAndGet()
        sessionJob?.cancel()
        sessionJob = null
        farewellJob?.cancel()
        farewellJob = null
        socket = null
        engine?.release()
        engine = null
        continuous = false
        // 关面板 = 收回未兑现的接通意图：否则下次连上会「自己说起话来」
        talkOnReady = false
        // 嘴也要闭上：会话没了不会再有人推电平，留着最后一帧的值就是「定格半张嘴」
        _mouthShape.value = MouthShape.CLOSED
        _state.value = AssistantState.Idle
    }

    // ---- 对话控制（UI 主线程调用，全部非阻塞） ----

    /**
     * 一颗按钮走完「接通 / 挂断」：
     *   - Ready  → 接通（开始连续对话：说完自动续听，像打电话）
     *   - Listening / Speaking → 挂断（停止收音、掐掉正在播的回答，回到 Ready）
     */
    fun toggleTalk() {
        when (_state.value) {
            is AssistantState.Ready -> startListening()
            // 通话中（听 / 想 / 说）一律是「挂断」。
            // ⚠️ Thinking 以前漏在这里，落进 else 变成「点了没反应」——
            //    而它当时还是**绿色接通**的样子，孩子按了没动静会以为坏了。
            //    思考中也是通话中，按了就该挂。
            is AssistantState.Listening,
            is AssistantState.Thinking,
            is AssistantState.Speaking -> {
                // AI 正说着、孩子想挂断：掐掉剩余音频再挂
                if (_state.value is AssistantState.Speaking) engine?.flushPlayback()
                hangUp()
            }
            // ⚠️ 错误态曾经是个**死状态**：按钮灰着点不动、这里也落 else -> Unit，
            //    孩子唯一的退出口只剩「关面板再打开」。这里给它一条活路，
            //    见 [retryFromError]。
            is AssistantState.Error -> retryFromError()
            // 告别语播放中：按钮已禁用（见 TalkButton 的 enabled），这里再挡一道 ——
            // 物理键 / 误触都不该把收尾打断，几秒后它自己会回到 Ready。
            is AssistantState.Farewell -> Unit
            else -> Unit
        }
    }

    /**
     * 从 [AssistantState.Error] 恢复。
     *
     * 两条路径：
     *  - **手里还有连接**：主动 [XiaozhiSocket.close]，触发 onDisconnected →
     *    `closed` 完成 → [runSession] 返回 → [sessionLoop] 走标准退避重连。
     *  - **连接已经没了**（OTA 阶段就失败、或会话协程已退出）：直接 [startIfNeeded]
     *    把会话循环拉起来；若循环其实还活着，它自己也会在退避后重试。
     *
     * 先把状态落成 [AssistantState.Connecting]，让按钮立刻有反应（否则点了没动静，
     * 又是「按了没反应」）。
     */
    private fun retryFromError() {
        _state.value = AssistantState.Connecting
        val s = socket
        if (s != null) runCatching { s.close() } else startIfNeeded()
    }

    /**
     * 兑现「就绪即接通」意图。**只在真正 Ready 时接通，且只消费一次**。
     *
     * 提前调用（还在 Connecting / 等家长激活）是安全的：意图留着不消费，
     * 等 WS 握手那一处再兑现 —— 这样「按键时网速慢」也不会白按。
     */
    private fun tryAutoTalk() {
        if (!talkOnReady) return
        if (_state.value !is AssistantState.Ready) return
        talkOnReady = false
        startListening()
    }

    private fun startListening() {
        val s = socket ?: return
        val e = engine ?: return
        continuous = true
        sessionId = UUID.randomUUID().toString()
        e.flushPlayback()
        e.startRecording()
        val ok = s.sendText(
            buildJsonObject {
                put("type", "listen")
                put("session_id", sessionId)
                put("state", "start")
                // ⚠️ auto 模式：服务端自己跑 VAD（静音检测），孩子一停嘴就自动断句、
                //   推 stt 回来。用 manual 的话服务端会一直等客户端发 listen stop，
                //   表现就是「说完话必须手点『停』小智才回话」。
                put("mode", "auto")
            }.toString()
        )
        _state.value = if (ok) AssistantState.Listening else AssistantState.Error("连接已断开，正在重连…")
    }

    /**
     * 挂断（结束连续对话）：停麦 → 让小智回一句告别 → 回到 Ready。
     * 与「孩子点停」绑定 —— 之后不再自动续听，要重新点「说」才接通。
     */
    private fun hangUp() {
        continuous = false
        // ⚠️ **绝不能**在 socket/engine 为 null 时提前 return。
        //    断线重连的窗口里这两个就是 null，而「挂断」是孩子唯一的退出口 ——
        //    早退会让状态永久停在 Listening/Speaking：按钮一直红、点它也没反应。
        //    所以状态先无条件落下，网络动作能发就发（发不出去也没关系，反正要挂断）。
        engine?.stopRecording()

        // ⚠️⚠️ 这里**绝不能发 `listen stop`**。
        //    协议里 `listen stop` 的语义是「我（用户）话说完了，你回答吧」——
        //    服务端收到它会拿缓冲的音频跑一遍 ASR → LLM → TTS。而挂断的瞬间
        //    并没有真实语音，等于喂给 LLM 一句空话，人格就吐一句通用开场白
        //    （实测现象：一按挂断，小智反而说「好呀，有什么可以帮你的吗」，
        //     像挂断触发她自说自话）。
        //
        //    正解是用 `detect` + `text`：这是协议里的**文本输入**通道。
        //    见 xiaozhi-server `core/handle/textHandler/listenMessageHandler.py` ——
        //    detect 带 text 时，只要该 text 不是配置里的唤醒词，服务端就会
        //    `await startToChat(conn, original_text)`，把它当用户这一句话交给 LLM，
        //    于是小智会用**她自己的声音**回一句告别。
        //    ⚠️ 两个坑：① text 别写成唤醒词，否则会走「嘿，你好呀」那条问候分支，
        //    又绕回同样的问题；② 也**不要**额外发 `abort` —— 服务端 `startToChat`
        //    内部发现自己在说话时会自行 abort，多发一条 abort 会附带一个
        //    `tts stop` 下行，白白搅乱播放侧的 draining 状态。
        farewellJob?.cancel()
        val sent = socket?.sendText(
            buildJsonObject {
                put("type", "listen")
                put("session_id", sessionId)
                put("state", "detect")
                put("text", HANGUP_FAREWELL)
            }.toString()
        ) == true
        android.util.Log.d(TAG, "hangup → detect/$HANGUP_FAREWELL sent=$sent")

        if (!sent) {
            // 发不出去（正处在断线重连窗口，socket 为 null）：这一轮没有告别语可等，
            // 直接落地 Ready。**不能**停在这里等超时 —— 那几秒按钮是灰的，而「挂断」
            // 是孩子唯一的退出口，任何卡顿都会被当成「坏了」。
            _state.value = AssistantState.Ready
            return
        }

        // 先落「告别中」：detect 的回包要一个来回才到，若这里还写 Ready，
        // 按钮会先闪一下绿再进告别态（等于没解决问题）。见 [Farewell] 的说明。
        _state.value = AssistantState.Farewell
        // 兜底：对端也可能压根不推 tts（网络半死 / 服务端没有这条会话）。
        // 超时无条件回 Ready，绝不让「告别中」变成一个新的死状态。
        farewellJob = scope.launch {
            delay(FAREWELL_TIMEOUT_MS)
            if (_state.value is AssistantState.Farewell) {
                android.util.Log.w(TAG, "farewell 超时（$FAREWELL_TIMEOUT_MS ms 没等到 tts），回到 Ready")
                _state.value = AssistantState.Ready
            }
        }
    }

    // ---- 会话循环 ----

    private suspend fun sessionLoop() {
        var backoffMs = 1_000L
        while (currentCoroutineContext().isActive) {
            try {
                val cfg = acquireConfig()
                _subtitle.value = null
                runSession(cfg)
                // runSession 返回 = 对端正常关闭；自动重连
                _state.value = AssistantState.Connecting
                backoffMs = 1_000L
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                android.util.Log.w(TAG, "session error: ${readable(e)}", e)
                _state.value = AssistantState.Error(readable(e))
                delay(backoffMs)
                // ⚠️ 退避上限 30s（实测教训）：官方云端对高频重连会**静默拉黑**
                //    （升级/hello 全合规仍被立即 close，无错误码）。曾用 5s 上限 +
                //    每 15-20s 一轮完整 OTA+WS 重试，一小时几十次后被拒。
                //    面板关闭即整体断开，30s 退避的电量代价可接受。
                backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
            }
        }
    }

    /**
     * OTA + 激活，拿 WS 配置。
     * 激活码会在 [AssistantState.AwaitingActivation] 里展示，等家长输完才继续。
     */
    private suspend fun acquireConfig(): XiaozhiOta.WsConfig {
        when (val outcome = ota.fetchOta()) {
            is XiaozhiOta.OtaOutcome.Ready -> return outcome.config
            is XiaozhiOta.OtaOutcome.NeedsActivation -> {
                _state.value = AssistantState.AwaitingActivation(outcome.code)
                ota.awaitActivation(outcome.challenge)
                // 激活完成：重新 OTA，这次云端会给 websocket 配置
                val second = ota.fetchOta()
                if (second is XiaozhiOta.OtaOutcome.Ready) return second.config
                throw IOException("激活完成但 OTA 仍未返回 websocket 配置")
            }
        }
    }

    /** 挂起直到断线；退出前释放 socket 与音频引擎（finally 保证） */
    private suspend fun runSession(cfg: XiaozhiOta.WsConfig) {
        val closed = CompletableDeferred<Unit>()
        // 认领本轮代次：下面每个回调都带着它，shutdown / 下一轮会话 +1 之后即自动失效。
        val epoch = sessionEpoch.incrementAndGet()
        lastDownlinkAt.set(SystemClock.elapsedRealtime())

        val sessionEngine = AudioEngine(
            codec = OpusCodec(),
            onFrame = { bytes ->
                // 声卡线程回调：只往**本轮**的 socket 上发，残骸一律丢
                if (epoch == sessionEpoch.get()) socket?.sendAudio(bytes)
            },
            onPlaybackDrained = drain@{
                if (epoch != sessionEpoch.get()) return@drain
                // 播放线程回调：队列里没有帧了，TTS 才算真正说完
                when (_state.value) {
                    is AssistantState.Speaking -> {
                        if (continuous && socket != null) {
                            // 电话式：AI 说完立刻重开麦续听，孩子不用再点「说」
                            _state.value = AssistantState.Ready
                            startListening()
                        } else {
                            _state.value = AssistantState.Ready
                        }
                    }
                    // 告别语说完：收尾回 Ready。continuous 已在 hangUp 里置 false，
                    // 所以这里**不会**再开麦续听（那会变成「挂了还在听」）。
                    is AssistantState.Farewell -> {
                        farewellJob?.cancel()
                        _state.value = AssistantState.Ready
                    }
                    else -> Unit
                }
            },
            onError = { msg -> if (epoch == sessionEpoch.get()) _subtitle.value = "（$msg）" },
            // 口型：TTS 每解出一帧就推一次嘴形（60ms 一帧，跟播放严格同步）
            onPlaybackShape = { s -> if (epoch == sessionEpoch.get()) _mouthShape.value = s },
        )
        val sessionSocket = XiaozhiSocket(
            url = cfg.url,
            token = cfg.token,
            deviceId = identity.deviceId,
            clientId = identity.clientId,
            onJson = { obj -> handleJson(epoch, obj) },
            onAudio = { bytes -> sessionEngine.enqueuePlayback(bytes) },
            onDisconnected = { closed.complete(Unit) },
        )
        engine = sessionEngine
        socket = sessionSocket
        // 假死看门狗：见 [stallWatchdog]。随会话退出（finally 里 cancel）。
        val watchdog = scope.launch { stallWatchdog(epoch, sessionSocket) }
        try {
            sessionEngine.startPlayback()
            _state.value = AssistantState.Connecting
            val serverAudio = sessionSocket.awaitServerHello()
            android.util.Log.i(TAG, "ws handshake ok, server ${serverAudio.sampleRate}Hz/${serverAudio.frameMs}ms")
            sessionEngine.serverSampleRate = serverAudio.sampleRate
            _state.value = AssistantState.Ready
            // 一键来电：如果这轮会话是被物理键叫起来的，握手一完成就直接开麦，
            // 不让孩子再点绿色按钮（意图为 false 时这里是空操作）。
            tryAutoTalk()
            closed.await() // 挂起直到断线（cancel 也会打断这里）
        } finally {
            watchdog.cancel()
            // ⚠️ 只清「还是自己那一份」的引用。协程取消是协作式的：快速「关 → 再开」时，
            //    旧协程的 finally 可能晚于新会话写入才跑，无条件置 null 会把**新会话**的
            //    socket/engine 一起抹掉 —— 表现是面板看着正常、按键却全哑。见 [sessionEpoch]。
            if (socket === sessionSocket) socket = null
            if (engine === sessionEngine) engine = null
            runCatching { sessionSocket.close() }
            sessionEngine.release()
        }
    }

    /**
     * 「假死」看门狗 —— 给 [XiaozhiSocket] 兜住一个**真实存在的盲区**。
     *
     * 背景：WS 的读超时必须是 0（长连接必需），也不能开 OkHttp 的 pingInterval
     * （小智服务端不回 pong，会被活活掐死，见 socket 里的注释）。于是当 TCP 被
     * NAT / 中间设备**静默回收**时，客户端既收不到 onFailure 也收不到 onClosed，
     * `closed.await()` 永不返回、重连逻辑永不触发。
     * 现象很迷惑人：状态停在绿按钮（看着好好的），孩子点「说话」后卡在
     * [AssistantState.Listening]，而且**不会自愈**。
     *
     * 判据：**通话中**（Listening / Thinking）持续一段时间没收到任何下行消息。
     * 正常说话时服务端 VAD 很快推 stt、LLM 回复也只要几秒 —— 超过下面的阈值还
     * 一片安静，只可能是链路断了。这时主动 close()，让 `closed` 完成、
     * [sessionLoop] 走标准退避重连（这就是「自愈」）。
     *
     * ⚠️ 只在**通话中**判：Ready 待机时本来就长时间没有下行，那是正常的，不能误杀。
     */
    private suspend fun stallWatchdog(epoch: Int, sessionSocket: XiaozhiSocket) {
        while (currentCoroutineContext().isActive) {
            delay(WATCHDOG_TICK_MS)
            if (epoch != sessionEpoch.get()) return
            val state = _state.value
            val limit = when {
                state is AssistantState.Listening -> LISTEN_STALL_MS
                state is AssistantState.Thinking -> THINK_STALL_MS
                else -> null
            } ?: continue
            val idleMs = SystemClock.elapsedRealtime() - lastDownlinkAt.get()
            if (idleMs > limit) {
                android.util.Log.w(
                    TAG,
                    "看门狗：$state 已 ${idleMs}ms 无任何下行，判定链路假死 → 主动断开重连"
                )
                runCatching { sessionSocket.close() }
                return
            }
        }
    }

    // ---- 下行消息分发（OkHttp WS 线程） ----

    private fun handleJson(epoch: Int, obj: JsonObject) {
        // 残骸消息直接丢（会话已关 or 已换成新一轮，见 [sessionEpoch]）。
        if (epoch != sessionEpoch.get()) return
        // 任何一条下行都证明「链路还活着」—— 看门狗据此复位。
        lastDownlinkAt.set(SystemClock.elapsedRealtime())
        val type = runCatching { obj["type"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return
        // 诊断用：每条下行打一行（只有 type/state/text，**不含**音频帧，日志量可忽略）。
        // 排查「小智到底说了什么 / 状态为什么不动」时不用再改代码重编。
        run {
            val dState = obj["state"]?.jsonPrimitive?.contentOrNull
            val dText = obj["text"]?.jsonPrimitive?.contentOrNull
            android.util.Log.d(TAG, "↓ type=$type state=$dState text=$dText")
        }
        when (type) {
            "stt" -> {
                // 孩子说的话：只用于内部状态机，**不显示成字幕**
                //   （靖哥哥要求：对话时不要在面板上出现文字，保持纯语音的电话感）。
                // auto 模式的关键收尾：服务端 VAD 判定「孩子说完了」才会推 stt，
                //   此刻要主动停掉麦克风上传，否则后面全是环境噪音会污染这一轮。
                if (_state.value is AssistantState.Listening) {
                    // 直接停麦：AudioEngine.stopRecording 已改成**只翻标志位、不阻塞**
                    // （见那边的注释），所以在这条 WS 读线程上调用是安全的。
                    engine?.stopRecording()
                    _state.value = AssistantState.Thinking
                }
            }

            "tts" -> {
                val st = obj["state"]?.jsonPrimitive?.contentOrNull
                when (st) {
                    "start" -> {
                        // 小智说的话：同样不做字幕，只推进状态。
                        // ⚠️ 告别中（Farewell）**不能**被改回 Speaking —— 那正是按钮
                        //    绿→红→绿闪一下的原因。告别语照常播（音频走 AudioEngine），
                        //    只是状态保持稳定的蓝色收尾态。
                        if (_state.value !is AssistantState.Farewell) {
                            _state.value = AssistantState.Speaking
                        }
                    }

                    // 句级字幕：既然不显示文字，这个分支无需处理
                    "sentence_start" -> Unit

                    "stop" -> engine?.markDraining()
                }
            }

            // auto 模式下服务端 VAD 判定「说完了」也会推 listen(state=stop)，
            //  不同服务端实现推 stt / listen stop 的先后不一，两个都兜住。
            "listen" -> {
                val st = obj["state"]?.jsonPrimitive?.contentOrNull
                if (st == "stop" && _state.value is AssistantState.Listening) {
                    engine?.stopRecording()
                    _state.value = AssistantState.Thinking
                }
            }

            // llm(表情) / system / hello：本 UI 暂不消费
            else -> Unit
        }
    }

    private fun readable(e: Exception): String = when (e) {
        is XiaozhiOta.ActivationTimeoutException -> "验证码一直没人输入，暂停了。再点开重试。"
        is UnknownHostException -> "连不上小智云端（检查网络）"
        is IOException -> e.message ?: "网络错误"
        else -> e.message ?: "未知错误"
    }

    private companion object {
        const val TAG = "XiaozhiAssistant"

        /** 假死看门狗的轮询间隔。 */
        const val WATCHDOG_TICK_MS = 5_000L

        /**
         * [AssistantState.Listening] 多久没收到任何下行就判定链路假死。
         *
         * 取 45 秒：孩子念一段完整的话用不到这个量级，服务端 VAD 一断句就会推 stt；
         * 整整 45 秒一个字都没回来，只可能是连接已经被中间设备静默回收了。
         * 宁可宽松一点 —— 误杀一条正常通话比晚 45 秒自愈更伤体验。
         */
        const val LISTEN_STALL_MS = 45_000L

        /**
         * [AssistantState.Thinking] 的假死阈值。
         *
         * 这一段是纯等服务端 LLM 回复，正常几秒就有；20 秒还没动静就是断了。
         */
        const val THINK_STALL_MS = 20_000L

        /**
         * 挂断时「喂」给小智的告别语（见 [hangUp]）。
         *
         * 走的是 `listen detect` + text 的文本输入通道，所以这段文字会被当作
         * **孩子说的话**交给 LLM，再由小智用自己的声音回一句告别。
         *
         * 刻意写成一句「我要结束了」的陈述而不是光秃秃的「再见」：陈述句能更稳地
         * 让模型收尾，不然它容易反问一句「还需要我帮忙吗」——那就不是告别了。
         * 想换话术直接改这里即可。
         */
        const val HANGUP_FAREWELL = "我先去写作业了，拜拜"

        /**
         * [AssistantState.Farewell] 的最长停留时间（见 [hangUp] 的兜底计时器）。
         *
         * 实测告别语从发出 detect 到播完约 4 秒。给 12 秒是留足「LLM 慢 + TTS 首帧慢」
         * 的余量；真等到 12 秒还没播完，说明这条会话已经不正常了 —— 状态必须落地回
         * [AssistantState.Ready]，否则按钮会一直灰着。
         */
        const val FAREWELL_TIMEOUT_MS = 12_000L
    }
}
