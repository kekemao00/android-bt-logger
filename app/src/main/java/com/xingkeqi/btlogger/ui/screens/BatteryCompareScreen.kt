package com.xingkeqi.btlogger.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blankj.utilcode.util.TimeUtils
import com.xingkeqi.btlogger.BatteryComparisonUiState
import com.xingkeqi.btlogger.CompareAlignment
import com.xingkeqi.btlogger.MAX_COMPARE_SERIES
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.domain.battery.ComparisonCandidate
import com.xingkeqi.btlogger.domain.battery.compressPlateaus
import com.xingkeqi.btlogger.ui.components.BatteryChartState
import com.xingkeqi.btlogger.ui.components.BatteryLineChart
import com.xingkeqi.btlogger.ui.components.CardActionButton
import com.xingkeqi.btlogger.ui.components.ChartSeries
import com.xingkeqi.btlogger.ui.components.ChartTimeAxis
import com.xingkeqi.btlogger.ui.components.EmptyState
import com.xingkeqi.btlogger.ui.components.LineIcons
import com.xingkeqi.btlogger.ui.components.SectionCaption
import com.xingkeqi.btlogger.ui.components.StatItem
import com.xingkeqi.btlogger.ui.components.StatValue
import com.xingkeqi.btlogger.ui.components.WmCard
import com.xingkeqi.btlogger.ui.components.WmSegmentedTabs
import com.xingkeqi.btlogger.ui.components.rememberBatteryChartState
import com.xingkeqi.btlogger.ui.components.valueAt
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.ui.theme.MonoTextStyle
import com.xingkeqi.btlogger.ui.theme.Springs
import com.xingkeqi.btlogger.ui.theme.chartSeries
import com.xingkeqi.btlogger.utils.getCompactDurationString
import kotlinx.coroutines.launch
import java.util.Locale

private val Alignments = listOf(CompareAlignment.RELATIVE, CompareAlignment.ABSOLUTE)

/** 横轴最短跨度，避免刚开始测试时曲线挤成一团 */
private const val MIN_DOMAIN_MILLIS = 10 * 60_000L

/**
 * 多耳机电量对比（无状态，缩放与游标除外）。
 *
 * Why:
 * 续航测试常常是几台耳机同时或先后放同一首歌，需要回答“谁掉得快、谁更耐用”。
 * 按开始对齐时横轴是“开始后多久”，不同时间测的耳机也能直接比；按实际时间叠加用来核对同时测试的几台。
 * 颜色按设备占用的槽位固定，曲线、选择标签与表格三处同色同名，不只靠颜色区分。
 */
