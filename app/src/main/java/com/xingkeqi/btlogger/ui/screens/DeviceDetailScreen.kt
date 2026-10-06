package com.xingkeqi.btlogger.ui.screens

import android.bluetooth.BluetoothDevice
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blankj.utilcode.util.TimeUtils
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.RecordFilter
import com.xingkeqi.btlogger.data.BLUETOOTH_VERSION_UNKNOWN
import com.xingkeqi.btlogger.data.CODEC_UNKNOWN
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.data.RecordEventType
import com.xingkeqi.btlogger.data.RecordInfo
import com.xingkeqi.btlogger.data.RecordStats
import com.xingkeqi.btlogger.ui.components.BadgeType
import com.xingkeqi.btlogger.ui.components.BatteryIndicator
import com.xingkeqi.btlogger.ui.components.BatteryTrendChart
import com.xingkeqi.btlogger.ui.components.ConnectionStatusIndicator
import com.xingkeqi.btlogger.ui.components.DurationProgressBar
import com.xingkeqi.btlogger.ui.components.EmptyState
import com.xingkeqi.btlogger.ui.components.LineIcons
import com.xingkeqi.btlogger.ui.components.SectionCaption
import com.xingkeqi.btlogger.ui.components.SegmentPosition
import com.xingkeqi.btlogger.ui.components.StatCard
import com.xingkeqi.btlogger.ui.components.StatItem
import com.xingkeqi.btlogger.ui.components.StatusBadge
import com.xingkeqi.btlogger.ui.components.WmCard
import com.xingkeqi.btlogger.ui.components.WmChip
import com.xingkeqi.btlogger.ui.components.WmSegmentedTabs
import com.xingkeqi.btlogger.ui.components.cardSegment
import com.xingkeqi.btlogger.ui.components.segmentPositionOf
import com.xingkeqi.btlogger.ui.components.shape
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.ui.theme.MonoTextStyle
import com.xingkeqi.btlogger.ui.theme.Springs
import com.xingkeqi.btlogger.utils.getCompactDurationString
import com.xingkeqi.btlogger.utils.getDurationString

private const val STATE_CONNECTED = 2

private val HistoryFilters = listOf(
    RecordFilter.ALL to R.string.filter_all,
    RecordFilter.CONNECTION to R.string.filter_connection,
    RecordFilter.CODEC to R.string.filter_codec,
    RecordFilter.BATTERY to R.string.filter_battery
)

/**
 * 设备详情（无状态）：概览、统计、编解码、电量趋势与可筛选的历史记录。
 *
 * 头部不再使用渐变底色，概览、统计与图表分别放在白色卡片里；历史记录是一张分组卡片，
 * 用分段标签页筛选。
 */
@Composable
fun DeviceDetailScreen(
    device: DeviceInfo,
    records: List<RecordInfo>,
    stats: RecordStats,
    filter: RecordFilter,
    onFilterChange: (RecordFilter) -> Unit,
    onRecordLongClick: (RecordInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    if (records.isEmpty()) {
        EmptyState(
            icon = LineIcons.Bluetooth,
            title = stringResource(id = R.string.detail_empty_title),
            body = stringResource(id = R.string.detail_empty_body),
            modifier = modifier
        )
        return
    }

    val historyRecords = remember(records, filter) {
        records.filter { record ->
            when (filter) {
                RecordFilter.ALL -> true
                RecordFilter.CONNECTION -> record.eventType == RecordEventType.CONNECTED ||
                    record.eventType == RecordEventType.DISCONNECTED
                RecordFilter.CODEC -> record.eventType == RecordEventType.CODEC_CHANGED
                RecordFilter.BATTERY -> record.eventType == RecordEventType.BATTERY_CHANGED
            }
        }
    }
    // 图表按时间升序绘制
    val chronologicalRecords = remember(records) { records.asReversed() }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Dimens.pageGutter, end = Dimens.pageGutter, bottom = Dimens.spacingXl)
    ) {
        item(key = "header") {
            DetailHeader(
                device = device,
                latestRecord = records.first(),
                stats = stats,
                chronologicalRecords = chronologicalRecords
            )
        }

        item(key = "filters") {
            HistoryFilterRow(
                selected = filter,
                count = historyRecords.size,
                onFilterChange = onFilterChange
            )
        }

        if (historyRecords.isEmpty()) {
            item(key = "empty-filter") {
                Text(
                    text = stringResource(id = R.string.detail_history_empty_filter),
                    style = MaterialTheme.typography.bodyMedium,
                    color = BtTheme.colors.ink3,
                    modifier = Modifier.padding(vertical = Dimens.spacingLg, horizontal = Dimens.spacingXs)
                )
            }
        }

        itemsIndexed(items = historyRecords, key = { _, record -> record.id }) { index, record ->
            RecordItem(
                record = record,
                position = segmentPositionOf(index, historyRecords.size),
                onLongClick = { onRecordLongClick(record) }
            )
        }
    }
}

