package com.nuostudy.feature.assistant

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nuostudy.core.ui.theme.AnswerCorrect
import com.nuostudy.core.ui.theme.AnswerWrong
import com.nuostudy.core.ui.theme.BrandBlue
import com.nuostudy.core.ui.theme.SurfaceBase
import com.nuostudy.core.ui.theme.WarningAmber
import com.nuostudy.feature.assistant.avatar.Live2DAvatar

/**
 * 悬浮助手对外唯一入口：右下角小姐姐 + 点开后的对话面板。
 *
 * 结构决策：
 *   - **悬浮球永远在**（纯本地动画，无网络）：它是「功能存在」的可见线索；
 *   - 会话随**面板**开/关（打开 = startIfNeeded，关闭 = shutdown）：
 *     孩子不会 7×24 挂着一条到公网的 WebSocket，电量流量都经不起这么造。
 *   - 整个 overlay 不拦截面板/球以外的触摸 —— Box 上不加 clickable，
 *     子元素之外的事件自然穿透到下面的 Scaffold。
 */
@Composable
fun AssistantOverlay(modifier: Modifier = Modifier) {
    val vm: AssistantViewModel = hiltViewModel()

    // ⚠️ 面板开关**不再**是本文件的局部状态（原来是 rememberSaveable）：
    //    它现在住在 AssistantViewModel.panelOpen 里，因为平板上的物理键
    //    （KEYCODE_F11，见 MainActivity）要在 Activity 层触发同一个开关。
    //    UI 层各记一份布尔必然与真实状态漂移 —— 那正是「点一下没反应」的来源。

    // ⚠️ 悬浮球贴着屏幕右下角时会被系统导航栏（手势条/三键）压住，
    //   孩子点不到。用 safeDrawing 的【底部 + 横向】inset 把整个 overlay 顶上来：
    //   - 底部：避开导航栏；
    //   - 横向：横屏设备侧边挖孔/手势区。
    //   不加 Top——顶部没有遮挡球的东西，加了反而让面板莫名下移。
    Box(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(
                    WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal
                )
            ),
    ) {
        AnimatedVisibility(
            visible = vm.panelOpen,
            enter = fadeIn() + expandVertically(expandFrom = Alignment.Bottom),
            exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Bottom),
            // ⚠️ 面板**居中**，不再贴右下角：靖哥哥反馈「画面没必要在右边」。
            //    居中的另一个好处是它跟右下角的悬浮球彻底分开，两者不会互相压。
            modifier = Modifier.align(Alignment.Center),
        ) {
            AssistantPanel(onClose = vm::closePanel)
        }

        AssistantBubble(
            state = vm.state.collectAsStateWithLifecycle().value,
            // 悬浮球 = 开关：开着就关、关着就开（判断逻辑在 VM 里，见 togglePanel）
            // ⚠️ 必须写 lambda，不能写 `vm::togglePanel` —— togglePanel 带默认参数
            //    （autoTalk），Kotlin 的函数引用**不享受默认值**，`::togglePanel` 的类型是
            //    `(Boolean) -> Unit`，赋给 `() -> Unit` 直接编译不过。
            //    悬浮球走默认 autoTalk=false：先看面板、再决定说还是不说（物理键才一键通话）。
            onClick = { vm.togglePanel() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // 底边再额外留 8dp：inset 之外给导航栏一点呼吸，不贴着边
                .padding(end = 12.dp, bottom = 8.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 状态语义色 —— 悬浮球光环与面板头像描边共用一套，避免两处各写一套色
// ---------------------------------------------------------------------------

/**
 * 状态强调色。悬浮球的光环、面板头像的描边与柔光都读它。
 * 教育场景约定：绿 = 就绪 / 红 = 收音或出错 / 黄 = 忙 / 蓝 = 品牌与说话。
 */
private fun stateAccent(state: AssistantState): Color = when (state) {
    is AssistantState.Listening -> AnswerWrong
    is AssistantState.Error -> AnswerWrong
    is AssistantState.Thinking -> WarningAmber
    is AssistantState.Connecting -> WarningAmber
    is AssistantState.AwaitingActivation -> WarningAmber
    is AssistantState.Speaking -> BrandBlue
    is AssistantState.Ready -> BrandBlue
    is AssistantState.Idle -> BrandBlue
    // 告别中也是蓝的：这几秒是「收尾」不是「又接通了」，用 Speaking 的红会让
    // 孩子按下挂断后又看到一次红按钮（原来那一下闪烁的来源之一）。
    is AssistantState.Farewell -> BrandBlue
}

// ---------------------------------------------------------------------------
// 悬浮球
// ---------------------------------------------------------------------------

/**
 * 球径。56dp = 原 84dp 的 2/3（靖哥哥反馈太大）。
 * 孩子端最小可点区域 56dp，正好卡在合规线上 —— 再缩就点不准了。
 */
private val BUBBLE_SIZE = 56.dp

@Composable
private fun AssistantBubble(
    state: AssistantState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 说话时轻微呼吸：给「她在发声」一个动觉线索，孩子不用识字也能看懂状态。
    //
    // ⚠️ 这里**不能**写 `val scale by breath.animateFloat(...)` —— 在 composable
    //    函数体顶层读动画状态，等于每帧都重组这个 composable（而 scale 只在
    //    Speaking 时用得上）。改成下面在 `graphicsLayer{}` 里读：读取发生在**绘制阶段**，
    //    只触发重绘、不触发重组。非 Speaking 时短路掉 `s.value`（不读 = 不订阅），
    //    连重绘都不会有。
    val breath = rememberInfiniteTransition(label = "breath")
    val scale = breath.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "scale",
    )
    // 告别中她也确实在发声，悬浮球照常呼吸 —— 视觉上「她还在说话」，只是按钮不再变红。
    val speaking = state is AssistantState.Speaking || state is AssistantState.Farewell

    val accent = stateAccent(state)
    Box(
        modifier = modifier
            .size(BUBBLE_SIZE)
            .shadow(6.dp, CircleShape)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .border(3.dp, accent, CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.assistant_avatar),
            contentDescription = "学习小助手",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val s = if (speaking) scale.value else 1f
                    scaleX = s
                    scaleY = s
                },
        )
    }
}

