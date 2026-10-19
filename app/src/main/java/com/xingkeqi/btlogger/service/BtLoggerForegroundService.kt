package com.xingkeqi.btlogger.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothAssignedNumbers
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import com.blankj.utilcode.util.ToastUtils
import com.xingkeqi.btlogger.MainActivity
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.data.BtLoggerDatabase
import com.xingkeqi.btlogger.data.BLUETOOTH_VERSION_UNKNOWN
import com.xingkeqi.btlogger.data.CODEC_LIST_UNAVAILABLE
import com.xingkeqi.btlogger.data.CODEC_UNKNOWN
import com.xingkeqi.btlogger.data.DEVICE_BATTERY_LEVEL_UNKNOWN
import com.xingkeqi.btlogger.data.Device
import com.xingkeqi.btlogger.data.DeviceConnectionRecord
import com.xingkeqi.btlogger.data.DeviceDao
import com.xingkeqi.btlogger.data.MessageEvent
import com.xingkeqi.btlogger.data.RecordEventType
import com.xingkeqi.btlogger.data.RecordDao
import com.xingkeqi.btlogger.utils.ACTION_BLUETOOTH_DEVICE_BATTERY_LEVEL_CHANGED
import com.xingkeqi.btlogger.utils.BluetoothBatteryUtils
import com.xingkeqi.btlogger.utils.BluetoothVersionProbeResult
import com.xingkeqi.btlogger.utils.BluetoothVersionSnapshot
import com.xingkeqi.btlogger.utils.BluetoothVersionUtils
import com.xingkeqi.btlogger.utils.AppSettings
import com.xingkeqi.btlogger.utils.HeadsetBatterySnapshot
import com.xingkeqi.btlogger.utils.applyFixedMediaVolume
import com.xingkeqi.btlogger.utils.MediaVolumeSnapshot
import com.xingkeqi.btlogger.utils.readMediaVolumeSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.ConcurrentHashMap

/**
 * 前台服务，用于保持应用存活并监听蓝牙连接状态
 *
 * 优势：
 * - 前台服务优先级高，不易被系统杀死
 * - Receiver 生命周期与 Service 绑定，独立于 Activity
 * - 通知栏常驻，用户可感知应用运行状态
 */
class BtLoggerForegroundService : Service() {

    private val tag = "BtLoggerService"
    private val latestCodecSnapshots = ConcurrentHashMap<String, CodecSnapshot>()
    private val connectedDevices = ConcurrentHashMap<String, ConnectedDeviceState>()
    private val latestHeadsetBatteryLevels = ConcurrentHashMap<String, Int>()
    /** 最近一次落库的耳机电量，用于判断是否需要写入“电量更新” */
    private val latestPersistedHeadsetLevels = ConcurrentHashMap<String, Int>()
    /** 最近一次写入带电量记录的时间，定时采样据此判断是否到期 */
    private val lastBatteryPersistAt = ConcurrentHashMap<String, Long>()
    private val fixedVolumeJobs = ConcurrentHashMap<String, Job>()
    private val periodicSampleJobs = ConcurrentHashMap<String, Job>()
    private val appSettings by lazy { AppSettings.get(applicationContext) }
    private val codecSnapshotMutex = Mutex()
    private val batterySnapshotMutex = Mutex()
    private val batteryProbeManager by lazy {
        BluetoothBatteryProbeManager(
            context = applicationContext,
            scope = serviceScope
        ) { device, snapshot ->
            launchSafely("batteryProbeResult") {
                handleHeadsetBatteryChanged(device, snapshot)
            }
        }
    }
    private val versionProbeManager by lazy {
        BluetoothVersionProbeManager(
            context = applicationContext,
            scope = serviceScope
        ) { device, result ->
            launchSafely("versionProbeResult") {
                handleBluetoothVersionProbeResult(device, result)
            }
        }
    }
    private val versionAdvertisementProbeManager by lazy {
        BluetoothVersionAdvertisementProbeManager(
            context = applicationContext,
            scope = serviceScope
        ) { device, result ->
            launchSafely("versionAdvertisementProbeResult") {
                handleBluetoothVersionProbeResult(device, result)
            }
        }
    }

    @Volatile
    private var latestPhoneBatteryLevel: Int = 0

