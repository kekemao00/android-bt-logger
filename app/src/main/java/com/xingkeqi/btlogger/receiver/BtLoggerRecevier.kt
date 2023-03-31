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
import android.content.Context.BATTERY_SERVICE;
import android.os.Build
import android.util.Log
import android.view.View
import androidx.core.app.NotificationCompat.StreamType
import androidx.core.content.ContextCompat.getSystemService
import androidx.lifecycle.ViewModelProvider
import com.blankj.utilcode.util.TimeUtils
import com.blankj.utilcode.util.VolumeUtils
import com.xingkeqi.btlogger.BtLoggerApplication
import com.xingkeqi.btlogger.MainViewModel
import com.xingkeqi.btlogger.data.NewRecordInfo


class BtLoggerReceiver : BroadcastReceiver() {

    private val tag = "@@@@"


//    val viewModel = ViewModelProvider(
//        application,
//        ViewModelProvider.AndroidViewModelFactory.getInstance(application)
//    ).get(MyViewModel::class.java)

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
//
//        val viewModel = View(
//            BtLoggerApplication.instance,
//            ViewModelProvider.AndroidViewModelFactory.getInstance(BtLoggerApplication.instance)
//        ).get(MainViewModel::class.java)

        val action = intent.action

        if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED == action) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
            val device =
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val name = device?.name // 蓝牙设备名称
            val address = device?.address // 蓝牙设备地址
            val type = device?.type // 蓝牙设备类型
            val bondState = device?.bondState // 蓝牙设备配对状态
            val rssi = intent.getShortExtra(
                BluetoothDevice.EXTRA_RSSI,
                Short.MIN_VALUE
            ) // 蓝牙设备信号强度
            val uuids = device?.uuids // 蓝牙设备的服务UUID列表
            val alias =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) device?.alias else "" // 蓝牙设备别名

            val newRecord =
                NewRecordInfo(state, device, name, address, type, bondState, rssi, uuids, alias)



            when (state) {
                BluetoothAdapter.STATE_CONNECTED -> {
                    // 处理蓝牙设备连接的相关逻辑
                    Log.i(
                        tag,
                        "onReceive: 设备已连接到蓝牙设备 $name[$address],设备类型：$type,当前音量：${getCurrVolume()},是否在播放音乐：${
                            isPlaying(
                                context
                            )
                        }, 当前手机电量： ${
                            getBatteryLevel(context)
                        }，配对状态：$bondState, rssi=$rssi, uuids=${uuids?.joinToString()}: ${TimeUtils.getNowString()}"
                    )
                }

                BluetoothAdapter.STATE_DISCONNECTED -> {
                    // 处理蓝牙设备连接的相关逻辑
                    Log.i(
                        tag,
                        "onReceive: 蓝牙连接已断开 $name[$address],设备类型：$type,当前音量：${getCurrVolume()},是否在播放音乐：${
                            isPlaying(
                                context
                            )
                        }, 当前手机电量： ${
                            getBatteryLevel(context)
                        }，配对状态：$bondState, rssi=$rssi, uuids=${uuids?.joinToString()}: ${TimeUtils.getNowString()}"
                    )

                }

            }
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

