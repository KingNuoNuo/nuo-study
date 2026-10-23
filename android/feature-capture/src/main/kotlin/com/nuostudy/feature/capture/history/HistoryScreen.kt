package com.nuostudy.feature.capture.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.nuostudy.core.ui.text.friendlyDate
import com.nuostudy.core.ui.text.subjectLabel
import com.nuostudy.core.ui.theme.AnswerCorrect
import com.nuostudy.core.ui.theme.AnswerWrong
import com.nuostudy.core.ui.theme.AppTheme
import com.nuostudy.core.ui.theme.SubjectHomework
import com.nuostudy.core.ui.theme.WarningAmber
import kotlinx.coroutines.delay

/**
 * 「历史作业」列表。挂在录入页下方，**刻意不做成第四个底部 Tab** ——
 * 家长打开这个 App 的心智是「我要录作业」，历史是顺手的补充动作。
 * 摆成一等功能的代价是首屏多一个入口，而这个 App 对家长的全部要求就是
 * 「拍一张照、点一下确认」。
 *
 * 分组分「待确认 / 已发布」而不是混在一起按时间排：
 * 草稿是**待办**（家长还没确认，孩子看不到，事情没完），已发布是**档案**。
 * 两者混排会让家长以为草稿也已经生效了。
 */
/**
 * 历史列表本体，**以 `LazyListScope` 扩展的形式**挂在录入页那一个 LazyColumn 上。
 *
 * ⚠️ 别改回 `Column { rows.forEach { ... } }`。LIST_LIMIT = 40 条时那样会：
 *    · 一次性组合 40 个 Card（每个 Card 内部还有若干 Text）；
 *    · 还没进屏幕的行就先被测量/绘制准备一遍；
 *    · 外层如果又是 `verticalScroll`，滚动时整棵子树每帧重排。
 *    换成 `items(..., key = { it.id })` 之后只有可见行参与组合，且行插入/删除
 *    不会让下面所有行的状态错位（key 稳定）。
 *
 * 这里（非 composable 作用域）只能做「发几条 item」这种结构性判断；
 * `AppTheme.dimens` 是 `@Composable @ReadOnlyComposable` 的，尺寸一律下沉到
 * item / 子组件内部去读。
 */
fun LazyListScope.historySection(
    vm: HistoryViewModel,
    rows: List<HistoryViewModel.Row>,
    onEdit: (HistoryViewModel.Row) -> Unit,
) {
    item(key = "history-divider") { HorizontalDivider(Modifier.fillMaxWidth()) }
    item(key = "history-header") { HistoryHeader(vm = vm, isEmpty = rows.isEmpty()) }

    // 空态文案已经由 header 负责，这里不用再发占位 item。
    if (rows.isEmpty()) return

    val drafts = rows.filter { it.isDraft }
    val published = rows.filterNot { it.isDraft }

    if (drafts.isNotEmpty()) {
        item(key = "history-drafts-title") {
            GroupTitle("待确认 · 孩子还看不到", WarningAmber)
        }
        items(drafts, key = { "history-draft-${it.id}" }) { row ->
            HistoryRow(row, onEdit, vm::askDelete)
        }
    }
    if (published.isNotEmpty()) {
        item(key = "history-published-title") {
            GroupTitle("已发布", MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(published, key = { "history-published-${it.id}" }) { row ->
            HistoryRow(row, onEdit, vm::askDelete)
        }
    }
}

/**
 * 列表标题行 + 刷新按钮 + 一次性提示。
 *
 * 单独拆出来是为了让它**能自己读状态**：`listLoading` / `toast` / `listError`
 * 一变只重组合这一块，不必把整个 LazyColumn 的内容 lambda 重跑一遍。
 */
@Composable
private fun HistoryHeader(vm: HistoryViewModel, isEmpty: Boolean) {
    val dimens = AppTheme.dimens

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("历史作业", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            if (vm.listLoading) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(dimens.itemGap / 2))
            }
            TextButton(onClick = vm::load, enabled = !vm.listLoading) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新")
            }
        }

        // 一次性提示（保存 / 删除成功）。放在列表上方而不是弹 toast：
        // 家长多是改完从编辑页退回来的，回到列表第一眼就该看到发生了什么。
        vm.toast?.let { msg ->
            LaunchedEffect(msg) {
                delay(6000)
                vm.clearToast()
            }
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = AnswerCorrect,
                modifier = Modifier.padding(top = dimens.itemGap / 2),
            )
        }

        // ⚠️ 用 listError，不是「详情加载失败」那条（那个进 editError）。
        vm.listError?.let { msg ->
            Text(
                text = msg,
                style = MaterialTheme.typography.bodySmall,
                color = AnswerWrong,
                modifier = Modifier.padding(top = dimens.itemGap / 2),
            )
        }

        if (isEmpty && !vm.listLoading) {
            Text(
                text = if (vm.listError == null) "还没有录过作业。上面拍一张老师发的作业截图就有了。"
                else "列表没加载出来，点上面的「刷新」再试一次。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = dimens.itemGap / 2),
            )
        }
    }
}

