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
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.TimeUtils
import com.xingkeqi.btlogger.MainViewModel
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.UiEvent
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.data.RecordEventType
import com.xingkeqi.btlogger.data.RecordInfo
import com.xingkeqi.btlogger.ui.components.BlurSwap
import com.xingkeqi.btlogger.ui.components.IslandNotificationHost
import com.xingkeqi.btlogger.ui.components.LineIcons
import com.xingkeqi.btlogger.ui.components.WmIconButton
import com.xingkeqi.btlogger.ui.dialogs.ConfirmDeleteDialog
import com.xingkeqi.btlogger.ui.dialogs.FixedVolumeDialog
import com.xingkeqi.btlogger.ui.dialogs.UpdateDialogs
import com.xingkeqi.btlogger.update.ApkInstaller
import com.xingkeqi.btlogger.ui.screens.BatteryCompareScreen
import com.xingkeqi.btlogger.ui.screens.DeviceDetailScreen
import com.xingkeqi.btlogger.ui.screens.DeviceListScreen
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.utils.EXCEL_MIME_TYPE
import com.xingkeqi.btlogger.utils.MediaVolumeSnapshot
import com.xingkeqi.btlogger.utils.readMediaVolumeSnapshot
import com.xingkeqi.btlogger.utils.setMediaVolumePercent
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private const val TAG = "BtLoggerApp"
private const val STATE_CONNECTED = 2
private const val EXIT_CONFIRM_WINDOW_MILLIS = 2_000L

/**
 * 当前页面：列表、设备详情或电量对比；对比页可以从列表或详情进入，返回时回到来源页
 */
private sealed interface AppPage {
    val key: String

    object List : AppPage {
        override val key = "list"
    }

    data class Detail(val device: DeviceInfo) : AppPage {
        override val key = "detail:${device.mac}"
    }

    object Compare : AppPage {
        override val key = "compare"
    }
}

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
    val expandedHistoryGroups by viewModel.expandedHistoryGroups.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val fixedVolumeEnabled by viewModel.fixedVolumeEnabled.collectAsState()
    val fixedVolumePercent by viewModel.fixedVolumePercent.collectAsState()
    val mediaVolumeSnapshot by viewModel.mediaVolumeSnapshot.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val batteryOverview by viewModel.batteryOverview.collectAsState()
    val dualEarState by viewModel.dualEarState.collectAsState()
    val compareOpen by viewModel.compareOpen.collectAsState()
    val batteryComparison by viewModel.batteryComparison.collectAsState()

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

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::importFromExcel)
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

                is UiEvent.InstallApk -> launchApkInstall(context, event.file)?.let {
                    snackbarHostState.showSnackbar(context.getString(it))
                }
            }
        }
    }

    // 返回键：对比 -> 来源页；详情 -> 列表；搜索 -> 关闭搜索；列表 -> 2 秒内再按一次退出
    BackHandler(enabled = compareOpen) { viewModel.closeCompare() }
    BackHandler(enabled = !compareOpen && selectedDevice != null) { viewModel.clearSelection() }
    BackHandler(enabled = !compareOpen && selectedDevice == null && searchActive) {
        searchActive = false
        viewModel.setSearchQuery("")
    }
    BackHandler(enabled = !compareOpen && selectedDevice == null && !searchActive) {
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
    val page: AppPage = when {
        compareOpen -> AppPage.Compare
        detailDevice != null -> AppPage.Detail(detailDevice)
        else -> AppPage.List
    }
    val colors = BtTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AppTopBar(
                page = page,
                searchActive = searchActive,
                fixedVolumeEnabled = fixedVolumeEnabled,
                showOverflow = showOverflow,
                onShowOverflowChange = { showOverflow = it },
                onBack = {
                    if (compareOpen) viewModel.closeCompare() else viewModel.clearSelection()
                },
                onOpenCompare = viewModel::openCompare,
                onToggleSearch = {
                    searchActive = !searchActive
                    if (!searchActive) viewModel.setSearchQuery("")
                },
                onOpenVolume = {
                    viewModel.updateMediaVolumeSnapshot(readMediaVolumeSnapshot(context))
                    showVolumeDialog = true
                },
                onOpenBluetoothSettings = openBluetoothSettings,
                onExportDevice = { viewModel.exportSelectedDevice() },
                onExportAll = { viewModel.exportAll(context.getString(R.string.export_all_name)) },
                onImport = {
                    runCatching { importLauncher.launch(IMPORT_MIME_TYPES) }
                        .onFailure { Log.e(TAG, "[BtLoggerApp] import -> no document picker", it) }
                },
                onClearAll = { pendingConfirm = PendingConfirm.ClearAll },
                onCheckUpdate = { viewModel.checkUpdate(manual = true) },
                onClearDeviceRecords = { device -> pendingConfirm = PendingConfirm.ClearDeviceRecords(device) }
            )

            // 页面之间用模糊交叉淡化切换：同一块内容区换内容，而不是叠一个新页面上来
            BlurSwap(
                targetState = page,
                contentKey = { it.key },
                contentAlignment = Alignment.TopStart,
                label = "pageSwap",
                modifier = Modifier.fillMaxSize()
            ) { target ->
                when (target) {
                    AppPage.List -> DeviceListScreen(
                        devices = filteredDevices,
                        totalCount = allDevices?.size ?: 0,
                        connectedCount = allDevices.orEmpty().count { it.connectState == STATE_CONNECTED },
                        searchActive = searchActive,
                        searchQuery = searchQuery,
                        onSearchQueryChange = viewModel::setSearchQuery,
                        onDeviceClick = viewModel::selectDevice,
                        onDeviceLongClick = { pendingConfirm = PendingConfirm.DeleteDevice(it) },
                        onOpenBluetoothSettings = openBluetoothSettings
                    )

                    is AppPage.Detail -> DeviceDetailScreen(
                        device = target.device,
                        records = records,
                        stats = stats,
                        batteryOverview = batteryOverview,
                        dualEarState = dualEarState,
                        filter = recordFilter,
                        onFilterChange = viewModel::setRecordFilter,
                        expandedGroups = expandedHistoryGroups,
                        onToggleGroup = viewModel::toggleHistoryGroup,
                        onBatteryRangeChange = viewModel::setBatteryRange,
                        onBatteryChannelChange = viewModel::setBatteryChannel,
                        onDualEarChange = viewModel::setDualEarEnabled,
                        onOpenCompare = viewModel::openCompare,
                        onRecordLongClick = { pendingConfirm = PendingConfirm.DeleteRecord(it) }
                    )

                    AppPage.Compare -> BatteryCompareScreen(
                        state = batteryComparison,
                        onAlignmentChange = viewModel::setCompareAlignment,
                        onToggleDevice = viewModel::toggleCompareDevice
                    )
                }
            }
        }

        IslandNotificationHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 14.dp)
        )
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
        onSkip = viewModel::skipUpdate,
        onDismiss = viewModel::dismissUpdate,
        onCancelDownload = viewModel::cancelDownload,
        onInstall = viewModel::requestInstall,
        onExportData = { viewModel.exportAll(context.getString(R.string.export_all_name)) },
        onOpenRelease = { url -> ApkInstaller.openUrl(context, url) }
    )
}

