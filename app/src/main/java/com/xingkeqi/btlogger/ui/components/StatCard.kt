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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.Dimens

/**
 * 统计数据项
 */
data class StatItem(
    val label: String,
    val value: String
)

/**
 * 统计数据卡片：标签在上（caption、ink-3），数值在下（title、ink），左对齐；不再用主题色强调数值
 * @param items 统计项列表
 */
@Composable
fun StatCard(
    items: List<StatItem>,
    modifier: Modifier = Modifier
) {
    WmCard(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd)
        ) {
            items.forEach { item ->
                StatItemView(item = item, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun StatItemView(item: StatItem, modifier: Modifier = Modifier) {
    val colors = BtTheme.colors
    Column(modifier = modifier) {
        Text(
            text = item.label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.ink3,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = item.value,
            style = MaterialTheme.typography.titleSmall,
            color = colors.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
