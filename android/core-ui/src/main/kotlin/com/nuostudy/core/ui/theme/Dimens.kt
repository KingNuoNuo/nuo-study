package com.nuostudy.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 两端全部的形态差异都集中在这一个 data class 里。
 *
 * 为什么不是每个组件各写各的字号 —— 因为「孩子端要更大」这件事有几十处体现
 * （按钮、标题、间距、圆角），散落在各 feature 里必然漂移。集中成 token 之后，
 * 「孩子端再大一号」这种需求只需要改一个数。
 */
@Immutable
data class AppDimens(
    /** 最小可点区域。孩子手指控制精度差，低于这个值一定会误触 */
    val minTouchTarget: Dp,
    val screenPadding: Dp,
    val cardPadding: Dp,
    val itemGap: Dp,
    val radiusCard: Dp,
    val radiusButton: Dp,
    val bodySize: TextUnit,
    val titleSize: TextUnit,
    val displaySize: TextUnit,
)

/**
 * 孩子端 · 平板横屏。
 * 触控 56dp（远超 Material 建议的 48dp）—— 小学生误触率实测高得多，
 * 而这个 App 里「误触」的代价是作业数据被打断，不是多翻一页。
 */
val StudentDimens = AppDimens(
    minTouchTarget = 56.dp,
    screenPadding = 20.dp,
    cardPadding = 18.dp,
    itemGap = 16.dp,
    radiusCard = 20.dp,
    radiusButton = 16.dp,
    bodySize = 20.sp,
    titleSize = 26.sp,
    displaySize = 40.sp,
)

/**
 * 家长端 · 手机竖屏。
 * 用标准 Material 密度即可 —— 家长看的是信息，不是要「点得准」。
 */
val ParentDimens = AppDimens(
    minTouchTarget = 48.dp,
    screenPadding = 16.dp,
    cardPadding = 14.dp,
    itemGap = 12.dp,
    radiusCard = 14.dp,
    radiusButton = 12.dp,
    bodySize = 15.sp,
    titleSize = 19.sp,
    displaySize = 28.sp,
)
