package com.xingkeqi.btlogger.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.xingkeqi.btlogger.ui.theme.ConnectedGreen
import com.xingkeqi.btlogger.ui.theme.DisconnectedGray
import com.xingkeqi.btlogger.ui.theme.PlayingBlue

/**
 * 状态徽章类型
 */
enum class BadgeType(val backgroundColor: Color, val contentColor: Color) {
    Connected(ConnectedGreen, Color.White),
    Disconnected(DisconnectedGray, Color.White),
    Playing(PlayingBlue, Color.White),
    Paused(Color(0xFF757575), Color.White)
}

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
    Surface(
        color = type.backgroundColor,
        shape = RoundedCornerShape(4.dp),
        modifier = modifier
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = type.contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
