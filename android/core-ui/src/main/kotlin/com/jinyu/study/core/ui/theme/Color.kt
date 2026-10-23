package com.jinyu.study.core.ui.theme

import androidx.compose.ui.graphics.Color

// =============================================================================
// 颜色 token
// =============================================================================

// ---------------- 品牌色 ----------------
val BrandBlue = Color(0xFF2F6FED)
val BrandBlueDark = Color(0xFF1B4FBF)
val BrandBlueLight = Color(0xFFDCE7FF)

// ---------------- 学科识别色 ----------------
// ⚠️ 四个板块各有自己的颜色，**不是装饰**：小学生靠颜色定位模块比读文字快得多。
//    顶部导航的指示器、卡片左侧色条、知识点标签都复用它，保持一致。
val SubjectHomework = BrandBlue          // 作业
val SubjectLearning = Color(0xFF6C5CE7)  // 学习（容器，自身不是学科）
val SubjectChinese = Color(0xFFD24B45)   // 语文：朱红
val SubjectMath = Color(0xFF3D5AFE)      // 数学：靛蓝
val SubjectEnglish = Color(0xFFE8833A)   // 英语：橙

// ---------------- 语义色 ----------------
// ⚠️ 这里「绿 = 对 / 红 = 错」是**教育场景**的通用约定，
//    与股票行情的「红涨绿跌」无关，不要混用两套心智模型。
val AnswerCorrect = Color(0xFF2E9E5B)
val AnswerWrong = Color(0xFFE04B4B)
val AnswerPending = Color(0xFF8A8F98)    // 待批 / 打卡项，刻意用中性灰

// ---------------- 中性色 ----------------
val InkStrong = Color(0xFF1B1F27)
val InkNormal = Color(0xFF444952)
val InkMuted = Color(0xFF8A8F98)
val SurfaceBase = Color(0xFFFFFFFF)
val SurfaceSunken = Color(0xFFF4F6FA)
val DividerSoft = Color(0xFFE3E7EF)
val WarningAmber = Color(0xFFE0A030)     // 「模型不确定，请重点核对」的高亮

// ---------------- 深色（孩子端夜间做题用）----------------
val DarkSurface = Color(0xFF16191F)
val DarkSurfaceSunken = Color(0xFF0E1116)
val DarkInkStrong = Color(0xFFF2F4F8)
/** 深色模式下正文不能直接复用浅色的 InkNormal —— 对比度不够，看着发闷。 */
val DarkInkNormal = Color(0xFFB9C0CC)
val DarkDivider = Color(0xFF2A2F3A)
