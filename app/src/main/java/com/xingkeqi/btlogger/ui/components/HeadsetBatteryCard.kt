package com.xingkeqi.btlogger.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blankj.utilcode.util.TimeUtils
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.domain.battery.BatteryChannel
import com.xingkeqi.btlogger.domain.battery.BatteryChannelOverview
import com.xingkeqi.btlogger.domain.battery.BatteryRange
import com.xingkeqi.btlogger.domain.battery.BatterySession
import com.xingkeqi.btlogger.domain.battery.DrainStats
import com.xingkeqi.btlogger.domain.battery.LevelPoint
import com.xingkeqi.btlogger.domain.battery.compressPlateaus
import com.xingkeqi.btlogger.domain.battery.levelStepAt
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.ui.theme.MonoTextStyle
import com.xingkeqi.btlogger.ui.theme.Springs
import com.xingkeqi.btlogger.ui.theme.WarmMonoColors
import com.xingkeqi.btlogger.ui.theme.chartSeries
import com.xingkeqi.btlogger.utils.getCompactDurationString
import kotlinx.coroutines.launch
import java.util.Locale

private val BatteryRanges = listOf(BatteryRange.LATEST_SESSION, BatteryRange.LAST_24_HOURS, BatteryRange.ALL)

/** 横轴最短跨度：刚连接、只有一两个点时曲线不至于挤成一团 */
private const val MIN_DOMAIN_MILLIS = 10 * 60_000L

/** 曲线上的一个来源：所选范围内有电量的连接，以及去掉平台中段重复点后的各段 */
private class ChannelCurve(
    val channel: BatteryChannel,
    val sessions: List<BatterySession>,
    val segments: List<List<LevelPoint>>
)

/**
 * 详情页的耳机电量卡片（无状态，缩放与游标除外）：范围与来源切换、读数、贝塞尔曲线与掉电统计。
 *
 * 有左右耳数据时，来源标签同时充当图例：选中左耳或右耳时两只耳机的曲线叠加，读数与统计跟随所选来源，
 * 游标停在哪条曲线上，读数就显示哪个来源。
 *
 * @param deviceKey 设备 MAC，切换设备时重置缩放与游标
 * @param isConnected 设备当前是否连接，决定第一个范围显示“本次连接”还是“上次连接”
 */
