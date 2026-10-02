package com.xingkeqi.btlogger.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.FileProvider
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.TimeUtils
import com.xingkeqi.btlogger.MainViewModel
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.UiEvent
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.data.RecordEventType
import com.xingkeqi.btlogger.data.RecordInfo
import com.xingkeqi.btlogger.ui.dialogs.ConfirmDeleteDialog
import com.xingkeqi.btlogger.ui.dialogs.FixedVolumeDialog
import com.xingkeqi.btlogger.ui.dialogs.UpdateDialogs
import com.xingkeqi.btlogger.ui.screens.DeviceDetailScreen
import com.xingkeqi.btlogger.ui.screens.DeviceListScreen
import com.xingkeqi.btlogger.utils.EXCEL_MIME_TYPE
import com.xingkeqi.btlogger.utils.MediaVolumeSnapshot
import com.xingkeqi.btlogger.utils.readMediaVolumeSnapshot
import com.xingkeqi.btlogger.utils.setMediaVolumePercent
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.roundToInt

private const val TAG = "BtLoggerApp"
private const val STATE_CONNECTED = 2
private const val EXIT_CONFIRM_WINDOW_MILLIS = 2_000L

/**
 * 需要用户二次确认的危险操作
 */
private sealed interface PendingConfirm {
    data class DeleteDevice(val device: DeviceInfo) : PendingConfirm
    data class ClearDeviceRecords(val device: DeviceInfo) : PendingConfirm
    object ClearAll : PendingConfirm
    data class DeleteRecord(val record: RecordInfo) : PendingConfirm
}

