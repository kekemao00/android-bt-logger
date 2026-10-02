package com.xingkeqi.btlogger.ui.screens

import android.bluetooth.BluetoothDevice
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
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
import com.xingkeqi.btlogger.ui.components.StatCard
import com.xingkeqi.btlogger.ui.components.StatItem
import com.xingkeqi.btlogger.ui.components.StatusBadge
import com.xingkeqi.btlogger.ui.theme.ConnectedGreenDark
import com.xingkeqi.btlogger.ui.theme.ConnectedGreenLight
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.utils.getCompactDurationString
import com.xingkeqi.btlogger.utils.getDurationString

private const val STATE_CONNECTED = 2

/**
 * 设备详情（无状态）：概览、统计、编解码、电量趋势与可筛选的历史记录
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
            icon = painterResource(id = R.drawable.ic_bluetooth_settings),
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
        contentPadding = PaddingValues(bottom = Dimens.spacingLg)
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
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(Dimens.spacingLg)
                )
            }
        }

        items(items = historyRecords, key = { it.id }) { record ->
            RecordItem(
                record = record,
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
    val isConnected = latestRecord.connectState == STATE_CONNECTED
    val bluetoothVersion = latestRecord.bluetoothVersion
        .takeUnless { it == BLUETOOTH_VERSION_UNKNOWN }
        ?: device.bluetoothVersion
    val headerTint = if (isConnected) {
        if (isSystemInDarkTheme()) ConnectedGreenDark.copy(alpha = 0.45f) else ConnectedGreenLight
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(headerTint, MaterialTheme.colorScheme.background)))
            .padding(Dimens.spacingLg),
        verticalArrangement = Arrangement.spacedBy(Dimens.spacingMd)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionStatusIndicator(isConnected = isConnected)
                Spacer(modifier = Modifier.width(Dimens.spacingSm))
                Text(
                    text = device.name.ifBlank { latestRecord.name }
                        .ifBlank { stringResource(id = R.string.unknown_device) },
                    style = MaterialTheme.typography.titleLarge,
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
            Spacer(modifier = Modifier.height(Dimens.spacingXs))
            Text(
                text = device.mac,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.outline
            )
        }

        DurationProgressBar(
            connectionTime = latestRecord.totalConnectionTime ?: 0L,
            disconnectionTime = latestRecord.totalDisConnectionTime ?: 0L
        )

        RecordMetricsRow(record = latestRecord, recordedLabels = true)

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

        CodecInfoSection(
            phoneSupportedCodecs = latestRecord.phoneSupportedCodecs,
            negotiableCodecs = latestRecord.negotiableCodecs,
            activeCodec = latestRecord.activeCodec
        )

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryFilterRow(
    selected: RecordFilter,
    count: Int,
    onFilterChange: (RecordFilter) -> Unit
) {
    Column(modifier = Modifier.padding(top = Dimens.spacingSm)) {
        Text(
            text = stringResource(id = R.string.detail_history_title, count),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(horizontal = Dimens.spacingLg)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Dimens.spacingLg),
            horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)
        ) {
            listOf(
                RecordFilter.ALL to R.string.filter_all,
                RecordFilter.CONNECTION to R.string.filter_connection,
                RecordFilter.CODEC to R.string.filter_codec,
                RecordFilter.BATTERY to R.string.filter_battery
            ).forEach { (filter, labelRes) ->
                FilterChip(
                    selected = selected == filter,
                    onClick = { onFilterChange(filter) },
                    label = { Text(stringResource(id = labelRes)) }
                )
            }
        }
    }
}

/**
 * 电量、音量与播放状态指标行
 *
 * @param recordedLabels 详情头部强调“记录时”的状态，历史条目使用简短文案
 */
