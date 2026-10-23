package com.jinyu.study.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.jinyu.study.core.ui.theme.AnswerCorrect
import com.jinyu.study.core.ui.theme.AnswerWrong
import com.jinyu.study.core.ui.theme.AppTheme
import com.jinyu.study.core.ui.theme.WarningAmber

/**
 * 服务端设置页。孩子端与家长端共用。
 *
 * 为什么孩子端**也要**有这个页面（而不是只让家长端配一次）：
 * 两个 App 是两个独立安装包，各自的 DataStore 完全隔离，家长端填的地址
 * 没有任何渠道能推送给平板上的孩子端。与其做一个跨设备下发协议（为一台平板
 * 不值得），不如给平板留一个带口令的本地入口。
 *
 * @param stackFields 输入框的排布。**这是两端唯一的版式差异**：
 *   - `false`（默认）＝ 并排，给平板横屏用 —— 横向空间富裕，省下的纵向空间留给结果区；
 *   - `true` ＝ 堆叠，给手机竖屏用 —— 手机可用宽度约 360dp，并排后每个框只剩 ~170dp，
 *     填 `192.168.1.10:8092` 这种 13 字符的地址会被截断，家长没法核对。
 * @param onClose 传非 null 时右上角出现「返回」。家长端设置是底部 Tab、不需要返回，
 *   孩子端是从主界面弹出来的、需要一个明确的出口。
 * @param extraContent 追加在页面最末尾的一块内容。
 *   ⚠️ 做成插槽而不是把那些内容搬进来：孩子端要在这里挂一个「实时画面」的开关
 *      （见 app-student 的 `mirror` 包），而家长端完全不需要它。
 *      把只属于一端的调试功能塞进共用页面，家长端会莫名其妙多出一堆看不懂的选项。
 */
@Composable
fun ServerSettingsScreen(
    modifier: Modifier = Modifier,
    stackFields: Boolean = false,
    onClose: (() -> Unit)? = null,
    extraContent: (@Composable () -> Unit)? = null,
    vm: ServerSettingsViewModel = hiltViewModel(),
) {
    val dimens = AppTheme.dimens
    var tokenVisible by remember { mutableStateOf(false) }
    val running = vm.state is ServerSettingsViewModel.TestState.Running

    // 每次进入设置页都从存储回读一次：VM 挂在 Activity 上比本页活得久，
    // 不回读就会把上次没保存的草稿当成本次配置显示出来。
    LaunchedEffect(Unit) { vm.reloadFromStore() }

    Column(
        modifier = modifier
            .fillMaxSize()
            // 加滚动是廉价的保险：孩子端横屏折算下来只有 ~533dp 高，
            // 家长端在字体放大设置下也会溢出。配置页滚不动是最难受的。
            .verticalScroll(rememberScrollState())
            .padding(dimens.screenPadding),
        verticalArrangement = Arrangement.spacedBy(dimens.itemGap),
    ) {
        // ---------------- 标题行 ----------------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("服务端设置", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(dimens.itemGap))
            Text(
                // 把设备标识摆在标题旁边：填错地址时，家长需要把它念给排查的人听，
                // 藏在二级页面里等于没有。
                text = "设备标识 ${vm.deviceId}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (onClose != null) {
                TextButton(onClick = onClose) { Text("返回") }
            }
        }

        Text(
            text = "填家庭服务器的地址与家庭令牌（都在服务端 .env 里）。" +
                "「测试连接」会先保存再试，所以不用先点保存。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 测试期内置默认值的提示。
        // 为什么非要显式说明：内置值在**编译期**烧进 APK，家长改设置页却不重新编译时，
        // 「看到的地址」和「实际生效的地址」会不一致 —— 这句话是排查时唯一的线索。
        // 用琥珀色（与「令牌未验证」同一极性）：它表示「状态需要你留意」，不是报错。
        // ⚠️ 刻意压成一行：孩子端横屏只有 ~533dp 高，多一行会把下面的结果区顶出屏幕，
        //    家长点了「测试连接」却看不到结果，会以为按钮坏了。
        if (vm.usingDefaults) {
            Text(
                text = "注：至少一项用的是内置测试配置（编译进安装包，改了要重装）。",
                style = MaterialTheme.typography.bodySmall,
                color = WarningAmber,
            )
        }

        // ---------------- 两个输入项 ----------------
        val urlField: @Composable (Modifier) -> Unit = { m ->
            OutlinedTextField(
                value = vm.baseUrl,
                onValueChange = vm::onBaseUrlChange,
                modifier = m,
                singleLine = true,
                label = { Text("服务端地址") },
                // 提示家长「可以少写」，因为 normalizeBaseUrl 真的会替你补全
                placeholder = { Text("192.168.1.10:8092") },
            )
        }
        val tokenField: @Composable (Modifier) -> Unit = { m ->
            OutlinedTextField(
                value = vm.token,
                onValueChange = vm::onTokenChange,
                modifier = m,
                singleLine = true,
                label = { Text("家庭令牌") },
                // 令牌是凭据，默认遮蔽；但家长核对时又要能看全，所以给个眼睛开关。
                visualTransformation = if (tokenVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                trailingIcon = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            imageVector = if (tokenVisible) Icons.Filled.VisibilityOff
                            else Icons.Filled.Visibility,
                            contentDescription = if (tokenVisible) "隐藏令牌" else "显示令牌",
                        )
                    }
                },
            )
        }

        if (stackFields) {
            Column(verticalArrangement = Arrangement.spacedBy(dimens.itemGap)) {
                urlField(Modifier.fillMaxWidth())
                tokenField(Modifier.fillMaxWidth())
            }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(dimens.itemGap),
                verticalAlignment = Alignment.Top,
            ) {
                urlField(Modifier.weight(1f))
                tokenField(Modifier.weight(1f))
            }
        }

        // ---------------- 操作行 ----------------
        // 手机上两个按钮 + 提示文字横排会挤成一行放不下，所以竖屏改成两行。
        val buttons: @Composable () -> Unit = {
            Button(
                onClick = vm::test,
                enabled = !running,
                modifier = Modifier.heightIn(min = dimens.minTouchTarget),
            ) { Text("测试连接") }

            OutlinedButton(
                onClick = vm::save,
                enabled = !running,
                modifier = Modifier.heightIn(min = dimens.minTouchTarget),
            ) { Text("保存") }

            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        }

        if (stackFields) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.itemGap),
            ) { buttons() }
            vm.hint?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(dimens.itemGap),
            ) {
                buttons()
                vm.hint?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        // ---------------- 结果区 ----------------
        TestStatusPanel(vm.state)

        // ---------------- 各端自己的附加内容 ----------------
        extraContent?.invoke()
    }
}