// ---------------------------------------------------------------------------
// 对话面板
// ---------------------------------------------------------------------------

/**
 * 面板宽度 = 左内边距 + 角色画面宽 + 间距 + 右侧按钮列宽 + 右内边距
 *        = 12 + 280 + 16 + 112（按钮列宽 = 最宽的 [PILL_WIDTH]） + 12 = 432dp。
 *
 * 形态上是一张**居中的卡片**：左边人物、右边一列按钮，贴近手机视频通话的样子 ——
 * 靖哥哥要的是「人物为主体，按钮在人物右侧」，不是「屏幕右边挂个浮层」，
 * 也不是「按钮压在画面下面把主体顶小」。
 *
 * ⚠️ 整体放大过一轮（394 → 432，见 [STAGE_HEIGHT]），**约束是高度不是宽度**：
 *    app 可用区 1280×728px、density 240 → 逻辑只有 485dp 高（顶部那 72px 被系统占了），
 *    面板总高 = 画面 408 + 上下内边距 24 = 432dp，再给偶发的 [SystemTip] 留 ~40 → 472，
 *    离天花板只剩十几 dp。想再大就得先解决「提示条一出现就顶破屏幕」，别直接加 STAGE_HEIGHT。
 */
private val PANEL_WIDTH = 432.dp

/**
 * 角色画面尺寸 —— **必须是竖长方形**，比例 ≈ 0.687。
 *
 * 页面里的 `fit=panel` 是「按人物总高的比例取上半身」，取到的身体跟容器**高度**绑定；
 * 容器越宽，人物两侧的空白越多。实测：近正方形的 480×450 槽里人物只占中间 40% 宽，
 * 看着像一张没裁好的照片。
 *
 * 408dp 是照屏幕高度倒推的：平板 app 可用区 1280×728px、density 240（1dp=1.5px）
 * → 逻辑高 485dp，减去面板上下内边距 24，再给偶发的 [SystemTip] 留 ~40
 * → 天花板约 421dp，取 408 留一点余量。
 * （靖哥哥 2026-10-10 要求「面板窗口更大一点」，从 364 放过一轮；
 * 剩下那十几 dp 的余量是**给提示条的**，别再动，否则它一出现面板就顶破屏幕。）
 *
 * ⚠️ 布局改成「按钮在人物右侧」之后，画面下方不再被按钮列占位 ——
 *    但**放大幅度要宽高同改**：`fit=panel` 是按高度等比的，只加高不加宽，
 *    人物会横向溢出被裁掉肩膀；只加宽不加高，两侧又会多出空白。
 *    保持 280/408 ≈ 0.686 这个竖比例不变。
 */
