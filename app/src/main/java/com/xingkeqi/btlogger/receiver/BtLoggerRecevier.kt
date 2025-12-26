package com.xingkeqi.btlogger.receiver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.BATTERY_SERVICE
import android.content.Intent
import android.media.AudioManager
import android.media.AudioManager.STREAM_MUSIC
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.blankj.utilcode.util.ToastUtils
import com.blankj.utilcode.util.VolumeUtils
import com.xingkeqi.btlogger.BtLoggerApplication
import com.xingkeqi.btlogger.data.Device
import com.xingkeqi.btlogger.data.DeviceConnectionRecord
import com.xingkeqi.btlogger.data.MessageEvent
import org.greenrobot.eventbus.EventBus


class BtLoggerReceiver : BroadcastReceiver() {

    private val tag = "BtLoggerReceiver"

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return

        // 根据不同的 Action 获取对应的状态 Extra Key
        val (stateExtraKey, profileName) = when (action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                BluetoothProfile.EXTRA_STATE to "A2DP"
            }
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                BluetoothProfile.EXTRA_STATE to "Headset"
            }
            BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED -> {
                BluetoothAdapter.EXTRA_CONNECTION_STATE to "Adapter"
            }
            else -> return
        }

        val state = intent.getIntExtra(stateExtraKey, -1)
        // 仅处理连接和断开状态，忽略中间状态
        if (state != BluetoothProfile.STATE_CONNECTED && state != BluetoothProfile.STATE_DISCONNECTED) {
            return
        }

        val bluetoothDevice = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
        if (bluetoothDevice == null) {
            Log.w(tag, "onReceive: BluetoothDevice is null for action=$action")
            return
        }

        handleConnectionStateChanged(bluetoothDevice, state, profileName)
    }

    /**
     * 处理蓝牙连接状态变化
     * 提取公共逻辑，避免代码重复
     */
    @SuppressLint("MissingPermission")
    private fun handleConnectionStateChanged(
        bluetoothDevice: BluetoothDevice,
        state: Int,
        profileName: String
    ) {
        val name = bluetoothDevice.name
        val address = bluetoothDevice.address
        val type = bluetoothDevice.type
        val bondState = bluetoothDevice.bondState
        val uuids = bluetoothDevice.uuids
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bluetoothDevice.alias
        } else {
            ""
        }

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

        Log.i(
            tag,
            "[$profileName] ${if (isConnected) "已连接" else "已断开"} $name[$address], " +
                    "设备类型：$type, 当前音量：$volume, 是否在播放音乐：$isPlaying, " +
                    "当前手机电量：$batteryLevel, 配对状态：$bondState, " +
                    "uuids=${uuids?.joinToString()}: $now"
        )

        ToastUtils.showLong("$name - ${if (isConnected) "已连接" else "已断开"}")

        val device = Device(
            mac = address,
            name = name ?: "",
            bondState = bondState,
            rssi = Short.MIN_VALUE, // 连接状态变化时无法获取 RSSI
            alias = alias ?: "",
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

        // 直接发送事件，移除延迟保存机制以避免数据丢失
        EventBus.getDefault().post(MessageEvent("ADD_RECORD", device, record))
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
