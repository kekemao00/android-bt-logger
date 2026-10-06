package com.xingkeqi.btlogger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xingkeqi.btlogger.ui.theme.BtTheme

/** 低于这个电量才用危险色提醒，其余电量保持黑白 */
private const val LOW_BATTERY_THRESHOLD = 20

/**
 * 电量指示器组件
 * @param level 电量百分比 (0-100)
 * @param label 前缀文案（如“手机电量”）
 */
@Composable
fun BatteryIndicator(
    level: Int,
    modifier: Modifier = Modifier,
    label: String? = null
) {
    val colors = BtTheme.colors
    WmChip(
        text = buildString {
            if (!label.isNullOrBlank()) {
                append(label)
                append(' ')
            }
            append(level)
            append('%')
        },
        icon = LineIcons.Battery,
        contentColor = if (level < LOW_BATTERY_THRESHOLD) colors.danger else colors.ink2,
        modifier = modifier
    )
}
