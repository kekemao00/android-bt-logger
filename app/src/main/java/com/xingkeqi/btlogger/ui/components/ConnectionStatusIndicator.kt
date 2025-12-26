package com.xingkeqi.btlogger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.xingkeqi.btlogger.ui.theme.ConnectedGreen
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.ui.theme.DisconnectedGray

/**
 * 连接状态指示器 - 圆点形式
 * @param isConnected 是否已连接
 */
@Composable
fun ConnectionStatusIndicator(
    isConnected: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(Dimens.statusIndicatorSize)
            .background(
                color = if (isConnected) ConnectedGreen else DisconnectedGray,
                shape = CircleShape
            )
    )
}
