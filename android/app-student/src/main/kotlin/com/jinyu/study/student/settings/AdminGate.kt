package com.jinyu.study.student.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.jinyu.study.core.ui.theme.AppTheme

/**
 * 家长口令。孩子端点「设置」后必须先输对它才进得去。
 *
 * ⚠️ 这是**防误触 / 防误改的门闩，不是安全边界**，两点必须说清：
 *
 *  1. 它在 APK 里是**明文**。`strings` 一下、或者反编译一眼就能看到。
 *     真要有心人是挡不住的 —— 它要挡的是「孩子好奇点进去把服务器地址改坏，
 *     第二天打不开作业，而家长完全不知道发生了什么」。
 *  2. **真正的安全边界是服务端的 `FAMILY_TOKEN`**，那串东西才决定谁能读写
 *     家里的数据。别把这里的口令和它混为一谈，更别拿它去当任何真实凭据。
 *
 * 为什么不存哈希：本地比对哈希和比对明文面对的是同一个攻击者（都能改 APK 或
 * 直接调 API），哈希只多挡住「用记事本 grep 字符串」这一种，却让「家长忘了口令」
 * 变得无法挽救。自家用的 App，可维护性优先于这种象征性的加固。
 *
 * 要换口令就改这一行 —— 它只在这一个地方出现，别在别处复制一份。
 */
internal const val ADMIN_PASSWORD = "npc1024"

/**
 * 口令对话框。输对了调 [onSuccess]，取消/点外面调 [onDismiss]。
 *
 * 输错时**不清空到只剩错误提示就完事**，而是把输入框清掉并把错误挂在
 * supportingText 上 —— 这样家长能立刻重输，不用先手动删干净。
 */
@Composable
internal fun AdminPasswordDialog(
    onDismiss: () -> Unit,
    onSuccess: () -> Unit,
) {
    val dimens = AppTheme.dimens
    var input by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    // 用 val 而不是局部 fun：局部 fun 在 @Composable 里无法被 lambda 参数捕获成
    // 稳定的引用，写成 val 更贴合 Compose 的心智模型（它就是个值）。
    val submit: () -> Unit = {
        if (input == ADMIN_PASSWORD) {
            onSuccess()
        } else {
            error = "口令不对，再试一次"
            input = ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("家长验证") },
        text = {
            Column {
                Text(
                    text = "服务端设置会影响孩子能不能看到作业，需要家长口令。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(dimens.itemGap))
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        error = null          // 一动手就清错误，别让红字一直挂着
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("口令") },
                    isError = error != null,
                    // ⚠️ 写成「永远非空的 lambda，内部再判空」，不要写
                    //    `error?.let { { Text(it) } }` —— 后者内层 lambda 的期望类型
                    //    是 let 的返回类型 R，推断出来是普通的 `() -> Unit`，
                    //    而槽位要的是 `@Composable () -> Unit`，两者在 Kotlin 里
                    //    **不是同一种类型**，会直接编译不过。
                    supportingText = {
                        val msg = error
                        if (msg != null) Text(msg)
                    },
                    // 口令含字母（npc1024 这种「字母+数字」组合），所以不能用
                    // NumberPassword，否则部分输入法会把字母键藏起来。
                    // Password 是「任意字符 + 遮蔽」，才是对的。
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    // 输完直接按回车就能进，不用再去戳「确定」
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
            }
        },
        confirmButton = { TextButton(onClick = submit) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