@Composable
private fun GroupTitle(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        // 纵向间距交给外层 LazyColumn 的 spacedBy，这里只留一点点贴行距。
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun HistoryRow(
    row: HistoryViewModel.Row,
    onEdit: (HistoryViewModel.Row) -> Unit,
    onDelete: (HistoryViewModel.Row) -> Unit,
) {
    val dimens = AppTheme.dimens
    Card(
        modifier = Modifier
            .fillMaxWidth()
            // 行间距由外层 LazyColumn 的 spacedBy 给，这里不再自带 padding。
            // 整行可点 = 进编辑页。删除单独一个按钮、且必须过弹窗 ——
            // 一个「点哪儿都能删」的列表，迟早会被误删（服务端是硬删除）。
            .clickable { onEdit(row) },
    ) {
        Row(
            modifier = Modifier.padding(dimens.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = subjectLabel(row.subject),
                        style = MaterialTheme.typography.titleSmall,
                        color = SubjectHomework,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.width(dimens.itemGap / 2))
                    Text(
                        text = friendlyDate(row.assignDate),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Text(
                    text = row.title?.takeIf { it.isNotBlank() } ?: "（没有标题）",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 2.dp),
                )
                Text(
                    text = buildString {
                        append("${row.itemCount} 项")
                        if (!row.needSubmit) append(" · 无需提交")
                        if (row.isDraft) append(" · 待确认")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onEdit(row) }) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { onDelete(row) }) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "删除",
                    tint = AnswerWrong,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * 编辑一整条作业单：日期、标题、是否需提交，以及作业项的增删改。
 *
 * 做成**整页**而不是底部弹层：一条语文卷子能拆出十几项，弹层里滚十几行输入框，
 * 键盘一弹出来就只剩两行可视区，改起来比整页难受得多。
 *
 * ⚠️ 科目**不给改**。它不是「一个字段」，它是作业单身份键
 *    （UNIQUE(student_id, assign_date, subject)）的一半 ——
 *    改科目等于换一条单子，硬做只会撞唯一键。要换科目就删了重录。
 */
@Composable
fun AssignmentEditor(vm: HistoryViewModel, modifier: Modifier = Modifier) {
    val dimens = AppTheme.dimens
    val e = vm.editing ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(dimens.itemGap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::closeEdit, enabled = !vm.busy) {
                Icon(Icons.Filled.Close, contentDescription = "返回")
            }
            Text("编辑作业", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(dimens.itemGap))
            Text(
                text = subjectLabel(e.subject),
                style = MaterialTheme.typography.titleMedium,
                color = SubjectHomework,
                fontWeight = FontWeight.Bold,
            )
        }

        Text(
            text = if (e.isDraft) "草稿 · 孩子端还看不到" else "已发布 · 孩子端能看到",
            style = MaterialTheme.typography.bodySmall,
            color = if (e.isDraft) WarningAmber else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = e.assignDate,
            onValueChange = vm::setDate,
            label = { Text("作业日期") },
            supportingText = { Text("按 2026-10-09 这种写法（年-月-日）") },
            singleLine = true,
            enabled = !vm.busy,
            keyboardOptions = KeyboardOptions(
                // ⚠️ 这里**不能**用 KeyboardType.Number：数字键盘上没有 `-`，
                //    家长按提示敲 "2026-10-09" 时根本打不出那个连字符，
                //    只能临时切到符号页 —— 换成 Ascii 后数字与 `-` 同屏可用，
                //    顺带避开中文输入法把 `-` 打成全角 `－` 的老坑。
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Next,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = e.title,
            onValueChange = vm::setTitle,
            label = { Text("标题（可留空）") },
            singleLine = true,
            enabled = !vm.busy,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = e.needSubmit,
                onCheckedChange = vm::setNeedSubmit,
                enabled = !vm.busy,
            )
            Text("需要提交（老师要求交上去）", style = MaterialTheme.typography.bodyMedium)
        }

        HorizontalDivider()
        Text("作业项（${e.items.size} 项）", style = MaterialTheme.typography.titleSmall)

        e.items.forEachIndexed { index, item ->
            // ⚠️ key 必须用 uid，不能用下标：删掉第 2 项后下标 2 会指到原来的第 3 项，
            //    Compose 会把第 2 项输入框的内部状态（光标位置、输入法组合串）留在原地，
            //    表现是「删掉一行，另一行的字串了」。
            key(item.uid) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(dimens.cardPadding)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "${index + 1}.",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Spacer(Modifier.weight(1f))
                            // 只剩一项时不给删：一条没有作业项的作业单根本发不出去
                            // （服务端 publish 会拒），留着按钮只会让人点出一个错。
                            if (e.items.size > 1) {
                                IconButton(
                                    onClick = { vm.removeItem(item.uid) },
                                    enabled = !vm.busy,
                                ) {
                                    Icon(
                                        Icons.Filled.Delete,
                                        contentDescription = "删掉这一项",
                                        tint = AnswerWrong,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = item.title,
                            onValueChange = { vm.setItemTitle(item.uid, it) },
                            label = { Text("题目 / 任务") },
                            enabled = !vm.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = item.requirement,
                            onValueChange = { vm.setItemRequirement(item.uid, it) },
                            label = { Text("老师的要求（可留空）") },
                            enabled = !vm.busy,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = dimens.itemGap / 2),
                        )
                    }
                }
            }
        }

        OutlinedButton(
            onClick = vm::addItem,
            enabled = !vm.busy,
            modifier = Modifier.heightIn(min = dimens.minTouchTarget),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("加一项")
        }

        vm.editError?.let {
            Text(text = it, style = MaterialTheme.typography.bodyMedium, color = AnswerWrong)
        }

        Text(
            // 这句话必须写出来。不写的话家长会以为「保存＝孩子那边立刻变了」，
            // 发现没变就会反复点保存，然后怀疑是不是没保存上。
            text = "保存后不会自动同步给孩子的平板：孩子端下次打开 App 或后台同步时才会更新。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(
            horizontalArrangement = Arrangement.spacedBy(dimens.itemGap),
            modifier = Modifier.padding(bottom = dimens.screenPadding),
        ) {
            Button(
                onClick = vm::save,
                enabled = !vm.busy,
                modifier = Modifier.heightIn(min = dimens.minTouchTarget),
            ) {
                if (vm.busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(dimens.itemGap / 2))
                }
                Text("保存")
            }
            if (e.isDraft) {
                Button(
                    onClick = vm::publish,
                    enabled = !vm.busy,
                    modifier = Modifier.heightIn(min = dimens.minTouchTarget),
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("发布")
                }
            }
        }

        TextButton(
            onClick = { vm.askDelete(e.asRow()) },
            enabled = !vm.busy,
            modifier = Modifier.padding(bottom = dimens.screenPadding),
        ) {
            Icon(Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("删除这条作业", color = AnswerWrong)
        }
    }
}

/**
 * 删除的二次确认。
 *
 * ⚠️ 服务端是**硬删除**（无软删除、无回收站）。这个弹窗是唯一的误删防线，
 *    所以文案要讲清两件事：删了就找不回来、孩子端也会跟着消失。
 *    顺带把「只是改错了内容 → 用编辑更稳妥」指出来 —— 家长最常见的删除动机
 *    其实是「改不动」，而那个需求现在已经能直接满足了。
 */
@Composable
fun DeleteConfirmDialog(
    row: HistoryViewModel.Row,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        icon = { Icon(Icons.Filled.Error, contentDescription = null, tint = AnswerWrong) },
        title = { Text("删除这条作业？") },
        text = {
            Column {
                Text(
                    text = "${subjectLabel(row.subject)} · " +
                        "${friendlyDate(row.assignDate)} · ${row.itemCount} 项",
                    style = MaterialTheme.typography.bodyMedium,
                )
                row.title?.takeIf { it.isNotBlank() }?.let {
                    Text(text = it, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "删掉就找不回来了（服务端没有回收站），孩子的平板上这条也会消失。" +
                        "如果只是内容写错了，点「取消」回去编辑更稳妥。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("删除", color = AnswerWrong) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("取消") }
        },
    )
}
