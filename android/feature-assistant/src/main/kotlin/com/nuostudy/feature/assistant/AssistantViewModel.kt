package com.nuostudy.feature.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.nuostudy.feature.assistant.audio.MouthShape
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * 悬浮助手的 ViewModel：会话状态机全在 [XiaozhiClient]（单例），这里只管**面板开关**。
 *
 * 它的价值有三个：
 *   1. 面板开关的**单一事实来源**（见 [panelOpen]）；
 *   2. 把「面板打开/关闭」翻译成会话的 start/shutdown（client 是 @Singleton，
 *      组合树重建（旋转/回收）不丢会话状态）；
 *   3. onCleared 兜底释放（进程级单例不会自己死，必须显式断）。
 *
 * ⚠️ 它必须是 **Activity 作用域**的：`MainActivity` 里的物理键要调 [togglePanel]，
 *    而 [AssistantOverlay]、[AssistantPanel] 用 `hiltViewModel()` 取 —— 三者
 *    在同一个 ViewModelStoreOwner（Activity）下拿到的是**同一个实例**。
 */
@HiltViewModel
class AssistantViewModel @Inject constructor(
    private val client: XiaozhiClient,
) : ViewModel() {

    val state: StateFlow<AssistantState> = client.state

    val subtitle: StateFlow<String?> = client.subtitle

    /**
     * 当前口型（开合度 0~1 + 圆扁度 -1~+1）。形象那边每来一个值就推一次 WebView。
     *
     * ⚠️ 它**必须走 StateFlow 而不是给 UI 一个回调**：这个值在播放线程产生，
     *    而 WebView 只能在主线程碰。StateFlow 顺带解决了线程切换，
     *    也顺带做了「相同的值不重复发」（静音段一整串「闭嘴」不会白推）。
     */
    val mouthShape: StateFlow<MouthShape> = client.mouthShape

    /**
     * 面板是否打开。**开关的唯一事实来源**。
     *
     * ⚠️ 原来它是一个写在 [AssistantOverlay] 里的 `rememberSaveable` 局部布尔，
     *    有两个后果：
     *      1. 平板上的物理键（`KEYCODE_F11`，见 `MainActivity.dispatchKeyEvent`）
     *         要在 Activity 层触发开关，那一层**看不到 Compose 的局部状态**，
     *         物理键根本没法接；
     *      2. 局部状态与真实会话状态各记一份，一旦漂移就会出现
     *         「点一下没反应、要点两下」—— 正是靖哥哥要求「按之前先检测小智状态」
     *         要防的那类 bug。
     *    提到 VM 之后，**悬浮球点击 / 面板右上角关闭 / 物理键** 三条路径共用同一个值。
     */
    var panelOpen by mutableStateOf(false)
        private set

    /**
     * 开关面板：**先检测当前状态，再决定开还是关**，不做无脑取反。
     * 这就是「开着就关、关着就开」—— 语义上等价于取反，但每一步都基于真实状态判断，
     * 崩溃/异常导致状态漂移时也不会出现「点不动」。
     *
     * @param autoTalk 打开时**直接接通通话**（平板物理键用；见 [openPanel]）。
     *   关闭时该参数无意义 —— 关就是挂断。
     */
    fun togglePanel(autoTalk: Boolean = false) {
        if (panelOpen) closePanel() else openPanel(autoTalk)
    }

    /**
     * 打开面板（并起会话）。幂等：已经开着就什么都不做。
     *
     * @param autoTalk 一键来电：会话**就绪后立刻开麦**，孩子不用再点绿色「接通」按钮。
     *   由 [AssistantOverlay] 的悬浮球走默认 `false`（先看面板、再决定要不要说），
     *   物理键走 `true`（按下即通话，像接电话）。
     */
    fun openPanel(autoTalk: Boolean = false) {
        if (panelOpen) {
            // 面板已开着：这次要求通话（例如开着但还没接通时又按了一下键），
            // 把意图补交给 client，它会视当前状态立即兑现或等就绪。
            if (autoTalk) client.startIfNeeded(autoTalk = true)
            return
        }
        panelOpen = true
        client.startIfNeeded(autoTalk = autoTalk)
    }

    /** 关闭面板（并断会话）。幂等：已经关着就什么都不做。 */
    fun closePanel() {
        if (!panelOpen) return
        panelOpen = false
        client.shutdown()
    }

    fun toggleTalk() = client.toggleTalk()

    override fun onCleared() {
        client.shutdown()
    }
}
