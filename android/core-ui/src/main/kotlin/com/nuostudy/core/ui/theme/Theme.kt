package com.nuostudy.core.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight

/** 两个客户端形态。用枚举而非布尔，将来要加「老师端 web view」也不用改签名。 */
enum class FormFactor { Student, Parent }

val LocalDimens = staticCompositionLocalOf { StudentDimens }
val LocalFormFactor = staticCompositionLocalOf { FormFactor.Student }

private val LightColors = lightColorScheme(
    primary = BrandBlue,
    onPrimary = SurfaceBase,
    primaryContainer = BrandBlueLight,
    onPrimaryContainer = BrandBlueDark,
    background = SurfaceBase,
    onBackground = InkStrong,
    surface = SurfaceBase,
    onSurface = InkStrong,
    surfaceVariant = SurfaceSunken,
    onSurfaceVariant = InkNormal,
    outline = DividerSoft,
    error = AnswerWrong,
    onError = SurfaceBase,
)

private val DarkColors = darkColorScheme(
    primary = BrandBlueLight,
    onPrimary = BrandBlueDark,
    // 容器色刻意与浅色模式「镜像」：浅色用浅蓝底 + 深蓝字，暗色用深蓝底 + 浅蓝字。
    // ⚠️ 这两行本来漏了 —— 暗色下 container 会退回 Material 默认紫色，
    //    对话气泡和激活验证码会变成紫色块。#DCE7FF on #1B4FBF 对比度约 4.6:1，过 AA。
    primaryContainer = BrandBlueDark,
    onPrimaryContainer = BrandBlueLight,
    background = DarkSurface,
    onBackground = DarkInkStrong,
    surface = DarkSurface,
    onSurface = DarkInkStrong,
    surfaceVariant = DarkSurfaceSunken,
    onSurfaceVariant = DarkInkNormal,
    outline = DarkDivider,
    error = AnswerWrong,
)

/**
 * 排版随形态缩放 —— 这是本 App 最实在的一条无障碍措施：
 * 孩子端的字号不是「大一点点」，而是整体放大一档。
 */
private fun studyTypography(d: AppDimens): Typography {
    val base = Typography()
    val body = TextStyle(fontSize = d.bodySize, lineHeight = d.bodySize * 1.6)
    return base.copy(
        bodyLarge = body,
        bodyMedium = body,
        bodySmall = base.bodySmall.copy(fontSize = d.bodySize * 0.85f),
        titleLarge = base.titleLarge.copy(fontSize = d.titleSize, fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontSize = d.titleSize * 0.8f, fontWeight = FontWeight.Medium),
        displaySmall = base.displaySmall.copy(fontSize = d.displaySize, fontWeight = FontWeight.Bold),
        labelLarge = base.labelLarge.copy(fontSize = d.bodySize * 0.9f, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun StudyTheme(
    formFactor: FormFactor = FormFactor.Student,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val dimens = if (formFactor == FormFactor.Student) StudentDimens else ParentDimens
    CompositionLocalProvider(
        LocalDimens provides dimens,
        LocalFormFactor provides formFactor,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = studyTypography(dimens),
            content = content,
        )
    }
}

/** 取当前形态的尺寸 token：`AppTheme.dimens.minTouchTarget`。 */
object AppTheme {
    val dimens: AppDimens
        @Composable @ReadOnlyComposable get() = LocalDimens.current

    val formFactor: FormFactor
        @Composable @ReadOnlyComposable get() = LocalFormFactor.current
}