@Composable
private fun DetailHeader(
    device: DeviceInfo,
    latestRecord: RecordInfo,
    stats: RecordStats,
    chronologicalRecords: List<RecordInfo>
) {
    val colors = BtTheme.colors
    val isConnected = latestRecord.connectState == STATE_CONNECTED
    val bluetoothVersion = latestRecord.bluetoothVersion
        .takeUnless { it == BLUETOOTH_VERSION_UNKNOWN }
        ?: device.bluetoothVersion

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Dimens.spacingXs),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd)
    ) {
        WmCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionStatusIndicator(isConnected = isConnected)
                Spacer(modifier = Modifier.width(Dimens.spacingSm))
                Text(
                    text = device.name.ifBlank { latestRecord.name }
                        .ifBlank { stringResource(id = R.string.unknown_device) },
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Spacer(modifier = Modifier.width(Dimens.spacingSm))
                StatusBadge(
                    text = stringResource(
                        id = if (isConnected) R.string.status_connected else R.string.status_disconnected
                    ),
                    type = if (isConnected) BadgeType.Connected else BadgeType.Disconnected
                )
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = device.mac,
                style = MonoTextStyle,
                color = colors.ink3,
                modifier = Modifier.padding(start = Dimens.statusIndicatorSize + Dimens.spacingSm)
            )

            Spacer(modifier = Modifier.height(Dimens.spacingLg))
            DurationProgressBar(
                connectionTime = latestRecord.totalConnectionTime ?: 0L,
                disconnectionTime = latestRecord.totalDisConnectionTime ?: 0L
            )
            Spacer(modifier = Modifier.height(Dimens.spacingMd))
            RecordMetricsRow(record = latestRecord, recordedLabels = true)
        }

        StatCard(
            items = listOf(
                StatItem(stringResource(id = R.string.stat_connect_count), stringResource(id = R.string.count_times, stats.connectCount)),
                StatItem(stringResource(id = R.string.stat_disconnect_count), stringResource(id = R.string.count_times, stats.disconnectCount)),
                StatItem(stringResource(id = R.string.stat_longest_session), stats.longestSessionMillis.toCompactOrDash()),
                StatItem(stringResource(id = R.string.stat_average_session), stats.averageSessionMillis.toCompactOrDash())
            )
        )

        StatCard(
            items = listOf(
                StatItem(stringResource(id = R.string.stat_device_type), deviceTypeLabel(latestRecord.deviceType)),
                StatItem(stringResource(id = R.string.stat_bluetooth_version), bluetoothVersion),
                StatItem(stringResource(id = R.string.stat_bond_state), bondStateLabel(latestRecord.bondState))
            )
        )

        WmCard(contentPadding = 0.dp) {
            CodecInfoTable(
                phoneSupportedCodecs = latestRecord.phoneSupportedCodecs,
                negotiableCodecs = latestRecord.negotiableCodecs,
                activeCodec = latestRecord.activeCodec
            )
        }

        BatteryTrendChart(records = chronologicalRecords)
    }
}

@Composable
private fun Long.toCompactOrDash(): String =
    if (this > 0) getCompactDurationString(this) else stringResource(id = R.string.placeholder_dash)

