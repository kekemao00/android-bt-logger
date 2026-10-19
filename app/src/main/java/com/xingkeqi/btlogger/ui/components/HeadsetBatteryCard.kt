package com.xingkeqi.btlogger.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blankj.utilcode.util.TimeUtils
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.domain.battery.BatteryOverview
import com.xingkeqi.btlogger.domain.battery.BatteryRange
import com.xingkeqi.btlogger.domain.battery.DrainStats
import com.xingkeqi.btlogger.domain.battery.LevelPoint
import com.xingkeqi.btlogger.domain.battery.compressPlateaus
import com.xingkeqi.btlogger.domain.battery.levelStepAt
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.ui.theme.MonoTextStyle
import com.xingkeqi.btlogger.ui.theme.Springs
import com.xingkeqi.btlogger.utils.getCompactDurationString
import kotlinx.coroutines.launch
import java.util.Locale

private val BatteryRanges = listOf(BatteryRange.LATEST_SESSION, BatteryRange.LAST_24_HOURS, BatteryRange.ALL)

/** 横轴最短跨度：刚连接、只有一两个点时曲线不至于挤成一团 */
private const val MIN_DOMAIN_MILLIS = 10 * 60_000L

/**
 * 详情页的耳机电量卡片（无状态，缩放与游标除外）：范围切换、读数、贝塞尔曲线与掉电统计。
 *
 * @param deviceKey 设备 MAC，切换设备时重置缩放与游标
 * @param isConnected 设备当前是否连接，决定第一个范围显示“本次连接”还是“上次连接”
 */
@Composable
fun HeadsetBatteryCard(
    overview: BatteryOverview,
    deviceKey: String,
    isConnected: Boolean,
    onRangeChange: (BatteryRange) -> Unit,
    onOpenCompare: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BtTheme.colors
    val scope = rememberCoroutineScope()
    val chartState = rememberBatteryChartState(deviceKey, overview.range)
    val sessions = remember(overview) { overview.sessions.filter { it.headset.isNotEmpty() } }
    // 平台中段的重复点不影响曲线形状，去掉后游标只停在电量变化处与各段首尾
    val segments = remember(sessions) { sessions.map { compressPlateaus(it.headset) } }
    val series = remember(segments, sessions, colors.accent, deviceKey) {
        listOf(
            ChartSeries(
                key = deviceKey,
                label = deviceKey,
                color = colors.accent,
                segments = segments,
                holdUntil = sessions.map { it.endTime },
                emphasized = true
            )
        )
    }

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

        Spacer(modifier = Modifier.height(Dimens.spacingMd))
        if (!overview.hasData) {
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

        BatteryReadout(
            cursorPoint = chartState.cursor?.point,
            segments = segments,
            sessionStarts = sessions.map { it.connectedAt },
            latest = segments.last().last()
        )
        Spacer(modifier = Modifier.height(Dimens.spacingXs))
        BatteryLineChart(
            series = series,
            domainStart = overview.windowStart,
            domainEnd = maxOf(overview.windowEnd, overview.windowStart + MIN_DOMAIN_MILLIS),
            axis = ChartTimeAxis.ABSOLUTE,
            state = chartState,
            description = stringResource(id = R.string.battery_chart_description),
            revealKey = deviceKey to overview.range
        )

        Spacer(modifier = Modifier.height(Dimens.spacingMd))
        DrainStatsGrid(stats = overview.stats, ongoing = overview.ongoing, remainingMillis = overview.estimatedRemainingMillis)

        Spacer(modifier = Modifier.height(Dimens.spacingMd))
        Text(
            text = stringResource(id = R.string.battery_note),
            style = MaterialTheme.typography.bodySmall,
            color = colors.ink3
        )
    }
}

/**
 * 读数区固定高度放在曲线上方，不用浮层：手指在图上拖动时不会挡住数值，布局也不跳动。
 * 第一行是记录时间与连接后用时；第二行是相对上一档的变化，没有游标时显示操作提示。
 */
@Composable
private fun BatteryReadout(
    cursorPoint: LevelPoint?,
    segments: List<List<LevelPoint>>,
    sessionStarts: List<Long>,
    latest: LevelPoint
) {
    val colors = BtTheme.colors
    val point = cursorPoint ?: latest
    val segmentIndex = segments.indexOfFirst { segment -> segment.any { it == point } }
    val segment = segments.getOrNull(segmentIndex).orEmpty()
    val elapsed = sessionStarts.getOrNull(segmentIndex)?.let { point.timestamp - it }?.takeIf { it >= 0 }
    val timeLine = listOfNotNull(
        TimeUtils.millis2String(point.timestamp, "MM-dd HH:mm:ss"),
        elapsed?.let { stringResource(id = R.string.battery_cursor_elapsed, getCompactDurationString(it)) }
    ).joinToString(" · ")
    val detail = if (cursorPoint == null) {
        stringResource(id = R.string.battery_chart_hint)
    } else {
        levelStepAt(segment, segment.indexOf(point))?.let {
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
            text = "${point.level}%",
            style = MonoTextStyle.copy(fontSize = 26.sp, lineHeight = 30.sp),
            color = if (cursorPoint == null) colors.ink else colors.accent
        )
        Spacer(modifier = Modifier.width(Dimens.spacingMd))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = timeLine,
                style = MonoTextStyle,
                color = colors.ink2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
