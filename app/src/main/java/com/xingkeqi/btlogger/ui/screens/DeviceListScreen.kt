package com.xingkeqi.btlogger.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import com.blankj.utilcode.util.TimeUtils
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.ui.components.BadgeType
import com.xingkeqi.btlogger.ui.components.EmptyState
import com.xingkeqi.btlogger.ui.components.LiveDurationText
import com.xingkeqi.btlogger.ui.components.StatusBadge
import com.xingkeqi.btlogger.ui.theme.ConnectedGreen
import com.xingkeqi.btlogger.ui.theme.ConnectedGreenDark
import com.xingkeqi.btlogger.ui.theme.ConnectedGreenLight
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.ui.theme.DisconnectedGray

private const val STATE_CONNECTED = 2

/**
 * 设备列表（无状态）。
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
    Column(modifier = modifier.fillMaxSize()) {
        if (searchActive) {
            DeviceSearchField(
                query = searchQuery,
                onQueryChange = onSearchQueryChange,
                modifier = Modifier.padding(horizontal = Dimens.listContentPadding, vertical = Dimens.spacingXs)
            )
        }

        when {
            devices == null -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            totalCount == 0 -> EmptyState(
                icon = painterResource(id = R.drawable.ic_bluetooth_settings),
                title = stringResource(id = R.string.device_list_empty_title),
                body = stringResource(id = R.string.device_list_empty_body),
                actionLabel = stringResource(id = R.string.device_list_empty_action),
                onAction = onOpenBluetoothSettings
            )

            devices.isEmpty() -> EmptyState(
                icon = rememberVectorPainter(image = Icons.Filled.Search),
                title = stringResource(id = R.string.device_search_empty, searchQuery.trim()),
                body = ""
            )

            else -> LazyColumn(
                contentPadding = PaddingValues(
                    start = Dimens.listContentPadding,
                    end = Dimens.listContentPadding,
                    bottom = Dimens.spacingLg
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.listItemSpacing)
            ) {
                item(key = "summary") {
                    Text(
                        text = stringResource(id = R.string.device_list_summary, totalCount, connectedCount),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(start = Dimens.spacingXs, top = Dimens.spacingXs)
                    )
                }
                items(items = devices, key = { it.mac }) { device ->
                    DeviceCard(
                        device = device,
                        onClick = { onDeviceClick(device) },
                        onLongClick = { onDeviceLongClick(device) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(Dimens.cardCornerRadius),
        placeholder = { Text(stringResource(id = R.string.device_search_hint)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(id = R.string.action_clear))
                }
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DeviceCard(
    device: DeviceInfo,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    val isConnected = device.connectState == STATE_CONNECTED
    val shape = RoundedCornerShape(Dimens.cardCornerRadius)
    val containerColor = if (isConnected) {
        if (isSystemInDarkTheme()) ConnectedGreenDark.copy(alpha = 0.45f) else ConnectedGreenLight
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                }
            ),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Dimens.cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            DeviceAvatar(isConnected = isConnected)

            Spacer(modifier = Modifier.width(Dimens.spacingMd))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = device.name.ifBlank { stringResource(id = R.string.unknown_device) },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.mac,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )

                Spacer(modifier = Modifier.height(Dimens.spacingSm))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm)
                ) {
                    StatusBadge(
                        text = stringResource(
                            id = if (isConnected) R.string.status_connected else R.string.status_disconnected
                        ),
                        type = if (isConnected) BadgeType.Connected else BadgeType.Disconnected
                    )
                    if (isConnected) {
                        val context = LocalContext.current
                        LiveDurationText(
                            sinceMillis = device.lastRecordTime,
                            style = MaterialTheme.typography.labelMedium,
                            color = ConnectedGreen,
                            format = { context.getString(R.string.connected_for, it) }
                        )
                    } else {
                        Text(
                            text = stringResource(
                                id = R.string.device_last_seen,
                                TimeUtils.millis2String(device.lastRecordTime, "MM-dd HH:mm")
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Dimens.spacingXs))

                Text(
                    text = stringResource(
                        id = R.string.device_first_seen,
                        TimeUtils.millis2String(device.firstRecordTime, "yyyy-MM-dd HH:mm")
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Icon(
                imageVector = Icons.Filled.KeyboardArrowRight,
                contentDescription = stringResource(id = R.string.device_open_detail),
                tint = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun DeviceAvatar(isConnected: Boolean) {
    val accent = if (isConnected) ConnectedGreen else DisconnectedGray
    Box(contentAlignment = Alignment.BottomEnd) {
        Box(
            modifier = Modifier
                .size(Dimens.avatarSize)
                .background(accent.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_bluetooth_settings),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(Dimens.iconSizeMd)
            )
        }
        Box(
            modifier = Modifier
                .size(Dimens.statusIndicatorSize + Dimens.spacingXs)
                .background(MaterialTheme.colorScheme.surface, CircleShape)
                .padding(2.dp)
                .background(accent, CircleShape)
        )
    }
}
