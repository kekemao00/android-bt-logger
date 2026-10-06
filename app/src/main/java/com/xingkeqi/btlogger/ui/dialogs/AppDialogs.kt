package com.xingkeqi.btlogger.ui.dialogs

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.UpdateUiState
import com.xingkeqi.btlogger.ui.components.WmButton
import com.xingkeqi.btlogger.ui.components.WmButtonStyle
import com.xingkeqi.btlogger.ui.components.WmPanel
import com.xingkeqi.btlogger.ui.components.WmProgressBar
import com.xingkeqi.btlogger.ui.components.WmSwitch
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.ui.theme.MonoTextStyle
import com.xingkeqi.btlogger.utils.MediaVolumeSnapshot
import kotlin.math.roundToInt

/**
 * 危险操作确认面板（删除/清空）：取消为次按钮，确认用危险色——危险色只在这里出现
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    WmPanel(
        title = title,
        onDismissRequest = onDismiss,
        actions = {
            WmButton(
                text = stringResource(id = R.string.action_cancel),
                onClick = onDismiss,
                style = WmButtonStyle.Secondary
            )
            WmButton(
                text = stringResource(id = R.string.action_delete),
                onClick = onConfirm,
                style = WmButtonStyle.Danger
            )
        }
    ) {
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = BtTheme.colors.ink2
        )
    }
}

/**
 * 固定连接音量设置。
 *
 * 拖动滑块会实时修改系统媒体音量，方便边听边调；松手后的值作为连接后的目标音量。
 */
@Composable
fun FixedVolumeDialog(
    enabled: Boolean,
    targetPercent: Int,
    snapshot: MediaVolumeSnapshot,
    onEnabledChange: (Boolean) -> Unit,
    onTargetChange: (Float) -> Unit,
    onTargetChangeFinished: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = BtTheme.colors
    WmPanel(
        title = stringResource(id = R.string.volume_dialog_title),
        onDismissRequest = onDismiss,
        closeContentDescription = stringResource(id = R.string.action_close),
        actions = {
            WmButton(text = stringResource(id = R.string.action_done), onClick = onDismiss)
        }
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(id = R.string.volume_dialog_switch),
                style = MaterialTheme.typography.titleSmall,
                color = colors.ink,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(Dimens.spacingMd))
            WmSwitch(checked = enabled, onCheckedChange = onEnabledChange)
        }
        Spacer(modifier = Modifier.height(Dimens.spacingXs))
        Text(
            text = stringResource(id = R.string.volume_dialog_switch_hint),
            style = MaterialTheme.typography.bodySmall,
            color = colors.ink3
        )

        Spacer(modifier = Modifier.height(Dimens.spacingXl))

        Text(
            text = stringResource(id = R.string.volume_dialog_target, targetPercent),
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) colors.ink else colors.ink3
        )
        Slider(
            value = targetPercent.toFloat(),
            onValueChange = onTargetChange,
            onValueChangeFinished = onTargetChangeFinished,
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = colors.ink,
                activeTrackColor = colors.ink,
                inactiveTrackColor = colors.surface3,
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent
            )
        )

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = stringResource(id = R.string.volume_dialog_current, snapshot.percent),
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink2
            )
            Text(
                text = stringResource(
                    id = R.string.volume_dialog_route,
                    stringResource(
                        id = if (snapshot.hasBluetoothOutput) R.string.volume_route_bluetooth else R.string.volume_route_none
                    ),
                    snapshot.currentLevel,
                    snapshot.maxLevel,
                    stringResource(
                        id = if (snapshot.isMusicActive) R.string.badge_playing else R.string.badge_not_playing
                    )
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3
            )
        }
    }
}

@Composable
fun UpdateDialogs(
    state: UpdateUiState,
    onUpdateNow: () -> Unit,
    onDismiss: () -> Unit,
    onCancelDownload: () -> Unit
) {
    val colors = BtTheme.colors
    when (state) {
        UpdateUiState.Hidden -> Unit
        is UpdateUiState.Available -> WmPanel(
            title = stringResource(id = R.string.update_title),
            onDismissRequest = onDismiss,
            actions = {
                WmButton(
                    text = stringResource(id = R.string.update_later),
                    onClick = onDismiss,
                    style = WmButtonStyle.Secondary
                )
                WmButton(text = stringResource(id = R.string.update_now), onClick = onUpdateNow)
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
                Text(
                    text = stringResource(id = R.string.update_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink2
                )
                Text(
                    text = stringResource(id = R.string.update_version, state.version.buildVersion.orEmpty()),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.ink
                )
                state.version.buildUpdateDescription
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        Text(
                            text = stringResource(id = R.string.update_description, it),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.ink2
                        )
                    }
            }
        }

        is UpdateUiState.Downloading -> {
            val context = LocalContext.current
            // 下载过程中返回键与点遮罩都不关闭，避免用户误以为已取消
            WmPanel(
                title = stringResource(id = R.string.update_downloading),
                onDismissRequest = {},
                dismissible = false,
                actions = {
                    WmButton(
                        text = stringResource(id = R.string.update_cancel_download),
                        onClick = onCancelDownload,
                        style = WmButtonStyle.Secondary
                    )
                }
            ) {
                WmProgressBar(progress = state.progress)
                Spacer(modifier = Modifier.height(Dimens.spacingSm))
                Text(
                    text = stringResource(
                        id = R.string.update_download_progress,
                        (state.progress * 100).roundToInt().coerceIn(0, 100),
                        Formatter.formatShortFileSize(context, state.downloadedBytes),
                        Formatter.formatShortFileSize(context, state.totalBytes)
                    ),
                    style = MonoTextStyle,
                    color = colors.ink2
                )
            }
        }
    }
}
