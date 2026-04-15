package com.xingkeqi.btlogger.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.data.RecordInfo
import com.xingkeqi.btlogger.ui.theme.BatteryHigh
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.ui.theme.PlayingBlue
import kotlin.math.max

/**
 * 电量趋势图表
 *
 * Why:
 * 续航测试更关心“随时间变化的电量”，这里使用记录时间作为横轴，并对耳机未上报电量的区间保留断线。
 */
@Composable
fun BatteryTrendChart(
    records: List<RecordInfo>,
    modifier: Modifier = Modifier
) {
    if (records.isEmpty()) return

    val timelinePoints = remember(records) {
        records.sortedBy { it.timestamp }.map {
            BatteryTimelinePoint(
                timestamp = it.timestamp,
                phoneBatteryLevel = it.batteryLevel.takeIf { level -> level in 0..100 },
                headsetBatteryLevel = it.headsetBatteryLevel.takeIf { level -> level in 0..100 }
            )
        }
    }

    val phonePointCount = timelinePoints.count { it.phoneBatteryLevel != null }
    val headsetPointCount = timelinePoints.count { it.headsetBatteryLevel != null }
    if (phonePointCount < 1 && headsetPointCount < 1) return

    val firstTimestamp = timelinePoints.first().timestamp
    val lastTimestamp = timelinePoints.last().timestamp
    val totalDuration = max(lastTimestamp - firstTimestamp, 0L)
    val hasHeadsetGap = headsetPointCount < timelinePoints.size
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.18f)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Dimens.cardCornerRadius),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Column(modifier = Modifier.padding(Dimens.cardPadding)) {
            Text(
                text = stringResource(id = R.string.battery_trend_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )

            Spacer(modifier = Modifier.height(Dimens.spacingXs))

            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd)) {
                BatteryTrendLegend(
                    color = BatteryHigh,
                    label = stringResource(id = R.string.phone_battery_label)
                )
                BatteryTrendLegend(
                    color = PlayingBlue,
                    label = stringResource(id = R.string.headset_battery_label)
                )
            }

            Spacer(modifier = Modifier.height(Dimens.spacingSm))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.height(140.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                    horizontalAlignment = Alignment.End
                ) {
                    listOf("100%", "50%", "0%").forEach { label ->
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Spacer(modifier = Modifier.width(Dimens.spacingSm))

                Canvas(
                    modifier = Modifier
                        .weight(1f)
                        .height(140.dp)
                ) {
                    val chartWidth = size.width
                    val chartHeight = size.height
                    val phoneStroke = 3.dp.toPx()
                    val headsetStroke = 3.dp.toPx()
                    val pointRadius = 3.dp.toPx()

                    listOf(0f, 0.5f, 1f).forEach { ratio ->
                        val y = chartHeight * ratio
                        drawLine(
                            color = gridColor,
                            start = Offset(0f, y),
                            end = Offset(chartWidth, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }

                    drawSeries(
                        points = timelinePoints,
                        chartWidth = chartWidth,
                        chartHeight = chartHeight,
                        totalDuration = totalDuration,
                        lineColor = BatteryHigh,
                        strokeWidth = phoneStroke,
                        pointRadius = pointRadius,
                        valueSelector = { it.phoneBatteryLevel }
                    )

                    drawSeries(
                        points = timelinePoints,
                        chartWidth = chartWidth,
                        chartHeight = chartHeight,
                        totalDuration = totalDuration,
                        lineColor = PlayingBlue,
                        strokeWidth = headsetStroke,
                        pointRadius = pointRadius,
                        valueSelector = { it.headsetBatteryLevel }
                    )
                }
            }

            Spacer(modifier = Modifier.height(Dimens.spacingXs))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 44.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatElapsedDuration(0L),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = formatElapsedDuration(totalDuration / 2),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Text(
                    text = formatElapsedDuration(totalDuration),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            if (hasHeadsetGap) {
                Spacer(modifier = Modifier.height(Dimens.spacingXs))
                Text(
                    text = stringResource(id = R.string.headset_battery_gap_note),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun BatteryTrendLegend(color: Color, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Canvas(
            modifier = Modifier
                .width(18.dp)
                .height(6.dp)
        ) {
            drawLine(
                color = color,
                start = Offset(0f, size.height / 2f),
                end = Offset(size.width, size.height / 2f),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSeries(
    points: List<BatteryTimelinePoint>,
    chartWidth: Float,
    chartHeight: Float,
    totalDuration: Long,
    lineColor: Color,
    strokeWidth: Float,
    pointRadius: Float,
    valueSelector: (BatteryTimelinePoint) -> Int?
) {
    var previousPoint: Offset? = null
    points.forEachIndexed { index, point ->
        val value = valueSelector(point)
        val currentPoint = if (value != null) {
            Offset(
                x = resolveXPosition(
                    index = index,
                    timestamp = point.timestamp,
                    firstTimestamp = points.first().timestamp,
                    chartWidth = chartWidth,
                    totalDuration = totalDuration,
                    lastIndex = points.lastIndex
                ),
                y = chartHeight - (value / 100f * chartHeight)
            )
        } else {
            null
        }

        if (previousPoint != null && currentPoint != null) {
            drawLine(
                color = lineColor,
                start = previousPoint,
                end = currentPoint,
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }

        if (currentPoint != null) {
            drawCircle(
                color = lineColor,
                radius = pointRadius,
                center = currentPoint
            )
        }

        previousPoint = currentPoint
    }
}

private fun resolveXPosition(
    index: Int,
    timestamp: Long,
    firstTimestamp: Long,
    chartWidth: Float,
    totalDuration: Long,
    lastIndex: Int
): Float {
    return when {
        totalDuration > 0L -> ((timestamp - firstTimestamp).toFloat() / totalDuration.toFloat()) * chartWidth
        lastIndex > 0 -> (index.toFloat() / lastIndex.toFloat()) * chartWidth
        else -> chartWidth / 2f
    }
}

private fun formatElapsedDuration(durationMillis: Long): String {
    val totalMinutes = durationMillis / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return when {
        hours > 0L && minutes > 0L -> "${hours}h${minutes}m"
        hours > 0L -> "${hours}h"
        else -> "${minutes}m"
    }
}

private data class BatteryTimelinePoint(
    val timestamp: Long,
    val phoneBatteryLevel: Int?,
    val headsetBatteryLevel: Int?
)