@Composable
fun BatteryCompareScreen(
    state: BatteryComparisonUiState,
    onAlignmentChange: (CompareAlignment) -> Unit,
    onToggleDevice: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    if (state.loading) {
        Box(modifier = modifier.fillMaxSize())
        return
    }
    if (state.candidates.isEmpty()) {
        EmptyState(
            icon = LineIcons.ChartLine,
            title = stringResource(id = R.string.compare_empty_title),
            body = stringResource(id = R.string.compare_empty_body),
            modifier = modifier
        )
        return
    }

    val colors = BtTheme.colors
    val relative = state.alignment == CompareAlignment.RELATIVE
    val chartState = rememberBatteryChartState(state.alignment)
    val selected = state.selected
    val series = remember(selected, state.slots, relative, colors) {
        selected.map { candidate ->
            ChartSeries(
                key = candidate.key,
                label = candidate.name,
                color = colors.chartSeries(state.slots[candidate.key] ?: 0),
                segments = listOf(compressPlateaus(if (relative) candidate.relativeHeadset else candidate.session.headset))
            )
        }
    }
    val domain = remember(series, relative) { compareDomain(series, relative) }
    // 取消选中游标所在的设备后不再显示游标
    val cursor = chartState.cursor?.takeIf { cursor -> series.any { it.key == cursor.seriesKey } }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Dimens.pageGutter, end = Dimens.pageGutter, top = Dimens.spacingXs, bottom = Dimens.spacingXl),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd)
    ) {
        item(key = "chart") {
            CompareChartCard(
                state = state,
                chartState = chartState,
                series = series,
                domain = domain,
                relative = relative,
                cursorTime = cursor?.point?.timestamp,
                cursorLabel = cursor?.let { c -> series.firstOrNull { it.key == c.seriesKey }?.label },
                cursorLevel = cursor?.point?.level,
                onAlignmentChange = onAlignmentChange,
                onToggleDevice = onToggleDevice
            )
        }

        if (selected.isNotEmpty()) {
            item(key = "table") {
                CompareTable(
                    candidates = selected,
                    colorOf = { colors.chartSeries(state.slots[it.key] ?: 0) },
                    cursorValueOf = { candidate ->
                        cursor?.let { c -> series.firstOrNull { it.key == candidate.key }?.valueAt(c.point.timestamp)?.level }
                    },
                    showCursor = cursor != null
                )
            }
        }

        item(key = "note") {
            Text(
                text = stringResource(id = R.string.compare_note),
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3,
                modifier = Modifier.padding(horizontal = Dimens.spacingXs)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CompareChartCard(
    state: BatteryComparisonUiState,
    chartState: BatteryChartState,
    series: List<ChartSeries>,
    domain: Pair<Long, Long>,
    relative: Boolean,
    cursorTime: Long?,
    cursorLabel: String?,
    cursorLevel: Int?,
    onAlignmentChange: (CompareAlignment) -> Unit,
    onToggleDevice: (String) -> Unit
) {
    val colors = BtTheme.colors
    val scope = rememberCoroutineScope()
    WmCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                WmSegmentedTabs(
                    labels = listOf(
                        stringResource(id = R.string.compare_align_relative),
                        stringResource(id = R.string.compare_align_absolute)
                    ),
                    selectedIndex = Alignments.indexOf(state.alignment).coerceAtLeast(0),
                    onSelect = { onAlignmentChange(Alignments[it]) }
                )
            }
            if (chartState.isZoomed) {
                Spacer(modifier = Modifier.width(Dimens.spacingXs))
                CardActionButton(
                    text = stringResource(id = R.string.battery_reset_zoom),
                    icon = LineIcons.RotateCcw,
                    onClick = { scope.launch { chartState.animateResetZoom() } }
                )
            }
        }

        Spacer(modifier = Modifier.height(Dimens.spacingMd))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (cursorTime != null && cursorLevel != null) {
                Text(
                    text = "$cursorLevel%",
                    style = MonoTextStyle.copy(fontSize = 22.sp, lineHeight = 26.sp),
                    color = colors.ink
                )
                Spacer(modifier = Modifier.width(Dimens.spacingMd))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = cursorLabel.orEmpty(),
                        style = MaterialTheme.typography.labelMedium,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = if (relative) {
                            "+" + getCompactDurationString(cursorTime)
                        } else {
                            TimeUtils.millis2String(cursorTime, "MM-dd HH:mm:ss")
                        },
                        style = MonoTextStyle,
                        color = colors.ink2,
                        maxLines = 1
                    )
                }
            } else {
                Text(
                    text = stringResource(
                        id = if (series.isEmpty()) R.string.compare_none_selected else R.string.battery_chart_hint
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink3,
                    maxLines = 2
                )
            }
        }

        BatteryLineChart(
            series = series,
            domainStart = domain.first,
            domainEnd = domain.second,
            axis = if (relative) ChartTimeAxis.RELATIVE else ChartTimeAxis.ABSOLUTE,
            state = chartState,
            description = stringResource(id = R.string.compare_title),
            height = 240.dp,
            revealKey = state.alignment
        )

        Spacer(modifier = Modifier.height(Dimens.spacingMd))
        SectionCaption(text = stringResource(id = R.string.compare_select_caption, MAX_COMPARE_SERIES))
        Spacer(modifier = Modifier.height(Dimens.spacingSm))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)
        ) {
            state.candidates.forEach { candidate ->
                val slot = state.slots[candidate.key]
                CompareDeviceChip(
                    name = candidate.name.ifBlank { stringResource(id = R.string.unknown_device) },
                    color = slot?.let { colors.chartSeries(it) },
                    onClick = { onToggleDevice(candidate.key) }
                )
            }
        }
    }
}

/**
 * 设备选择标签，同时充当图例：选中时圆点与描边为该设备的曲线颜色
 *
 * @param color 未选中时为 null
 */
