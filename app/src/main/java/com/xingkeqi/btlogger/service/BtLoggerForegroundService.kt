package com.xingkeqi.btlogger.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
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
import com.xingkeqi.btlogger.utils.BluetoothVersionUtils
import com.xingkeqi.btlogger.utils.HeadsetBatterySnapshot
import com.xingkeqi.btlogger.utils.readMediaVolumeSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private val latestPersistedBatterySnapshots = ConcurrentHashMap<String, PersistedBatterySnapshot>()
    private val codecSnapshotMutex = Mutex()
    private val batterySnapshotMutex = Mutex()
    private val batteryProbeManager by lazy {
        BluetoothBatteryProbeManager(
            context = applicationContext,
            scope = serviceScope
        ) { device, snapshot ->
            serviceScope.launch {
                handleHeadsetBatteryChanged(device, snapshot)
            }
        }
    }
    private val versionProbeManager by lazy {
        BluetoothVersionProbeManager(
            context = applicationContext,
            scope = serviceScope
        ) { device, result ->
            serviceScope.launch {
                handleBluetoothVersionProbeResult(device, result)
            }
        }
    }
    private val versionAdvertisementProbeManager by lazy {
        BluetoothVersionAdvertisementProbeManager(
            context = applicationContext,
            scope = serviceScope
        ) { device, result ->
            serviceScope.launch {
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
                    serviceScope.launch {
                        handlePhoneBatteryChanged(phoneBatteryLevel)
                    }
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
                    serviceScope.launch {
                        handleHeadsetBatteryChanged(bluetoothDevice, snapshot)
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        latestPhoneBatteryLevel = getPhoneBatteryLevel()
        Log.i(tag, "onCreate: 前台服务启动")

        val intentFilter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(ACTION_A2DP_CODEC_CONFIG_CHANGED)
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(ACTION_BLUETOOTH_DEVICE_BATTERY_LEVEL_CHANGED)
            addAction(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT)
        }
        registerReceiver(btReceiver, intentFilter)
        Log.i(tag, "onCreate: 已注册蓝牙与电量广播接收器")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification())
        Log.i(tag, "onStartCommand: 前台服务已启动")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        batteryProbeManager.stopAll()
        versionProbeManager.stopAll()
        versionAdvertisementProbeManager.stopAll()
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
    private fun handleConnectionStateChanged(bluetoothDevice: BluetoothDevice, state: Int) {
        val address = bluetoothDevice.address
        val isConnected = state == BluetoothProfile.STATE_CONNECTED
        val connectStatus = if (isConnected) {
            BluetoothA2dp.STATE_CONNECTED
        } else {
            BluetoothA2dp.STATE_DISCONNECTED
        }

        if (isConnected) {
            BluetoothBatteryUtils.readBatteryLevelReflectively(bluetoothDevice)?.let { snapshot ->
                latestHeadsetBatteryLevels[address] = snapshot.level
                Log.i(
                    tag,
                    "[BtLoggerForegroundService] handleConnectionStateChanged -> preload reflection battery: device=${bluetoothDevice.name.orEmpty()}[$address], battery=${snapshot.level}"
                )
            }
            batteryProbeManager.startMonitoring(bluetoothDevice)
        }

        serviceScope.launch {
            val existingDevice = deviceDao.getDeviceByMacSnapshot(address)
            val deviceState = buildConnectedDeviceState(bluetoothDevice, existingDevice)
            if (isConnected) {
                connectedDevices[address] = deviceState
                versionProbeManager.startMonitoring(
                    device = bluetoothDevice,
                    persistedVersion = deviceState.bluetoothVersion
                )
                versionAdvertisementProbeManager.startMonitoring(
                    device = bluetoothDevice,
                    persistedVersion = deviceState.bluetoothVersion
                )
            }
            val codecSnapshot = resolveCodecSnapshot(address)
            val phoneBatteryLevel = getPhoneBatteryLevel().also { latestPhoneBatteryLevel = it }
            val headsetBatteryLevel = resolveHeadsetBatteryLevel(address)
            val volumeSnapshot = readMediaVolumeSnapshot(this@BtLoggerForegroundService)
            val record = DeviceConnectionRecord(
                deviceMac = address,
                timestamp = System.currentTimeMillis(),
                connectState = connectStatus,
                batteryLevel = phoneBatteryLevel,
                headsetBatteryLevel = headsetBatteryLevel,
                isPlaying = isPlaying(),
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
            ToastUtils.showLong("${device.name} - ${if (isConnected) "已连接" else "已断开"}")
            if (persistDeviceAndRecord(device, record)) {
                rememberPersistedBatterySnapshot(address, phoneBatteryLevel, headsetBatteryLevel)
            }

            if (!isConnected) {
                batteryProbeManager.stopMonitoring(address)
                versionProbeManager.stopMonitoring(address)
                versionAdvertisementProbeManager.stopMonitoring(address)
                connectedDevices.remove(address)
                latestPersistedBatterySnapshots.remove(address)
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    @SuppressLint("MissingPermission")
    private fun handleCodecConfigChanged(bluetoothDevice: BluetoothDevice, intent: Intent) {
        val parsedSnapshot = BluetoothCodecFormatter.parseCodecStatus(intent)
        if (parsedSnapshot == null) {
            Log.w(
                tag,
                "[BtLoggerForegroundService] handleCodecConfigChanged -> codec status missing for ${bluetoothDevice.address}"
            )
            return
        }

        serviceScope.launch {
            codecSnapshotMutex.withLock {
                val address = bluetoothDevice.address
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
                        "[BtLoggerForegroundService] handleCodecConfigChanged -> skip duplicate snapshot: device=${bluetoothDevice.name.orEmpty()}[$address], activeCodec=${resolvedSnapshot.activeCodec}"
                    )
                    return@withLock
                }

                val existingDevice = deviceDao.getDeviceByMacSnapshot(address)
                val deviceState = buildConnectedDeviceState(bluetoothDevice, existingDevice)
                connectedDevices[address] = deviceState
                batteryProbeManager.startMonitoring(bluetoothDevice)
                versionProbeManager.startMonitoring(
                    device = bluetoothDevice,
                    persistedVersion = deviceState.bluetoothVersion
                )
                versionAdvertisementProbeManager.startMonitoring(
                    device = bluetoothDevice,
                    persistedVersion = deviceState.bluetoothVersion
                )
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

                val volumeSnapshot = readMediaVolumeSnapshot(this@BtLoggerForegroundService)
                val phoneBatteryLevel = getPhoneBatteryLevel().also { latestPhoneBatteryLevel = it }
                val headsetBatteryLevel = resolveHeadsetBatteryLevel(address)
                val record = DeviceConnectionRecord(
                    deviceMac = address,
                    timestamp = resolvedSnapshot.updatedAt,
                    connectState = BluetoothA2dp.STATE_CONNECTED,
                    batteryLevel = phoneBatteryLevel,
                    headsetBatteryLevel = headsetBatteryLevel,
                    volume = volumeSnapshot.percent,
                    isPlaying = isPlaying(),
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
                    rememberPersistedBatterySnapshot(address, phoneBatteryLevel, headsetBatteryLevel)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleBluetoothVersionProbeResult(
        bluetoothDevice: BluetoothDevice,
        result: BluetoothVersionProbeResult
    ) {
        val address = bluetoothDevice.address
        val existingDevice = deviceDao.getDeviceByMacSnapshot(address)
        val previousBluetoothVersion = existingDevice?.bluetoothVersion ?: BLUETOOTH_VERSION_UNKNOWN
        val mergedBluetoothVersion = BluetoothVersionUtils.mergeBluetoothVersion(
            persistedVersion = previousBluetoothVersion,
            resolvedVersion = result.versionSnapshot.version
        )
        Log.i(
            tag,
            "[BtLoggerForegroundService] handleBluetoothVersionProbeResult -> device=${bluetoothDevice.name.orEmpty()}[$address], previous=$previousBluetoothVersion, resolved=${result.versionSnapshot.version}, merged=$mergedBluetoothVersion, source=${result.versionSnapshot.source}, info=${result.deviceInformation.summary()}, adv=${result.advertisementSnapshot.summary()}"
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

    private suspend fun handlePhoneBatteryChanged(phoneBatteryLevel: Int) {
        val previousPhoneBatteryLevel = latestPhoneBatteryLevel
        latestPhoneBatteryLevel = phoneBatteryLevel
        if (previousPhoneBatteryLevel == phoneBatteryLevel) {
            Log.d(
                tag,
                "[BtLoggerForegroundService] handlePhoneBatteryChanged -> skip duplicate phone battery: level=$phoneBatteryLevel"
            )
            return
        }

        val connectedAddresses = connectedDevices.keys.toList()
        if (connectedAddresses.isEmpty()) {
            Log.d(
                tag,
                "[BtLoggerForegroundService] handlePhoneBatteryChanged -> no connected device, cache phone battery only: level=$phoneBatteryLevel"
            )
            return
        }

        Log.i(
            tag,
            "[BtLoggerForegroundService] handlePhoneBatteryChanged -> phoneBattery=$phoneBatteryLevel, connectedDevices=${connectedAddresses.size}"
        )
        batterySnapshotMutex.withLock {
            connectedAddresses.forEach { address ->
                persistBatterySample(address = address, reason = "phone-battery")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun handleHeadsetBatteryChanged(
        bluetoothDevice: BluetoothDevice,
        snapshot: HeadsetBatterySnapshot
    ) {
        val address = bluetoothDevice.address
        val previousHeadsetBatteryLevel = latestHeadsetBatteryLevels.put(address, snapshot.level)
        if (previousHeadsetBatteryLevel == snapshot.level) {
            Log.d(
                tag,
                "[BtLoggerForegroundService] handleHeadsetBatteryChanged -> skip duplicate headset battery: device=${bluetoothDevice.name.orEmpty()}[$address], level=${snapshot.level}, source=${snapshot.source}"
            )
            return
        }

        Log.i(
            tag,
            "[BtLoggerForegroundService] handleHeadsetBatteryChanged -> device=${bluetoothDevice.name.orEmpty()}[$address], battery=${previousHeadsetBatteryLevel ?: DEVICE_BATTERY_LEVEL_UNKNOWN} -> ${snapshot.level}, source=${snapshot.source}"
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

    private suspend fun persistBatterySample(address: String, reason: String) {
        val deviceState = connectedDevices[address]
        if (deviceState == null) {
            Log.d(
                tag,
                "[BtLoggerForegroundService] persistBatterySample -> skip because device is no longer connected: device=$address, reason=$reason"
            )
            return
        }

        val phoneBatteryLevel = latestPhoneBatteryLevel
        val headsetBatteryLevel = resolveHeadsetBatteryLevel(address)
        val pendingSnapshot = PersistedBatterySnapshot(
            phoneBatteryLevel = phoneBatteryLevel,
            headsetBatteryLevel = headsetBatteryLevel
        )
        if (latestPersistedBatterySnapshots[address] == pendingSnapshot) {
            Log.d(
                tag,
                "[BtLoggerForegroundService] persistBatterySample -> skip duplicate persisted snapshot: device=$address, reason=$reason, phoneBattery=$phoneBatteryLevel, headsetBattery=$headsetBatteryLevel"
            )
            return
        }

        val codecSnapshot = resolveCodecSnapshot(address)
        val volumeSnapshot = readMediaVolumeSnapshot(this)
        val device = buildDevice(deviceState, codecSnapshot)
        val record = DeviceConnectionRecord(
            deviceMac = address,
            timestamp = System.currentTimeMillis(),
            connectState = BluetoothA2dp.STATE_CONNECTED,
            batteryLevel = phoneBatteryLevel,
            headsetBatteryLevel = headsetBatteryLevel,
            volume = volumeSnapshot.percent,
            isPlaying = isPlaying(),
            eventType = RecordEventType.BATTERY_CHANGED,
            phoneSupportedCodecs = codecSnapshot.phoneSupportedCodecs,
            negotiableCodecs = codecSnapshot.negotiableCodecs,
            activeCodec = codecSnapshot.activeCodec
        )

        Log.i(
            tag,
            "[BtLoggerForegroundService] persistBatterySample -> persist battery sample: device=${device.name}[${device.mac}], reason=$reason, phoneBattery=$phoneBatteryLevel, headsetBattery=$headsetBatteryLevel, activeCodec=${codecSnapshot.activeCodec}, volume=${volumeSnapshot.percent}(${volumeSnapshot.currentLevel}/${volumeSnapshot.maxLevel})"
        )
        if (persistDeviceAndRecord(device, record)) {
            rememberPersistedBatterySnapshot(address, phoneBatteryLevel, headsetBatteryLevel)
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
        val resolvedBluetoothVersion = BluetoothVersionUtils.resolveBluetoothVersion(
            device = bluetoothDevice,
            persistedVersion = previousBluetoothVersion
        )
        val mergedBluetoothVersion = BluetoothVersionUtils.mergeBluetoothVersion(
            persistedVersion = previousBluetoothVersion,
            resolvedVersion = resolvedBluetoothVersion.version
        )
        if (previousBluetoothVersion != mergedBluetoothVersion || resolvedBluetoothVersion.source != "cache") {
            Log.i(
                tag,
                "[BtLoggerForegroundService] buildConnectedDeviceState -> BluetoothVersion: device=${bluetoothDevice.name.orEmpty()}[${bluetoothDevice.address}], previous=$previousBluetoothVersion, resolved=$mergedBluetoothVersion, source=${resolvedBluetoothVersion.source}"
            )
        }
        return ConnectedDeviceState(
            mac = bluetoothDevice.address,
            name = bluetoothDevice.name ?: existingDevice?.name.orEmpty(),
            bondState = bluetoothDevice.bondState,
            alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bluetoothDevice.alias ?: existingDevice?.alias.orEmpty()
            } else {
                existingDevice?.alias.orEmpty()
            },
            deviceType = if (bluetoothDevice.type != BluetoothDevice.DEVICE_TYPE_UNKNOWN) {
                bluetoothDevice.type
            } else {
                existingDevice?.deviceType ?: BluetoothDevice.DEVICE_TYPE_UNKNOWN
            },
            bluetoothVersion = mergedBluetoothVersion,
            uuids = bluetoothDevice.uuids?.joinToString() ?: existingDevice?.uuids.orEmpty()
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

    private fun rememberPersistedBatterySnapshot(
        address: String,
        phoneBatteryLevel: Int,
        headsetBatteryLevel: Int
    ) {
        latestPersistedBatterySnapshots[address] = PersistedBatterySnapshot(
            phoneBatteryLevel = phoneBatteryLevel,
            headsetBatteryLevel = headsetBatteryLevel
        )
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

    private fun mergeBatteryValue(
        currentValue: Int,
        incomingValue: Int?
    ): Int {
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

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("蓝牙日志记录中")
            .setContentText("正在监听蓝牙连接状态...")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun isPlaying(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return audioManager.isMusicActive
    }

    private fun getPhoneBatteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            .coerceIn(0, 100)
    }

    companion object {
        const val CHANNEL_ID = "bt_logger_channel"
        const val NOTIFICATION_ID = 1001

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

private data class PersistedBatterySnapshot(
    val phoneBatteryLevel: Int,
    val headsetBatteryLevel: Int
)
