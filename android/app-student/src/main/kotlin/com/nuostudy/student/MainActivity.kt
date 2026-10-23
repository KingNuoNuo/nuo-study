package com.nuostudy.student

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.nuostudy.core.sync.LiveSync
import com.nuostudy.core.ui.theme.FormFactor
import com.nuostudy.core.ui.theme.StudyTheme
import com.nuostudy.feature.assistant.AssistantViewModel
import com.nuostudy.student.mirror.ScreenMirror
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var mirror: ScreenMirror

    /** 作业变更实时通道（SSE）。前台挂着，退后台断开 —— 见 LiveSync 注释。 */
    @Inject
    lateinit var liveSync: LiveSync

    /**
     * 小智助手面板的开关（物理键要在这里触发它）。
     *
     * ⚠️ 必须是**同一个实例**：AssistantOverlay / AssistantPanel 里的 `hiltViewModel()`
     *    在非导航场景下同样解析到 Activity 这个 ViewModelStoreOwner，
     *    与本处的 `by viewModels()` 是同一份 VM。两边各拿一份就会出现
     *    「键打开了、球上的状态没变」。
     */
    private val assistantVm: AssistantViewModel by viewModels()

    /**
     * 麦克风权限：物理键是「按下即通话」，没有权限就会开麦失败 —— 那正是最扫兴的体验。
     * 所以在这里前置申请，授予后**自动把那一通电话接通**，孩子只按了一下键，别的不用管。
     *
     * 先在字段初始化阶段注册（必须在 STARTED 之前，属性初始化满足这个时机）。
     */
    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // ⚠️ 这里必须是 openPanel（幂等：已开就只补交通话意图），**不能**用 togglePanel。
        //    权限弹窗期间面板状态不受我们控制 —— 孩子可能又按了一下物理键、
        //    或者别的地方把面板打开了。togglePanel 到那时会**把面板关掉**，
        //    表现是「授权完，界面反而没了」，而且极难复现。
        if (granted) {
            assistantVm.openPanel(autoTalk = true)
        } else {
            // 拒绝了：面板还是打开，里面会显示系统提示，孩子能看见「为什么没声音」；
            // 后续在面板里点绿色按钮会再走一次申请（AssistantPanel.onTalk）。
            assistantVm.openPanel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 进 App 就是全屏：藏掉状态栏 + 导航栏。见 hideSystemBars()。
        hideSystemBars()
        setContent {
            // ⚠️ formFactor 必须是 Student —— 它决定了整套尺寸 token（字号放大一档、
            //    触控区 56dp）。孩子端与家长端共用同一份主题代码，差异全在这里分流。
            StudyTheme(formFactor = FormFactor.Student) {
                StudentRoot()
            }
        }

        // 「实时画面」镜像（调试功能，见 ScreenMirror）。
        // 挂在这里而不是放进 Composable：它要抓的是**整个窗口**，必须拿到 Activity；
        // 而且它自己管生命周期（只在 RESUMED 期间抓帧），与 Compose 的重组周期无关。
        // 开关在学生端设置页里，默认开（ServerDefaults.MIRROR_ENABLED）。
        mirror.bind(this)
    }

    /**
     * 全屏沉浸：藏掉状态栏（顶部时间/电量/通知）和导航栏（底部手势条 / 三键）。
     *
     * **为什么孩子端要这么做**：这台上是「作答台」，整屏只该有学习内容。顶部状态栏会
     * 滚出通知、底部导航栏容易被误触直接退出 App —— 都是分心源。
     *
     * **为什么用 [WindowInsetsControllerCompat] 而不是老的
     * `WindowManager.LayoutParams.FLAG_FULLSCREEN`**：后者只管状态栏，且从 Android 11
     * 起对导航栏已被忽略（设了也不生效）。InsetsController 是官方推荐做法。
     *
     * **`BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE` 是特意选的**，不是彻底禁掉系统栏：
     * 从屏幕边缘上滑/下滑仍能临时唤出系统栏（几秒后自动缩回）。留着这条活路，
     * 万一真需要（比如要开 WiFi、处理弹窗），还能操作，不至于把设备锁死。
     *
     * ⚠️ 光在 onCreate 调一次**不够**。系统栏会在这些时机被系统自己放回来：
     *    下拉通知栏、弹权限框、切输入法、锁屏解锁 —— 共同点是 Activity 重新拿到焦点。
     *    所以 [onWindowFocusChanged] 里必须再藏一次，否则用一会儿顶部就冒出来了。
     */
    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    /**
     * 拿到焦点时把系统栏再藏一次。
     *
     * @see hideSystemBars 里说明了为什么这一处不能省。
     */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        // 家长在手机上一发布/删除/修正作业，服务端广播 SSE 事件，这里秒级感知并
        // 重新拉取 —— 不用等 6 小时周期任务，也不用孩子手动点刷新。
        // 只在可见期间挂连接：退后台由 WorkManager 周期任务兜底。
        liveSync.start()
    }

    override fun onStop() {
        liveSync.stop()
        super.onStop()
    }

    /**
     * 平板侧边的物理「小智」键 —— **一键接电话**。
     *
     * 语义（靖哥哥要求）：按一下 = 打开小智并**直接进入通话**，不用再点绿色按钮；
     * 再按一下 = 挂断并关掉面板。所以「检测状态」这一步仍然在
     * [AssistantViewModel.togglePanel] 里 —— 开着就关、关着就（带通话意图）开。
     *
     * 为什么要重写 [dispatchKeyEvent] 而不是 `onKeyDown`：这里能同时拿到 DOWN / UP，
     * 而且是在**事件进入 window 之前**拦截，确保不会漏给系统或下面的视图。
     *
     * 触发规则：
     *  - 只在 ACTION_DOWN 且 `repeatCount == 0` 时动作 —— 长按按住不放会连发
     *    repeatCount>0 的 DOWN，若不判就会疯狂开/关；
     *  - DOWN 和 UP **都 return true** 消费掉（F11 本身没有系统默认行为，
     *    吃掉它不会顶掉任何功能）；
     *  - 面板关着时先确保**有麦克风权限**再开 —— 没权限的「直接通话」等于开了个哑巴面板，
     *    见 [micPermissionLauncher]。
     *
     * ⚠️ 不要在别处再监听这个键，否则一次按键会触发两遍（开了又立刻关）。
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KEY_ASSISTANT) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                if (assistantVm.panelOpen) {
                    // 已在通话/已开着 → 挂断并收起（不需要权限）
                    assistantVm.togglePanel()
                } else if (
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    // 一键来电：打开 + 就绪后自动开麦
                    assistantVm.togglePanel(autoTalk = true)
                } else {
                    // 首次使用：先把权限拿到手，授予后自动把那通电话接上
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        /**
         * 平板上的物理「小智」键。
         *
         * 实测（`getevent -lt`，2026-10-10）：设备 `gpio-keys`（`/dev/input/event4`）
         * 上报 Linux scancode **87** → 经 `/system/usr/keylayout/Generic.kl`（`key 87 F11`）
         * 映射为 `KEYCODE_F11` = **141**。
         *
         * ⚠️ 别把 141 记成 131 —— `KEYCODE_F1` 才是 131，F 系列是 131~142 连续排下来的
         *    （F11 是第 11 个）。用 `adb shell input keyevent 141` 可以精确模拟这个键；
         *    发 131 只会命中 F1，什么都测不出来，而且现象是「按了完全没反应」，
         *    极易被误判成代码没生效。
         *
         * 系统对这个键没有任何默认行为（按下去原本「没反应」），所以接管它是安全的。
         * 该键在其它 App 里也不会有任何动作 —— 只有学生端认它。
         */
        private const val KEY_ASSISTANT = KeyEvent.KEYCODE_F11
    }
}