@Composable
fun HeadsetBatteryCard(
    channelOverview: BatteryChannelOverview,
    deviceKey: String,
    isConnected: Boolean,
    onRangeChange: (BatteryRange) -> Unit,
    onChannelChange: (BatteryChannel) -> Unit,
    onOpenCompare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BtTheme.colors
    val scope = rememberCoroutineScope()
    val overview = channelOverview.overview
    // “本次连接”换成新的一次连接时也重置缩放与游标，否则会停在上一次连接的时间范围上
    val chartState = rememberBatteryChartState(
        deviceKey,
        overview.range,
        overview.sessions.lastOrNull()?.connectedAt.takeIf { overview.range == BatteryRange.LATEST_SESSION }
    )
    val curves = remember(channelOverview) {
        channelOverview.plotted.map { channel ->
            val sessions = channelOverview.overviews.getValue(channel).sessions.filter { it.headset.isNotEmpty() }
            // 平台中段的重复点不影响曲线形状，去掉后游标只停在电量变化处与各段首尾
            ChannelCurve(channel = channel, sessions = sessions, segments = sessions.map { compressPlateaus(it.headset) })
        }
    }
    val labels = BatteryChannel.entries.associateWith { channelLabel(it) }
    val series = remember(curves, channelOverview.selected, colors, deviceKey, labels) {
        curves.map { curve ->
            ChartSeries(
                key = seriesKey(deviceKey, curve.channel),
                label = labels.getValue(curve.channel),
                color = colors.channelColor(curve.channel),
                segments = curve.segments,
                holdUntil = curve.sessions.map { it.endTime },
                emphasized = curve.channel == channelOverview.selected
            )
        }
    }
    val showChannels = channelOverview.channels.size > 1

    WmCard(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionCaption(text = stringResource(id = R.string.battery_card_title), modifier = Modifier.weight(1f))
            if (chartState.isZoomed) {
                CardActionButton(
                    text = stringResource(id = R.string.battery_reset_zoom),
                    icon = LineIcons.RotateCcw,
                    onClick = { scope.launch { chartState.animateResetZoom() } }
                )
            }
            CardActionButton(
                text = stringResource(id = R.string.battery_open_compare),
                icon = LineIcons.ChartLine,
                onClick = onOpenCompare
            )
        }

        Spacer(modifier = Modifier.height(Dimens.spacingSm))
        WmSegmentedTabs(
            labels = listOf(
                stringResource(id = if (isConnected) R.string.battery_range_current else R.string.battery_range_last),
                stringResource(id = R.string.battery_range_day),
                stringResource(id = R.string.battery_range_all)
            ),
            selectedIndex = BatteryRanges.indexOf(overview.range).coerceAtLeast(0),
            onSelect = { index -> onRangeChange(BatteryRanges[index]) }
        )

        if (showChannels) {
            Spacer(modifier = Modifier.height(Dimens.spacingSm))
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
                channelOverview.channels.forEach { channel ->
                    BatteryChannelChip(
                        label = labels.getValue(channel),
                        color = colors.channelColor(channel).takeIf { curves.any { it.channel == channel } },
                        selected = channel == channelOverview.selected,
                        onClick = { onChannelChange(channel) }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(Dimens.spacingMd))
        if (curves.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(id = R.string.battery_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink3,
                    modifier = Modifier.padding(horizontal = Dimens.spacingLg)
                )
            }
            return@WmCard
        }

        // 读数跟随游标所在的曲线；没有游标时显示所选来源（所选来源这段时间没数据时取第一条曲线）
        val cursor = chartState.cursor
        val focused = curves.firstOrNull { cursor != null && seriesKey(deviceKey, it.channel) == cursor.seriesKey }
            ?: curves.firstOrNull { it.channel == channelOverview.selected }
            ?: curves.first()
        BatteryReadout(
            cursorPoint = cursor?.point?.takeIf { point -> focused.segments.any { point in it } },
            segments = focused.segments,
            sessionStarts = focused.sessions.map { it.connectedAt },
            carriedSince = focused.sessions.map { it.carriedSince },
            latest = focused.segments.last().last(),
            channelLabel = labels.getValue(focused.channel).takeIf { showChannels },
            channelColor = colors.channelColor(focused.channel)
        )
        Spacer(modifier = Modifier.height(Dimens.spacingXs))
        // 左右耳的横轴取两者并集，一只耳机先读到电量时另一条曲线也完整可见
        val domainStart = curves.minOf { channelOverview.overviews.getValue(it.channel).windowStart }
        val domainEnd = curves.maxOf { channelOverview.overviews.getValue(it.channel).windowEnd }
        BatteryLineChart(
            series = series,
            domainStart = domainStart,
            domainEnd = maxOf(domainEnd, domainStart + MIN_DOMAIN_MILLIS),
            axis = ChartTimeAxis.ABSOLUTE,
            state = chartState,
            description = stringResource(id = R.string.battery_chart_description),
            revealKey = Triple(deviceKey, overview.range, channelOverview.plotted)
        )

        Spacer(modifier = Modifier.height(Dimens.spacingMd))
        DrainStatsGrid(stats = overview.stats, ongoing = overview.ongoing, remainingMillis = overview.estimatedRemainingMillis)

        Spacer(modifier = Modifier.height(Dimens.spacingMd))
        Text(
            text = stringResource(id = if (showChannels) R.string.battery_note_earbuds else R.string.battery_note),
            style = MaterialTheme.typography.bodySmall,
            color = colors.ink3
        )
    }
}

private fun seriesKey(deviceKey: String, channel: BatteryChannel) = "$deviceKey#${channel.name}"

/** 左耳、右耳取对比图的前两种颜色，系统上报沿用原来的强调色（与左耳不会同时出现） */
private fun WarmMonoColors.channelColor(channel: BatteryChannel): Color = when (channel) {
    BatteryChannel.LEFT -> chartSeries(0)
    BatteryChannel.RIGHT -> chartSeries(1)
    BatteryChannel.HEADSET -> accent
}

@Composable
private fun channelLabel(channel: BatteryChannel): String = stringResource(
    id = when (channel) {
        BatteryChannel.LEFT -> R.string.battery_channel_left
        BatteryChannel.RIGHT -> R.string.battery_channel_right
        BatteryChannel.HEADSET -> R.string.battery_channel_headset
    }
)

/**
 * 电量来源标签，同时充当图例：圆点为该来源的曲线颜色（这段时间没有数据时为灰色），选中时描边也是曲线颜色。
 *
 * @param color 该来源这段时间没有曲线时为 null
 */
@Composable
private fun BatteryChannelChip(label: String, color: Color?, selected: Boolean, onClick: () -> Unit) {
    val colors = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, Springs.snappy(), label = "channelChipScale")
    val border by animateColorAsState(
        if (selected) color ?: colors.ink2 else colors.lineStrong,
        Springs.snappy(),
        label = "channelChipBorder"
    )
    val dot by animateColorAsState(color ?: colors.lineStrong, Springs.snappy(), label = "channelChipDot")
    val text by animateColorAsState(if (selected) colors.ink else colors.ink3, Springs.snappy(), label = "channelChipText")
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
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = null,
                role = Role.RadioButton,
                onClick = onClick
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
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = text, maxLines = 1)
    }
}

