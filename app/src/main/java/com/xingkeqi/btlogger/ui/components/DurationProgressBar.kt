package com.xingkeqi.btlogger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.utils.getDurationString

/**
 * 连接/断开时长占比：墨黑表示连接，surface-3 轨道表示断开
 * @param connectionTime 连接总时长 (毫秒)
 * @param disconnectionTime 断开总时长 (毫秒)
 */
@Composable
fun DurationProgressBar(
    connectionTime: Long,
    disconnectionTime: Long,
    modifier: Modifier = Modifier
) {
    val colors = BtTheme.colors
    val total = connectionTime + disconnectionTime
    val progress = if (total > 0) connectionTime.toFloat() / total else 0f

    Column(modifier = modifier.fillMaxWidth()) {
        WmProgressBar(progress = progress)
        Spacer(modifier = Modifier.height(Dimens.spacingSm))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(id = R.string.duration_connected, getDurationString(connectionTime)),
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink
            )
            Text(
                text = stringResource(id = R.string.duration_disconnected, getDurationString(disconnectionTime)),
                style = MaterialTheme.typography.labelSmall,
                color = colors.ink3
            )
        }
    }
}
