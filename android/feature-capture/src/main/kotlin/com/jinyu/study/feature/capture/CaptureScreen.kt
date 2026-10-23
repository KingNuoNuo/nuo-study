package com.jinyu.study.feature.capture

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.jinyu.study.core.ui.text.subjectLabel
import com.jinyu.study.core.ui.theme.AnswerCorrect
import com.jinyu.study.core.ui.theme.AnswerWrong
import com.jinyu.study.core.ui.theme.AppTheme
import com.jinyu.study.core.ui.theme.SubjectHomework
import com.jinyu.study.core.ui.theme.WarningAmber
import com.jinyu.study.feature.capture.history.AssignmentEditor
import com.jinyu.study.feature.capture.history.DeleteConfirmDialog
import com.jinyu.study.feature.capture.history.HistoryViewModel
import com.jinyu.study.feature.capture.history.historySection

/**
 * 作业录入 · 家长端（手机竖屏）。
 *
 * 流程：选图（**一次可勾选多张**）→ 逐张上传 → 轮询识别 → **合并确认界面** → 发布
 *
 * ⚠️ 本模块的重心不是「上传」，而是**确认界面**：
 *    识别结果永远是草稿（assignment.status = 0），家长不点确认，孩子端就看不到。
 *    这是**防错**，不是合规 —— 识别错了，孩子就照着错的做。
 *
 *    两个必须体现在 UI 上的点：
 *    1. 模型自认不确定的字段要**高亮**（服务端随草稿返回顶层 `needs_review`，
 *       是中文说明），引导家长重点核对，而不是让他在一屏条目里自己找；
 *    2. 识别是异步的，要给出**可感知的等待**（状态 + 已等待秒数）。
 *       单张图实测 6–60 秒，没有反馈的话家长会以为卡死然后反复点。
 *
 * 选图用 Photo Picker（`PickVisualMedia`）而不是自己申请 `READ_MEDIA_IMAGES`：
 * 它由系统提供 UI，**不需要任何运行时权限**，家长只会看到自己的相册，
 * App 也就拿不到任何其他照片。少一个权限就是少一个要跟孩子解释的东西。
 *
 * 2026-10-09 起多选用 `PickMultipleVisualMedia`（同一套 Photo Picker，同样零权限）：
 * 每张图独立上传识别，全部完成后合并进同一个确认页 —— 见 CaptureViewModel 的约束 5。
 *
 * 2026-10-09 追加：页面下方接了「历史作业」（[com.jinyu.study.feature.capture.history.historySection]）。
 * 原先这条通道是**一次性**的 —— 拍完、确认、发布，然后就没有回去的路：
 * 日期选错改不了、录完没确认的草稿也找不回来。历史列表既当档案也当草稿回收站。
 */
@Composable
fun CaptureScreen(
    modifier: Modifier = Modifier,
    vm: CaptureViewModel = hiltViewModel(),
    history: HistoryViewModel = hiltViewModel(),
) {
    // 编辑页整页接管，不做成弹层 —— 理由见 AssignmentEditor 的注释。
    if (history.editing != null) {
        AssignmentEditor(vm = history, modifier = modifier)
    } else {
        CaptureHome(vm = vm, history = history, modifier = modifier)
    }

    // 删除确认挂在**最外层**：列表和编辑页都要用，而且必须不受滚动容器影响
    // （放进 verticalScroll 里会被裁掉，或者在滚动时跟着动）。
    history.confirmDelete?.let { row ->
        DeleteConfirmDialog(
            row = row,
            onCancel = history::cancelDelete,
            onConfirm = history::confirmDeleteNow,
        )
    }
}

