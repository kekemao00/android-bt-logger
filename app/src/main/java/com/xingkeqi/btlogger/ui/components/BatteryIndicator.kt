package com.xingkeqi.btlogger.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.ui.theme.BatteryHigh
import com.xingkeqi.btlogger.ui.theme.BatteryLow
import com.xingkeqi.btlogger.ui.theme.BatteryMedium
import com.xingkeqi.btlogger.ui.theme.Dimens

/**
 * 电量指示器组件
 * @param level 电量百分比 (0-100)
 * @param showText 是否显示百分比文字
 */
@Composable
fun BatteryIndicator(
    level: Int,
    modifier: Modifier = Modifier,
    showText: Boolean = true
) {
    val color = when {
        level < 20 -> BatteryLow
        level < 50 -> BatteryMedium
        else -> BatteryHigh
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_battery),
            contentDescription = "电量",
            tint = color,
            modifier = Modifier.size(Dimens.iconSizeSm)
        )
        if (showText) {
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = "$level%",
                style = MaterialTheme.typography.labelSmall,
                color = color
            )
        }
    }
}
