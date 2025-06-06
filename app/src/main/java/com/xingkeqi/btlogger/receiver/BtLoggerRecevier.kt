package com.xingkeqi.btlogger.receiver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.BATTERY_SERVICE
import android.content.Intent
import android.media.AudioManager
import android.media.AudioManager.STREAM_MUSIC
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.util.Log
import com.blankj.utilcode.util.ToastUtils
import com.blankj.utilcode.util.VolumeUtils
import com.xingkeqi.btlogger.BtLoggerApplication
import com.xingkeqi.btlogger.data.Device
import com.xingkeqi.btlogger.data.DeviceConnectionRecord
import com.xingkeqi.btlogger.data.MessageEvent
import org.greenrobot.eventbus.EventBus


class BtLoggerReceiver : BroadcastReceiver() {

    private val tag = "@@@@"

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {

        val action = intent.action

        if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED == action) {
            val state = intent.getIntExtra(BluetoothA2dp.EXTRA_STATE, -1)
            val bluetoothDevice =
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val name = bluetoothDevice?.name // 蓝牙设备名称
            val address = bluetoothDevice?.address ?: "0000000" // 蓝牙设备地址
            val type = bluetoothDevice?.type // 蓝牙设备类型
            val bondState = bluetoothDevice?.bondState // 蓝牙设备配对状态
            val rssi = intent.getShortExtra(
                BluetoothDevice.EXTRA_RSSI,
                Short.MIN_VALUE
            ) // 蓝牙设备信号强度
            val uuids = bluetoothDevice?.uuids // 蓝牙设备的服务UUID列表
            val alias =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) bluetoothDevice?.alias else "" // 蓝牙设备别名

            val isPlaying = isPlaying()
            val volume = getCurrVolume()
            val now = System.currentTimeMillis()
            val batteryLevel = getBatteryLevel()


            val connectStatus = when (state) {
                BluetoothA2dp.STATE_CONNECTED -> {
                    // 处理蓝牙设备连接的相关逻辑
                    Log.i(
                        tag,
                        "onReceive: A2DP已连接 $name[$address],设备类型：$type,当前音量：$volume,是否在播放音乐：$isPlaying, 当前手机电量： $batteryLevel，配对状态：$bondState, rssi=$rssi, uuids=${uuids?.joinToString()}: $now"
                    )
                    ToastUtils.showLong("$name - 已连接")
                    BluetoothA2dp.STATE_CONNECTED
                }

                BluetoothA2dp.STATE_DISCONNECTED -> {
                    // A2DP已断开
                    Log.i(
                        tag,
                        "onReceive: A2DP已断开 $name[$address],设备类型：$type,当前音量：$volume,是否在播放音乐：$isPlaying, 当前手机电量： $batteryLevel，配对状态：$bondState, rssi=$rssi, uuids=${uuids?.joinToString()}: $now"
                    )
                    ToastUtils.showLong("$name - 已断开")

                    BluetoothA2dp.STATE_DISCONNECTED
                }

                else -> {
                    BluetoothA2dp.STATE_DISCONNECTED
                }
            }

            val deviceTab =
                Device(
                    mac = address,
                    name = name ?: "",
                    bondState = bondState ?: -1,
                    rssi = rssi,
                    alias = alias ?: "",
                    deviceType = type ?: -1,
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

            handler.removeMessages(save_data)
            handler.sendMessageDelayed(
                Message().apply {
                    obj = Pair(deviceTab, record)
                    what = save_data
                }, 1000
            )
        }
    }

}

fun getCurrVolume() =
    (VolumeUtils.getVolume(STREAM_MUSIC) * 100) / VolumeUtils.getMaxVolume(STREAM_MUSIC)

fun setVolume() = VolumeUtils.setVolume(STREAM_MUSIC, 15, 0)

fun isPlaying(): Boolean {
    val audioManager =
        BtLoggerApplication.instance.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return audioManager.isMusicActive
}


private fun getBatteryLevel(): Int {
    val batteryManager =
        BtLoggerApplication.instance.getSystemService(BATTERY_SERVICE) as BatteryManager
    return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}

const val save_data = 0x1
val handler = Handler(
    Looper.getMainLooper()
) {
    when (it.what) {
        save_data -> {
            val obj = it.obj as Pair<Device, DeviceConnectionRecord>
            val deviceTab = obj.first
            val record = obj.second
            // 获取最新的音量
            record.volume = getCurrVolume()
            EventBus.getDefault().post(MessageEvent("ADD_RECORD", deviceTab, record))
        }
    }
    return@Handler false
}