@Composable
private fun deviceTypeLabel(type: Int): String = stringResource(
    id = when (type) {
        BluetoothDevice.DEVICE_TYPE_CLASSIC -> R.string.device_type_classic
        BluetoothDevice.DEVICE_TYPE_LE -> R.string.device_type_le
        BluetoothDevice.DEVICE_TYPE_DUAL -> R.string.device_type_dual
        else -> R.string.device_type_other
    }
)

@Composable
private fun bondStateLabel(state: Int): String = stringResource(
    id = when (state) {
        BluetoothDevice.BOND_BONDED -> R.string.bond_bonded
        BluetoothDevice.BOND_BONDING -> R.string.bond_bonding
        else -> R.string.bond_none
    }
)

@Composable
private fun HistoryFilterRow(
    selected: RecordFilter,
    count: Int,
    onFilterChange: (RecordFilter) -> Unit
) {
    Column(modifier = Modifier.padding(top = Dimens.spacingXl, bottom = Dimens.spacingMd)) {
        SectionCaption(
            text = stringResource(id = R.string.detail_history_title, count),
            modifier = Modifier.padding(start = Dimens.spacingXs, bottom = Dimens.spacingSm)
        )
        WmSegmentedTabs(
            labels = HistoryFilters.map { (_, labelRes) -> stringResource(id = labelRes) },
            selectedIndex = HistoryFilters.indexOfFirst { it.first == selected }.coerceAtLeast(0),
            onSelect = { index -> onFilterChange(HistoryFilters[index].first) }
        )
    }
}

/**
 * 电量、音量与播放状态指标行（标签 chip）
 *
 * @param recordedLabels 详情头部强调“记录时”的状态，历史条目使用简短文案
 */
@Composable
private fun RecordMetricsRow(record: RecordInfo, recordedLabels: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        record.batteryLevel.takeIf { it in 0..100 }?.let { level ->
            BatteryIndicator(level = level, label = stringResource(id = R.string.phone_battery_label))
        }
        record.headsetBatteryLevel.takeIf { it in 0..100 }?.let { level ->
            BatteryIndicator(level = level, label = stringResource(id = R.string.headset_battery_label))
        }
        WmChip(
            text = "${record.volume}%",
            icon = LineIcons.Volume,
            modifier = Modifier
        )
        val playing = record.isPlaying == 1
        StatusBadge(
            text = stringResource(
                id = when {
                    recordedLabels && playing -> R.string.badge_playing_at_record
                    recordedLabels -> R.string.badge_muted_at_record
                    playing -> R.string.badge_playing
                    else -> R.string.badge_not_playing
                }
            ),
            type = if (playing) BadgeType.Playing else BadgeType.Paused
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecordItem(
    record: RecordInfo,
    position: SegmentPosition,
    onLongClick: () -> Unit
) {
    val colors = BtTheme.colors
    val haptics = LocalHapticFeedback.current
    val isCodecChanged = record.eventType == RecordEventType.CODEC_CHANGED
    val isBatteryChanged = record.eventType == RecordEventType.BATTERY_CHANGED
    val isConnected = record.connectState == STATE_CONNECTED
    val isStateEvent = !isCodecChanged && !isBatteryChanged
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedBackground by animateColorAsState(
        if (pressed) colors.surface2 else colors.surface2.copy(alpha = 0f),
        Springs.snappy(),
        label = "recordPressed"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .cardSegment(position, background = colors.surface, border = colors.line, divider = colors.line)
            .clip(position.shape())
            .background(pressedBackground)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = {},
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            .padding(horizontal = Dimens.cardPadding, vertical = 14.dp)
    ) {
        EventIcon(
            icon = when {
                isCodecChanged -> LineIcons.Waveform
                isBatteryChanged -> LineIcons.Battery
                isConnected -> LineIcons.Bluetooth
                else -> LineIcons.BluetoothOff
            },
            emphasized = isStateEvent && isConnected
        )
        Spacer(modifier = Modifier.width(Dimens.spacingMd))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(
                        id = when {
                            isCodecChanged -> R.string.event_codec_changed
                            isBatteryChanged -> R.string.event_battery_changed
                            isConnected -> R.string.event_connected
                            else -> R.string.event_disconnected
                        }
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.ink,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = TimeUtils.millis2String(record.timestamp, "MM-dd HH:mm:ss"),
                    style = MonoTextStyle,
                    color = colors.ink3
                )
            }

            val summary = when {
                isCodecChanged -> stringResource(id = R.string.record_active_codec, record.activeCodec.ifBlank { CODEC_UNKNOWN })
                isBatteryChanged -> batterySummary(record)
                record.timestamp == record.lastRecordTime -> stringResource(id = R.string.record_first)
                else -> {
                    val duration = getDurationString(record.timestamp - (record.lastRecordTime ?: record.timestamp))
                    stringResource(
                        id = if (isConnected) R.string.record_disconnected_gap else R.string.record_session_duration,
                        duration
                    )
                }
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = if (isStateEvent && !isConnected) colors.ink else colors.ink2
            )

            RecordMetricsRow(record = record, recordedLabels = false)

            if (isCodecChanged) {
                CodecInfoTable(
                    phoneSupportedCodecs = record.phoneSupportedCodecs,
                    negotiableCodecs = record.negotiableCodecs,
                    activeCodec = record.activeCodec,
                    compact = true
                )
            } else if (!isBatteryChanged) {
                Text(
                    text = stringResource(id = R.string.record_active_codec, record.activeCodec.ifBlank { CODEC_UNKNOWN }),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink3
                )
            }
        }
    }
}

