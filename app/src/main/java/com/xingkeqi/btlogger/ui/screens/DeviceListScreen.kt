package com.xingkeqi.btlogger.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.blankj.utilcode.util.TimeUtils
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.ui.components.EmptyState
import com.xingkeqi.btlogger.ui.components.LineIcons
import com.xingkeqi.btlogger.ui.components.LiveDurationText
import com.xingkeqi.btlogger.ui.components.SectionCaption
import com.xingkeqi.btlogger.ui.components.SegmentPosition
import com.xingkeqi.btlogger.ui.components.WmSearchField
import com.xingkeqi.btlogger.ui.components.cardSegment
import com.xingkeqi.btlogger.ui.components.segmentPositionOf
import com.xingkeqi.btlogger.ui.components.shape
import com.xingkeqi.btlogger.ui.components.staggeredEntrance
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.ui.theme.MonoTextStyle
import com.xingkeqi.btlogger.ui.theme.Springs

private const val STATE_CONNECTED = 2

/**
 * 设备列表（无状态）。
 *
 * 所有设备放在同一张白色卡片里，行间用细线分隔；已连接只用强调色圆点标记，不再整行染绿。
 *
 * @param devices 过滤后的设备；null 表示首次加载中
 * @param totalCount 未过滤前的设备总数，用于区分“没有设备”和“没有搜索结果”
 */
@Composable
fun DeviceListScreen(
    devices: List<DeviceInfo>?,
    totalCount: Int,
    connectedCount: Int,
    searchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onDeviceClick: (DeviceInfo) -> Unit,
    onDeviceLongClick: (DeviceInfo) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BtTheme.colors
    Column(modifier = modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = searchActive,
            enter = expandVertically(Springs.smooth()) + fadeIn(Springs.snappy()),
            exit = shrinkVertically(Springs.default()) + fadeOut(Springs.snappy())
        ) {
            WmSearchField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = stringResource(id = R.string.device_search_hint),
                clearContentDescription = stringResource(id = R.string.action_clear),
                modifier = Modifier.padding(
                    start = Dimens.pageGutter,
                    end = Dimens.pageGutter,
                    top = Dimens.spacingXs,
                    bottom = Dimens.spacingSm
                )
            )
        }

        when {
            devices == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = colors.ink,
                    trackColor = colors.surface3,
                    strokeWidth = 1.75.dp,
                    modifier = Modifier.size(20.dp)
                )
            }

            totalCount == 0 -> EmptyState(
                icon = LineIcons.Bluetooth,
                title = stringResource(id = R.string.device_list_empty_title),
                body = stringResource(id = R.string.device_list_empty_body),
                actionLabel = stringResource(id = R.string.device_list_empty_action),
                onAction = onOpenBluetoothSettings
            )

            devices.isEmpty() -> EmptyState(
                icon = LineIcons.Search,
                title = stringResource(id = R.string.device_search_empty, searchQuery.trim()),
                body = ""
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = Dimens.pageGutter,
                    end = Dimens.pageGutter,
                    bottom = Dimens.spacingXl
                )
            ) {
                item(key = "summary") {
                    SectionCaption(
                        text = stringResource(id = R.string.device_list_summary, totalCount, connectedCount),
                        modifier = Modifier.padding(start = Dimens.spacingXs, top = Dimens.spacingSm, bottom = Dimens.spacingSm)
                    )
                }
                itemsIndexed(items = devices, key = { _, device -> device.mac }) { index, device ->
                    DeviceRow(
                        device = device,
                        position = segmentPositionOf(index, devices.size),
                        onClick = { onDeviceClick(device) },
                        onLongClick = { onDeviceLongClick(device) },
                        modifier = Modifier.staggeredEntrance(index)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceRow(
    device: DeviceInfo,
    position: SegmentPosition,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BtTheme.colors
    val haptics = LocalHapticFeedback.current
    val isConnected = device.connectState == STATE_CONNECTED
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedBackground by animateColorAsState(
        if (pressed) colors.surface2 else colors.surface2.copy(alpha = 0f),
        Springs.snappy(),
        label = "rowPressed"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .cardSegment(position, background = colors.surface, border = colors.line, divider = colors.line)
            .clip(position.shape())
            .background(pressedBackground)
            .combinedClickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            )
            .padding(horizontal = Dimens.cardPadding, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DeviceAvatar(isConnected = isConnected)

        Spacer(modifier = Modifier.width(Dimens.spacingMd))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = device.name.ifBlank { stringResource(id = R.string.unknown_device) },
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = device.mac,
                style = MonoTextStyle,
                color = colors.ink3
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isConnected) {
                    val context = LocalContext.current
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(colors.accent, CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    LiveDurationText(
                        sinceMillis = device.connectedSince ?: device.lastRecordTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.ink,
                        format = { context.getString(R.string.connected_for, it) }
                    )
                } else {
                    Text(
                        text = stringResource(
                            id = R.string.device_last_seen,
                            TimeUtils.millis2String(device.lastRecordTime, "MM-dd HH:mm")
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.ink2
                    )
                }
                Text(
                    text = "  ·  " + stringResource(
                        id = R.string.device_first_seen,
                        TimeUtils.millis2String(device.firstRecordTime, "yyyy-MM-dd")
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink3,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Icon(
            imageVector = LineIcons.ChevronRight,
            contentDescription = stringResource(id = R.string.device_open_detail),
            tint = colors.ink3,
            modifier = Modifier.size(Dimens.iconSizeSm + 2.dp)
        )
    }
}

/**
 * 头像：surface-3 圆底 + 蓝牙线性图标；已连接时右下角加一个强调色圆点
 */
@Composable
private fun DeviceAvatar(isConnected: Boolean) {
    val colors = BtTheme.colors
    Box(contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier
                .size(Dimens.avatarSize)
                .background(colors.surface3, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isConnected) LineIcons.Bluetooth else LineIcons.BluetoothOff,
                contentDescription = null,
                tint = if (isConnected) colors.ink else colors.ink3,
                modifier = Modifier.size(Dimens.iconSizeMd)
            )
        }
        if (isConnected) {
            Box(
                modifier = Modifier
                    .size(Dimens.statusIndicatorSize + 4.dp)
                    .background(colors.surface, CircleShape)
                    .padding(2.dp)
                    .background(colors.accent, CircleShape)
            )
        }
    }
}