    private val database by lazy { BtLoggerDatabase.getDatabase(applicationContext) }
    private val deviceDao: DeviceDao by lazy { database.deviceDao() }
    private val recordDao: RecordDao by lazy { database.connectionRecordDao() }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val btReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            runSafely("onReceive action=${intent.action}") {
                handleBluetoothBroadcast(intent)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBluetoothBroadcast(intent: Intent) {
        when (intent.action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
                if (state != BluetoothProfile.STATE_CONNECTED && state != BluetoothProfile.STATE_DISCONNECTED) {
                    return
                }

                val bluetoothDevice =
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (bluetoothDevice == null) {
                    Log.w(tag, "[BtLoggerForegroundService] onReceive -> BluetoothDevice is null")
                    return
                }

                handleConnectionStateChanged(bluetoothDevice, state)
            }

            ACTION_A2DP_CODEC_CONFIG_CHANGED -> {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                    Log.w(tag, "[BtLoggerForegroundService] onReceive -> codec action ignored below API 28")
                    return
                }

                val bluetoothDevice =
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                if (bluetoothDevice == null) {
                    Log.w(tag, "[BtLoggerForegroundService] onReceive -> codec device is null")
                    return
                }

                handleCodecConfigChanged(bluetoothDevice, intent)
            }

            Intent.ACTION_BATTERY_CHANGED -> {
                val phoneBatteryLevel = BluetoothBatteryUtils.extractPhoneBatteryLevel(intent) ?: return
                handlePhoneBatteryChanged(phoneBatteryLevel)
            }

            ACTION_BLUETOOTH_DEVICE_BATTERY_LEVEL_CHANGED,
            BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> {
                val bluetoothDevice = BluetoothBatteryUtils.extractBluetoothDevice(intent)
                if (bluetoothDevice == null) {
                    Log.w(
                        tag,
                        "[BtLoggerForegroundService] onReceive -> battery device missing for action=${intent.action}"
                    )
                    return
                }

                val snapshot = BluetoothBatteryUtils.extractHeadsetBatterySnapshot(intent) ?: return
                launchSafely("headsetBatteryChanged") {
                    handleHeadsetBatteryChanged(bluetoothDevice, snapshot)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        latestPhoneBatteryLevel = getPhoneBatteryLevelSafely()
        Log.i(tag, "onCreate: 前台服务启动")

        val intentFilter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(ACTION_A2DP_CODEC_CONFIG_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(ACTION_BLUETOOTH_DEVICE_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT)
            // Why: 系统转发 HFP 厂商事件时会附带 company-id 类别，过滤器里不声明对应类别就永远匹配不到，
            // +IPHONEACCEV（Apple）与 +XEVENT（Plantronics）两条耳机电量来源过去因此一直收不到
            addCategory(vendorEventCategory(BluetoothAssignedNumbers.APPLE))
            addCategory(vendorEventCategory(BluetoothAssignedNumbers.PLANTRONICS))
        }
        registerReceiver(btReceiver, intentFilter)
        Log.i(tag, "onCreate: 已注册蓝牙与电量广播接收器")
        syncAlreadyConnectedDevices()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification(connectedDeviceNames()))
        Log.i(tag, "onStartCommand: 前台服务已启动")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        runSafely("stopAllProbes") {
            batteryProbeManager.stopAll()
            versionProbeManager.stopAll()
            versionAdvertisementProbeManager.stopAll()
        }
        fixedVolumeJobs.values.forEach { it.cancel() }
        fixedVolumeJobs.clear()
        periodicSampleJobs.values.forEach { it.cancel() }
        periodicSampleJobs.clear()
        serviceScope.cancel()
        try {
            unregisterReceiver(btReceiver)
            Log.i(tag, "onDestroy: 已取消注册蓝牙广播接收器")
        } catch (e: Exception) {
            Log.w(tag, "onDestroy: 取消注册失败", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 连接状态变化必须立即落库，同时把当前已知的耳机电量快照一并写入，避免详情页只显示手机电量。
     */
    @SuppressLint("MissingPermission")
    private fun handleConnectionStateChanged(
        bluetoothDevice: BluetoothDevice,
        state: Int,
        applyFixedVolume: Boolean = true
    ) {
        val address = readDeviceAddressOrNull(bluetoothDevice) ?: return
        val isConnected = state == BluetoothProfile.STATE_CONNECTED
        val connectStatus = if (isConnected) {
            BluetoothA2dp.STATE_CONNECTED
        } else {
            BluetoothA2dp.STATE_DISCONNECTED
        }

        if (isConnected) {
            preloadHeadsetBatterySafely(bluetoothDevice, address)
        }

        launchSafely("connectionStateChanged[$address]") {
            val existingDevice = deviceDao.getDeviceByMacSnapshot(address)
            val deviceState = buildConnectedDeviceState(bluetoothDevice, existingDevice)
            if (isConnected) {
                connectedDevices[address] = deviceState
            }
            val codecSnapshot = resolveCodecSnapshot(address)
            val phoneBatteryLevel = getPhoneBatteryLevelSafely().also { latestPhoneBatteryLevel = it }
            val headsetBatteryLevel = resolveHeadsetBatteryLevel(address)
            val volumeSnapshot = readMediaVolumeSnapshotSafely()
            val record = DeviceConnectionRecord(
                deviceMac = address,
                timestamp = System.currentTimeMillis(),
                connectState = connectStatus,
                batteryLevel = phoneBatteryLevel,
                headsetBatteryLevel = headsetBatteryLevel,
                isPlaying = isPlayingSafely(),
                volume = volumeSnapshot.percent,
                eventType = if (isConnected) RecordEventType.CONNECTED else RecordEventType.DISCONNECTED,
                phoneSupportedCodecs = codecSnapshot.phoneSupportedCodecs,
                negotiableCodecs = codecSnapshot.negotiableCodecs,
                activeCodec = codecSnapshot.activeCodec
            )
            val device = buildDevice(deviceState, codecSnapshot)

            Log.i(
                tag,
                "[BtLoggerForegroundService] handleConnectionStateChanged -> state=${record.eventType}, device=${device.name}[${device.mac}], activeCodec=${codecSnapshot.activeCodec}, phoneBattery=$phoneBatteryLevel, headsetBattery=$headsetBatteryLevel, volume=${volumeSnapshot.percent}(${volumeSnapshot.currentLevel}/${volumeSnapshot.maxLevel}), bluetoothConnected=${volumeSnapshot.hasBluetoothOutput}"
            )
            if (persistDeviceAndRecord(device, record)) {
                rememberPersistedBattery(address, headsetBatteryLevel)
            }
            showConnectionToastSafely(device.name, isConnected)

            if (isConnected) {
                if (applyFixedVolume) {
                    scheduleFixedVolume(address)
                }
                startConnectedDeviceProbes(bluetoothDevice, deviceState)
                startPeriodicSampling(address)
            } else {
                fixedVolumeJobs.remove(address)?.cancel()
                periodicSampleJobs.remove(address)?.cancel()
                stopConnectedDeviceProbes(address)
                connectedDevices.remove(address)
                latestPersistedHeadsetLevels.remove(address)
                lastBatteryPersistAt.remove(address)
                // Why: 断开期间耳机可能已放回充电盒充电，下次连接沿用旧值会让新一段曲线从错误的电量起步；
                // 清掉后新连接记录先记为未上报，等首个真实电量到达再回填
                latestHeadsetBatteryLevels.remove(address)
            }
            refreshNotificationSafely()
        }
    }

    /**
     * 固定音量由前台服务执行。
     *
     * Why:
     * 过去该逻辑挂在 Activity 的 EventBus 回调上，App 界面关闭后（最常见的使用场景）不会生效。
     */
    private fun scheduleFixedVolume(address: String) {
        if (!appSettings.fixedVolumeEnabled.value) return
        val targetPercent = appSettings.fixedVolumePercent.value
        fixedVolumeJobs.remove(address)?.cancel()
        fixedVolumeJobs[address] = serviceScope.launch {
            try {
                val applied = applyFixedMediaVolume(applicationContext, targetPercent)
                Log.i(
                    tag,
                    "[BtLoggerForegroundService] scheduleFixedVolume -> device=$address, target=$targetPercent%, applied=$applied"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(tag, "[BtLoggerForegroundService] scheduleFixedVolume -> failed: device=$address", e)
            }
        }
    }

    /**
     * 服务（重新）启动时同步系统中已连接的 A2DP 设备。
     *
     * Why:
     * 服务被系统回收后以 START_STICKY 重建、或开机前耳机已连上时，不会再收到连接广播，
     * 导致电量采样与版本探测全部失效。这里主动查询一次已连接设备：
     * 数据库最新状态已是“已连接”的只恢复内存跟踪；否则补记一条连接记录。
     */
    @SuppressLint("MissingPermission")
    private fun syncAlreadyConnectedDevices() {
        runSafely("syncAlreadyConnectedDevices") {
            @Suppress("DEPRECATION")
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@runSafely
            if (!adapter.isEnabled) return@runSafely
            adapter.getProfileProxy(applicationContext, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    runSafely("syncAlreadyConnectedDevices.onServiceConnected") {
                        val devices = proxy.connectedDevices.orEmpty()
                        Log.i(
                            tag,
                            "[BtLoggerForegroundService] syncAlreadyConnectedDevices -> connected=${devices.size}"
                        )
                        devices.forEach { restoreConnectedDevice(it) }
                    }
                    runSafely("syncAlreadyConnectedDevices.closeProxy") {
                        adapter.closeProfileProxy(profile, proxy)
                    }
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            }, BluetoothProfile.A2DP)
        }
    }

    private fun restoreConnectedDevice(bluetoothDevice: BluetoothDevice) {
        val address = readDeviceAddressOrNull(bluetoothDevice) ?: return
        if (connectedDevices.containsKey(address)) return
        launchSafely("restoreConnectedDevice[$address]") {
            val latestStateRecord = recordDao.getLatestConnectionStateRecord(address)
            if (latestStateRecord?.connectState == BluetoothA2dp.STATE_CONNECTED) {
                val existingDevice = deviceDao.getDeviceByMacSnapshot(address)
                val deviceState = buildConnectedDeviceState(bluetoothDevice, existingDevice)
                connectedDevices[address] = deviceState
                preloadHeadsetBatterySafely(bluetoothDevice, address)
                startConnectedDeviceProbes(bluetoothDevice, deviceState)
                startPeriodicSampling(address)
                refreshNotificationSafely()
            } else {
                handleConnectionStateChanged(
                    bluetoothDevice = bluetoothDevice,
                    state = BluetoothProfile.STATE_CONNECTED,
                    applyFixedVolume = false
                )
            }
        }
    }

    private fun connectedDeviceNames(): List<String> =
        connectedDevices.values.map { state -> state.name.ifBlank { state.mac } }

    private fun refreshNotificationSafely() {
        runSafely("refreshNotification") {
            val manager = getSystemService(NotificationManager::class.java) ?: return@runSafely
            manager.notify(NOTIFICATION_ID, createNotification(connectedDeviceNames()))
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @SuppressLint("MissingPermission")
    private fun handleCodecConfigChanged(bluetoothDevice: BluetoothDevice, intent: Intent) {
        val address = readDeviceAddressOrNull(bluetoothDevice) ?: return
        val parsedSnapshot = BluetoothCodecFormatter.parseCodecStatus(intent)
        if (parsedSnapshot == null) {
            Log.w(
                tag,
                "[BtLoggerForegroundService] handleCodecConfigChanged -> codec status missing for $address"
            )
            return
        }

        launchSafely("codecConfigChanged[$address]") {
            codecSnapshotMutex.withLock {
                val previousSnapshot = resolveCodecSnapshot(address)
                val resolvedSnapshot = BluetoothCodecFormatter.normalizeSnapshot(
                    parsedSnapshot.copy(updatedAt = System.currentTimeMillis())
                )
                val hasSnapshotChanged =
                    BluetoothCodecFormatter.hasCodecSnapshotChanged(previousSnapshot, resolvedSnapshot)
                val shouldPersistHistory =
                    BluetoothCodecFormatter.shouldPersistCodecHistory(previousSnapshot, resolvedSnapshot)

                if (!hasSnapshotChanged) {
                    latestCodecSnapshots[address] = resolvedSnapshot
                    Log.d(
                        tag,
                        "[BtLoggerForegroundService] handleCodecConfigChanged -> skip duplicate snapshot: device=${readDeviceNameOrEmpty(bluetoothDevice)}[$address], activeCodec=${resolvedSnapshot.activeCodec}"
                    )
                    return@withLock
                }

                val existingDevice = deviceDao.getDeviceByMacSnapshot(address)
                val deviceState = buildConnectedDeviceState(bluetoothDevice, existingDevice)
                connectedDevices[address] = deviceState
                val device = buildDevice(
                    deviceState = deviceState,
                    codecSnapshot = resolvedSnapshot,
                    fallbackRssi = existingDevice?.rssi
                )

                if (!shouldPersistHistory) {
                    Log.i(
                        tag,
                        "[BtLoggerForegroundService] handleCodecConfigChanged -> refresh latest codec only: device=${device.name}[${device.mac}], activeCodec=${previousSnapshot.activeCodec} -> ${resolvedSnapshot.activeCodec}"
                    )
                    if (persistDevice(device)) {
                        latestCodecSnapshots[address] = resolvedSnapshot
                        backfillCurrentConnectionRecords(
                            address = address,
                            codecSnapshot = resolvedSnapshot,
                            reason = "codec-refresh"
                        )
                    }
                    return@withLock
                }

                val volumeSnapshot = readMediaVolumeSnapshotSafely()
                val phoneBatteryLevel = getPhoneBatteryLevelSafely().also { latestPhoneBatteryLevel = it }
                val headsetBatteryLevel = resolveHeadsetBatteryLevel(address)
                val record = DeviceConnectionRecord(
                    deviceMac = address,
                    timestamp = resolvedSnapshot.updatedAt,
                    connectState = BluetoothA2dp.STATE_CONNECTED,
                    batteryLevel = phoneBatteryLevel,
                    headsetBatteryLevel = headsetBatteryLevel,
                    volume = volumeSnapshot.percent,
                    isPlaying = isPlayingSafely(),
                    eventType = RecordEventType.CODEC_CHANGED,
                    phoneSupportedCodecs = resolvedSnapshot.phoneSupportedCodecs,
                    negotiableCodecs = resolvedSnapshot.negotiableCodecs,
                    activeCodec = resolvedSnapshot.activeCodec
                )

                Log.i(
                    tag,
                    "[BtLoggerForegroundService] handleCodecConfigChanged -> persist codec change: device=${device.name}[${device.mac}], activeCodec=${previousSnapshot.activeCodec} -> ${resolvedSnapshot.activeCodec}, negotiable=${resolvedSnapshot.negotiableCodecs}, phoneBattery=$phoneBatteryLevel, headsetBattery=$headsetBatteryLevel, volume=${volumeSnapshot.percent}(${volumeSnapshot.currentLevel}/${volumeSnapshot.maxLevel}), bluetoothConnected=${volumeSnapshot.hasBluetoothOutput}"
                )
                if (persistDeviceAndRecord(device, record)) {
                    latestCodecSnapshots[address] = resolvedSnapshot
                    rememberPersistedBattery(address, headsetBatteryLevel)
                }
                startConnectedDeviceProbes(bluetoothDevice, deviceState)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleBluetoothVersionProbeResult(
        bluetoothDevice: BluetoothDevice,
        result: BluetoothVersionProbeResult
    ) {
        val address = readDeviceAddressOrNull(bluetoothDevice) ?: return
        val existingDevice = deviceDao.getDeviceByMacSnapshot(address)
        val previousBluetoothVersion = existingDevice?.bluetoothVersion ?: BLUETOOTH_VERSION_UNKNOWN
        val mergedBluetoothVersion = BluetoothVersionUtils.mergeBluetoothVersion(
            persistedVersion = previousBluetoothVersion,
            resolvedVersion = result.versionSnapshot.version
        )
        Log.i(
            tag,
            "[BtLoggerForegroundService] handleBluetoothVersionProbeResult -> device=${readDeviceNameOrEmpty(bluetoothDevice)}[$address], previous=$previousBluetoothVersion, resolved=${result.versionSnapshot.version}, merged=$mergedBluetoothVersion, source=${result.versionSnapshot.source}, info=${result.deviceInformation.summary()}, adv=${result.advertisementSnapshot.summary()}"
        )
        if (mergedBluetoothVersion == previousBluetoothVersion) {
            return
        }

        val baseState = connectedDevices[address] ?: buildConnectedDeviceState(bluetoothDevice, existingDevice)
        val updatedState = baseState.copy(bluetoothVersion = mergedBluetoothVersion)
        connectedDevices[address] = updatedState
        val device = buildDevice(
            deviceState = updatedState,
            codecSnapshot = resolveCodecSnapshot(address),
            fallbackRssi = existingDevice?.rssi
        )
        persistDevice(device)
    }

    /**
     * 手机电量只更新缓存，不再单独落库。
     *
     * Why:
     * 续航测试关注的是耳机电量；过去手机电量每变化 1% 就给每台已连接设备各写一条记录，
     * 历史与曲线里大部分点都是手机电量。现在手机电量只随耳机电量更新、定时采样和连接事件一起记录。
     */
    private fun handlePhoneBatteryChanged(phoneBatteryLevel: Int) {
        val previousPhoneBatteryLevel = latestPhoneBatteryLevel
        latestPhoneBatteryLevel = phoneBatteryLevel
        if (previousPhoneBatteryLevel != phoneBatteryLevel) {
            Log.d(
                tag,
                "[BtLoggerForegroundService] handlePhoneBatteryChanged -> cache phone battery: $previousPhoneBatteryLevel -> $phoneBatteryLevel"
            )
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleHeadsetBatteryChanged(
        bluetoothDevice: BluetoothDevice,
        snapshot: HeadsetBatterySnapshot
    ) {
        val address = readDeviceAddressOrNull(bluetoothDevice) ?: return
        val previousHeadsetBatteryLevel = latestHeadsetBatteryLevels.put(address, snapshot.level)
        if (previousHeadsetBatteryLevel == snapshot.level) {
            Log.d(
                tag,
                "[BtLoggerForegroundService] handleHeadsetBatteryChanged -> skip duplicate headset battery: device=${readDeviceNameOrEmpty(bluetoothDevice)}[$address], level=${snapshot.level}, source=${snapshot.source}"
            )
            return
        }

        Log.i(
            tag,
            "[BtLoggerForegroundService] handleHeadsetBatteryChanged -> device=${readDeviceNameOrEmpty(bluetoothDevice)}[$address], battery=${previousHeadsetBatteryLevel ?: DEVICE_BATTERY_LEVEL_UNKNOWN} -> ${snapshot.level}, source=${snapshot.source}"
        )

        if (!connectedDevices.containsKey(address)) {
            Log.d(
                tag,
                "[BtLoggerForegroundService] handleHeadsetBatteryChanged -> device not tracked as connected, cache only: device=$address, level=${snapshot.level}"
            )
            return
        }

        batterySnapshotMutex.withLock {
            backfillCurrentConnectionRecords(
                address = address,
                headsetBatteryLevel = snapshot.level,
                reason = "headset-battery"
            )
            persistBatterySample(address = address, reason = snapshot.source)
        }
    }

    /**
     * 写入一条电量记录。
     *
     * @param periodic 定时采样：耳机电量未变化也写入（事件为“定时采样”），否则只在耳机电量变化时写入“电量更新”
     */
    private suspend fun persistBatterySample(address: String, reason: String, periodic: Boolean = false) {
        val deviceState = connectedDevices[address]
        if (deviceState == null) {
            Log.d(
                tag,
                "[BtLoggerForegroundService] persistBatterySample -> skip because device is no longer connected: device=$address, reason=$reason"
            )
            return
        }

        val headsetBatteryLevel = resolveHeadsetBatteryLevel(address)
        val headsetChanged = latestPersistedHeadsetLevels[address] != headsetBatteryLevel
        if (!periodic && !headsetChanged) {
            Log.d(
                tag,
                "[BtLoggerForegroundService] persistBatterySample -> skip unchanged headset battery: device=$address, reason=$reason, headsetBattery=$headsetBatteryLevel"
            )
            return
        }

        val phoneBatteryLevel = getPhoneBatteryLevelSafely().also { latestPhoneBatteryLevel = it }
        val codecSnapshot = resolveCodecSnapshot(address)
        val volumeSnapshot = readMediaVolumeSnapshotSafely()
        val device = buildDevice(deviceState, codecSnapshot)
        val record = DeviceConnectionRecord(
            deviceMac = address,
            timestamp = System.currentTimeMillis(),
            connectState = BluetoothA2dp.STATE_CONNECTED,
            batteryLevel = phoneBatteryLevel,
            headsetBatteryLevel = headsetBatteryLevel,
            volume = volumeSnapshot.percent,
            isPlaying = isPlayingSafely(),
            eventType = if (headsetChanged) RecordEventType.BATTERY_CHANGED else RecordEventType.BATTERY_SAMPLE,
            phoneSupportedCodecs = codecSnapshot.phoneSupportedCodecs,
            negotiableCodecs = codecSnapshot.negotiableCodecs,
            activeCodec = codecSnapshot.activeCodec
        )

        Log.i(
            tag,
            "[BtLoggerForegroundService] persistBatterySample -> persist ${record.eventType}: device=${device.name}[${device.mac}], reason=$reason, headsetBattery=$headsetBatteryLevel, phoneBattery=$phoneBatteryLevel, activeCodec=${codecSnapshot.activeCodec}, volume=${volumeSnapshot.percent}(${volumeSnapshot.currentLevel}/${volumeSnapshot.maxLevel})"
        )
        if (persistDeviceAndRecord(device, record)) {
            rememberPersistedBattery(address, headsetBatteryLevel)
        }
    }

    /**
     * 连接期间按设置的间隔写入“定时采样”。
     *
     * Why:
     * 耳机电量多为 10% 一档，两次变化之间可能隔一小时以上；只记变化时，曲线上看不出这段时间
     * 是电量稳定还是记录被系统中断，也拿不到期间的音量与播放状态。定时采样补上这些点，
     * 间隔从“最近一次带电量的记录”起算，变化记录密集时不会重复写入。
     */
    private fun startPeriodicSampling(address: String) {
        if (periodicSampleJobs[address]?.isActive == true) return
        periodicSampleJobs[address] = serviceScope.launch {
            while (isActive) {
                delay(PERIODIC_SAMPLE_TICK_MS)
                try {
                    persistPeriodicSampleIfDue(address)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(tag, "[BtLoggerForegroundService] startPeriodicSampling -> sample failed: device=$address", e)
                }
            }
        }
        Log.i(tag, "[BtLoggerForegroundService] startPeriodicSampling -> started: device=$address")
    }

    private suspend fun persistPeriodicSampleIfDue(address: String) {
        val intervalMillis = appSettings.batterySampleIntervalMinutes.value * 60_000L
        if (intervalMillis <= 0L || !connectedDevices.containsKey(address)) return
        // 只围绕耳机电量采样：耳机从未上报（如车机）时不写入只有手机电量的记录
        if (resolveHeadsetBatteryLevel(address) == DEVICE_BATTERY_LEVEL_UNKNOWN) return
        batterySnapshotMutex.withLock {
            val elapsed = System.currentTimeMillis() - (lastBatteryPersistAt[address] ?: 0L)
            if (elapsed < intervalMillis) return@withLock
            persistBatterySample(address = address, reason = "periodic", periodic = true)
        }
    }

    private suspend fun resolveCodecSnapshot(address: String): CodecSnapshot {
        latestCodecSnapshots[address]?.let { return it }
        val existingDevice = deviceDao.getDeviceByMacSnapshot(address) ?: return BluetoothCodecFormatter.emptySnapshot()
        return BluetoothCodecFormatter.normalizeSnapshot(
            CodecSnapshot(
                phoneSupportedCodecs = existingDevice.latestPhoneSupportedCodecs,
                negotiableCodecs = existingDevice.latestNegotiableCodecs,
                activeCodec = existingDevice.latestActiveCodec,
                updatedAt = existingDevice.latestCodecUpdatedAt
            )
        ).also {
            latestCodecSnapshots[address] = it
        }
    }

    private fun resolveHeadsetBatteryLevel(address: String): Int {
        return latestHeadsetBatteryLevels[address] ?: DEVICE_BATTERY_LEVEL_UNKNOWN
    }

    @SuppressLint("MissingPermission")
    private fun buildConnectedDeviceState(
        bluetoothDevice: BluetoothDevice,
        existingDevice: Device? = null
    ): ConnectedDeviceState {
        val previousBluetoothVersion = existingDevice?.bluetoothVersion ?: BLUETOOTH_VERSION_UNKNOWN
        val address = readDeviceAddressOrNull(bluetoothDevice) ?: existingDevice?.mac.orEmpty()
        val deviceName = readDeviceNameOrEmpty(bluetoothDevice).ifBlank { existingDevice?.name.orEmpty() }
        val resolvedBluetoothVersion = runCatching {
            BluetoothVersionUtils.resolveBluetoothVersion(
                device = bluetoothDevice,
                persistedVersion = previousBluetoothVersion
            )
        }.getOrElse { throwable ->
            Log.e(
                tag,
                "[BtLoggerForegroundService] buildConnectedDeviceState -> bluetooth version resolve failed: device=$address",
                throwable
            )
            BluetoothVersionSnapshot(
                version = previousBluetoothVersion,
                source = "fallback:error"
            )
        }
        val mergedBluetoothVersion = BluetoothVersionUtils.mergeBluetoothVersion(
            persistedVersion = previousBluetoothVersion,
            resolvedVersion = resolvedBluetoothVersion.version
        )
        if (previousBluetoothVersion != mergedBluetoothVersion || resolvedBluetoothVersion.source != "cache") {
            Log.i(
                tag,
                "[BtLoggerForegroundService] buildConnectedDeviceState -> BluetoothVersion: device=$deviceName[$address], previous=$previousBluetoothVersion, resolved=$mergedBluetoothVersion, source=${resolvedBluetoothVersion.source}"
            )
        }
        return ConnectedDeviceState(
            mac = address,
            name = deviceName,
            bondState = readDeviceBondState(bluetoothDevice, existingDevice),
            alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                readDeviceAliasOrEmpty(bluetoothDevice).ifBlank { existingDevice?.alias.orEmpty() }
            } else {
                existingDevice?.alias.orEmpty()
            },
            deviceType = readDeviceType(bluetoothDevice).takeUnless {
                it == BluetoothDevice.DEVICE_TYPE_UNKNOWN
            } ?: existingDevice?.deviceType ?: BluetoothDevice.DEVICE_TYPE_UNKNOWN,
            bluetoothVersion = mergedBluetoothVersion,
            uuids = readDeviceUuidsOrEmpty(bluetoothDevice).ifBlank { existingDevice?.uuids.orEmpty() }
        )
    }

    private fun buildDevice(
        deviceState: ConnectedDeviceState,
        codecSnapshot: CodecSnapshot,
        fallbackRssi: Short? = null
    ): Device {
        return Device(
            mac = deviceState.mac,
            name = deviceState.name,
            bondState = deviceState.bondState,
            rssi = fallbackRssi,
            alias = deviceState.alias,
            deviceType = deviceState.deviceType,
            bluetoothVersion = deviceState.bluetoothVersion,
            uuids = deviceState.uuids,
            latestPhoneSupportedCodecs = codecSnapshot.phoneSupportedCodecs,
            latestNegotiableCodecs = codecSnapshot.negotiableCodecs,
            latestActiveCodec = codecSnapshot.activeCodec,
            latestCodecUpdatedAt = codecSnapshot.updatedAt
        )
    }

    private fun rememberPersistedBattery(address: String, headsetBatteryLevel: Int) {
        latestPersistedHeadsetLevels[address] = headsetBatteryLevel
        lastBatteryPersistAt[address] = System.currentTimeMillis()
    }

    private suspend fun backfillCurrentConnectionRecords(
        address: String,
        codecSnapshot: CodecSnapshot? = null,
        headsetBatteryLevel: Int? = null,
        reason: String
    ) {
        val latestConnectedRecord = recordDao.getLatestRecordSnapshotByConnectState(
            deviceMac = address,
            connectState = BluetoothA2dp.STATE_CONNECTED
        ) ?: return
        val latestConnectedStateRecord = recordDao.getLatestRecordSnapshotByEventAndState(
            deviceMac = address,
            eventType = RecordEventType.CONNECTED,
            connectState = BluetoothA2dp.STATE_CONNECTED
        )

        sequenceOf(latestConnectedRecord, latestConnectedStateRecord)
            .filterNotNull()
            .distinctBy { it.id }
            .forEach { record ->
                val updatedRecord = mergeConnectionRecordSnapshot(
                    baseRecord = record,
                    codecSnapshot = codecSnapshot,
                    headsetBatteryLevel = headsetBatteryLevel
                )
                if (updatedRecord == record) {
                    return@forEach
                }

                recordDao.update(updatedRecord)
                Log.i(
                    tag,
                    "[BtLoggerForegroundService] backfillCurrentConnectionRecords -> updated: reason=$reason, device=$address, recordId=${record.id}, event=${record.eventType}, activeCodec=${record.activeCodec} -> ${updatedRecord.activeCodec}, headsetBattery=${record.headsetBatteryLevel} -> ${updatedRecord.headsetBatteryLevel}"
                )
            }
    }

    private fun mergeConnectionRecordSnapshot(
        baseRecord: DeviceConnectionRecord,
        codecSnapshot: CodecSnapshot? = null,
        headsetBatteryLevel: Int? = null
    ): DeviceConnectionRecord {
        return baseRecord.copy(
            phoneSupportedCodecs = mergeCodecListValue(
                currentValue = baseRecord.phoneSupportedCodecs,
                incomingValue = codecSnapshot?.phoneSupportedCodecs
            ),
            negotiableCodecs = mergeCodecListValue(
                currentValue = baseRecord.negotiableCodecs,
                incomingValue = codecSnapshot?.negotiableCodecs
            ),
            activeCodec = mergeCodecValue(
                currentValue = baseRecord.activeCodec,
                incomingValue = codecSnapshot?.activeCodec
            ),
            headsetBatteryLevel = mergeBatteryValue(
                currentValue = baseRecord.headsetBatteryLevel,
                incomingValue = headsetBatteryLevel
            )
        )
    }

    private fun mergeCodecListValue(
        currentValue: String,
        incomingValue: String?
    ): String {
        return incomingValue
            ?.takeUnless { it == CODEC_LIST_UNAVAILABLE }
            ?: currentValue
    }

    private fun mergeCodecValue(
        currentValue: String,
        incomingValue: String?
    ): String {
        return incomingValue
            ?.takeUnless { it == CODEC_UNKNOWN }
            ?: currentValue
    }

    /**
     * 只给“未上报”的记录补电量。
     *
     * Why:
     * 过去每次耳机电量变化都会把最近一条采样和本次连接记录改写成新值：上一条采样的电量被提前，
     * 连接起始电量也跟着变成最新值，曲线整体错位一档、掉电量算不出来。已记录的值必须保持原样。
     */
    private fun mergeBatteryValue(
        currentValue: Int,
        incomingValue: Int?
    ): Int {
        if (currentValue in 0..100) return currentValue
        return incomingValue?.takeIf { it in 0..100 } ?: currentValue
    }

    private suspend fun persistDevice(device: Device): Boolean {
        return try {
            deviceDao.insert(device)
            Log.d(
                tag,
                "[BtLoggerForegroundService] persistDevice -> Codec/BluetoothVersion: device=${device.mac}, activeCodec=${device.latestActiveCodec}, bluetoothVersion=${device.bluetoothVersion}"
            )
            true
        } catch (e: Exception) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] persistDevice -> failed for ${device.mac}",
                e
            )
            false
        }
    }

    private suspend fun persistDeviceAndRecord(device: Device, record: DeviceConnectionRecord): Boolean {
        return try {
            deviceDao.insert(device)
            recordDao.insert(record)
            Log.d(
                tag,
                "[BtLoggerForegroundService] persistDeviceAndRecord -> Record/BluetoothVersion: event=${record.eventType}, device=${device.mac}, bluetoothVersion=${device.bluetoothVersion}"
            )
            EventBus.getDefault().post(MessageEvent("ADD_RECORD", device, record))
            true
        } catch (e: Exception) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] persistDeviceAndRecord -> failed for ${device.mac}",
                e
            )
            false
        }
    }

    private fun createNotification(connectedNames: List<String>): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val contentText = if (connectedNames.isEmpty()) {
            getString(R.string.notification_waiting_device)
        } else {
            getString(R.string.notification_connected_devices, connectedNames.joinToString("、"))
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setOnlyAlertOnce(true)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun launchSafely(
        operation: String,
        block: suspend CoroutineScope.() -> Unit
    ) {
        serviceScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(
                    tag,
                    "[BtLoggerForegroundService] launchSafely -> operation failed: $operation",
                    e
                )
            } catch (e: LinkageError) {
                Log.e(
                    tag,
                    "[BtLoggerForegroundService] launchSafely -> platform API unavailable: $operation",
                    e
                )
            }
        }
    }

    private fun runSafely(operation: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] runSafely -> operation failed: $operation",
                e
            )
        } catch (e: LinkageError) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] runSafely -> platform API unavailable: $operation",
                e
            )
        }
    }

    /**
     * Why:
     * 系统广播主链路只需要记录连接/断开，耳机电量、蓝牙版本等探测失败时必须降级，
     * 不能反向影响基础连接日志。
     */
    private fun startConnectedDeviceProbes(
        bluetoothDevice: BluetoothDevice,
        deviceState: ConnectedDeviceState
    ) {
        runSafely("startConnectedDeviceProbes[${deviceState.mac}]") {
            batteryProbeManager.startMonitoring(bluetoothDevice)
        }
        runSafely("startVersionProbe[${deviceState.mac}]") {
            versionProbeManager.startMonitoring(
                device = bluetoothDevice,
                persistedVersion = deviceState.bluetoothVersion
            )
        }
        runSafely("startVersionAdvertisementProbe[${deviceState.mac}]") {
            versionAdvertisementProbeManager.startMonitoring(
                device = bluetoothDevice,
                persistedVersion = deviceState.bluetoothVersion
            )
        }
    }

    private fun stopConnectedDeviceProbes(address: String) {
        runSafely("stopBatteryProbe[$address]") {
            batteryProbeManager.stopMonitoring(address)
        }
        runSafely("stopVersionProbe[$address]") {
            versionProbeManager.stopMonitoring(address)
        }
        runSafely("stopVersionAdvertisementProbe[$address]") {
            versionAdvertisementProbeManager.stopMonitoring(address)
        }
    }

    private fun preloadHeadsetBatterySafely(
        bluetoothDevice: BluetoothDevice,
        address: String
    ) {
        try {
            BluetoothBatteryUtils.readBatteryLevelReflectively(bluetoothDevice)?.let { snapshot ->
                latestHeadsetBatteryLevels[address] = snapshot.level
                Log.i(
                    tag,
                    "[BtLoggerForegroundService] preloadHeadsetBatterySafely -> reflection battery: device=${readDeviceNameOrEmpty(bluetoothDevice)}[$address], battery=${snapshot.level}"
                )
            }
        } catch (e: Exception) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] preloadHeadsetBatterySafely -> failed: device=$address",
                e
            )
        } catch (e: LinkageError) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] preloadHeadsetBatterySafely -> platform API unavailable: device=$address",
                e
            )
        }
    }

    private fun showConnectionToastSafely(deviceName: String, isConnected: Boolean) {
        try {
            val state = getString(if (isConnected) R.string.status_connected else R.string.status_disconnected)
            ToastUtils.showLong(getString(R.string.toast_device_state, deviceName, state))
        } catch (e: Exception) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] showConnectionToastSafely -> failed",
                e
            )
        } catch (e: LinkageError) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] showConnectionToastSafely -> platform API unavailable",
                e
            )
        }
    }

    private fun readMediaVolumeSnapshotSafely(): MediaVolumeSnapshot {
        return try {
            readMediaVolumeSnapshot(this)
        } catch (e: Exception) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] readMediaVolumeSnapshotSafely -> failed",
                e
            )
            MediaVolumeSnapshot()
        } catch (e: LinkageError) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] readMediaVolumeSnapshotSafely -> platform API unavailable",
                e
            )
            MediaVolumeSnapshot()
        }
    }

    private fun isPlayingSafely(): Boolean {
        return try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.isMusicActive
        } catch (e: Exception) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] isPlayingSafely -> failed",
                e
            )
            false
        } catch (e: LinkageError) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] isPlayingSafely -> platform API unavailable",
                e
            )
            false
        }
    }

    private fun getPhoneBatteryLevelSafely(): Int {
        return try {
            val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .coerceIn(0, 100)
        } catch (e: Exception) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] getPhoneBatteryLevelSafely -> failed",
                e
            )
            latestPhoneBatteryLevel.coerceIn(0, 100)
        } catch (e: LinkageError) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] getPhoneBatteryLevelSafely -> platform API unavailable",
                e
            )
            latestPhoneBatteryLevel.coerceIn(0, 100)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceAddressOrNull(bluetoothDevice: BluetoothDevice): String? {
        return try {
            bluetoothDevice.address?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] readDeviceAddressOrNull -> failed",
                e
            )
            null
        } catch (e: LinkageError) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] readDeviceAddressOrNull -> platform API unavailable",
                e
            )
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceNameOrEmpty(bluetoothDevice: BluetoothDevice): String {
        return try {
            bluetoothDevice.name.orEmpty()
        } catch (e: Exception) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] readDeviceNameOrEmpty -> failed",
                e
            )
            ""
        } catch (e: LinkageError) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] readDeviceNameOrEmpty -> platform API unavailable",
                e
            )
            ""
        }
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceAliasOrEmpty(bluetoothDevice: BluetoothDevice): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bluetoothDevice.alias.orEmpty()
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] readDeviceAliasOrEmpty -> failed",
                e
            )
            ""
        } catch (e: LinkageError) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] readDeviceAliasOrEmpty -> platform API unavailable",
                e
            )
            ""
        }
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceBondState(
        bluetoothDevice: BluetoothDevice,
        existingDevice: Device?
    ): Int {
        return try {
            bluetoothDevice.bondState
        } catch (e: Exception) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] readDeviceBondState -> failed",
                e
            )
            existingDevice?.bondState ?: BluetoothDevice.BOND_NONE
        } catch (e: LinkageError) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] readDeviceBondState -> platform API unavailable",
                e
            )
            existingDevice?.bondState ?: BluetoothDevice.BOND_NONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceType(bluetoothDevice: BluetoothDevice): Int {
        return try {
            bluetoothDevice.type
        } catch (e: Exception) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] readDeviceType -> failed",
                e
            )
            BluetoothDevice.DEVICE_TYPE_UNKNOWN
        } catch (e: LinkageError) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] readDeviceType -> platform API unavailable",
                e
            )
            BluetoothDevice.DEVICE_TYPE_UNKNOWN
        }
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceUuidsOrEmpty(bluetoothDevice: BluetoothDevice): String {
        return try {
            bluetoothDevice.uuids?.joinToString().orEmpty()
        } catch (e: Exception) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] readDeviceUuidsOrEmpty -> failed",
                e
            )
            ""
        } catch (e: LinkageError) {
            Log.e(
                tag,
                "[BtLoggerForegroundService] readDeviceUuidsOrEmpty -> platform API unavailable",
                e
            )
            ""
        }
    }

    companion object {
        const val CHANNEL_ID = "bt_logger_channel"
        const val NOTIFICATION_ID = 1001

        /** 定时采样的检查间隔；实际写入间隔由设置决定 */
        private const val PERIODIC_SAMPLE_TICK_MS = 15_000L

        fun start(context: Context) {
            val intent = Intent(context, BtLoggerForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BtLoggerForegroundService::class.java))
        }
    }
}

private data class ConnectedDeviceState(
    val mac: String,
    val name: String,
    val bondState: Int,
    val alias: String,
    val deviceType: Int,
    val bluetoothVersion: String,
    val uuids: String
)

/** 系统转发 HFP 厂商事件时附带的类别，见 `HeadsetStateMachine.broadcastVendorSpecificEventIntent` */
private fun vendorEventCategory(companyId: Int): String =
    "${BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY}.$companyId"
