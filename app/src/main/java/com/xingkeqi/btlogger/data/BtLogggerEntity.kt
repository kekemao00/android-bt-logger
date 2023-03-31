package com.xingkeqi.btlogger.data

import android.os.ParcelUuid
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date
import javax.crypto.Mac


data class DeviceInfo(
    val mac: String?,
    val name: String?,
    val deviceType: Int? = -1,
    val uuids: String? = "",
    val lastRecordTime: Long,
    val firstConnectTime: Long,
    val connectStatus: Int,
)

data class RecordInfo(
    // mac 地址
    val mac: String,
    val name: String,
    // 时间戳
    val timestamp: Long,
    // 连接状态 0 未连接，1 已连接
    val connectType: Int,
    // 音量
    val volume: Int,
    //  播放状态（0：未播放，1：正在播放）
    val isPlaying: Int,
    // 手机电量（0~100）
    val batteryLevel: Int
)

//data class NewRecordInfo(
//    val state: Int,// BluetoothAdapter.STATE_CONNECTED
//    val device: BluetoothDevice?,
//    val name: String?,
//    val mac: String?,
//    val type: Int?,
//    val bondState: Int?,
//    val rssi: Short?,
//    val uuids: Array<ParcelUuid>?,
//    val alias: String?
//
//)


/**
 * 创建 Device 表实体类
 *
 * @constructor Create empty Device entity
 */
@Entity(tableName = "devices")
data class Device(
    @PrimaryKey val mac: String,
    val name: String?,
    // 配对状态
    val bondState: Int?,
    val rssi: Short?,
    val alias: String?,
    val type: Int?,
    val uuids: String?
)

/**
 * 创建 ConnectionRecord 表实体类
 *
 * @constructor Create empty Connection record entity
 */
@Entity(tableName = "device_connection_records")
data class DeviceConnectionRecord(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val deviceMac: String,
    val timestamp: Long,
    val connectState: Int,
    val batteryLevel: Int,
    val volume: Int,
    val isPlaying: Boolean
)