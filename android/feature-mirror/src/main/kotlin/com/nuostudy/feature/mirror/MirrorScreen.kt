package com.nuostudy.feature.mirror

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.nuostudy.core.network.MirrorSource
import com.nuostudy.core.ui.theme.AppTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 状态点与强调色。**不跟随 Material 主题** —— 这三个颜色表达的是「连接状态」，不是配色偏好。 */
private val DotLive = Color(0xFF34D399)
private val DotStale = Color(0xFFF59E0B)
private val DotDead = Color(0xFFEF4444)
private val DotIdle = Color(0xFF9AA0A6)

/** 图片容器底色。任何主题下都用黑 —— 孩子端界面是浅色的，黑底能把画面边界显出来。 */
private val FrameBackdrop = Color(0xFF0B0C0E)

/** 点「放大」按钮一步到位的倍数。2.5× 时 640 宽的图等效 1600px，作业标题能看清。 */
private const val ZOOM_STEP = 2.5f
private const val ZOOM_MAX = 6f

/**
 * 家长端 · 实时画面（看孩子平板此刻的界面）。
 *
 * 用法：孩子端的「实时画面」开关打开后，这里每 [MirrorViewModel.POLL_INTERVAL_MS]
 * 毫秒拉一帧 —— 家长坐在客厅沙发上就能看到孩子平板上是作业还是别的。
 *
 * ⚠️ 轮询循环写在**这个 composable 里**，不是 ViewModel 里，原因见
 *    [MirrorViewModel] 的类注释第 1 条（切 Tab 时 `LaunchedEffect` 自动取消，
 *    而 ViewModel 在这个 App 里不会随 Tab 销毁）。
 *
 * ⚠️ 放大用的是**原地缩放**（`graphicsLayer` + `transformable`），不是弹全屏 Dialog ——
 *    这是踩过之后改的：Dialog 是独立窗口，配合「打开时把 Activity 转横屏」会在旋转
 *    过程中被系统关掉（家长看到的是屏幕转了一下又转回来，什么也没放大）。
 *    原地缩放没有窗口、没有方向切换，只在当前这个 Box 里改 `scale/offset`，行为完全可预期。
 */