private val STAGE_WIDTH = 280.dp
private val STAGE_HEIGHT = 408.dp

/** 角色画面圆角。比面板的 28dp 小一档 —— 内外同值会显得「盒子套盒子」，很呆。 */
private val STAGE_RADIUS = 20.dp

@Composable
private fun AssistantPanel(onClose: () -> Unit) {
    val vm: AssistantViewModel = hiltViewModel()
    val state by vm.state.collectAsStateWithLifecycle()
    val subtitle by vm.subtitle.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    // 麦克风运行时权限：点接通的**当下**才弹（不要面板一开就问，
    // 孩子会被莫名其妙的系统弹窗吓到 —— 与学生端朗读题的权限时机同一原则）
    val micLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // ⚠️ 不能无条件 vm.toggleTalk()。它是**切换**语义，而权限弹窗期间状态不是
        //    我们能保证的：孩子可能又按了一下物理键（那条路会 openPanel(autoTalk=true)
        //    并直接接通），此时再 toggle 就会把刚接通的电话**挂断** ——
        //    现象是「授权完反而挂了」，且只在特定时序下出现。
        //    只在状态确实还是「可接通」时才发起。（MainActivity 的权限回调同理。）
        if (granted && state is AssistantState.Ready) vm.toggleTalk()
    }

    val onTalk: () -> Unit = {
        if (
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            vm.toggleTalk()
        } else {
            micLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    Surface(
        // 28dp 圆角 + 20dp 投影：这张卡片是屏幕正中唯一的主体，轮廓得立得住
        shape = RoundedCornerShape(28.dp),
        // ⚠️ 不能用 colorScheme.surface：暗色下 surface 和 background 都是 DarkSurface(#16191F)，
        //    面板会直接消失在 App 背景里。用 surfaceVariant 取「下一层」的对比色。
        color = MaterialTheme.colorScheme.surfaceVariant,
        shadowElevation = 20.dp,
        // 描边用 outlineVariant（比 outline 深一档）：暗色下 1dp outline 只有 ~14% 的明度差，
        //    实测肉眼几乎看不见，换深一档才勾得出轮廓。
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.width(PANEL_WIDTH),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // ⚠️ 这里**刻意不要**顶部那条品牌渐变条、也不要「小智」标题：
            //    靖哥哥要求「上面没必要显示小智」。去掉之后角色画面直接顶到面板内沿，
            //    视觉上人物才是主体，而不是「一个标题下面挂张图」。
            //    代价是品牌标识只剩悬浮球那一个入口 —— 在一台只跑这一个 App 的平板上够用。
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
        ) {
            when (val s = state) {
                // 激活态要的是**大字验证码**，角色画面在这种一次性流程里只会抢注意力，
                // 所以整块换掉（也让面板高度不至于撑破屏幕）。
                is AssistantState.AwaitingActivation -> {
                    ActivationCard(s.code)
                    Spacer(Modifier.height(16.dp))
                    CloseButton(onClose, Modifier.align(Alignment.End))
                }

                else -> {
                    // 左：角色画面；右：一列按钮（接听/挂断在上、关闭在下）。
                    // ⚠️ 两个按钮都**不能**叠在角色画面上 —— 里面是 WebView（真 View），
                    //    它永远画在 Compose 之上，压上去的控件点不到、也看不见。
                    //    所以按钮列只能放在画面**右侧**，跟画面并排。
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Live2DAvatar(
                            state = s,
                            mouthShape = vm.mouthShape,
                            modifier = Modifier
                                .size(STAGE_WIDTH, STAGE_HEIGHT)
                                .clip(RoundedCornerShape(STAGE_RADIUS)),
                        )

                        // 画面与按钮列之间固定 16dp；按钮列宽度由 [PILL_WIDTH] 定死，
                        // [PANEL_WIDTH] 就是照这三者加出来的。
                        Spacer(Modifier.width(16.dp))

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // 先看状态（眼睛）再做动作（手）：标志在上、按钮在下。
                            StatusPill(state = s)
                            Spacer(Modifier.height(10.dp))
                            TalkButton(state = s, onTalk = onTalk)
                            Spacer(Modifier.height(18.dp))
                            CloseButton(onClose)
                        }
                    }
                }
            }

            // 系统提示（权限被拒等）：和「她说的话」在视觉上明确分开。
            // 激活态下也照常显示 —— 那时最需要告诉家长「去 xiaozhi.me 输码」。
            subtitle?.let { tip ->
                Spacer(Modifier.height(10.dp))
                SystemTip(tip)
            }
        }
    }
}

