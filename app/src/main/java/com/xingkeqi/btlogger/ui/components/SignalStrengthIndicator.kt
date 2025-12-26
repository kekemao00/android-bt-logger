package com.xingkeqi.btlogger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.ui.theme.SignalMedium
import com.xingkeqi.btlogger.ui.theme.SignalStrong
import com.xingkeqi.btlogger.ui.theme.SignalWeak

/**
 * 信号强度指示器组件
 * @param rssi 信号强度 (dBm)，典型范围 -100 ~ 0
 * @param showText 是否显示 dBm 数值
 */
@Composable
fun SignalStrengthIndicator(
    rssi: Short,
    modifier: Modifier = Modifier,
    showText: Boolean = false
) {
    // 将 RSSI 映射到 0-4 格信号
    val level = when {
        rssi >= -50 -> 4
        rssi >= -60 -> 3
        rssi >= -70 -> 2
        rssi >= -80 -> 1
        else -> 0
    }

    val color = when {
        rssi >= -50 -> SignalStrong
        rssi >= -70 -> SignalMedium
        else -> SignalWeak
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        SignalBars(level = level, color = color)
        if (showText) {
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "${rssi}dBm",
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}

/**
 * 绘制信号格
 */
@Composable
private fun SignalBars(
    level: Int,
    color: Color,
    modifier: Modifier = Modifier
) {
    val inactiveColor = Color.Gray.copy(alpha = 0.3f)

    Canvas(modifier = modifier.size(Dimens.iconSizeSm)) {
        val barCount = 4
        val barWidth = size.width / (barCount * 2 - 1)
        val maxHeight = size.height

        for (i in 0 until barCount) {
            val barHeight = maxHeight * (i + 1) / barCount
            val x = i * barWidth * 2
            val y = maxHeight - barHeight

            drawRoundRect(
                color = if (i < level) color else inactiveColor,
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }
    }
}