/**
 * 事件图标：32dp 的 surface-2 圆底 + 16dp 线性图标；已连接事件图标为 ink，其余 ink-2
 */
@Composable
private fun EventIcon(icon: ImageVector, emphasized: Boolean) {
    val colors = BtTheme.colors
    Box(
        modifier = Modifier
            .size(32.dp)
            .background(colors.surface2, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (emphasized) colors.ink else colors.ink2,
            modifier = Modifier.size(Dimens.iconSizeSm)
        )
    }
}

@Composable
private fun batterySummary(record: RecordInfo): String {
    val phone = record.batteryLevel.takeIf { it in 0..100 }
        ?.let { stringResource(id = R.string.record_battery_phone, it) }
        ?: stringResource(id = R.string.record_battery_phone_unknown)
    val headset = record.headsetBatteryLevel.takeIf { it in 0..100 }
        ?.let { stringResource(id = R.string.record_battery_headset, it) }
        ?: stringResource(id = R.string.record_battery_headset_unknown)
    return stringResource(id = R.string.record_battery_summary, phone, headset)
}

/**
 * 编解码信息表：标签 ink-3 在左、值 ink 在右，行间 1px 分隔线，无竖线
 *
 * @param compact 嵌在历史条目里时去掉内边距与分隔线
 */
@Composable
private fun CodecInfoTable(
    phoneSupportedCodecs: String,
    negotiableCodecs: String,
    activeCodec: String,
    compact: Boolean = false
) {
    val rows = listOf(
        stringResource(id = R.string.codec_phone_supported) to phoneSupportedCodecs,
        stringResource(id = R.string.codec_negotiable) to negotiableCodecs,
        stringResource(id = R.string.codec_active) to activeCodec
    )
    Column {
        rows.forEachIndexed { index, (label, value) ->
            if (index > 0 && !compact) {
                HorizontalDivider(
                    thickness = Dimens.stroke,
                    color = BtTheme.colors.line,
                    modifier = Modifier.padding(horizontal = Dimens.rowDividerInset)
                )
            }
            CodecInfoLine(
                label = label,
                value = value,
                emphasized = index == rows.lastIndex,
                modifier = if (compact) {
                    Modifier
                } else {
                    Modifier.padding(horizontal = Dimens.cardPadding, vertical = Dimens.spacingMd)
                }
            )
        }
    }
}

@Composable
private fun CodecInfoLine(label: String, value: String, emphasized: Boolean, modifier: Modifier = Modifier) {
    val colors = BtTheme.colors
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.ink3,
            modifier = Modifier.width(72.dp)
        )
        Text(
            text = value.ifBlank { CODEC_UNKNOWN },
            style = if (emphasized) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
            color = colors.ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