/**
 * 应用主界面：负责把 ViewModel 状态分发给无状态的页面，并承载顶栏、弹框与一次性事件。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BtLoggerApp(
    viewModel: MainViewModel,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    val allDevices by viewModel.devices.collectAsState()
    val filteredDevices by viewModel.filteredDevices.collectAsState()
    val selectedDevice by viewModel.selectedDevice.collectAsState()
    val records by viewModel.records.collectAsState()
    val stats by viewModel.recordStats.collectAsState()
    val recordFilter by viewModel.recordFilter.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val fixedVolumeEnabled by viewModel.fixedVolumeEnabled.collectAsState()
    val fixedVolumePercent by viewModel.fixedVolumePercent.collectAsState()
    val mediaVolumeSnapshot by viewModel.mediaVolumeSnapshot.collectAsState()
    val updateState by viewModel.updateState.collectAsState()

    var searchActive by rememberSaveable { mutableStateOf(false) }
    var showVolumeDialog by rememberSaveable { mutableStateOf(false) }
    var showOverflow by remember { mutableStateOf(false) }
    var pendingConfirm by remember { mutableStateOf<PendingConfirm?>(null) }
    var lastBackPressAt by remember { mutableStateOf(0L) }

    val openBluetoothSettings = {
        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .onFailure { Log.e(TAG, "[BtLoggerApp] openBluetoothSettings -> failed", it) }
        Unit
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is UiEvent.ShowMessage -> snackbarHostState.showSnackbar(
                    context.getString(event.resId, *event.args.toTypedArray())
                )

                is UiEvent.ShareFile -> {
                    val shared = shareExportedFile(context, event.file)
                    snackbarHostState.showSnackbar(
                        if (shared) {
                            context.getString(R.string.export_saved, event.file.name)
                        } else {
                            context.getString(R.string.export_no_app)
                        }
                    )
                }
            }
        }
    }

    // 返回键：详情 -> 列表；搜索 -> 关闭搜索；列表 -> 2 秒内再按一次退出
    BackHandler(enabled = selectedDevice != null) { viewModel.clearSelection() }
    BackHandler(enabled = selectedDevice == null && searchActive) {
        searchActive = false
        viewModel.setSearchQuery("")
    }
    BackHandler(enabled = selectedDevice == null && !searchActive) {
        val now = System.currentTimeMillis()
        if (now - lastBackPressAt < EXIT_CONFIRM_WINDOW_MILLIS) {
            onExit()
        } else {
            lastBackPressAt = now
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.press_again_to_exit))
            }
        }
    }

    val detailDevice = selectedDevice

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = detailDevice?.name?.ifBlank { stringResource(id = R.string.unknown_device) }
                            ?: stringResource(id = R.string.app_name),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (detailDevice != null) {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = stringResource(id = R.string.action_back))
                        }
                    }
                },
                actions = {
                    if (detailDevice == null) {
                        IconButton(onClick = {
                            searchActive = !searchActive
                            if (!searchActive) viewModel.setSearchQuery("")
                        }) {
                            Icon(
                                imageVector = if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = stringResource(
                                    id = if (searchActive) R.string.action_close_search else R.string.action_search
                                )
                            )
                        }
                        IconButton(onClick = {
                            viewModel.updateMediaVolumeSnapshot(readMediaVolumeSnapshot(context))
                            showVolumeDialog = true
                        }) {
                            Icon(
                                painter = painterResource(id = R.drawable.icon_volue),
                                contentDescription = stringResource(id = R.string.action_volume_preset),
                                tint = if (fixedVolumeEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = openBluetoothSettings) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_bluetooth_settings),
                                contentDescription = stringResource(id = R.string.action_bluetooth_settings)
                            )
                        }
                    } else {
                        IconButton(onClick = { viewModel.exportSelectedDevice() }) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(id = R.string.action_export_device))
                        }
                    }
                    IconButton(onClick = { showOverflow = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(id = R.string.action_more))
                    }
                    DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                        if (detailDevice == null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.action_export_all)) },
                                leadingIcon = { Icon(painterResource(id = R.drawable.icon_ex_excel), contentDescription = null) },
                                onClick = {
                                    showOverflow = false
                                    viewModel.exportAll(context.getString(R.string.export_all_name))
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.action_clear_all), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showOverflow = false
                                    pendingConfirm = PendingConfirm.ClearAll
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.action_check_update)) },
                                leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                                onClick = {
                                    showOverflow = false
                                    viewModel.checkUpdate(manual = true)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.app_version, AppUtils.getAppVersionName())) },
                                leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null) },
                                enabled = false,
                                onClick = {}
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(id = R.string.action_clear_device_records), color = MaterialTheme.colorScheme.error) },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showOverflow = false
                                    pendingConfirm = PendingConfirm.ClearDeviceRecords(detailDevice)
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (detailDevice == null) {
            val total = allDevices?.size ?: 0
            DeviceListScreen(
                devices = filteredDevices,
                totalCount = total,
                connectedCount = allDevices.orEmpty().count { it.connectState == STATE_CONNECTED },
                searchActive = searchActive,
                searchQuery = searchQuery,
                onSearchQueryChange = viewModel::setSearchQuery,
                onDeviceClick = viewModel::selectDevice,
                onDeviceLongClick = { pendingConfirm = PendingConfirm.DeleteDevice(it) },
                onOpenBluetoothSettings = openBluetoothSettings,
                modifier = Modifier.padding(innerPadding)
            )
        } else {
            DeviceDetailScreen(
                device = detailDevice,
                records = records,
                stats = stats,
                filter = recordFilter,
                onFilterChange = viewModel::setRecordFilter,
                onRecordLongClick = { pendingConfirm = PendingConfirm.DeleteRecord(it) },
                modifier = Modifier.padding(innerPadding)
            )
        }
    }

    pendingConfirm?.let { confirm ->
        ConfirmDialogFor(
            confirm = confirm,
            onConfirm = {
                pendingConfirm = null
                when (confirm) {
                    is PendingConfirm.DeleteDevice -> viewModel.deleteDevice(confirm.device.mac)
                    is PendingConfirm.ClearDeviceRecords -> viewModel.clearSelectedDeviceRecords()
                    PendingConfirm.ClearAll -> viewModel.clearAll()
                    is PendingConfirm.DeleteRecord -> viewModel.deleteRecord(confirm.record.id)
                }
            },
            onDismiss = { pendingConfirm = null }
        )
    }

    if (showVolumeDialog) {
        ObserveMediaVolume { viewModel.updateMediaVolumeSnapshot(it) }
        FixedVolumeDialog(
            enabled = fixedVolumeEnabled,
            targetPercent = fixedVolumePercent,
            snapshot = mediaVolumeSnapshot,
            onEnabledChange = viewModel::setFixedVolumeEnabled,
            onTargetChange = { value ->
                val percent = value.roundToInt()
                viewModel.setFixedVolumePercent(percent)
                // 实时调整系统音量，便于边听边确定合适的数值
                runCatching { setMediaVolumePercent(context, percent) }
            },
            onTargetChangeFinished = {
                viewModel.updateMediaVolumeSnapshot(readMediaVolumeSnapshot(context))
            },
            onDismiss = { showVolumeDialog = false }
        )
    }

    UpdateDialogs(
        state = updateState,
        onUpdateNow = viewModel::startDownload,
        onDismiss = viewModel::dismissUpdate,
        onCancelDownload = viewModel::cancelDownload
    )
}

@Composable
private fun ConfirmDialogFor(
    confirm: PendingConfirm,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val unknownName = stringResource(id = R.string.unknown_device)
    val irreversible = stringResource(id = R.string.confirm_irreversible_body)
    val (title, body) = when (confirm) {
        is PendingConfirm.DeleteDevice -> {
            val name = confirm.device.name.ifBlank { unknownName }
            stringResource(id = R.string.confirm_delete_device_title, name) to
                stringResource(id = R.string.confirm_delete_device_body, name, confirm.device.mac)
        }

        is PendingConfirm.ClearDeviceRecords -> stringResource(
            id = R.string.confirm_clear_device_title,
            confirm.device.name.ifBlank { unknownName }
        ) to irreversible

        PendingConfirm.ClearAll -> stringResource(id = R.string.confirm_clear_all_title) to irreversible

        is PendingConfirm.DeleteRecord -> {
            val record = confirm.record
            val eventLabel = stringResource(
                id = when (record.eventType) {
                    RecordEventType.CONNECTED -> R.string.event_label_connected
                    RecordEventType.DISCONNECTED -> R.string.event_label_disconnected
                    RecordEventType.CODEC_CHANGED -> R.string.event_codec_changed
                    RecordEventType.BATTERY_CHANGED -> R.string.event_battery_changed
                    else -> R.string.event_label_unknown
                }
            )
            stringResource(id = R.string.confirm_delete_record_title) to stringResource(
                id = R.string.confirm_delete_record_body,
                record.name.ifBlank { unknownName },
                TimeUtils.millis2String(record.timestamp),
                eventLabel
            )
        }
    }
    ConfirmDeleteDialog(title = title, body = body, onConfirm = onConfirm, onDismiss = onDismiss)
}

/**
 * 通过系统分享面板发送导出的 Excel，返回是否成功拉起
 */
