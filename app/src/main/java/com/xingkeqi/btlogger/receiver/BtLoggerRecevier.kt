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
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import com.blankj.utilcode.util.ToastUtils
import com.xingkeqi.btlogger.BtLoggerApplication
import com.xingkeqi.btlogger.data.CODEC_LIST_UNAVAILABLE
import com.xingkeqi.btlogger.data.CODEC_UNKNOWN
import com.xingkeqi.btlogger.data.DEVICE_BATTERY_LEVEL_UNKNOWN
import com.xingkeqi.btlogger.data.Device
import com.xingkeqi.btlogger.data.DeviceConnectionRecord
import com.xingkeqi.btlogger.data.MessageEvent
import com.xingkeqi.btlogger.data.RecordEventType
import com.xingkeqi.btlogger.utils.readMediaVolumeSnapshot
import org.greenrobot.eventbus.EventBus


class BtLoggerReceiver : BroadcastReceiver() {

    private val tag = "BtLoggerReceiver"

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        try {
            handleBroadcast(intent)
        } catch (e: Exception) {
            Log.e(tag, "[BtLoggerReceiver] onReceive -> failed for action=${intent.action}", e)
        } catch (e: LinkageError) {
            Log.e(tag, "[BtLoggerReceiver] onReceive -> platform API unavailable for action=${intent.action}", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun handleBroadcast(intent: Intent) {
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
        val name = readDeviceNameOrEmpty(bluetoothDevice)
        val address = readDeviceAddressOrNull(bluetoothDevice) ?: return
        val type = readDeviceType(bluetoothDevice)
        val bondState = readDeviceBondState(bluetoothDevice)
        val uuids = readDeviceUuidsOrNull(bluetoothDevice)
        val alias = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            readDeviceAliasOrEmpty(bluetoothDevice)
        } else {
            ""
        }

        val isPlaying = isPlayingSafely()
        val volumeSnapshot = readMediaVolumeSnapshotSafely()
        val volume = volumeSnapshot.percent
        val now = System.currentTimeMillis()
        val batteryLevel = getBatteryLevelSafely()

        val isConnected = state == BluetoothProfile.STATE_CONNECTED
        val connectStatus = if (isConnected) {
            BluetoothA2dp.STATE_CONNECTED
        } else {
            BluetoothA2dp.STATE_DISCONNECTED
        }

        Log.i(
            tag,
            "[$profileName] ${if (isConnected) "已连接" else "已断开"} $name[$address], " +
                    "设备类型：$type, 当前音量：$volume(${volumeSnapshot.currentLevel}/${volumeSnapshot.maxLevel}), 蓝牙输出连接：${volumeSnapshot.hasBluetoothOutput}, 是否在播放音乐：$isPlaying, " +
                    "当前手机电量：$batteryLevel, 配对状态：$bondState, " +
                    "uuids=${uuids?.joinToString()}: $now"
        )

        showConnectionToastSafely(name, isConnected)

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
            headsetBatteryLevel = DEVICE_BATTERY_LEVEL_UNKNOWN,
            isPlaying = isPlaying,
            volume = volume,
            eventType = if (isConnected) RecordEventType.CONNECTED else RecordEventType.DISCONNECTED,
            phoneSupportedCodecs = CODEC_LIST_UNAVAILABLE,
            negotiableCodecs = CODEC_LIST_UNAVAILABLE,
            activeCodec = CODEC_UNKNOWN
        )

        // 直接发送事件，移除延迟保存机制以避免数据丢失
        postRecordEventSafely(device, record)
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceAddressOrNull(bluetoothDevice: BluetoothDevice): String? {
        return try {
            bluetoothDevice.address?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.e(tag, "[BtLoggerReceiver] readDeviceAddressOrNull -> failed", e)
            null
        } catch (e: LinkageError) {
            Log.e(tag, "[BtLoggerReceiver] readDeviceAddressOrNull -> platform API unavailable", e)
            null
        }
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceNameOrEmpty(bluetoothDevice: BluetoothDevice): String {
        return try {
            bluetoothDevice.name.orEmpty()
        } catch (e: Exception) {
            Log.e(tag, "[BtLoggerReceiver] readDeviceNameOrEmpty -> failed", e)
            ""
        } catch (e: LinkageError) {
            Log.e(tag, "[BtLoggerReceiver] readDeviceNameOrEmpty -> platform API unavailable", e)
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
            Log.e(tag, "[BtLoggerReceiver] readDeviceAliasOrEmpty -> failed", e)
            ""
        } catch (e: LinkageError) {
            Log.e(tag, "[BtLoggerReceiver] readDeviceAliasOrEmpty -> platform API unavailable", e)
            ""
        }
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceType(bluetoothDevice: BluetoothDevice): Int {
        return try {
            bluetoothDevice.type
        } catch (e: Exception) {
            Log.e(tag, "[BtLoggerReceiver] readDeviceType -> failed", e)
            BluetoothDevice.DEVICE_TYPE_UNKNOWN
        } catch (e: LinkageError) {
            Log.e(tag, "[BtLoggerReceiver] readDeviceType -> platform API unavailable", e)
            BluetoothDevice.DEVICE_TYPE_UNKNOWN
        }
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceBondState(bluetoothDevice: BluetoothDevice): Int {
        return try {
            bluetoothDevice.bondState
        } catch (e: Exception) {
            Log.e(tag, "[BtLoggerReceiver] readDeviceBondState -> failed", e)
            BluetoothDevice.BOND_NONE
        } catch (e: LinkageError) {
            Log.e(tag, "[BtLoggerReceiver] readDeviceBondState -> platform API unavailable", e)
            BluetoothDevice.BOND_NONE
        }
    }

    @SuppressLint("MissingPermission")
    private fun readDeviceUuidsOrNull(bluetoothDevice: BluetoothDevice): Array<android.os.ParcelUuid>? {
        return try {
            bluetoothDevice.uuids
        } catch (e: Exception) {
            Log.e(tag, "[BtLoggerReceiver] readDeviceUuidsOrNull -> failed", e)
            null
        } catch (e: LinkageError) {
            Log.e(tag, "[BtLoggerReceiver] readDeviceUuidsOrNull -> platform API unavailable", e)
            null
        }
    }

    private fun readMediaVolumeSnapshotSafely() =
        try {
            readMediaVolumeSnapshot(BtLoggerApplication.instance)
        } catch (e: Exception) {
            Log.e(tag, "[BtLoggerReceiver] readMediaVolumeSnapshotSafely -> failed", e)
            com.xingkeqi.btlogger.utils.MediaVolumeSnapshot()
        } catch (e: LinkageError) {
            Log.e(tag, "[BtLoggerReceiver] readMediaVolumeSnapshotSafely -> platform API unavailable", e)
            com.xingkeqi.btlogger.utils.MediaVolumeSnapshot()
        }

    private fun isPlayingSafely(): Boolean {
        return try {
            isPlaying()
        } catch (e: Exception) {
            Log.e(tag, "[BtLoggerReceiver] isPlayingSafely -> failed", e)
            false
        } catch (e: LinkageError) {
            Log.e(tag, "[BtLoggerReceiver] isPlayingSafely -> platform API unavailable", e)
            false
        }
    }

    private fun getBatteryLevelSafely(): Int {
        return try {
            getBatteryLevel()
        } catch (e: Exception) {
            Log.e(tag, "[BtLoggerReceiver] getBatteryLevelSafely -> failed", e)
            0
        } catch (e: LinkageError) {
            Log.e(tag, "[BtLoggerReceiver] getBatteryLevelSafely -> platform API unavailable", e)
            0
        }
    }

    private fun showConnectionToastSafely(name: String, isConnected: Boolean) {
        try {
            ToastUtils.showLong("$name - ${if (isConnected) "已连接" else "已断开"}")
        } catch (e: Exception) {
            Log.e(tag, "[BtLoggerReceiver] showConnectionToastSafely -> failed", e)
        } catch (e: LinkageError) {
            Log.e(tag, "[BtLoggerReceiver] showConnectionToastSafely -> platform API unavailable", e)
        }
    }

    private fun postRecordEventSafely(device: Device, record: DeviceConnectionRecord) {
        try {
            EventBus.getDefault().post(MessageEvent("ADD_RECORD", device, record))
        } catch (e: Exception) {
            Log.e(tag, "[BtLoggerReceiver] postRecordEventSafely -> failed for ${device.mac}", e)
        } catch (e: LinkageError) {
            Log.e(tag, "[BtLoggerReceiver] postRecordEventSafely -> platform API unavailable for ${device.mac}", e)
        }
    }
}

fun getCurrVolume() =
    readMediaVolumeSnapshot(BtLoggerApplication.instance).percent

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
