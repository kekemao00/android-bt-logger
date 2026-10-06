package com.xingkeqi.btlogger.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 暖灰黑白风格的颜色令牌。
 *
 * Why:
 * 界面只用浅暖灰的底、白色表面和墨黑元素区分层次，唯一的强调色只出现在选中、焦点与“已连接”这类成功状态上；
 * 组件只引用这里的令牌，不再直接写死绿色、蓝色等状态色，避免页面重新变得花哨。
 */
@Immutable
data class WarmMonoColors(
    /** 页面背景 */
    val canvas: Color,
    /** 卡片、输入框、面板 */
    val surface: Color,
    /** 表面上的按下、次级填充 */
    val surface2: Color,
    /** 头像底、幽灵按钮按下 */
    val surface3: Color,
    /** 主文字、主按钮、指示条、通知胶囊 */
    val ink: Color,
    val inkHover: Color,
    /** 次级文字 */
    val ink2: Color,
    /** 占位符、提示、表头 */
    val ink3: Color,
    /** 墨黑实心块上的文字（浅色主题为白，深色主题反转为深色） */
    val onInk: Color,
    val line: Color,
    val lineStrong: Color,
    /** 唯一强调色 */
    val accent: Color,
    val accentSoft: Color,
    val danger: Color,
    val dangerSoft: Color,
    /** 面板遮罩（暖黑） */
    val scrim: Color,
    val isDark: Boolean
) {
    /** 焦点光环：强调色 22% 不透明度 */
    val focusRing: Color get() = accent.copy(alpha = 0.22f)
}

val LightWarmMonoColors = WarmMonoColors(
    canvas = Color(0xFFEFEEEA),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF6F5F2),
    surface3 = Color(0xFFECEBE7),
    ink = Color(0xFF141413),
    inkHover = Color(0xFF2B2A28),
    ink2 = Color(0xFF5E5B56),
    ink3 = Color(0xFF9A968F),
    onInk = Color(0xFFFFFFFF),
    line = Color(0xFFE3E1DC),
    lineStrong = Color(0xFFCFCCC5),
    accent = Color(0xFF2F5BFF),
    accentSoft = Color(0xFFE8EDFF),
    danger = Color(0xFFD93B2B),
    dangerSoft = Color(0xFFFCEBE8),
    scrim = Color(0x421A1814),
    isDark = false
)

/**
 * 暗色值按规范第二节的建议推出：明度关系与浅色一致，主按钮反转为浅底深字，强调色不变。
 */
val DarkWarmMonoColors = WarmMonoColors(
    canvas = Color(0xFF161614),
    surface = Color(0xFF1E1E1C),
    surface2 = Color(0xFF252523),
    surface3 = Color(0xFF2C2C29),
    ink = Color(0xFFF2F1ED),
    inkHover = Color(0xFFDDDBD5),
    ink2 = Color(0xFFA8A49C),
    ink3 = Color(0xFF6F6B64),
    onInk = Color(0xFF141413),
    line = Color(0xFF2E2D2A),
    lineStrong = Color(0xFF3B3A36),
    accent = Color(0xFF2F5BFF),
    accentSoft = Color(0xFF1D2547),
    danger = Color(0xFFE5533F),
    dangerSoft = Color(0xFF3A1F1B),
    scrim = Color(0x80000000),
    isDark = true
)

val LocalWarmMonoColors = staticCompositionLocalOf { LightWarmMonoColors }
