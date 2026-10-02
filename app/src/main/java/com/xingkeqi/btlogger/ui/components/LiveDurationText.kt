package com.xingkeqi.btlogger.ui.components

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.xingkeqi.btlogger.utils.getDurationString
import kotlinx.coroutines.delay

/**
 * 实时刷新的“距某时刻已过去多久”文本。
 *
 * Why:
 * 旧实现用 10ms 的 ticker 驱动，每秒触发约 100 次重组，而展示精度只到秒；
 * 这里改为每秒刷新一次。
 *
 * @param sinceMillis 起始时间戳
 * @param format 把时长字符串格式化为最终文案
 */
@Composable
fun LiveDurationText(
    sinceMillis: Long,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    format: (String) -> String = { it }
) {
    val elapsed by produceState(
        initialValue = System.currentTimeMillis() - sinceMillis,
        key1 = sinceMillis
    ) {
        while (true) {
            value = System.currentTimeMillis() - sinceMillis
            delay(1_000L - (value % 1_000L).coerceIn(0L, 999L))
        }
    }
    Text(
        text = format(getDurationString(elapsed)),
        modifier = modifier,
        color = color,
        style = style
    )
}
