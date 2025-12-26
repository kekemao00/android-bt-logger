package com.xingkeqi.btlogger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.xingkeqi.btlogger.ui.theme.ConnectedGreen
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.utils.getDurationString

/**
 * 连接/断开时长进度条
 * @param connectionTime 连接总时长 (毫秒)
 * @param disconnectionTime 断开总时长 (毫秒)
 */
@Composable
fun DurationProgressBar(
    connectionTime: Long,
    disconnectionTime: Long,
    modifier: Modifier = Modifier
) {
    val total = connectionTime + disconnectionTime
    val progress = if (total > 0) connectionTime.toFloat() / total else 0f

    Column(modifier = modifier.fillMaxWidth()) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(Dimens.progressBarHeight)
                .clip(RoundedCornerShape(4.dp)),
            color = ConnectedGreen,
            trackColor = MaterialTheme.colorScheme.errorContainer,
            strokeCap = StrokeCap.Round
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row {
            Text(
                text = "连接 ${getDurationString(connectionTime)}",
                style = MaterialTheme.typography.labelSmall,
                color = ConnectedGreen
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "断开 ${getDurationString(disconnectionTime)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}
