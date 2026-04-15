package com.xingkeqi.btlogger.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
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
import com.xingkeqi.btlogger.data.Device
import com.xingkeqi.btlogger.data.DeviceConnectionRecord
import com.xingkeqi.btlogger.data.DeviceDao
import com.xingkeqi.btlogger.data.MessageEvent
import com.xingkeqi.btlogger.data.RecordEventType
import com.xingkeqi.btlogger.data.RecordDao
import com.xingkeqi.btlogger.utils.readMediaVolumeSnapshot
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.greenrobot.eventbus.EventBus
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * 前台服务，用于保持应用存活并监听蓝牙连接状态
 *
 * 优势：
 * - 前台服务优先级高，不易被系统杀死
 * - Receiver 生命周期与 Service 绑定，独立于 Activity
 * - 通知栏常驻，用户可感知应用运行状态
 */
@AndroidEntryPoint
class BtLoggerForegroundService : Service() {

    private val tag = "BtLoggerService"
    private val latestCodecSnapshots = ConcurrentHashMap<String, CodecSnapshot>()
    private val codecSnapshotMutex = Mutex()

    @Inject
    lateinit var deviceDao: DeviceDao

    @Inject
    lateinit var recordDao: RecordDao

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
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "onCreate: 前台服务启动")

        // 动态注册蓝牙广播接收器
        val intentFilter = IntentFilter().apply {
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(ACTION_A2DP_CODEC_CONFIG_CHANGED)
        }
        registerReceiver(btReceiver, intentFilter)
        Log.i(tag, "onCreate: 已注册蓝牙广播接收器")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())
        Log.i(tag, "onStartCommand: 前台服务已启动")
        return START_STICKY // 服务被杀死后自动重启
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(btReceiver)
            Log.i(tag, "onDestroy: 已取消注册蓝牙广播接收器")
        } catch (e: Exception) {
            Log.w(tag, "onDestroy: 取消注册失败", e)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 处理蓝牙连接状态变化
     */
    @SuppressLint("MissingPermission")
    private fun handleConnectionStateChanged(bluetoothDevice: BluetoothDevice, state: Int) {
        val name = bluetoothDevice.name ?: ""
        val address = bluetoothDevice.address
        val type = bluetoothDevice.type
        val bondState = bluetoothDevice.bondState
        val uuids = bluetoothDevice.uuids
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bluetoothDevice.alias ?: ""
        } else ""

        val isPlaying = isPlaying()
        val volumeSnapshot = readMediaVolumeSnapshot(this)
        val volume = volumeSnapshot.percent
        val now = System.currentTimeMillis()
        val batteryLevel = getBatteryLevel()

        val isConnected = state == BluetoothProfile.STATE_CONNECTED
        val connectStatus = if (isConnected) {
            BluetoothA2dp.STATE_CONNECTED
        } else {
            BluetoothA2dp.STATE_DISCONNECTED
        }
        serviceScope.launch {
            val codecSnapshot = resolveCodecSnapshot(address)
            val device = Device(
                mac = address,
                name = name,
                bondState = bondState,
                rssi = null,
                alias = alias,
                deviceType = type,
                uuids = uuids?.joinToString() ?: "",
                latestPhoneSupportedCodecs = codecSnapshot.phoneSupportedCodecs,
                latestNegotiableCodecs = codecSnapshot.negotiableCodecs,
                latestActiveCodec = codecSnapshot.activeCodec,
                latestCodecUpdatedAt = codecSnapshot.updatedAt
            )

            val record = DeviceConnectionRecord(
                deviceMac = address,
                timestamp = now,
                connectState = connectStatus,
                batteryLevel = batteryLevel,
                isPlaying = isPlaying,
                volume = volume,
                eventType = if (isConnected) RecordEventType.CONNECTED else RecordEventType.DISCONNECTED,
                phoneSupportedCodecs = codecSnapshot.phoneSupportedCodecs,
                negotiableCodecs = codecSnapshot.negotiableCodecs,
                activeCodec = codecSnapshot.activeCodec
            )

            Log.i(
                tag,
                "[BtLoggerForegroundService] handleConnectionStateChanged -> state=${record.eventType}, device=$name[$address], activeCodec=${codecSnapshot.activeCodec}, battery=$batteryLevel, volume=$volume(${volumeSnapshot.currentLevel}/${volumeSnapshot.maxLevel}), bluetoothConnected=${volumeSnapshot.hasBluetoothOutput}"
            )
            ToastUtils.showLong("$name - ${if (isConnected) "已连接" else "已断开"}")
            persistDeviceAndRecord(device, record)
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
                val device = Device(
                    mac = address,
                    name = bluetoothDevice.name ?: existingDevice?.name.orEmpty(),
                    bondState = bluetoothDevice.bondState,
                    rssi = existingDevice?.rssi,
                    alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        bluetoothDevice.alias ?: existingDevice?.alias.orEmpty()
                    } else {
                        existingDevice?.alias.orEmpty()
                    },
                    deviceType = bluetoothDevice.type,
                    uuids = bluetoothDevice.uuids?.joinToString() ?: existingDevice?.uuids.orEmpty(),
                    latestPhoneSupportedCodecs = resolvedSnapshot.phoneSupportedCodecs,
                    latestNegotiableCodecs = resolvedSnapshot.negotiableCodecs,
                    latestActiveCodec = resolvedSnapshot.activeCodec,
                    latestCodecUpdatedAt = resolvedSnapshot.updatedAt
                )

                if (!shouldPersistHistory) {
                    Log.i(
                        tag,
                        "[BtLoggerForegroundService] handleCodecConfigChanged -> refresh latest codec only: device=${device.name}[${device.mac}], activeCodec=${previousSnapshot.activeCodec} -> ${resolvedSnapshot.activeCodec}"
                    )
                    if (persistDevice(device)) {
                        latestCodecSnapshots[address] = resolvedSnapshot
                    }
                    return@withLock
                }

                val volumeSnapshot = readMediaVolumeSnapshot(this@BtLoggerForegroundService)
                val record = DeviceConnectionRecord(
                    deviceMac = address,
                    timestamp = resolvedSnapshot.updatedAt,
                    connectState = BluetoothA2dp.STATE_CONNECTED,
                    batteryLevel = getBatteryLevel(),
                    volume = volumeSnapshot.percent,
                    isPlaying = isPlaying(),
                    eventType = RecordEventType.CODEC_CHANGED,
                    phoneSupportedCodecs = resolvedSnapshot.phoneSupportedCodecs,
                    negotiableCodecs = resolvedSnapshot.negotiableCodecs,
                    activeCodec = resolvedSnapshot.activeCodec
                )

                Log.i(
                    tag,
                    "[BtLoggerForegroundService] handleCodecConfigChanged -> persist codec change: device=${device.name}[${device.mac}], activeCodec=${previousSnapshot.activeCodec} -> ${resolvedSnapshot.activeCodec}, negotiable=${resolvedSnapshot.negotiableCodecs}, volume=${volumeSnapshot.percent}(${volumeSnapshot.currentLevel}/${volumeSnapshot.maxLevel}), bluetoothConnected=${volumeSnapshot.hasBluetoothOutput}"
                )
                if (persistDeviceAndRecord(device, record)) {
                    latestCodecSnapshots[address] = resolvedSnapshot
                }
            }
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

    private suspend fun persistDevice(device: Device): Boolean {
        return try {
            deviceDao.insert(device)
            Log.d(
                tag,
                "[BtLoggerForegroundService] persistDevice -> saved latest codec for ${device.mac}"
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
                "[BtLoggerForegroundService] persistDeviceAndRecord -> saved ${record.eventType} for ${device.mac}"
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

    private fun getBatteryLevel(): Int {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
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