/**
 * 顶栏：canvas 底、无阴影。列表页是大标题 + 幽灵图标按钮，详情页是返回 + 设备名，对比页是返回 + 标题；
 * 标题与操作按钮在不同页面之间用模糊交叉淡化切换。
 */
@Composable
private fun AppTopBar(
    page: AppPage,
    searchActive: Boolean,
    fixedVolumeEnabled: Boolean,
    showOverflow: Boolean,
    onShowOverflowChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenCompare: () -> Unit,
    onToggleSearch: () -> Unit,
    onOpenVolume: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onExportDevice: () -> Unit,
    onExportAll: () -> Unit,
    onImport: () -> Unit,
    onClearAll: () -> Unit,
    onCheckUpdate: () -> Unit,
    onClearDeviceRecords: (DeviceInfo) -> Unit
) {
    val colors = BtTheme.colors
    val detailDevice = (page as? AppPage.Detail)?.device
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Dimens.topBarHeight)
            .padding(start = 6.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 以页面为键：退出详情时旧标题仍显示原设备名，直到淡出完成
        BlurSwap(
            targetState = page,
            contentKey = { it.key },
            label = "topBarNavigation",
            modifier = Modifier.weight(1f)
        ) { target ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (target != AppPage.List) {
                    WmIconButton(
                        icon = LineIcons.ArrowLeft,
                        contentDescription = stringResource(id = R.string.action_back),
                        onClick = onBack
                    )
                    Spacer(modifier = Modifier.width(2.dp))
                    Text(
                        text = when (target) {
                            is AppPage.Detail -> target.device.name.ifBlank { stringResource(id = R.string.unknown_device) }
                            else -> stringResource(id = R.string.compare_title)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = stringResource(id = R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall,
                        color = colors.ink,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }
        }

        BlurSwap(
            targetState = page::class,
            label = "topBarActions",
            contentAlignment = Alignment.CenterEnd
        ) { pageType ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (pageType == AppPage.List::class) {
                    WmIconButton(
                        icon = if (searchActive) LineIcons.Close else LineIcons.Search,
                        contentDescription = stringResource(
                            id = if (searchActive) R.string.action_close_search else R.string.action_search
                        ),
                        onClick = onToggleSearch
                    )
                    WmIconButton(
                        icon = LineIcons.ChartLine,
                        contentDescription = stringResource(id = R.string.action_compare),
                        onClick = onOpenCompare
                    )
                    WmIconButton(
                        icon = LineIcons.Volume,
                        contentDescription = stringResource(id = R.string.action_volume_preset),
                        onClick = onOpenVolume,
                        active = fixedVolumeEnabled
                    )
                    WmIconButton(
                        icon = LineIcons.Bluetooth,
                        contentDescription = stringResource(id = R.string.action_bluetooth_settings),
                        onClick = onOpenBluetoothSettings
                    )
                } else if (pageType == AppPage.Detail::class) {
                    WmIconButton(
                        icon = LineIcons.Share,
                        contentDescription = stringResource(id = R.string.action_export_device),
                        onClick = onExportDevice
                    )
                }
            }
        }

        // 对比页没有可用的菜单项
        if (page != AppPage.Compare) Box {
            WmIconButton(
                icon = LineIcons.MoreVertical,
                contentDescription = stringResource(id = R.string.action_more),
                onClick = { onShowOverflowChange(true) }
            )
            DropdownMenu(
                expanded = showOverflow,
                onDismissRequest = { onShowOverflowChange(false) }
            ) {
                if (detailDevice == null) {
                    MenuItem(
                        text = stringResource(id = R.string.action_export_all),
                        icon = LineIcons.Spreadsheet,
                        onClick = {
                            onShowOverflowChange(false)
                            onExportAll()
                        }
                    )
                    MenuItem(
                        text = stringResource(id = R.string.action_import),
                        icon = LineIcons.Import,
                        onClick = {
                            onShowOverflowChange(false)
                            onImport()
                        }
                    )
                    MenuItem(
                        text = stringResource(id = R.string.action_clear_all),
                        icon = LineIcons.Trash,
                        danger = true,
                        onClick = {
                            onShowOverflowChange(false)
                            onClearAll()
                        }
                    )
                    MenuItem(
                        text = stringResource(id = R.string.action_check_update),
                        icon = LineIcons.Refresh,
                        onClick = {
                            onShowOverflowChange(false)
                            onCheckUpdate()
                        }
                    )
                    MenuItem(
                        text = stringResource(id = R.string.app_version, AppUtils.getAppVersionName()),
                        icon = LineIcons.Info,
                        enabled = false,
                        onClick = {}
                    )
                } else {
                    MenuItem(
                        text = stringResource(id = R.string.action_clear_device_records),
                        icon = LineIcons.Trash,
                        danger = true,
                        onClick = {
                            onShowOverflowChange(false)
                            onClearDeviceRecords(detailDevice)
                        }
                    )
                }
            }
        }
    }
}

/**
 * 菜单项：16dp 线性图标 + 正文；危险项只把图标换成危险色，文字仍为墨黑
 */
@Composable
private fun MenuItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false,
    enabled: Boolean = true
) {
    val colors = BtTheme.colors
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (enabled) colors.ink else colors.ink3
            )
        },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    danger -> colors.danger
                    enabled -> colors.ink2
                    else -> colors.ink3
                },
                modifier = Modifier.size(Dimens.iconSizeSm + 2.dp)
            )
        },
        enabled = enabled,
        onClick = onClick,
        contentPadding = PaddingValues(start = 16.dp, end = 20.dp)
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
 * jxl 导出的 .xls；部分文件管理器/聊天应用转存后 MIME 会变成 octet-stream
 */
private val IMPORT_MIME_TYPES = arrayOf(EXCEL_MIME_TYPE, "application/octet-stream")

/**
 * 拉起系统安装器；未授权“安装未知应用”时先跳转授权页，返回后由用户再点一次“安装”。
 *
 * @return 需要提示用户的文案，null 表示已正常拉起安装器
 */
private fun launchApkInstall(context: Context, file: File): Int? = when {
    !ApkInstaller.canRequestInstall(context) -> {
        Log.i(TAG, "[BtLoggerApp] launchApkInstall -> request unknown sources permission")
        if (ApkInstaller.openUnknownSourcesSettings(context)) {
            R.string.update_install_permission
        } else {
            R.string.update_install_failed
        }
    }

    ApkInstaller.install(context, file) -> null
    else -> R.string.update_install_failed
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