@Composable
private fun RecordMetricsRow(record: RecordInfo, recordedLabels: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Dimens.spacingMd),
        verticalAlignment = Alignment.CenterVertically
    ) {
        record.batteryLevel.takeIf { it in 0..100 }?.let { level ->
            BatteryIndicator(level = level, label = stringResource(id = R.string.phone_battery_label))
        }
        record.headsetBatteryLevel.takeIf { it in 0..100 }?.let { level ->
            BatteryIndicator(level = level, label = stringResource(id = R.string.headset_battery_label))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(id = R.drawable.icon_volue),
                contentDescription = stringResource(id = R.string.volume_content_description),
                modifier = Modifier.size(Dimens.iconSizeSm),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = "${record.volume}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
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
    onLongClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val isCodecChanged = record.eventType == RecordEventType.CODEC_CHANGED
    val isBatteryChanged = record.eventType == RecordEventType.BATTERY_CHANGED
    val isConnected = record.connectState == STATE_CONNECTED
    val isStateEvent = !isCodecChanged && !isBatteryChanged
    val shape = RoundedCornerShape(Dimens.cardCornerRadius)
    val containerColor = when {
        isCodecChanged -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        isBatteryChanged -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        isConnected -> if (isSystemInDarkTheme()) ConnectedGreenDark.copy(alpha = 0.45f) else ConnectedGreenLight
        else -> MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .padding(horizontal = Dimens.spacingMd, vertical = Dimens.spacingXs)
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isStateEvent) 1.dp else 0.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(Dimens.cardPadding)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    when {
                        isCodecChanged -> Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(Dimens.iconSizeSm)
                        )

                        isBatteryChanged -> Icon(
                            painter = painterResource(id = R.drawable.ic_battery),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(Dimens.iconSizeSm)
                        )

                        else -> ConnectionStatusIndicator(isConnected = isConnected)
                    }
                    Spacer(modifier = Modifier.width(Dimens.spacingSm))
                    Text(
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        text = stringResource(
                            id = when {
                                isCodecChanged -> R.string.event_codec_changed
                                isBatteryChanged -> R.string.event_battery_changed
                                isConnected -> R.string.event_connected
                                else -> R.string.event_disconnected
                            }
                        )
                    )
                }
                Text(
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    text = TimeUtils.millis2String(record.timestamp, "MM-dd HH:mm:ss")
                )
            }

            RecordMetricsRow(record = record, recordedLabels = false)

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
                style = MaterialTheme.typography.labelMedium,
                color = if (isStateEvent && !isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                fontWeight = if (isStateEvent && !isConnected) FontWeight.Medium else FontWeight.Normal,
                text = summary
            )

            if (isCodecChanged) {
                CodecInfoSection(
                    phoneSupportedCodecs = record.phoneSupportedCodecs,
                    negotiableCodecs = record.negotiableCodecs,
                    activeCodec = record.activeCodec
                )
            } else if (!isBatteryChanged) {
                Text(
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    text = stringResource(id = R.string.record_active_codec, record.activeCodec.ifBlank { CODEC_UNKNOWN })
                )
            }
        }
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

@Composable
private fun CodecInfoSection(
    phoneSupportedCodecs: String,
    negotiableCodecs: String,
    activeCodec: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        shape = RoundedCornerShape(Dimens.cardCornerRadius),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(Dimens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)
        ) {
            CodecInfoLine(label = stringResource(id = R.string.codec_phone_supported), value = phoneSupportedCodecs)
            CodecInfoLine(label = stringResource(id = R.string.codec_negotiable), value = negotiableCodecs)
            CodecInfoLine(label = stringResource(id = R.string.codec_active), value = activeCodec)
        }
    }
}

@Composable
private fun CodecInfoLine(label: String, value: String) {
    val labelColor = MaterialTheme.colorScheme.outline
    Text(
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurface,
        text = buildAnnotatedString {
            withStyle(SpanStyle(color = labelColor, fontWeight = FontWeight.Medium)) {
                append("$label：")
            }
            append(value.ifBlank { CODEC_UNKNOWN })
        }
    )
}