/**
 * 关闭面板。一颗**低调的圆形浅底按钮**，压在按钮行的右端。
 *
 * 为什么不再是顶部带「小智」字样的表头：靖哥哥说上面没必要显示小智。
 * 去掉标题后，关闭按钮只能另找落脚点 —— 而它**不能**浮在角色画面角上，
 * 因为那里是 WebView（真 View），会把它整个盖住、点不到。所以放画面下方同一行。
 *
 * 尺寸 44dp、半透明灰底：关闭 = 挂断 + 收起面板（和通话按钮走同一个 closePanel），
 * 语义比通话弱，不该跟红色的挂断键抢注意力。44dp 也还在孩子可点范围内。
 */
@Composable
private fun CloseButton(onClose: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f))
            .clickable(onClick = onClose),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "关闭助手",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 状态文案 —— **已去掉**（靖哥哥要求「取消下方提醒字样」）。
 *
 * 之前的实现是画面下方一行小字（「我在听…」「点绿色按钮接通…」）。去掉的理由：
 *  1. 状态其实已经由**人物自己**表达了 —— 待机/听/说各有动作，比一行字直观；
 *  2. 通话按钮的**颜色和图标**本身就说明「现在能点成什么」（绿=接通、红=挂断），
 *     再配一行字是重复的；
 *  3. 它占着画面下方那一条，把人物挤小了 —— 移到右侧按钮列之后这条空间没必要留。
 *
 * ⚠️ 唯一有信息量的是**出错**和**等待家长激活**：激活态走 [ActivationCard]（大字验证码），
 *    出错走 [SystemTip]（琥珀提示条），两条都还在，没丢信息。
 */

/**
 * 系统提示条（如麦克风权限被拒）：琥珀底 + 前导圆点。
 * 琥珀色和角色画面那块深蓝、以及通话按钮的绿/红都不会混淆，
 * 一眼能看出「这是 App 在跟你说事，不是小智在说话」。
 */