/**
 * 结果区。**必须分两行报**：地址通不通是一件事，令牌对不对是另一件事，
 * 合成一句「连接失败」等于没说 —— 这两种错要改的地方完全不同。
 */
@Composable
private fun TestStatusPanel(state: ServerSettingsViewModel.TestState) {
    val dimens = AppTheme.dimens

    Column(verticalArrangement = Arrangement.spacedBy(dimens.itemGap / 2)) {
        when (state) {
            ServerSettingsViewModel.TestState.Idle -> StatusLine(
                icon = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                text = "还没测过。",
            )

            ServerSettingsViewModel.TestState.Running -> StatusLine(
                icon = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                text = "正在测试…",
            )

            is ServerSettingsViewModel.TestState.Unreachable -> StatusLine(
                icon = Icons.Filled.Error,
                tint = AnswerWrong,
                text = state.detail,
            )

            is ServerSettingsViewModel.TestState.Reachable -> {
                StatusLine(
                    icon = Icons.Filled.CheckCircle,
                    tint = AnswerCorrect,
                    text = buildString {
                        append("地址可达 · 服务 ")
                        append(state.service)
                        append(" v")
                        append(state.version)
                        state.model?.let {
                            append(" · 模型 ")
                            append(it)
                        }
                    },
                )
                if (state.tokenOk) {
                    StatusLine(
                        icon = Icons.Filled.CheckCircle,
                        tint = AnswerCorrect,
                        text = "家庭令牌有效",
                    )
                } else {
                    StatusLine(
                        icon = Icons.Filled.Warning,
                        tint = WarningAmber,
                        text = state.tokenError ?: "令牌未验证",
                    )
                }
            }
        }
    }
}

/**
 * 一行结果。 [icon] 传 null 表示这一行只是说明文字，不表态 ——
 * 「还没测过」不是警告，不该画成琥珀色。
 *
 * 配色直接复用 [AnswerCorrect] / [AnswerWrong] / [WarningAmber]：
 * 极性一致（绿=通、红=不通），不值得为这一个页面再加三个颜色 token。
 */
@Composable
private fun StatusLine(
    icon: ImageVector?,
    tint: Color,
    text: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
        )
    }
}
