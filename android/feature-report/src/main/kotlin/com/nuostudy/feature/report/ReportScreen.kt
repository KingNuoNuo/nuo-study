package com.nuostudy.feature.report

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuostudy.core.network.EnglishApi
import com.nuostudy.core.network.ProgressSummary
import com.nuostudy.core.ui.theme.AppTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

/**
 * 学情报告 · 家长端（手机竖屏）。
 *
 * ## 数据从哪来
 *
 * **服务端 `english_progress` 表** —— 和网页、平板读的是同一个接口
 * （`GET /api/v1/english/progress`）。孩子在平板上翻的单词、答的题、
 * 跟读的分数，网页上刷一遍的课文，都会汇到这里。
 *
 * 之前这一页是空占位（「依赖 M2，当前暂缓」）—— 那套 M2 报告依赖
 * 作答/批改全链路，还遥遥无期；英语这条线的内容是现成的，先把它接通。
 *
 * ## 展示原则（沿用原来的设计约束）
 *
 * 家长要的是「孩子哪里不会」，不是一堆数字。所以每个数字旁边都尽量带
 * 对照物：正确率要能看到总数，翻过多少要能看出掌握了多少。
 */
@Composable
fun ReportScreen(modifier: Modifier = Modifier, vm: ReportViewModel = hiltViewModel()) {
    val dimens = AppTheme.dimens
    val s = vm.summary

    Column(modifier = modifier.fillMaxSize().padding(dimens.screenPadding)) {
        Text("学情", style = MaterialTheme.typography.displaySmall)
        Text(
            text = when {
                s != null -> "${s.student?.name ?: "孩子"} · ${s.range?.from ?: ""} 至 ${s.range?.to ?: ""}（最近 7 天）"
                else -> "最近 7 天"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when {
            // 首次加载还没有任何数据：转圈，而不是一堆 0
            s == null && vm.loading -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }

            s == null && vm.error.isNotBlank() -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(vm.error, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.load() }) { Text("重试") }
                }
            }

            s != null -> ReportContent(s, onRetry = { vm.load() })
        }
    }
}

@Composable
private fun ReportContent(s: ProgressSummary, onRetry: () -> Unit) {
    val t = s.totals
    LazyColumn(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
    ) {
        // ---- 三个家长最关心的 ----
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigStat(
                    label = "跟读均分",
                    value = if (t.speakCount > 0) "%.0f".format(t.speakAvg) else "—",
                    note = "${t.speakCount} 次跟读",
                    modifier = Modifier.weight(1f),
                )
                BigStat(
                    label = "答题正确率",
                    value = if (t.quizTotal > 0) "%.0f%%".format(t.quizAccuracy * 100) else "—",
                    note = "${t.quizCorrect}/${t.quizTotal} 题",
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallStat("学习天数", "${t.days} 天", Modifier.weight(1f))
                SmallStat("翻过单词", "${t.wordsSeen} 个", Modifier.weight(1f))
                SmallStat("掌握单词", "${t.wordsKnown} 个", Modifier.weight(1f))
            }
        }

        // ---- 按单元：孩子在哪里花的时间多、哪里还没碰 ----
        if (s.units.isNotEmpty()) {
            item {
                Text(
                    "按单元",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            items(s.units) { u ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f),
                    ),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Unit ${u.unit}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                if (u.speakCount > 0) "跟读均分 %.0f".format(u.speakAvg) else "还没跟读",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            "单词 翻${u.wordsSeen}/掌握${u.wordsKnown} · 题目 ${u.quizCorrect}/${u.quizTotal}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
        }

        if (t.wordsSeen == 0 && t.quizTotal == 0 && t.speakCount == 0) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f),
                    ),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text(
                        "最近 7 天还没有学习记录。\n孩子用网页或平板学过之后，这里会自动出现。",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }

        item {
            Text(
                "数据存在家里的 NAS 上，网页、孩子平板、这个 App 看到的是同一份。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BigStat(label: String, value: String, note: String, modifier: Modifier = Modifier) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .6f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(value, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(note, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SmallStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val api: EnglishApi,
) : ViewModel() {

    var summary by mutableStateOf<ProgressSummary?>(null)
        private set

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf("")
        private set

    init {
        load()
    }

    fun load(days: Int = 7) {
        if (loading) return
        loading = true
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { api.progress(days) }
            }
            loading = false
            result
                .onSuccess { summary = it; error = "" }
                .onFailure { error = friendly(it) }
        }
    }

    private fun friendly(t: Throwable): String = when (t) {
        is HttpException -> "服务端返回 ${t.code()}"
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            "连不上家里的服务端（检查 Wi-Fi 和 NAS 是否在跑）"
        is IOException -> "网络中断：${t.message ?: "未知"}"
        else -> t.message ?: "未知错误"
    }
}
