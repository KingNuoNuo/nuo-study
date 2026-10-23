package com.jinyu.study.student.mirror

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.jinyu.study.core.data.prefs.ServerConfigStore
import com.jinyu.study.core.data.prefs.ServerDefaults
import com.jinyu.study.core.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「实时画面」开关的状态。挂在设置页的末尾（见 [MirrorSettings]）。
 *
 * 开关本身很简单，[pageUrl] 才是重点：家长要看的网页地址里带着家庭令牌，
 * 而令牌平时在输入框里是**遮蔽**的。让他自己去拼一个带 token 的 URL 不现实，
 * 所以这里直接把完整地址摆出来让他照抄 —— 这也顺便说明了「令牌到底是什么」。
 */
@HiltViewModel
class MirrorViewModel @Inject constructor(
    private val store: ServerConfigStore,
) : ViewModel() {

    /**
     * 给家长照抄的网页地址。未配置服务端时是空串，界面就不显示这一行。
     *
     * ⚠️ 只在这里算一次。地址和令牌是**同步**的（[ServerConfigStore] 启动时预读进内存），
     *    但它们由同一个页面上的输入框维护、且「测试连接」会保存 ——
     *    所以家长在同一次设置里改完地址后，这一行要等重新进设置页才刷新。
     *    可接受的取舍：这是个调试入口，没人会边改地址边看它。
     */
    val pageUrl: String = run {
        val base = store.baseUrl().orEmpty().trimEnd('/')
        val token = store.familyToken().orEmpty()
        if (base.isBlank() || token.isBlank()) "" else "$base/debug/screen?token=$token"
    }

    /**
     * ⚠️ 初值必须给 [ServerDefaults.MIRROR_ENABLED] 而不是 `false`：
     *    DataStore 的第一次发射是异步的（要读盘），给 false 会让开关先闪一下「关」。
     *    这个默认值和 [ServerConfigStore.mirrorEnabled] 里那个是**同一个来源**，
     *    不会出现「界面说关、实际在跑」。
     */
    val enabled: StateFlow<Boolean> = store.mirrorEnabled
        .stateIn(viewModelScope, SharingStarted.Eagerly, ServerDefaults.MIRROR_ENABLED)

    fun setEnabled(on: Boolean) {
        viewModelScope.launch { store.setMirrorEnabled(on) }
    }
}

/**
 * 学生端设置页末尾的「实时画面」开关。
 *
 * ⚠️ 说明文字必须把**边界**讲清楚，否则家长会觉得这就是个监控器：
 *    · 只在孩子端处于前台时上传；
 *    · 只传到自己家里的服务器，不经过任何外部服务；
 *    · 关掉即停。
 *    这三句不是免责声明 —— 它是家长决定要不要给自己孩子开这个东西的依据。
 */
@Composable
fun MirrorSettings(vm: MirrorViewModel = hiltViewModel()) {
    val dimens = AppTheme.dimens
    val on by vm.enabled.collectAsStateWithLifecycle()

    HorizontalDivider(modifier = Modifier.padding(top = dimens.itemGap / 2))

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text("实时画面（调试）", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "打开后每 3 秒把平板当前画面传到家庭服务器，家长用手机或电脑的" +
                    "浏览器就能实时看。只在学生端处于前台时上传，只传给自己家的服务器。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (vm.pageUrl.isNotBlank()) {
                Text(
                    text = "查看地址：${vm.pageUrl}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Text(
                    text = "还没配置服务端地址，配好后这里会显示查看地址。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Spacer(Modifier.width(dimens.itemGap))
        Switch(checked = on, onCheckedChange = vm::setEnabled)
    }
}