@Composable
private fun CaptureHome(
    vm: CaptureViewModel,
    history: HistoryViewModel,
    modifier: Modifier = Modifier,
) {
    val dimens = AppTheme.dimens

    // 一次最多勾选几张：老师连发 2–3 张截图是常态，再多确认页和识别排队都会变长。
    // 超选时系统 Photo Picker 会自己弹确认，不会静默截断。
    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(MAX_PICK_IMAGES),
    ) { uris ->
        // 用户取消了就什么都不做 —— 不要把「取消」当成错误弹出来。
        if (uris.isNotEmpty()) vm.onImagesPicked(uris)
    }

    val launchPicker: () -> Unit = {
        picker.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // 刚发布完一条，历史列表要立刻反映出来 —— 否则家长发完还得自己点一下刷新，
    // 然后怀疑「到底发出去没有」。
    LaunchedEffect(vm.stage) {
        if (vm.stage is CaptureViewModel.Stage.Published) history.load()
    }

    // ⚠️ 这里必须是 **一个** LazyColumn，不能是 `Column + verticalScroll` 里塞列表。
    //    历史列表最长 40 行，套在 verticalScroll 里会被整棵一次性组合出来，
    //    而且嵌套滚动还得靠 measure 全量高度。LazyColumn 只有可见行参与组合。
    //
    //    分工：
    //      · 标题 / 当前阶段 —— 各一个 item，内部自己读状态；
    //      · 历史列表 —— [historySection]（LazyListScope 扩展），自带分组与 key。
    //    结构性的东西（rows 有几条）在哪个作用域读，就决定谁重组合 ——
    //    rows 在这里读，所以只有真正增删行时才会重跑这个内容 lambda。
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(dimens.itemGap),
    ) {
        item(key = "capture-title") {
            Text("录入作业", style = MaterialTheme.typography.displaySmall)
        }

        item(key = "capture-stage") {
            when (val stage = vm.stage) {
                CaptureViewModel.Stage.Pick -> PickStage(onPick = launchPicker)

                is CaptureViewModel.Stage.Busy -> ProgressStage(stage.progress, vm.lastUploadInfo)

                is CaptureViewModel.Stage.Review -> ReviewStage(
                    review = stage.review,
                    onPublish = vm::publish,
                    onDiscard = vm::discard,
                    onRepick = launchPicker,
                )

                is CaptureViewModel.Stage.Published -> ResultStage(
                    icon = Icons.Filled.CheckCircle,
                    tint = AnswerCorrect,
                    title = stage.message,
                    detail = "孩子端下次打开（或后台同步）时会看到这条作业。",
                    primaryLabel = "继续录入",
                    onPrimary = { vm.reset() },
                )

                is CaptureViewModel.Stage.Failed -> {
                    // 「重试」放主位：绝大多数失败是网络抖动，家长的下一个动作就是再试一次，
                    // 而不是回相册重找那张图 —— 图还在内存里，重选纯属折磨人。
                    // 重试一定没用的错误（令牌错、状态冲突）不显示重试按钮：
                    // 一个点了必然失败的按钮比没有按钮更糟。
                    val retry: () -> Unit = { vm.retry() }
                    val repick: () -> Unit = { vm.reset(); launchPicker() }
                    val home: () -> Unit = { vm.reset() }
                    ResultStage(
                        icon = Icons.Filled.Error,
                        tint = AnswerWrong,
                        title = "没能完成",
                        detail = stage.message,
                        primaryLabel = if (stage.retryable) "重试" else "重新选图",
                        onPrimary = if (stage.retryable) retry else repick,
                        secondaryLabel = if (stage.retryable) "重新选图" else "回到首页",
                        onSecondary = if (stage.retryable) repick else home,
                    )
                }
            }
        }

        historySection(vm = history, rows = history.rows, onEdit = history::openEdit)
    }
}

// ---------------------------------------------------------------- 各阶段

