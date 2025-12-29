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
import androidx.core.app.NotificationCompat
import com.blankj.utilcode.util.ToastUtils
import com.blankj.utilcode.util.VolumeUtils
import com.xingkeqi.btlogger.BtLoggerApplication
import com.xingkeqi.btlogger.MainActivity
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.data.Device
import com.xingkeqi.btlogger.data.DeviceConnectionRecord
import com.xingkeqi.btlogger.data.DeviceDao
import com.xingkeqi.btlogger.data.MessageEvent
import com.xingkeqi.btlogger.data.RecordDao
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
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

    @Inject
    lateinit var deviceDao: DeviceDao

    @Inject
    lateinit var recordDao: RecordDao

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val btReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED) return

            val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)
            if (state != BluetoothProfile.STATE_CONNECTED && state != BluetoothProfile.STATE_DISCONNECTED) {
                return
            }

            val bluetoothDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            if (bluetoothDevice == null) {
                Log.w(tag, "onReceive: BluetoothDevice is null")
                return
            }

            handleConnectionStateChanged(bluetoothDevice, state)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(tag, "onCreate: 前台服务启动")

        // 动态注册蓝牙广播接收器
        val intentFilter = IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
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
        val volume = getCurrVolume()
        val now = System.currentTimeMillis()
        val batteryLevel = getBatteryLevel()

        val isConnected = state == BluetoothProfile.STATE_CONNECTED
        val connectStatus = if (isConnected) {
            BluetoothA2dp.STATE_CONNECTED
        } else {
            BluetoothA2dp.STATE_DISCONNECTED
        }

        Log.i(tag, "[A2DP] ${if (isConnected) "已连接" else "已断开"} $name[$address], " +
                "音量：$volume, 电量：$batteryLevel")

        ToastUtils.showLong("$name - ${if (isConnected) "已连接" else "已断开"}")

        val device = Device(
            mac = address,
            name = name,
            bondState = bondState,
            rssi = null, // 连接状态变化时无法获取 RSSI
            alias = alias,
            deviceType = type,
            uuids = uuids?.joinToString() ?: ""
        )

        val record = DeviceConnectionRecord(
            deviceMac = address,
            timestamp = now,
            connectState = connectStatus,
            batteryLevel = batteryLevel,
            isPlaying = isPlaying,
            volume = volume
        )

        // 直接保存到数据库
        serviceScope.launch {
            try {
                deviceDao.insert(device)
                recordDao.insert(record)
                Log.d(tag, "数据已保存: $address, state=$connectStatus")
            } catch (e: Exception) {
                Log.e(tag, "保存数据失败", e)
            }
        }

        // 同时发送 EventBus 通知 UI 更新
        EventBus.getDefault().post(MessageEvent("ADD_RECORD", device, record))
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

    private fun getCurrVolume() =
        (VolumeUtils.getVolume(AudioManager.STREAM_MUSIC) * 100) / VolumeUtils.getMaxVolume(AudioManager.STREAM_MUSIC)

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
