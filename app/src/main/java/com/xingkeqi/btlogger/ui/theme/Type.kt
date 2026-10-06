package com.xingkeqi.btlogger.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.xingkeqi.btlogger.R

/**
 * Geist 只覆盖拉丁字母与数字，中文由系统字体自动回落；字体随 APK 打包，不依赖网络下载。
 */
val GeistFontFamily = FontFamily(
    Font(R.font.geist_regular, FontWeight.Normal),
    Font(R.font.geist_medium, FontWeight.Medium),
    Font(R.font.geist_semibold, FontWeight.SemiBold)
)

/** 等宽数字：MAC 地址、时间戳、百分比 */
val GeistMonoFontFamily = FontFamily(
    Font(R.font.geist_mono_regular, FontWeight.Normal),
    Font(R.font.geist_mono_medium, FontWeight.Medium)
)

private fun style(size: Float, weight: FontWeight, lineHeight: Float, family: FontFamily = GeistFontFamily) = TextStyle(
    fontFamily = family,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = 0.sp
)

/**
 * 字号刻度按规范的移动端密度（×1.15）放大：层级主要靠字重和 ink 深浅区分，而不是靠放大字号。
 *
 * - headline 24/600：页面主标题
 * - title 17/600：面板标题、空状态标题
 * - body 15/400、body-medium 15/500：正文、名称
 * - small 13/400：辅助说明
 * - caption 12/500：分组标题、标签 chip
 */
val Typography = Typography(
    headlineSmall = style(24f, FontWeight.SemiBold, 30f),
    titleLarge = style(20f, FontWeight.SemiBold, 26f),
    titleMedium = style(17f, FontWeight.SemiBold, 23f),
    titleSmall = style(15f, FontWeight.Medium, 21f),
    bodyLarge = style(16f, FontWeight.Normal, 22f),
    bodyMedium = style(15f, FontWeight.Normal, 21f),
    bodySmall = style(13f, FontWeight.Normal, 18f),
    labelLarge = style(15f, FontWeight.Medium, 20f),
    labelMedium = style(13f, FontWeight.Medium, 18f),
    labelSmall = style(12f, FontWeight.Medium, 16f)
)

/** 等宽数字样式 */
val MonoTextStyle = style(13f, FontWeight.Normal, 18f, GeistMonoFontFamily)