@Composable
fun MirrorScreen(
    modifier: Modifier = Modifier,
    vm: MirrorViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val source by vm.source.collectAsStateWithLifecycle()
    val dimens = AppTheme.dimens
    val scope = rememberCoroutineScope()

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // 轮询。
    //
    // ⚠️ 这里有两层「必须停下来」，缺一层就出事，两层都是踩出来的：
    //
    // 1. **切到别的 Tab**：`MirrorScreen` 是 `ParentRoot` 里 `when` 出来的，
    //    切走时直接离开组合，`LaunchedEffect` 的协程随之取消 —— 这层 `LaunchedEffect` 自带。
    //
    // 2. **Activity 退到后台**（家长按 Home 键、锁屏）。这一层**光靠 `LaunchedEffect`
    //    停不下来**：进后台只是 STOPPED，组合还在，协程会一直跑下去 ——
    //    家长锁屏去睡觉，画面还在 1.5 秒一次地拉。`repeatOnLifecycle(RESUMED)` 才是
    //    对的语义：**只在可见时才收帧**。
    //
    //    这一条还连着另一个更难发现的坑：如果 Manifest 没写 launchMode，某些入口
    //    （`am start`、通知跳转）会**再开一个 MainActivity 实例**，于是同一份代码
    //    有两个实例、两个 ViewModel、两套循环在跑 —— 一个在前台看 `app` 通道，
    //    一个在后台还看着 `adb`。表现是「家长明明切回了 App 内截图，服务端却一直
    //    有人取整屏帧、抓帧线程永远不停」。Manifest 那半已经用
    //    `launchMode="singleTask"` 堵掉了，这里堵掉这半。
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                if (!vm.state.value.paused) vm.poll()
                delay(MirrorViewModel.POLL_INTERVAL_MS)
            }
        }
    }

    // ⚠️ 「每秒重算一次」的秒表**不在这一层**（曾经在这里，`produceState`）。
    //    放在组合顶层会被整棵子树订阅：每秒失效一次，包括 `Image` 那条 modifier 链 ——
    //    明明只有状态行那几个字需要跟着时间动。
    //    现在它下沉到 [StatusLine] 内部，让失效范围收缩到那一行。
    val image = state.image

    fun resetZoom() {
        scale = 1f
        offset = Offset.Zero
    }

    Column(modifier = modifier.fillMaxSize().padding(dimens.screenPadding)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "实时画面",
                style = MaterialTheme.typography.displaySmall,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { vm.setPaused(!state.paused) }) {
                Icon(
                    imageVector = if (state.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    contentDescription = if (state.paused) "继续" else "暂停",
                )
            }
            IconButton(onClick = { scope.launch { vm.poll() } }) {
                Icon(Icons.Filled.Refresh, contentDescription = "立即刷新")
            }
            IconButton(
                enabled = image != null,
                onClick = { if (scale > 1f) resetZoom() else scale = ZOOM_STEP },
            ) {
                Icon(
                    imageVector = if (scale > 1f) Icons.Filled.ZoomOut else Icons.Filled.ZoomIn,
                    contentDescription = if (scale > 1f) "还原画面" else "放大画面",
                )
            }
        }

        // 通道切换。刻意放在状态行**上面**：先选「看哪一路」，再读「这一路现在怎么样」。
        // 顺序反过来，家长会先看到一句属于另一条通道的「还没收到画面」，白紧张一下。
        SourceSwitch(
            current = source,
            adbEnabled = state.adbEnabled,
            onPick = { picked ->
                vm.setSource(picked)
                // 立刻拉一次，不等下一个 1.5 秒周期。切换本身已经把画面清空了，
                // 再空等一秒多，家长看到的就只是「点了没反应」。
                scope.launch { vm.poll() }
            },
            modifier = Modifier.padding(top = dimens.itemGap),
        )

        StatusLine(state = state, image = image)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(top = dimens.itemGap)
                .clip(RoundedCornerShape(dimens.radiusCard))
                .background(FrameBackdrop),
            contentAlignment = Alignment.Center,
        ) {
            if (image != null) {
                val transform = rememberTransformableState { zoomChange, panChange, _ ->
                    val next = (scale * zoomChange).coerceIn(1f, ZOOM_MAX)
                    scale = next
                    // 缩回原始大小时把平移一起归零，否则图会停在屏幕角落找不回来。
                    offset = if (next <= 1f) Offset.Zero else offset + panChange
                }
                Image(
                    bitmap = image,
                    contentDescription = "孩子端当前画面",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .transformable(transform)
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { resetZoom() })
                        },
                )
            } else {
                EmptyState(state = state, onRetry = { scope.launch { vm.poll() } })
            }
        }

        // 有图时不把错误铺满屏幕（画面本身才是重点），只在下面挂一行灰字。
        state.error?.takeIf { image != null }?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = dimens.itemGap / 2),
            )
        }

        Text(
            text = when (source) {
                MirrorSource.ADB ->
                    "每 3 秒一帧，由服务端用无线 adb 抓整个屏幕 —— 系统弹窗、输入法、桌面都能看到。" +
                        "只在有人看的时候才抓，停止查看约一分钟后服务端自己停。" +
                        "画面可双指捏合放大、拖动，右上角放大镜一步放大，双击还原。"
                else ->
                    "每 3 秒一帧，由孩子端 App 自己截屏上传。画面可以双指捏合放大、拖动，" +
                        "右上角的放大镜按钮一步放大，双击还原。只能看到 App 自己的界面，看不到" +
                        "系统弹窗、输入法，也看不到桌面 —— 孩子退到别的应用时这里就会停住。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = dimens.itemGap / 2),
        )
    }
}