/**
 * 读数区固定高度放在曲线上方，不用浮层：手指在图上拖动时不会挡住数值，布局也不跳动。
 * 第一行是记录时间与连接后用时；第二行是相对上一档的变化，没有游标时显示操作提示。
 *
 * @param carriedSince 与 [segments] 一一对应，见 [com.xingkeqi.btlogger.domain.battery.BatterySession.carriedSince]
 * @param channelLabel 有多个来源时显示读数属于哪个来源，前面的圆点为该曲线颜色；只有一个来源时为 null
 */
@Composable
private fun BatteryReadout(
    cursorPoint: LevelPoint?,
    segments: List<List<LevelPoint>>,
    sessionStarts: List<Long>,
    carriedSince: List<Long?>,
    latest: LevelPoint,
    channelLabel: String?,
    channelColor: Color
) {
    val colors = BtTheme.colors
    val point = cursorPoint ?: latest
    val segmentIndex = segments.indexOfFirst { segment -> segment.any { it == point } }
    val segment = segments.getOrNull(segmentIndex).orEmpty()
    val elapsed = sessionStarts.getOrNull(segmentIndex)?.let { point.timestamp - it }?.takeIf { it >= 0 }
    val timeLine = listOfNotNull(
        channelLabel,
        TimeUtils.millis2String(point.timestamp, "MM-dd HH:mm:ss"),
        elapsed?.let { stringResource(id = R.string.battery_cursor_elapsed, getCompactDurationString(it)) }
    ).joinToString(" · ")
    val pointIndex = segment.indexOf(point)
    val firstRunStart = carriedSince.getOrNull(segmentIndex)
    val detail = when {
        cursorPoint == null -> stringResource(id = R.string.battery_chart_hint)
        // 裁剪出的首点不是真实记录，只是范围起点处沿用的电量
        pointIndex == 0 && firstRunStart != null -> stringResource(id = R.string.battery_cursor_window_start)
        else -> levelStepAt(segment, pointIndex, firstRunStart)?.let {
            if (it.delta == 0) {
                stringResource(id = R.string.battery_cursor_hold, getCompactDurationString(it.millis))
            } else {
                stringResource(id = R.string.battery_cursor_step, it.delta, getCompactDurationString(it.millis))
            }
        } ?: stringResource(id = R.string.battery_cursor_first)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(id = R.string.battery_percent, point.level),
            style = MonoTextStyle.copy(fontSize = 26.sp, lineHeight = 30.sp),
            color = if (cursorPoint == null) colors.ink else colors.accent
        )
        Spacer(modifier = Modifier.width(Dimens.spacingMd))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (channelLabel != null) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(channelColor, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = timeLine,
                    style = MonoTextStyle,
                    color = colors.ink2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * @param remainingMillis 仍在连接中时的预计剩余（已扣除最后一次掉电后经过的时间）
 */
@Composable
private fun DrainStatsGrid(stats: DrainStats?, ongoing: Boolean, remainingMillis: Long?) {
    val insufficient = stringResource(id = R.string.battery_insufficient)
    val rate = stats?.ratePerHour
    val projection = if (ongoing) remainingMillis else stats?.estimatedFullRuntimeMillis
    val items = listOf(
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
            rate?.let { stringResource(id = R.string.battery_stat_rate_value, String.format(Locale.US, "%.1f", it)) } ?: insufficient
        ),
        StatItem(
            stringResource(id = if (ongoing) R.string.battery_stat_remaining else R.string.battery_stat_full_runtime),
            projection?.let(::getCompactDurationString) ?: insufficient
        )
    )
    Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd)) {
        items.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd)) {
                row.forEach { item -> StatValue(item = item, modifier = Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
internal fun StatValue(item: StatItem, modifier: Modifier = Modifier) {
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

/**
 * 卡片标题行里的轻量操作：图标 + 文字，按下出现 surface-3 底
 */
@Composable
internal fun CardActionButton(text: String, icon: ImageVector, onClick: () -> Unit) {
    val colors = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val background by animateColorAsState(
        if (pressed) colors.surface3 else colors.surface3.copy(alpha = 0f),
        Springs.snappy(),
        label = "cardActionBackground"
    )
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(Dimens.radiusButton))
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = Dimens.spacingSm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = colors.ink2, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = colors.ink2, maxLines = 1)
    }
}
