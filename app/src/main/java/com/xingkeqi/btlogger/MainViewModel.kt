package com.xingkeqi.btlogger

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xingkeqi.btlogger.data.BtLoggerDatabase
import com.xingkeqi.btlogger.data.DeviceDao
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.data.DeviceWithRecordsDao
import com.xingkeqi.btlogger.data.RecordDao
import com.xingkeqi.btlogger.data.RecordInfo
import com.xingkeqi.btlogger.data.RecordStats
import com.xingkeqi.btlogger.data.buildRecordTimeline
import com.xingkeqi.btlogger.data.computeRecordStats
import com.xingkeqi.btlogger.update.ApkInstaller
import com.xingkeqi.btlogger.update.GitHubUpdateRepository
import com.xingkeqi.btlogger.update.ReleaseAsset
import com.xingkeqi.btlogger.update.ReleaseInfo
import com.xingkeqi.btlogger.update.isNewerVersion
import com.xingkeqi.btlogger.update.selectApkAsset
import com.xingkeqi.btlogger.update.simplifyReleaseNotes
import com.xingkeqi.btlogger.utils.AppSettings
import com.xingkeqi.btlogger.utils.MediaVolumeSnapshot
import com.xingkeqi.btlogger.utils.saveDataToSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 一次性 UI 事件（Toast、分享文件等），避免用 State 表达导致旋转屏幕后重复触发
 */
sealed interface UiEvent {
    data class ShowMessage(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiEvent
    data class ShareFile(val file: File) : UiEvent
    data class InstallApk(val file: File) : UiEvent
}

/**
 * 应用内更新弹框状态
 */
sealed interface UpdateUiState {
    object Hidden : UpdateUiState

    /**
     * @param notes 精简后的更新日志，为空时不展示
     */
    data class Available(
        val release: ReleaseInfo,
        val asset: ReleaseAsset,
        val currentVersion: String,
        val notes: String
    ) : UpdateUiState

