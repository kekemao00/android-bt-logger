package com.xingkeqi.btlogger.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 统一的尺寸常量，避免硬编码。
 *
 * 数值取自 warm-mono-spring-ui 规范，并按移动端触屏密度（约 ×1.15）放大，保证按钮点击区域不低于 44dp。
 */
object Dimens {
    // 间距
    val spacingXs = 4.dp
    val spacingSm = 8.dp
    val spacingMd = 12.dp
    val spacingLg = 16.dp
    val spacingXl = 24.dp
    val spacingXxl = 48.dp

    /** 页面左右留白 */
    val pageGutter = 16.dp

    // 圆角
    val radiusButton = 10.dp
    val radiusPrimary = 12.dp
    val radiusInput = 12.dp
    val radiusPanel = 16.dp

    // 控件高度
    val buttonHeight = 44.dp
    val inputHeight = 46.dp
    val iconButtonSize = 40.dp
    val tabHeight = 40.dp
    val chipHeight = 24.dp
    val topBarHeight = 60.dp
    val notificationHeight = 40.dp

    // 描边
    val stroke = 1.dp
    val focusRing = 3.dp

    // 卡片
    val cardPadding = 16.dp
    val cardCornerRadius = radiusPanel

    // 图标尺寸
    val iconSizeXs = 12.dp
    val iconSizeSm = 16.dp
    val iconSizeMd = 20.dp
    val iconSizeLg = 24.dp

    // 头像
    val avatarSize = 40.dp
    val emptyStateCircle = 48.dp

    // 状态指示器
    val statusIndicatorSize = 8.dp

    // 进度条
    val progressBarHeight = 6.dp

    // 列表
    val listContentPadding = pageGutter
    val listItemSpacing = 8.dp
    val rowDividerInset = 16.dp
}
