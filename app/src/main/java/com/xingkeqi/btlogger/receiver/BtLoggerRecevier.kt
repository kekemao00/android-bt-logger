package com.xingkeqi.btlogger.receiver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.BATTERY_SERVICE
import android.content.Intent
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.blankj.utilcode.util.TimeUtils
import com.blankj.utilcode.util.ToastUtils
import com.blankj.utilcode.util.VolumeUtils
import com.xingkeqi.btlogger.data.Device
import com.xingkeqi.btlogger.data.DeviceConnectionRecord
import com.xingkeqi.btlogger.data.MessageEvent
import org.greenrobot.eventbus.EventBus


class BtLoggerReceiver : BroadcastReceiver() {

    private val tag = "@@@@"

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {

        val action = intent.action

        if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED == action) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
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

            val isPlaying = isPlaying(context)
            val volume = getCurrVolume()
            val now = System.currentTimeMillis()
            val batteryLevel = getBatteryLevel(context)


            val connectStatus = when (state) {
                BluetoothAdapter.STATE_CONNECTED -> {
                    // 处理蓝牙设备连接的相关逻辑
                    Log.i(
                        tag,
                        "onReceive: 已连接到蓝牙设备 $name[$address],设备类型：$type,当前音量：$volume,是否在播放音乐：$isPlaying, 当前手机电量： $batteryLevel，配对状态：$bondState, rssi=$rssi, uuids=${uuids?.joinToString()}: $now"
                    )
                    ToastUtils.showLong("$name - 已连接")
                    BluetoothAdapter.STATE_CONNECTED
                }

                BluetoothAdapter.STATE_DISCONNECTED -> {
                    // 处理蓝牙设备连接的相关逻辑
                    Log.i(
                        tag,
                        "onReceive: 蓝牙连接已断开 $name[$address],设备类型：$type,当前音量：$volume,是否在播放音乐：$isPlaying, 当前手机电量： $batteryLevel，配对状态：$bondState, rssi=$rssi, uuids=${uuids?.joinToString()}: $now"
                    )
                    ToastUtils.showLong("$name - 已断开")

                    BluetoothAdapter.STATE_DISCONNECTED
                }

                else -> {
                    BluetoothAdapter.STATE_DISCONNECTED
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

            EventBus.getDefault().post(MessageEvent("ADD_RECORD", deviceTab, record))
        }
    }

}

fun getCurrVolume() = VolumeUtils.getVolume(AudioManager.STREAM_MUSIC)

fun isPlaying(context: Context): Boolean {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    return audioManager.isMusicActive
}


private fun getBatteryLevel(context: Context): Int {
    val batteryManager = context.getSystemService(BATTERY_SERVICE) as BatteryManager
    return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
}