@Composable
private fun PickStage(onPick: () -> Unit) {
    val dimens = AppTheme.dimens
    Text(
        text = "拍照 → 识别 → 逐项确认 → 发布",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
        text = "识别结果一律先进草稿态，确认前孩子端看不到",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(
        onClick = onPick,
        modifier = Modifier
            .fillMaxWidth()
            // 手机单手持握时的主操作，给到 2 倍最小点击区
            .heightIn(min = dimens.minTouchTarget * 2),
    ) {
        Icon(Icons.Filled.PhotoCamera, contentDescription = null)
        Spacer(Modifier.width(dimens.itemGap / 2))
        Text("选择作业截图（可多选）", style = MaterialTheme.typography.titleMedium)
    }

    Text(
        text = "可以一次勾选多张（最多 $MAX_PICK_IMAGES 张）—— 老师连发的几张截图" +
            "会各自识别，全部完成后在同一页逐条核对。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Text(
        text = "建议先在相册里把老师发的图裁一下，只留作业那部分 —— " +
            "群里混着的通知、学号反馈会干扰识别。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** 一次最多勾选几张图。与 CaptureViewModel 的批量上传/轮询一一对应。 */
private const val MAX_PICK_IMAGES = 5

/**
 * 处理进度。**上传/识别期间家长看的就是这一块**，所以它要回答三个问题：
 *   ① 现在到哪一步了？ → [StepBar]
 *   ② 跑了多久了、是不是死了？ → 已等待 N 秒 + 不定长进度条
 *   ③ 卡在哪了？ → 服务端重试次数、任务号、命中缓存提示
 *
 * ⚠️ 进度条刻意是**不定长**的。识别耗时 6–60 秒且与图片内容强相关，
 *    任何「百分比」都是编出来的 —— 编出来一个卡在 80% 不动的进度条，
 *    比一根一直动的条更让人以为死机。
 */
@Composable
private fun ProgressStage(p: CaptureViewModel.Progress, uploadInfo: String?) {
    val dimens = AppTheme.dimens
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(dimens.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(dimens.itemGap))
                Text(p.headline, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                if (p.elapsedSec > 0) {
                    Text(
                        text = "已等待 ${p.elapsedSec} 秒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (p.step >= 0) {
                Spacer(Modifier.height(dimens.itemGap))
                StepBar(current = p.step)
            }

            Spacer(Modifier.height(dimens.itemGap))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())

            val detail = p.detail ?: uploadInfo?.let { "已上传 $it" }
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = dimens.itemGap / 2),
                )
            }

            if (p.attempt > 0) {
                Text(
                    text = "服务端已重试 ${p.attempt}" +
                        (if (p.maxAttempt > 0) "/${p.maxAttempt}" else "") +
                        " 次，还在处理中。",
                    style = MaterialTheme.typography.bodySmall,
                    color = WarningAmber,
                    modifier = Modifier.padding(top = dimens.itemGap / 2),
                )
            }

            Text(
                text = "识别通常 6–60 秒，可以先放着，不用一直盯着。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = dimens.itemGap / 2),
            )
        }
    }
}

/** 三段流水线：上传图片 → 识别内容 → 家长确认。 */
private val CAPTURE_STEPS = listOf("上传图片", "识别内容", "家长确认")

@Composable
private fun StepBar(current: Int) {
    val dimens = AppTheme.dimens
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CAPTURE_STEPS.forEachIndexed { index, name ->
            val done = index < current
            val active = index == current
            val tint = when {
                active -> MaterialTheme.colorScheme.primary
                done -> AnswerCorrect
                else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                // 圆点用 tint 的 16% 做底再配同色文字，而不是实心底配白字 ——
                // 主题色是运行时给的，实心底没法保证白字一定够对比度。
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (done) "✓" else "${index + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = tint,
                    )
                }
                Spacer(Modifier.width(6.dp))
                Text(text = name, style = MaterialTheme.typography.bodySmall, color = tint)
            }

            if (index < CAPTURE_STEPS.lastIndex) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = dimens.itemGap / 2)
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)),
                )
            }
        }
    }
}

@Composable
private fun ReviewStage(
    review: CaptureViewModel.DraftReview,
    onPublish: (List<Int>) -> Unit,
    onDiscard: (List<Int>) -> Unit,
    onRepick: () -> Unit,
) {
    val dimens = AppTheme.dimens
    val allIds = review.assignments.map { it.id }

    if (!review.hasAssignments) {
        // 服务端的提示词允许模型判定「这不是作业」，这里如实告诉家长 ——
        // 显示一个空荡荡的确认页，家长只会以为程序坏了。
        ResultStage(
            icon = Icons.Filled.Warning,
            tint = WarningAmber,
            title = if (review.imageCount > 1) "这几张图里没识别出作业" else "这张图里没识别出作业",
            detail = review.notHomework.firstOrNull()
                ?: review.needsReview.firstOrNull()
                ?: "可能是通知、学号反馈或表情包。",
            primaryLabel = "重新选图",
            onPrimary = onRepick,
        )
        return
    }

    // ⚠️ 高亮区必须在**最上面**。放到卡片下面的话，家长看完题项就直接点发布了，
    //    而这里恰恰是「哪一处模型拿不准」—— 它才是要重点核对的东西。
    if (review.needsReview.isNotEmpty() || review.notHomework.isNotEmpty()) {
        WarningCard(review)
    }

    if (review.assignments.size > 1) {
        Text(
            text = if (review.imageCount > 1)
                "这 ${review.imageCount} 张图一共拆出了 ${review.assignments.size} 条作业单（不同科目/图分开）"
            else
                "这张图里拆出了 ${review.assignments.size} 条作业单（不同科目分开）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    review.assignments.forEach { a ->
        AssignmentCard(
            assignment = a,
            onPublish = { onPublish(listOf(a.id)) },
            onDiscard = { onDiscard(listOf(a.id)) },
        )
    }

    if (review.assignments.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(dimens.itemGap)) {
            Button(
                onClick = { onPublish(allIds) },
                modifier = Modifier.heightIn(min = dimens.minTouchTarget),
            ) { Text("全部发布") }
            OutlinedButton(
                onClick = { onDiscard(allIds) },
                modifier = Modifier.heightIn(min = dimens.minTouchTarget),
            ) { Text("全部丢弃") }
        }
    }

    OutlinedButton(
        onClick = onRepick,
        modifier = Modifier.heightIn(min = dimens.minTouchTarget),
    ) { Text("重新选图") }
}

