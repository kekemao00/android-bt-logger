package com.xingkeqi.btlogger.ui.dialogs

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.UpdateUiState
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.utils.MediaVolumeSnapshot
import kotlin.math.roundToInt

/**
 * 危险操作确认框（删除/清空），确认按钮使用 error 色强调
 */
@Composable
fun ConfirmDeleteDialog(
    title: String,
    body: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Text(stringResource(id = R.string.action_delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.action_cancel))
            }
        }
    )
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
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(id = R.drawable.icon_volue),
                contentDescription = null
            )
        },
        title = { Text(stringResource(id = R.string.volume_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingSm)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(id = R.string.volume_dialog_switch),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Switch(checked = enabled, onCheckedChange = onEnabledChange)
                }
                Text(
                    text = stringResource(id = R.string.volume_dialog_switch_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(Dimens.spacingXs))

                Text(
                    text = stringResource(id = R.string.volume_dialog_target, targetPercent),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = targetPercent.toFloat(),
                    onValueChange = onTargetChange,
                    onValueChangeFinished = onTargetChangeFinished,
                    valueRange = 0f..100f
                )

                Text(
                    text = stringResource(id = R.string.volume_dialog_current, snapshot.percent),
                    style = MaterialTheme.typography.bodyMedium
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
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(id = R.string.action_done))
            }
        }
    )
}

@Composable
fun UpdateDialogs(
    state: UpdateUiState,
    onUpdateNow: () -> Unit,
    onDismiss: () -> Unit,
    onCancelDownload: () -> Unit
) {
    when (state) {
        UpdateUiState.Hidden -> Unit
        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(id = R.string.update_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.spacingXs)) {
                    Text(stringResource(id = R.string.update_body))
                    Text(
                        text = stringResource(id = R.string.update_version, state.version.buildVersion.orEmpty()),
                        fontWeight = FontWeight.Medium
                    )
                    state.version.buildUpdateDescription
                        ?.takeIf { it.isNotBlank() }
                        ?.let { Text(stringResource(id = R.string.update_description, it)) }
                }
            },
            confirmButton = {
                Button(onClick = onUpdateNow) { Text(stringResource(id = R.string.update_now)) }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.update_later)) }
            }
        )

        is UpdateUiState.Downloading -> {
            val context = LocalContext.current
            AlertDialog(
                // 下载过程中点击外部不关闭，避免用户误以为已取消
                onDismissRequest = {},
                title = { Text(stringResource(id = R.string.update_downloading)) },
                text = {
                    Column {
                        LinearProgressIndicator(
                            progress = { state.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(Dimens.spacingSm))
                        Text(
                            text = stringResource(
                                id = R.string.update_download_progress,
                                (state.progress * 100).roundToInt().coerceIn(0, 100),
                                Formatter.formatShortFileSize(context, state.downloadedBytes),
                                Formatter.formatShortFileSize(context, state.totalBytes)
                            ),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onCancelDownload) {
                        Text(stringResource(id = R.string.update_cancel_download))
                    }
                }
            )
        }
    }
}