@Composable
private fun CompareDeviceChip(name: String, color: Color?, onClick: () -> Unit) {
    val colors = BtTheme.colors
    val selected = color != null
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, Springs.snappy(), label = "compareChipScale")
    val border by animateColorAsState(color ?: colors.lineStrong, Springs.snappy(), label = "compareChipBorder")
    val dot by animateColorAsState(color ?: colors.lineStrong, Springs.snappy(), label = "compareChipDot")
    val text by animateColorAsState(if (selected) colors.ink else colors.ink3, Springs.snappy(), label = "compareChipText")
    val shape = RoundedCornerShape(percent = 50)
    Row(
        modifier = Modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .height(32.dp)
            .clip(shape)
            .background(colors.surface)
            .border(Dimens.stroke, border, shape)
            .toggleable(
                value = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.Checkbox,
                onValueChange = { onClick() }
            )
            .padding(horizontal = Dimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dot, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = name,
            style = MaterialTheme.typography.labelMedium,
            color = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 续航对比表：按满电续航从长到短，数据不足的排在最后
 */
@Composable
private fun CompareTable(
    candidates: List<ComparisonCandidate>,
    colorOf: (ComparisonCandidate) -> Color,
    cursorValueOf: (ComparisonCandidate) -> Int?,
    showCursor: Boolean
) {
    val colors = BtTheme.colors
    val insufficient = stringResource(id = R.string.battery_insufficient)
    val sorted = remember(candidates) {
        candidates.sortedWith(compareByDescending<ComparisonCandidate> { it.stats?.estimatedFullRuntimeMillis ?: -1L })
    }
    WmCard(contentPadding = 0.dp) {
        SectionCaption(
            text = stringResource(id = R.string.compare_table_caption),
            modifier = Modifier.padding(start = Dimens.cardPadding, end = Dimens.cardPadding, top = Dimens.cardPadding)
        )
        sorted.forEachIndexed { index, candidate ->
            if (index > 0) {
                HorizontalDivider(
                    thickness = Dimens.stroke,
                    color = colors.line,
                    modifier = Modifier.padding(horizontal = Dimens.rowDividerInset)
                )
            }
            val stats = candidate.stats
            Column(modifier = Modifier.padding(horizontal = Dimens.cardPadding, vertical = Dimens.spacingMd)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(colorOf(candidate), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(Dimens.spacingSm))
                    Text(
                        text = candidate.name.ifBlank { stringResource(id = R.string.unknown_device) },
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (showCursor) {
                        Text(
                            text = stringResource(id = R.string.compare_cursor) + " " +
                                (cursorValueOf(candidate)?.let { "$it%" } ?: stringResource(id = R.string.placeholder_dash)),
                            style = MonoTextStyle,
                            color = colors.ink2
                        )
                    }
                }
                Text(
                    text = stringResource(
                        id = R.string.compare_session_start,
                        TimeUtils.millis2String(candidate.origin, "MM-dd HH:mm")
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink3,
                    modifier = Modifier.padding(start = 10.dp + Dimens.spacingSm, top = 2.dp)
                )
                Spacer(modifier = Modifier.height(Dimens.spacingSm))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm),
                    modifier = Modifier.padding(start = 10.dp + Dimens.spacingSm)
                ) {
                    listOf(
                        StatItem(
                            stringResource(id = R.string.battery_stat_levels),
                            stats?.let { stringResource(id = R.string.battery_stat_levels_value, it.startLevel, it.endLevel) } ?: insufficient
                        ),
                        StatItem(
                            stringResource(id = R.string.battery_stat_duration),
                            stats?.trackedMillis?.takeIf { it > 0 }?.let(::getCompactDurationString) ?: insufficient
                        ),
                        StatItem(
                            stringResource(id = R.string.battery_stat_rate),
                            stats?.ratePerHour?.let {
                                stringResource(id = R.string.battery_stat_rate_value, String.format(Locale.US, "%.1f", it))
                            } ?: insufficient
                        ),
                        StatItem(
                            stringResource(id = R.string.battery_stat_full_runtime),
                            stats?.estimatedFullRuntimeMillis?.let(::getCompactDurationString) ?: insufficient
                        )
                    ).forEach { item -> StatValue(item = item, modifier = Modifier.weight(1f)) }
                }
            }
        }
        Spacer(modifier = Modifier.height(Dimens.spacingXs))
    }
}

/**
 * 横轴范围：按开始对齐时从 0 到最长的一段；按实际时间时从最早的开始到最晚的结束
 */
private fun compareDomain(series: List<ChartSeries>, relative: Boolean): Pair<Long, Long> {
    val points = series.flatMap { it.points }
    if (points.isEmpty()) return 0L to MIN_DOMAIN_MILLIS
    val start = if (relative) 0L else points.minOf { it.timestamp }
    val end = points.maxOf { it.timestamp }
    return start to maxOf(end, start + MIN_DOMAIN_MILLIS)
}
