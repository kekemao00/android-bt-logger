package com.xingkeqi.btlogger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.Dimens

/**
 * 连接状态指示器 - 圆点形式：已连接用强调色，断开用 ink-3
 * @param isConnected 是否已连接
 */
@Composable
fun ConnectionStatusIndicator(
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = BtTheme.colors
    Box(
        modifier = modifier
            .size(Dimens.statusIndicatorSize)
            .background(
                color = if (isConnected) colors.accent else colors.ink3,
                shape = CircleShape
            )
    )
}