@Composable
private fun SystemTip(text: String) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(WarningAmber.copy(alpha = 0.14f))
            .border(1.dp, WarningAmber.copy(alpha = 0.35f), shape)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(WarningAmber),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            // 2 行：面板高度是算着屏幕来的，提示条多一行就会把角色画面挤出屏幕
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ActivationCard(code: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "需要家长激活",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        // 验证码放进品牌浅蓝胶囊：6 位数字在浅色容器里比浮在白底上更好辨认
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 28.dp, vertical = 16.dp),
        ) {
            Text(
                text = code ?: "…",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                letterSpacing = 8.sp,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = "请家长打开 xiaozhi.me 注册并登录，在「设备管理」里输入上面的验证码。" +
                "输好后这里会自动连上，不用关掉面板。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// ---------------------------------------------------------------------------
// 状态标志（胶囊）—— 按钮正上方那一条，回答孩子「现在轮到谁」
// ---------------------------------------------------------------------------

/**
 * 状态标志的固定宽度。
 *
 * ⚠️ **必须固定**，不能让 pill 随文字长短自适应。它在按钮列（Column，宽度取最宽子项）
 *    里，pill 一变宽，整列就变宽，居中排的按钮会跟着左右滑动 —— 状态每切一次，
 *    按钮就挪一下位置，孩子正要按的那个键会「跑」。
 *
 * 112dp = 面板 432 − 左右内边距 24 − 画面 280 − 间隙 16，正好把按钮列用满（见 [PANEL_WIDTH]）。
 *
 * ⚠️ 文案宽度上限（胶囊内宽 112−18 = 94dp）：
 *   - **带音波柱**的：音波柱 13dp + 间隙 6dp 占掉 19dp，文字只剩 75dp；
 *   - 不带音波柱的：94dp 全给文字。
 *   上一版只有 100dp（带波只剩 63dp），「小智正在说…」≈ 69dp 尾巴被切 ——
 *   这正是靖哥哥报的「文字显示不全」。所以**别把这个宽度缩回去**。
 *
 * ✅ 字号不再写死：见 [PILL_FONT_MAX] / [PILL_FONT_MIN] / [PILL_FONT_STEP] ——
 *    每种状态用自己的文案在上述宽限内**自动取到最大字号**（`Text(autoSize=…)`）。
 *    短文案（「小智在想」4 字）能顶到 [PILL_FONT_MAX]，长文案（「等家长验证码」6 字）
 *    自动缩到刚好放得下。`TextOverflow.Ellipsis` 只剩极端兜底作用（系统字体被调超大时）。
 */
private val PILL_WIDTH = 112.dp

/**
 * 状态文案的字号自适应区间。
 *
 * 用 `TextAutoSize.StepBased`（Compose Foundation 1.12 / BOM 2026.09 起稳定，无需 OptIn）：
 * 从 [PILL_FONT_MAX] 起按 [PILL_FONT_STEP] 往下降，取**第一个放得进单行**的字号。
 * 于是「尽量大」由框架保证，不必再手算「一个汉字 ≈ 多少 dp」——
 * 那种手算一旦碰上标点（全角的「，」「…」也是 1em）就会失准。
 *
 * [PILL_FONT_MAX] 定 19sp：比原来写死的 11sp 大出七成，且 19 字号的**行高**
 * （labelSmall 的 lineHeight 16sp @ fontSize 11sp，按比例 → ≈27.6dp，加 12dp 上下内边距
 * ≈ 39.6dp）仍塞得进 [PILL_MIN_HEIGHT]，**不会把胶囊顶高**。
 * 再往上（20sp+）就得抬高 [PILL_MIN_HEIGHT]，面板高度已经贴到天花板了，不值当。
 */
private val PILL_FONT_MAX = 19.sp
private val PILL_FONT_MIN = 13.sp
private val PILL_FONT_STEP = 0.5.sp

/**
 * 胶囊的**固定最小高度**（41dp）。
 *
 * ⚠️ 这是「按钮不乱跑」的另一半：宽度已经定死（[PILL_WIDTH]），但字号是自适应的，
 *    **不同状态的行高不同** → 胶囊高度会跟着变 → 它下面的通话按钮、关闭按钮
 *    会随状态切换上下滑几 dp。孩子正要按的那个键同样会「跑」。
 *    所以给胶囊一个下限，只要文字不超过它，高度就恒为 41dp（含 12dp 上下内边距）。
 *
 * 41dp = 19sp 字号的行高 ≈27.6dp + 上下内边距 12dp，留了约 1.4dp 余量。
 * 系统字体被调到很大时文字可能超出 → 用 `heightIn(min=…)` 而不是 `height(…)`，
 * 此时胶囊自然增高（宁可按钮挪一下，也不能把字裁掉）。
 */
private val PILL_MIN_HEIGHT = 41.dp

/** 胶囊内宽：左右各 9dp 内边距之后剩下的部分。 */
private val PILL_INNER_WIDTH = PILL_WIDTH - 18.dp

/**
 * 文字能占的最大宽度：有没有音波柱差 19dp（三条柱 13dp + 与文字之间的 6dp 间隙）。
 *
 * ⚠️ 这两个值现在**直接决定字号上限** —— `Text(autoSize=…)` 就是在这个宽限里
 *    找最大字号。调窄它们 = 把字调小，别顺手改。
 */
private val PILL_TEXT_MAX_PLAIN = PILL_INNER_WIDTH
private val PILL_TEXT_MAX_WAVING = PILL_INNER_WIDTH - 19.dp

/** 音波柱：3dp 宽、最高 12dp，圆角柱。三条并排的整宽 = 3×3 + 2×2 = 13dp。 */
private val PILL_BAR_WIDTH = 3.dp
private val PILL_BAR_HEIGHT = 12.dp

/** 状态 → (文案, 强调色, 是否显示音波柱)。 */
private fun statusLabel(state: AssistantState): Triple<String, Color, Boolean> = when (state) {
    // ⚠️ 这是个**穷尽** when（没有 else）：以后谁给 AssistantState 加了新状态，
    //    编译器会立刻在这里报错提醒补文案，而不是静默显示成上一句。
    is AssistantState.Idle, is AssistantState.Ready -> Triple("可以通话了", BrandBlue, false)
    // 唯一「等孩子」的状态：用收音的红（与红色挂断键同色系）+ 跳动的音波，
    // 让孩子一眼看出「现在该我说了」。
    is AssistantState.Listening -> Triple("我在听你说", AnswerWrong, true)
    // ⚠️ 带音波柱的**不挂省略号**：柱子在跳本身就是「进行中」的视觉指示，再加一个「…」纯冗余。
    //    「小智在想」/「小智在说」也正好对仗。（原先写作「小智正在说…」，在 100dp 的旧胶囊里
    //    被切过尾巴，见 [PILL_WIDTH]。）
    is AssistantState.Thinking -> Triple("小智在想", WarningAmber, true)
    is AssistantState.Speaking -> Triple("小智在说", BrandBlue, true)
    is AssistantState.Farewell -> Triple("告别中…", BrandBlue, false)
    is AssistantState.Connecting -> Triple("正在连接…", WarningAmber, false)
    is AssistantState.AwaitingActivation -> Triple("等家长验证码", WarningAmber, false)
    is AssistantState.Error -> Triple("连不上，重试", WarningAmber, false)
}

/**
 * 「现在是谁在说」的胶囊标志。放在通话按钮**正上方**。
 *
 * 为什么不把状态写进按钮里：按钮回答的是**动作**（接通 / 挂断），而孩子点它之前需要
 * 知道的是**当下谁在说**。两件事塞进一颗按钮，孩子会以为「要按它才轮到我说话」。
 * 拆成「上面看状态、下面做动作」，一个是眼睛的、一个是手的。
 */
@Composable
private fun StatusPill(state: AssistantState, modifier: Modifier = Modifier) {
    val (label, rawAccent, waving) = statusLabel(state)
    // ⚠️ 暗色面板上**不能**直接拿品牌色当文字：#2F6FED 压在 #1E222A 上对比度不够，
    //    蓝字会糊在底里（红/黄尚可，蓝最明显）。往白里提 30% 只作用于这一层文字与描边；
    //    按钮、悬浮球那种**实心色块**仍用原色 —— 它们要的是色相识别，不是可读性。
    val accent = if (isSystemInDarkTheme()) lerp(rawAccent, Color.White, 0.30f) else rawAccent
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = modifier
            .width(PILL_WIDTH)
            // ⚠️ 高度下限见 [PILL_MIN_HEIGHT]：字号是自适应的 → 每个状态的行高不同，
            //    不给下限的话胶囊高度会随状态变，下面两颗按钮就跟着上下滑。
            .heightIn(min = PILL_MIN_HEIGHT)
            // 16% 淡底 + 34% 描边：克制但看得清。底色是半透明叠在 surfaceVariant 上的，
            // 所以深浅两套主题都不用另配一组「胶囊专用色」。
            .clip(shape)
            .background(accent.copy(alpha = 0.16f))
            .border(1.dp, accent.copy(alpha = 0.34f), shape)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 音波柱只在「正在说 / 正在听」时出现：静态状态下多一个动效纯属噪音。
        // 它出现/消失只影响 pill 内部（宽度固定），不会推到外面的按钮。
        if (waving) {
            WaveBars(accent)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            // ⚠️ 字距归零：labelSmall 自带 0.5sp letterSpacing（为拉丁字母调的），
            //    汉字之间本来就不加字距；而 autoSize 会把它随字号一起放大，
            //    6 个字白吃 3dp 多，正好是从「刚好放得下」变成「差一点」的那几 dp。
            letterSpacing = 0.sp,
            color = accent,
            maxLines = 1,
            // 兜底（用户把系统字号调得极大、或以后有人加长文案）：宁可显示「…」，
            // 也不要把半个字生生切掉 —— 硬裁看着像渲染故障，省略号至少是「还有内容」。
            overflow = TextOverflow.Ellipsis,
            // 「尽量最大」由框架算：在下面 widthIn 给的宽限内，从 [PILL_FONT_MAX]
            // 按 [PILL_FONT_STEP] 往下降，取第一个放得进单行的字号。
            // 短文案（「小智在想」）顶到 19sp，长文案（「等家长验证码」）自动落到 ~15.5sp，
            // 不必再手算「一个汉字几 dp」——那种手算碰上全角标点（，…）就失准。
            autoSize = TextAutoSize.StepBased(
                minFontSize = PILL_FONT_MIN,
                maxFontSize = PILL_FONT_MAX,
                stepSize = PILL_FONT_STEP,
            ),
            modifier = Modifier.widthIn(
                max = if (waving) PILL_TEXT_MAX_WAVING else PILL_TEXT_MAX_PLAIN,
            ),
        )
    }
}

/**
 * 三条错落跳动的音波柱 —— 给「正在出声 / 正在收听」一个**动觉**线索：
 * 不识字的孩子也能看出对方在说（动得快）还是自己在听（动得慢）。
 *
 * ⚠️ 动画值一律在 `graphicsLayer{}` 里读，**不要**写 `val h by animateFloat(...)`：
 *    在 composable 顶层读动画状态 = 每帧重组这个组件（项目里 [AssistantBubble] 踩过同一个坑）。
 *    延迟到绘制阶段读只触发重绘。三条柱周期各不相同、且正反相，看起来才是「跳动」
 *    而不是整齐地一起呼吸。
 */
@Composable
private fun WaveBars(color: Color, modifier: Modifier = Modifier) {
    val wave = rememberInfiniteTransition(label = "wave")
    val bar1 = wave.animateFloat(
        initialValue = 0.40f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(430), RepeatMode.Reverse), label = "bar1",
    )
    val bar2 = wave.animateFloat(
        initialValue = 1f, targetValue = 0.40f,
        animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "bar2",
    )
    val bar3 = wave.animateFloat(
        initialValue = 0.55f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(360), RepeatMode.Reverse), label = "bar3",
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        WaveBar(bar1, color)
        Spacer(Modifier.width(2.dp))
        WaveBar(bar2, color)
        Spacer(Modifier.width(2.dp))
        WaveBar(bar3, color)
    }
}