@Composable
private fun WarningCard(review: CaptureViewModel.DraftReview) {
    val dimens = AppTheme.dimens
    Card(
        colors = CardDefaults.cardColors(containerColor = WarningAmber.copy(alpha = 0.12f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(dimens.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = WarningAmber)
                Spacer(Modifier.width(dimens.itemGap / 2))
                Text(
                    text = "模型拿不准的地方，请重点核对",
                    style = MaterialTheme.typography.titleSmall,
                    color = WarningAmber,
                )
            }
            review.needsReview.forEach {
                Text(
                    text = "· $it",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = dimens.itemGap / 3),
                )
            }
            review.notHomework.forEach {
                Text(
                    text = "· 判定为非作业内容：$it",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = dimens.itemGap / 3),
                )
            }
        }
    }
}

@Composable
private fun AssignmentCard(
    assignment: CaptureViewModel.ReviewAssignment,
    onPublish: () -> Unit,
    onDiscard: () -> Unit,
) {
    val dimens = AppTheme.dimens
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(dimens.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = subjectLabel(assignment.subject),
                    style = MaterialTheme.typography.titleMedium,
                    color = SubjectHomework,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(dimens.itemGap))
                Text(
                    text = assignment.assignDate,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            assignment.title?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = dimens.itemGap / 3),
                )
            }

            if (!assignment.needSubmit) {
                Text(
                    text = "标记为「无需提交」",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = dimens.itemGap / 3),
                )
            }

            Text(
                text = "共 ${assignment.items.size} 项",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = dimens.itemGap / 2),
            )

            assignment.items.forEach { item ->
                Column(Modifier.padding(top = dimens.itemGap / 2)) {
                    Text(
                        text = "${item.seq}. ${item.title}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    item.requirement?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = "要求：$it",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // 这里原先还有一行「客观题 · 手写作答 · 约 10 分钟」。
                    // 2026-10-09 按靖哥哥的要求去掉：作业内容下面不需要这类说明。
                    // 一条作业项后面挂一串题型术语，家长真正要做的是核对题目本身，
                    // 那行字只是把卡片撑长、让「发布」按钮被推到屏幕外。
                    // `itemTypeLabel` / `answerModeLabel` 仍留在 core-ui 的 Labels.kt
                    // （孩子端作答控件将来要按 answerMode 分流），只是界面上不再展示。
                }
            }

            Row(
                modifier = Modifier.padding(top = dimens.itemGap),
                horizontalArrangement = Arrangement.spacedBy(dimens.itemGap),
            ) {
                Button(
                    onClick = onPublish,
                    modifier = Modifier.heightIn(min = dimens.minTouchTarget),
                ) { Text("发布") }
                OutlinedButton(
                    onClick = onDiscard,
                    modifier = Modifier.heightIn(min = dimens.minTouchTarget),
                ) { Text("丢弃") }
            }
        }
    }
}

@Composable
private fun ResultStage(
    icon: ImageVector,
    tint: Color,
    title: String,
    detail: String,
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    val dimens = AppTheme.dimens
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(dimens.cardPadding)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(dimens.itemGap / 2))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = tint,
                )
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = dimens.itemGap / 2),
            )
            Row(
                modifier = Modifier.padding(top = dimens.itemGap),
                horizontalArrangement = Arrangement.spacedBy(dimens.itemGap),
            ) {
                Button(
                    onClick = onPrimary,
                    modifier = Modifier.heightIn(min = dimens.minTouchTarget),
                ) { Text(primaryLabel) }
                if (secondaryLabel != null && onSecondary != null) {
                    OutlinedButton(
                        onClick = onSecondary,
                        modifier = Modifier.heightIn(min = dimens.minTouchTarget),
                    ) { Text(secondaryLabel) }
                }
            }
        }
    }
}
