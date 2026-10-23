package com.nuostudy.feature.learning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.nuostudy.core.ui.theme.AppTheme
import com.nuostudy.feature.chinese.ChineseScreen
import com.nuostudy.feature.english.EnglishScreen
import com.nuostudy.feature.math.MathScreen

/**
 * 学习板块容器（孩子端）。
 *
 * 与「今日作业」的关系是导航设计的关键：
 *   作业 = 老师布置的 → 依赖家长拍照识别 → 有截止、要提交
 *   学习 = 孩子自己练的 → **完全不依赖家长**，打开就能练
 *
 * ⚠️ 内部**不要**再用横向 Pager。外层已经有一层横向 Pager 在切「作业 / 学习」，
 *    再嵌一层会抢手势，要写复杂的嵌套滚动仲裁 —— 切换成本这么低的事不值得。
 *    内部用顶部 Tab **点击**切换三科即可。
 */
@Composable
fun LearningScreen(modifier: Modifier = Modifier) {
    val dimens = AppTheme.dimens
    val subjects = listOf("语文", "数学", "英语")
    var selected by rememberSaveable { mutableIntStateOf(2) }

    if (selected == 2) {
        EnglishScreen(
            modifier = modifier.fillMaxSize(),
            onSelectSubject = { selected = it },
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(dimens.screenPadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(dimens.itemGap),
        ) {
            subjects.forEachIndexed { index, subject ->
                val active = selected == index
                Surface(
                    onClick = { selected = index },
                    shape = RoundedCornerShape(dimens.radiusButton),
                    color = if (active) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(dimens.radiusButton)),
                ) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = subject,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        when (selected) {
            0 -> ChineseScreen(Modifier.weight(1f))
            1 -> MathScreen(Modifier.weight(1f))
            else -> Unit
        }
    }
}