    data class Downloading(
        val versionName: String,
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateUiState

    /** 已下载并校验，等待用户（可能需先授权“安装未知应用”）拉起系统安装器 */
    data class ReadyToInstall(val versionName: String, val file: File) : UpdateUiState

    /** 新包签名与已安装版本不同，系统不允许覆盖安装，需要用户导出数据后卸载重装 */
    data class SignatureMismatch(val versionName: String, val releaseUrl: String) : UpdateUiState
}

/**
 * 详情页历史记录筛选
 */
enum class RecordFilter {
    ALL, CONNECTION, CODEC, BATTERY
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    private val deviceDao: DeviceDao,
    private val recordDao: RecordDao,
    private val deviceWithRecordsDao: DeviceWithRecordsDao,
    private val settings: AppSettings,
    private val exportDir: File,
    private val updateRepository: GitHubUpdateRepository,
    private val appVersionName: String,
    private val debuggable: Boolean,
    private val verifyApkSignature: (File) -> Boolean?
) : ViewModel() {

    private val tag = "MainViewModel"

    /**
     * 设备列表；null 表示首次加载尚未完成，用于区分“加载中”与“确实为空”
     */
    val devices: StateFlow<List<DeviceInfo>?> = deviceDao.getDeviceInfosWithConnectionRecords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    val filteredDevices: StateFlow<List<DeviceInfo>?> = combine(devices, _searchQuery) { list, query ->
        val keyword = query.trim()
        if (list == null || keyword.isEmpty()) {
            list
        } else {
            list.filter {
                it.name.contains(keyword, ignoreCase = true) || it.mac.contains(keyword, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _selectedDevice = MutableStateFlow<DeviceInfo?>(null)

    /**
     * 当前查看详情的设备；与设备列表合并以便连接状态实时刷新，记录被清空后保留最后一次快照
     */
    val selectedDevice: StateFlow<DeviceInfo?> = combine(_selectedDevice, devices) { selected, list ->
        selected?.let { device -> list?.firstOrNull { it.mac == device.mac } ?: device }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * 当前设备的记录时间线（倒序，已计算累计时长）
     */
    val records: StateFlow<List<RecordInfo>> = _selectedDevice
        .map { it?.mac }
        .distinctUntilChanged()
        .flatMapLatest { mac ->
            if (mac == null) {
                flowOf(emptyList())
            } else {
                recordDao.getRecordInfoListByMac(mac).map { buildRecordTimeline(it) }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recordStats: StateFlow<RecordStats> = records
        .map { computeRecordStats(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecordStats())

    private val _recordFilter = MutableStateFlow(RecordFilter.ALL)
    val recordFilter: StateFlow<RecordFilter> = _recordFilter.asStateFlow()

    val fixedVolumeEnabled: StateFlow<Boolean> = settings.fixedVolumeEnabled
    val fixedVolumePercent: StateFlow<Int> = settings.fixedVolumePercent

    private val _mediaVolumeSnapshot = MutableStateFlow(MediaVolumeSnapshot())
    val mediaVolumeSnapshot: StateFlow<MediaVolumeSnapshot> = _mediaVolumeSnapshot.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Hidden)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()
    private var checkJob: Job? = null
    private var downloadJob: Job? = null

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events: Flow<UiEvent> = _events.receiveAsFlow()

    fun selectDevice(device: DeviceInfo) {
        Log.i(tag, "[MainViewModel] selectDevice -> mac=${device.mac}")
        _recordFilter.value = RecordFilter.ALL
        _selectedDevice.value = device
    }

    fun clearSelection() {
        _selectedDevice.value = null
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setRecordFilter(filter: RecordFilter) {
        _recordFilter.value = filter
    }

    fun updateMediaVolumeSnapshot(snapshot: MediaVolumeSnapshot) {
        _mediaVolumeSnapshot.value = snapshot
    }

    fun setFixedVolumeEnabled(enabled: Boolean) {
        Log.i(tag, "[MainViewModel] setFixedVolumeEnabled -> $enabled")
        settings.setFixedVolumeEnabled(enabled)
    }

    fun setFixedVolumePercent(percent: Int) {
        settings.setFixedVolumePercent(percent)
    }

    fun deleteDevice(mac: String) = launchIo("deleteDevice") {
        deviceWithRecordsDao.deleteDeviceWithRecords(mac)
        if (_selectedDevice.value?.mac == mac) clearSelection()
        emit(UiEvent.ShowMessage(R.string.message_deleted))
    }

    fun clearAll() = launchIo("clearAll") {
        deviceWithRecordsDao.deleteAll()
        clearSelection()
        emit(UiEvent.ShowMessage(R.string.message_deleted))
    }

    /**
     * 清空当前设备记录后返回列表：设备已无记录，列表查询不会再返回它
     */
    fun clearSelectedDeviceRecords() {
        val mac = _selectedDevice.value?.mac ?: return
        launchIo("clearSelectedDeviceRecords") {
            recordDao.deleteRecordByDeviceMac(mac)
            clearSelection()
            emit(UiEvent.ShowMessage(R.string.message_deleted))
        }
    }

    fun deleteRecord(id: Int) = launchIo("deleteRecord") {
        recordDao.deleteRecordByDeviceId(id)
    }

    fun exportSelectedDevice() {
        val device = selectedDevice.value ?: return
        val snapshot = records.value
        launchIo("exportSelectedDevice") {
            export(device, snapshot)
        }
    }

    fun exportAll(allDevicesName: String) = launchIo("exportAll") {
        val all = recordDao.getRecordInfoListAll().first()
        export(DeviceInfo(name = allDevicesName), all)
    }

    private suspend fun export(device: DeviceInfo, records: List<RecordInfo>) {
        if (records.isEmpty()) {
            emit(UiEvent.ShowMessage(R.string.export_empty))
            return
        }
        val result = runCatching { saveDataToSheet(exportDir, device, records) }
        result.onSuccess { file ->
            Log.i(tag, "[MainViewModel] export -> success: ${file.name}, ${file.length()} bytes, rows=${records.size}")
            emit(UiEvent.ShareFile(file))
        }.onFailure { e ->
            Log.e(tag, "[MainViewModel] export -> failed", e)
            emit(UiEvent.ShowMessage(R.string.export_failed, listOf(e.message.orEmpty())))
        }
    }

    /**
     * 查询 GitHub 最新 Release 并与当前版本比较。
     *
     * @param manual 用户主动检查时才提示“已是最新/检查失败”，并忽略“跳过此版本”；启动时静默检查
     */
    fun checkUpdate(manual: Boolean) {
        if (checkJob?.isActive == true) return
        if (_updateState.value !is UpdateUiState.Hidden) return
        checkJob = viewModelScope.launch {
            Log.i(tag, "[MainViewModel] checkUpdate -> start, manual=$manual, current=$appVersionName")
            if (manual) emit(UiEvent.ShowMessage(R.string.update_checking))
            updateRepository.fetchLatestRelease()
                .onSuccess { release -> onLatestRelease(release, manual) }
                .onFailure { if (manual) emit(UiEvent.ShowMessage(R.string.update_check_failed)) }
        }
    }

    private fun onLatestRelease(release: ReleaseInfo, manual: Boolean) {
        val asset = selectApkAsset(release.assets, debuggable)
        val newer = isNewerVersion(release.versionName, appVersionName)
        Log.i(tag, "[MainViewModel] checkUpdate -> latest=${release.tagName}, newer=$newer, asset=${asset?.name}")
        when {
            !newer -> if (manual) emit(UiEvent.ShowMessage(R.string.update_latest))
            asset == null -> if (manual) emit(UiEvent.ShowMessage(R.string.update_no_apk))
            !manual && settings.skippedUpdateTag == release.tagName ->
                Log.i(tag, "[MainViewModel] checkUpdate -> skipped by user: ${release.tagName}")
            _updateState.value !is UpdateUiState.Hidden -> Unit
            else -> _updateState.value = UpdateUiState.Available(
                release = release,
                asset = asset,
                currentVersion = appVersionName,
                notes = simplifyReleaseNotes(release.notes)
            )
        }
    }

    fun dismissUpdate() {
        _updateState.value = UpdateUiState.Hidden
    }

    fun skipUpdate() {
        val available = _updateState.value as? UpdateUiState.Available ?: return
        Log.i(tag, "[MainViewModel] skipUpdate -> ${available.release.tagName}")
        settings.skippedUpdateTag = available.release.tagName
        dismissUpdate()
    }

    fun startDownload() {
        val available = _updateState.value as? UpdateUiState.Available ?: return
        val versionName = available.release.versionName
        _updateState.value = UpdateUiState.Downloading(versionName, 0f, 0L, available.asset.size)
        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            val expectedSha256 = updateRepository.fetchExpectedSha256(available.release, available.asset)
            var lastPercent = -1
            val result = updateRepository.download(available.asset, expectedSha256) { downloaded, total ->
                // 只在百分比变化时刷新状态，避免每个缓冲区都触发重组
                val percent = if (total > 0) (downloaded * 100 / total).toInt() else -1
                if (percent != lastPercent || total <= 0) {
                    lastPercent = percent
                    _updateState.value = UpdateUiState.Downloading(
                        versionName = versionName,
                        progress = if (total > 0) downloaded.toFloat() / total else 0f,
                        downloadedBytes = downloaded,
                        totalBytes = total
                    )
                }
            }
            result.onSuccess { file ->
                val signatureMatches = withContext(Dispatchers.IO) { verifyApkSignature(file) }
                Log.i(tag, "[MainViewModel] startDownload -> completed, signatureMatches=$signatureMatches")
                if (signatureMatches == false) {
                    _updateState.value = UpdateUiState.SignatureMismatch(versionName, available.release.htmlUrl)
                } else {
                    _updateState.value = UpdateUiState.ReadyToInstall(versionName, file)
                    emit(UiEvent.InstallApk(file))
                }
            }.onFailure { e ->
                if (e is CancellationException) return@onFailure
                _updateState.value = UpdateUiState.Hidden
                emit(UiEvent.ShowMessage(R.string.update_download_failed))
            }
        }
    }

    fun cancelDownload() {
        Log.i(tag, "[MainViewModel] cancelDownload")
        downloadJob?.cancel()
        downloadJob = null
        _updateState.value = UpdateUiState.Hidden
    }

    fun requestInstall() {
        val ready = _updateState.value as? UpdateUiState.ReadyToInstall ?: return
        emit(UiEvent.InstallApk(ready.file))
    }

    private fun emit(event: UiEvent) {
        _events.trySend(event)
    }

    private fun launchIo(operation: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "[MainViewModel] $operation -> failed", e)
            }
        }
    }

    companion object {
        private fun appVersionName(context: Context): String = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()

        fun provideFactory(appContext: Context): ViewModelProvider.Factory {
            val applicationContext = appContext.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
                        val database = BtLoggerDatabase.getDatabase(applicationContext)
                        return MainViewModel(
                            deviceDao = database.deviceDao(),
                            recordDao = database.connectionRecordDao(),
                            deviceWithRecordsDao = database.deviceWithRecordsDao(),
                            settings = AppSettings.get(applicationContext),
                            exportDir = File(applicationContext.filesDir, "exports"),
                            updateRepository = GitHubUpdateRepository(
                                downloadDir = File(applicationContext.cacheDir, "updates")
                            ),
                            appVersionName = appVersionName(applicationContext),
                            debuggable = ApkInstaller.isDebuggable(applicationContext),
                            verifyApkSignature = { ApkInstaller.signatureMatches(applicationContext, it) }
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}
