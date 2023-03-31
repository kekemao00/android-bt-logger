package com.xingkeqi.btlogger.data

import android.os.ParcelUuid
import android.os.Parcelable
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.Date
import javax.crypto.Mac

data class DeviceInfo(
    val mac: String = "",
    val name: String = "",
    val deviceType: Int = -1,
    val uuids: String = "",
    val firstRecordTime: Long = 0,
    val lastRecordTime: Long = 0,
    val connectStatus: Int
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
    @PrimaryKey
    @ColumnInfo(name = "mac")
    val mac: String,
    @ColumnInfo(name = "name")
    val name: String?,
    @ColumnInfo(name = "bondState")
    val bondState: Int?,
    @ColumnInfo(name = "rssi")
    val rssi: Short?,
    @ColumnInfo(name = "alias")
    val alias: String?,
    @ColumnInfo(name = "deviceType")
    val deviceType: Int?,
    @ColumnInfo(name = "uuids")
    val uuids: String?
)

/**
 * 创建 ConnectionRecord 表实体类
 *
 * @constructor Create empty Connection record entity
 */
@Entity(tableName = "device_connection_records")
data class DeviceConnectionRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "deviceMac")
    val deviceMac: String,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "connectStatus")
    val connectState: Int,
    @ColumnInfo(name = "batteryLevel")
    val batteryLevel: Int,
    @ColumnInfo(name = "volume")
    val volume: Int,
    @ColumnInfo(name = "isPlaying")
    val isPlaying: Boolean
)