/**
 * 「App 内截图 / 整屏抓取」二选一。
 *
 * 两条通道的取舍是**必然**的，不是实现偷懒：
 *   · App 内截图零配置、零权限，但只能看到 App 自己的窗口；
 *   · 整屏抓取什么都看得到，代价是 NAS 上要有一份 adb、平板要开着无线调试。
 * 所以不做「自动选最好的」—— 家长比代码更清楚自己现在想看什么。
 *
 * ⚠️ 整屏抓取不可用时**不隐藏这个按钮**，而是把它标出来并说明原因。
 *    隐藏的后果是：家长明明知道有「整屏」这个选项，却找不到它，
 *    只会怀疑是自己手机上的版本旧了。
 */
@Composable
private fun SourceSwitch(
    current: String,
    adbEnabled: Boolean?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 选中态本身由 FilterChip 的底色+描边表达，不再另加对勾图标：
            // `leadingIcon` 的类型是 `@Composable (() -> Unit)?`，写成 `if` 表达式
            // 需要在参数位置推断 @Composable 的函数类型，稍微绕一点就容易编译不过。
            FilterChip(
                selected = current == MirrorSource.APP,
                onClick = { onPick(MirrorSource.APP) },
                label = { Text("App 内截图") },
            )
            FilterChip(
                selected = current == MirrorSource.ADB,
                onClick = { onPick(MirrorSource.ADB) },
                label = { Text("整屏抓取") },
            )
        }

        if (adbEnabled == false) {
            Text(
                text = "服务端的「整屏抓取」还没配好（NAS 上缺 adb，或没设 ADB_TARGET），" +
                    "现在切过去只会看到报错。",
                style = MaterialTheme.typography.bodySmall,
                color = DotStale,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * 「● 孩子端 GM260200701 · 第 312 帧 · 3.2 秒前 · 42 KB」。
 *
 * 三个信息各有用途，缺一个家长就得靠猜：**设备名**（家里不止一台时区分）、
 * **帧号**（涨了＝画面在动）、**帧龄**（不涨＝孩子端停了）。
 *
 * ⚠️ 秒表就放在**这一层**（不是外面的 `MirrorScreen`）。没有它，状态点与
 *    「静止 X 秒」会永远停在首帧那一刻的值 —— 看起来一直很新鲜，其实早就断了。
 *    但它只驱动这一行的几个字，所以失效范围必须收在这一行里：
 *    放在组合顶层会让整棵子树（含 `Image`）每秒重组一次。
 */
@Composable
private fun StatusLine(
    state: MirrorViewModel.UiState,
    image: ImageBitmap?,
) {
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    val ageSec = state.ageSec(nowMs)

    val (dot, text) = when {
        state.paused -> DotIdle to "已暂停 · 点右上角继续"
        image == null && state.error == null -> DotIdle to "正在连接…"
        image == null -> DotDead to "还没有收到画面"
        state.failures > 0 -> DotStale to "取帧失败 ${state.failures} 次，显示的是最后一帧"
        ageSec < 8 -> DotLive to statusText(state, ageSec)
        ageSec < 60 -> DotStale to statusText(state, ageSec)
        else -> DotDead to statusText(state, ageSec)
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 6.dp),
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun statusText(state: MirrorViewModel.UiState, ageSec: Double): String {
    val label = state.label ?: state.deviceId ?: "未知设备"
    val age = if (ageSec < 1) "刚刚" else "%.1f 秒前".format(ageSec)
    val frozen = if (ageSec > 12) "  ⚠ 画面已静止，孩子端可能切走了或锁屏了" else ""
    return "$label · 第 ${state.seq} 帧 · $age · ${state.kb.roundToInt()} KB$frozen"
}

/** 一帧都还没拿到时，中间显示的东西。**必须带上「去检查什么」**，否则家长只会干等。 */
@Composable
private fun EmptyState(state: MirrorViewModel.UiState, onRetry: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.padding(24.dp),
    ) {
        if (state.error == null) {
            CircularProgressIndicator(color = DotIdle)
            Text(
                text = "正在连接…",
                style = MaterialTheme.typography.bodyMedium,
                color = DotIdle,
            )
        } else {
            Icon(Icons.Filled.CloudOff, contentDescription = null, tint = DotStale)
            Text(
                text = "还没看到孩子端的画面",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodyMedium,
                color = DotIdle,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onRetry) { Text("重试") }
        }
    }
}
