package com.xingkeqi.btlogger.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 主题入口：组件优先通过 [BtTheme.colors] 读取令牌；
 * 仍在使用的 Material 组件（菜单、滑块、进度圈）通过映射后的 colorScheme 拿到同一套颜色。
 */
object BtTheme {
    val colors: WarmMonoColors
        @Composable
        @ReadOnlyComposable
        get() = LocalWarmMonoColors.current
}

/**
 * 把令牌映射到 Material3 的 colorScheme。
 *
 * Why:
 * 关闭 Android 12+ 的动态取色，否则壁纸色会覆盖唯一强调色；surfaceTint 设为透明，
 * 去掉 Material 的 tonal elevation 着色，层次只靠 canvas / surface 的明度差和 1px 描边表达。
 */
private fun WarmMonoColors.toColorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = ink,
        onPrimary = onInk,
        primaryContainer = accentSoft,
        onPrimaryContainer = accent,
        secondary = ink2,
        onSecondary = onInk,
        secondaryContainer = surface3,
        onSecondaryContainer = ink,
        tertiary = accent,
        onTertiary = Color.White,
        background = canvas,
        onBackground = ink,
        surface = surface,
        onSurface = ink,
        surfaceVariant = surface2,
        onSurfaceVariant = ink2,
        surfaceTint = Color.Transparent,
        inverseSurface = ink,
        inverseOnSurface = onInk,
        outline = lineStrong,
        outlineVariant = line,
        error = danger,
        onError = Color.White,
        errorContainer = dangerSoft,
        onErrorContainer = danger,
        scrim = scrim,
        surfaceBright = surface,
        surfaceDim = surface2,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surface3
    )
}

private val WarmMonoShapes = Shapes(
    extraSmall = RoundedCornerShape(Dimens.radiusButton),
    small = RoundedCornerShape(Dimens.radiusButton),
    medium = RoundedCornerShape(Dimens.radiusInput),
    large = RoundedCornerShape(Dimens.radiusPanel),
    extraLarge = RoundedCornerShape(Dimens.radiusPanel)
)

@Composable
fun BtLoggerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkWarmMonoColors else LightWarmMonoColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 状态栏、导航栏与页面背景（canvas）一致，图标明暗跟随主题
            window.statusBarColor = colors.canvas.toArgb()
            window.navigationBarColor = colors.canvas.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalWarmMonoColors provides colors) {
        MaterialTheme(
            colorScheme = colors.toColorScheme(),
            typography = Typography,
            shapes = WarmMonoShapes,
            content = content
        )
    }
}