/**
 * 单条音波柱。
 *
 * 高度靠 `scaleY` 缩放而不是每帧改 `height`：改高度会触发**重新布局**（measure/layout），
 * 缩放在绘制阶段完成，便宜一个数量级。变换原点默认在中心，所以是上下对称地涨缩。
 */
@Composable
private fun WaveBar(phase: State<Float>, color: Color) {
    Box(
        Modifier
            .size(PILL_BAR_WIDTH, PILL_BAR_HEIGHT)
            .graphicsLayer { scaleY = phase.value }
            .background(color, RoundedCornerShape(percent = 50)),
    )
}

// ---------------------------------------------------------------------------

/**
 * 通话按钮：**绿色接听 / 红色挂断**，和手机拨号器同一套视觉直觉。
 *
 * 图标语义：[Icons.Filled.Phone] = 听筒朝上（接通），[Icons.Filled.CallEnd] = 听筒朝下（挂断）。
 * 之前用中文大字「说/挂」是为了绕开 material-icons-extended 依赖；现在依赖已显式声明，
 * 图标比汉字更国际化、也更贴近「打电话」这个隐喻本身。
 *
 * ⚠️ 图标 tint 用固定白色 [SurfaceBase] 而不是 `onPrimary`：
 *  按钮底色是固定的 AnswerCorrect / AnswerWrong（不随主题变），
 *  但暗色模式下 `onPrimary` 是 BrandBlueDark（深蓝），深蓝压在绿底/红底上就看不清了。
 */
