package com.xingkeqi.btlogger.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xingkeqi.btlogger.ui.theme.BtTheme

/**
 * 状态徽章类型。
 *
 * Why:
 * 原先每种状态一个实心彩色块（绿、灰、蓝），与“只有一个强调色”的规范冲突；
 * 现在统一为白底描边 chip，只用前面的小圆点区分：已连接用强调色（成功态），播放中用墨黑，其余用 ink-3。
 */
enum class BadgeType { Connected, Disconnected, Playing, Paused }

/**
 * 状态徽章组件
 * @param text 显示文本
 * @param type 徽章类型
 */
@Composable
fun StatusBadge(
    text: String,
    type: BadgeType,
    modifier: Modifier = Modifier
) {
    val colors = BtTheme.colors
    val dot = when (type) {
        BadgeType.Connected -> colors.accent
        BadgeType.Playing -> colors.ink
        BadgeType.Disconnected, BadgeType.Paused -> colors.ink3
    }
    val content = when (type) {
        BadgeType.Connected, BadgeType.Playing -> colors.ink
        BadgeType.Disconnected, BadgeType.Paused -> colors.ink2
    }
    WmChip(text = text, dotColor = dot, contentColor = content, modifier = modifier)
}
