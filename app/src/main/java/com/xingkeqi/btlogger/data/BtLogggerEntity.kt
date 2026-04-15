package com.xingkeqi.btlogger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

const val CODEC_LIST_UNAVAILABLE = "不可用"
const val CODEC_UNKNOWN = "未知"
const val DEVICE_BATTERY_LEVEL_UNKNOWN = -1

object RecordEventType {
    const val CONNECTED = "CONNECTED"
    const val DISCONNECTED = "DISCONNECTED"
    const val CODEC_CHANGED = "CODEC_CHANGED"
    const val BATTERY_CHANGED = "BATTERY_CHANGED"
}

data class DeviceInfo(
    val mac: String = "",
    val name: String = "",
    val deviceType: Int = -1,
    val uuids: String = "",
    val latestPhoneSupportedCodecs: String = CODEC_LIST_UNAVAILABLE,
    val latestNegotiableCodecs: String = CODEC_LIST_UNAVAILABLE,
    val latestActiveCodec: String = CODEC_UNKNOWN,
    val firstRecordTime: Long = 0,
    val lastRecordTime: Long = 0,
    val connectState: Int = 0
)

data class RecordInfo(
    val id: Int = -1,
    // mac 地址
    val mac: String = "",
    val name: String = "",
    // 时间戳
    val timestamp: Long = 0,
    // 连接状态 0 未连接，1 已连接
    val connectState: Int = 0,
    // 音量
    val volume: Int = 0,
    //  播放状态（0：未播放，1：正在播放）
    val isPlaying: Int = 0,
    // 手机电量（0~100）
    val batteryLevel: Int = 0,
    // 耳机电量（0~100，-1 表示当前机型或链路未上报）
    val headsetBatteryLevel: Int = DEVICE_BATTERY_LEVEL_UNKNOWN,
    val eventType: String = RecordEventType.CONNECTED,
    val phoneSupportedCodecs: String = CODEC_LIST_UNAVAILABLE,
    val negotiableCodecs: String = CODEC_LIST_UNAVAILABLE,
    val activeCodec: String = CODEC_UNKNOWN,

    val deviceType: Int = -1,
    val uuids: String = "",
    val bondState: Int = -1,
    val rssi: Short = -1,
    val alias: String = "",
) {
    /**
     * 上次记录的时间
     */
    var lastRecordTime: Long? = 0L

    /**
     * 连接总时长
     */
    var totalConnectionTime: Long? = 0L

    /**
     * 断开总时长
     */
    var totalDisConnectionTime: Long? = 0L
}

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
    val name: String,
    @ColumnInfo(name = "bond_state")
    val bondState: Int,
    @ColumnInfo(name = "rssi")
    val rssi: Short?, // 可空：连接状态变化时无法获取 RSSI
    @ColumnInfo(name = "alias")
    val alias: String,
    @ColumnInfo(name = "device_type")
    val deviceType: Int,
    @ColumnInfo(name = "uuids")
    val uuids: String,
    @ColumnInfo(name = "latest_phone_supported_codecs")
    val latestPhoneSupportedCodecs: String = CODEC_LIST_UNAVAILABLE,
    @ColumnInfo(name = "latest_negotiable_codecs")
    val latestNegotiableCodecs: String = CODEC_LIST_UNAVAILABLE,
    @ColumnInfo(name = "latest_active_codec")
    val latestActiveCodec: String = CODEC_UNKNOWN,
    @ColumnInfo(name = "latest_codec_updated_at")
    val latestCodecUpdatedAt: Long = 0L
)

/**
 * 创建 ConnectionRecord 表实体类
 *
 * @constructor Create empty Connection record entity
 *
 * 第一次测试完了后，第二次重新测试： 结束前点击完成按钮，对已有的数据添加一个列 Int？ "历史标记"， 下次查询时过滤掉这个字段有值的，每次历史使用当时完成时的时间戳, 但是导出数据时可以导出当前设备下全部的
 */
@Entity(
    tableName = "device_connection_records",
    foreignKeys = [ForeignKey(
        entity = Device::class,
        parentColumns = ["mac"],
        childColumns = ["device_mac"],
        onDelete = ForeignKey.CASCADE // 级联删除：设备删除时自动删除关联记录
    )],
    indices = [Index(value = ["device_mac"])] // 外键字段添加索引提升查询性能
)
data class DeviceConnectionRecord(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "device_mac")
    val deviceMac: String,
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,
    @ColumnInfo(name = "connect_state")
    val connectState: Int,
    @ColumnInfo(name = "battery_level")
    val batteryLevel: Int,
    @ColumnInfo(name = "headset_battery_level")
    val headsetBatteryLevel: Int = DEVICE_BATTERY_LEVEL_UNKNOWN,
    @ColumnInfo(name = "volume")
    var volume: Int,
    @ColumnInfo(name = "is_playing")
    val isPlaying: Boolean,
    @ColumnInfo(name = "event_type")
    val eventType: String = RecordEventType.CONNECTED,
    @ColumnInfo(name = "phone_supported_codecs")
    val phoneSupportedCodecs: String = CODEC_LIST_UNAVAILABLE,
    @ColumnInfo(name = "negotiable_codecs")
    val negotiableCodecs: String = CODEC_LIST_UNAVAILABLE,
    @ColumnInfo(name = "active_codec")
    val activeCodec: String = CODEC_UNKNOWN
)