/**
 * 通话按钮径 84dp / 柔光晕径 110dp / 图标 38dp。孩子端最小可点区域 56dp，84dp 明显超线。
 *
 * ⚠️ 这三个值跟着 [STAGE_HEIGHT] 一起放大过 —— 面板整体变大时按钮不跟着长会显得缩水。
 *    **光环 110dp 必须 ≤ [PILL_WIDTH](112dp)**：按钮列（Column）宽度取最宽子项，
 *    光环一旦超过胶囊，列的宽度就由光环决定，[PANEL_WIDTH] 的算式随即失准。
 */
private val TALK_BUTTON_SIZE = 84.dp
private val TALK_HALO_SIZE = 110.dp
private val TALK_ICON_SIZE = 38.dp

@Composable
private fun TalkButton(state: AssistantState, onTalk: () -> Unit, modifier: Modifier = Modifier) {
    val enabled = state is AssistantState.Ready ||
        state is AssistantState.Listening ||
        state is AssistantState.Thinking ||
        state is AssistantState.Speaking ||
        // ⚠️ Error 也必须是**可点**的。早先它落在 disabled 里、点击也无人接管，
        //    于是「出错」变成了死状态：按钮灰着、按了没反应，孩子唯一的退出口
        //    只剩关面板。现在点它是「重试连接」（见 XiaozhiClient.toggleTalk 的 Error 分支）。
        state is AssistantState.Error
    // 注：[AssistantState.Farewell] 刻意**不在** enabled 里 —— 告别语播放的几秒里
    //     按钮禁用（变灰、缩小到 0.9），既不会闪红也不会被孩子连点。
    //     它自己会回 Ready（播完 / 12s 超时兜底），不需要孩子做任何事。

    // AnswerCorrect 复用「答对」的绿色 —— 项目里唯一的绿，为通话单独再造一个同值 token 是重复造轮子。
    // 语义上这里表示「可接听」，不是「答对了」，两者在同一个色值上共用。
    // ⚠️ 解构声明不能带类型标注，类型靠两个分支的 Triple(...) 实参推断。
    // ⚠️ Thinking 也要算「通话中」（红）—— 之前它落在 else 是绿的，但按下去执行的是挂断，
    //    颜色和行为对不上；孩子看到绿按钮以为能接通，实际把通话挂了。
    val (bg, icon, desc) = when (state) {
        is AssistantState.Listening,
        is AssistantState.Thinking,
        is AssistantState.Speaking -> Triple(AnswerWrong, Icons.Filled.CallEnd, "挂断对话")
        // 出错：琥珀 + 刷新图标，明确表达「点我重试」，而不是伪装成绿色的「接通」。
        is AssistantState.Error -> Triple(WarningAmber, Icons.Filled.Refresh, "重试连接")
        // 告别中：稳定的蓝 + 挂断图标，但 enabled=false 让它显示为灰底（见上面的 enabled）。
        // 不写成红色是为了不再闪那一下，也不写成绿色 —— 那会让人以为还能接通。
        is AssistantState.Farewell -> Triple(BrandBlue, Icons.Filled.CallEnd, "告别中")
        else -> Triple(AnswerCorrect, Icons.Filled.Phone, "接通对话")
    }

    // 按压缩放：快 tween，不用回弹（回弹在儿童应用里容易显得「抖」）。
    // ⚠️ 这个 Compose BOM（2026.09.00）已经移除了 Modifier.ripple，
    //    所以按反馈全靠这个缩放 + 彩色阴影，indication 走默认值不显式传。
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(120),
        label = "talk_button_scale",
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        // 彩色柔光晕。⚠️ shadow 的 spotColor/ambientColor 已在这个 BOM 里被移除，
        //    只能自己叠一层同色圆盘。alpha 置 0 而不是不画 —— 保持布局尺寸稳定。
        Box(
            modifier = Modifier
                .size(TALK_HALO_SIZE)
                .clip(CircleShape)
                .background(bg.copy(alpha = if (enabled) 0.16f else 0f)),
        )

        Box(
            modifier = Modifier
                .size(TALK_BUTTON_SIZE)
                .scale(if (enabled) scale else 0.9f)
                .shadow(6.dp, CircleShape)
                .clip(CircleShape)
                .background(if (enabled) bg else MaterialTheme.colorScheme.surfaceVariant)
                .clickable(
                    interactionSource = interaction,
                    enabled = enabled,
                    onClick = onTalk,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = desc,
                tint = if (enabled) SurfaceBase else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(TALK_ICON_SIZE),
            )
        }
    }
}