private fun shareExportedFile(context: Context, file: File): Boolean {
    return try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = EXCEL_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(sendIntent, context.getString(R.string.export_share_title))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        )
        true
    } catch (e: ActivityNotFoundException) {
        Log.w(TAG, "[BtLoggerApp] shareExportedFile -> no activity", e)
        false
    } catch (e: IllegalArgumentException) {
        Log.e(TAG, "[BtLoggerApp] shareExportedFile -> FileProvider path not configured", e)
        false
    }
}

/**
 * 音量弹框打开期间监听系统音量、音频路由与播放状态变化
 */
@Composable
private fun ObserveMediaVolume(onSnapshotChanged: (MediaVolumeSnapshot) -> Unit) {
    val context = LocalContext.current
    val latestCallback by rememberUpdatedState(onSnapshotChanged)

    DisposableEffect(context) {
        val handler = Handler(Looper.getMainLooper())
        val notifyChanged = { latestCallback(readMediaVolumeSnapshot(context)) }
        val contentObserver = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = notifyChanged()
        }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) = notifyChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) = notifyChanged()
        }
        val playbackCallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            object : AudioManager.AudioPlaybackCallback() {
                override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>) =
                    notifyChanged()
            }
        } else {
            null
        }

        context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, contentObserver)
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        if (playbackCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.registerAudioPlaybackCallback(playbackCallback, handler)
        }
        notifyChanged()

        onDispose {
            context.contentResolver.unregisterContentObserver(contentObserver)
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            if (playbackCallback != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioManager.unregisterAudioPlaybackCallback(playbackCallback)
            }
        }
    }
}
