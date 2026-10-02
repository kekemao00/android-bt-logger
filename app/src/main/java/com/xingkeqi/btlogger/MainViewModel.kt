package com.xingkeqi.btlogger

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.blankj.utilcode.util.AppUtils
import com.pgyer.pgyersdk.PgyerSDKManager
import com.pgyer.pgyersdk.callback.CheckoutVersionCallBack
import com.pgyer.pgyersdk.model.CheckSoftModel
import com.xingkeqi.btlogger.data.BtLoggerDatabase
import com.xingkeqi.btlogger.data.DeviceDao
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.data.DeviceWithRecordsDao
import com.xingkeqi.btlogger.data.RecordDao
import com.xingkeqi.btlogger.data.RecordInfo
import com.xingkeqi.btlogger.data.RecordStats
import com.xingkeqi.btlogger.data.buildRecordTimeline
import com.xingkeqi.btlogger.data.computeRecordStats
import com.xingkeqi.btlogger.utils.AppSettings
import com.xingkeqi.btlogger.utils.MediaVolumeSnapshot
import com.xingkeqi.btlogger.utils.saveDataToSheet
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.rxkotlin.subscribeBy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import zlc.season.rxdownload4.download
import zlc.season.rxdownload4.file
import java.io.File

/**
 * 一次性 UI 事件（Toast、分享文件等），避免用 State 表达导致旋转屏幕后重复触发
 */
sealed interface UiEvent {
    data class ShowMessage(@StringRes val resId: Int, val args: List<Any> = emptyList()) : UiEvent
    data class ShareFile(val file: File) : UiEvent
}

/**
 * 应用内更新弹框状态
 */
sealed interface UpdateUiState {
    object Hidden : UpdateUiState
    data class Available(val version: CheckSoftModel) : UpdateUiState
    data class Downloading(
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateUiState
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
    private val exportDir: File
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
    private var downloadDisposable: Disposable? = null

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
     * @param manual 用户主动检查时才提示“已是最新/检查失败”，启动时静默检查
     */
    fun checkUpdate(manual: Boolean) {
        runCatching {
            PgyerSDKManager.checkSoftwareUpdate(object : CheckoutVersionCallBack {
                override fun onSuccess(version: CheckSoftModel?) {
                    Log.i(tag, "[MainViewModel] checkUpdate -> hasNew=${version?.isBuildHaveNewVersion}")
                    if (version?.isBuildHaveNewVersion == true) {
                        _updateState.value = UpdateUiState.Available(version)
                    } else if (manual) {
                        emit(UiEvent.ShowMessage(R.string.update_latest))
                    }
                }

                override fun onFail(message: String?) {
                    Log.w(tag, "[MainViewModel] checkUpdate -> failed: $message")
                    if (manual) emit(UiEvent.ShowMessage(R.string.update_check_failed))
                }
            })
        }.onFailure { e ->
            Log.e(tag, "[MainViewModel] checkUpdate -> exception", e)
            if (manual) emit(UiEvent.ShowMessage(R.string.update_check_failed))
        }
    }

    fun dismissUpdate() {
        _updateState.value = UpdateUiState.Hidden
    }

    fun startDownload() {
        val version = (_updateState.value as? UpdateUiState.Available)?.version ?: return
        val url = version.downloadURL
        if (url.isNullOrBlank()) {
            emit(UiEvent.ShowMessage(R.string.update_download_failed))
            dismissUpdate()
            return
        }
        _updateState.value = UpdateUiState.Downloading(0f, 0L, 0L)
        downloadDisposable?.dispose()
        downloadDisposable = url.download()
            .observeOn(AndroidSchedulers.mainThread())
            .subscribeBy(
                onNext = { progress ->
                    val total = progress.totalSize
                    _updateState.value = UpdateUiState.Downloading(
                        progress = if (total > 0) progress.downloadSize.toFloat() / total else 0f,
                        downloadedBytes = progress.downloadSize,
                        totalBytes = total
                    )
                },
                onComplete = {
                    Log.i(tag, "[MainViewModel] startDownload -> completed")
                    _updateState.value = UpdateUiState.Hidden
                    runCatching { AppUtils.installApp(url.file()) }
                        .onFailure { Log.e(tag, "[MainViewModel] installApk -> failed", it) }
                },
                onError = { e ->
                    Log.e(tag, "[MainViewModel] startDownload -> failed", e)
                    runCatching { PgyerSDKManager.reportException(Exception("下载新版本发生异常", e)) }
                    _updateState.value = UpdateUiState.Hidden
                    emit(UiEvent.ShowMessage(R.string.update_download_failed))
                }
            )
    }

    fun cancelDownload() {
        downloadDisposable?.dispose()
        downloadDisposable = null
        _updateState.value = UpdateUiState.Hidden
    }

    override fun onCleared() {
        downloadDisposable?.dispose()
        super.onCleared()
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
                            exportDir = File(applicationContext.filesDir, "exports")
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }
}
