package com.xingkeqi.btlogger.data

import com.xingkeqi.btlogger.domain.battery.BatteryReading
import com.xingkeqi.btlogger.domain.battery.DeviceBatteryHistory
import com.xingkeqi.btlogger.domain.battery.ReadingKind

/**
 * 多设备耳机电量对比只需要的字段，不查询编解码、UUID 等大字段。
 */
data class BatteryRecordRow(
    val mac: String,
    val name: String,
    val id: Int,
    val timestamp: Long,
    val eventType: String,
    val headsetBatteryLevel: Int,
    val volume: Int,
    val isPlaying: Boolean
)

fun readingKindOf(eventType: String): ReadingKind = when (eventType) {
    RecordEventType.CONNECTED -> ReadingKind.SESSION_START
    RecordEventType.DISCONNECTED -> ReadingKind.SESSION_END
    else -> ReadingKind.SAMPLE
}

fun RecordInfo.toBatteryReading(): BatteryReading = BatteryReading(
    timestamp = timestamp,
    kind = readingKindOf(eventType),
    headsetLevel = headsetBatteryLevel.takeIf { it in 0..100 },
    volume = volume,
    isPlaying = isPlaying == 1
)

fun BatteryRecordRow.toBatteryReading(): BatteryReading = BatteryReading(
    timestamp = timestamp,
    kind = readingKindOf(eventType),
    headsetLevel = headsetBatteryLevel.takeIf { it in 0..100 },
    volume = volume,
    isPlaying = isPlaying
)

/**
 * 按设备分组；[this] 需按时间升序（DAO 已排序），设备名取最新一条
 */
fun List<BatteryRecordRow>.toDeviceHistories(): List<DeviceBatteryHistory> =
    groupBy { it.mac }.map { (mac, rows) ->
        DeviceBatteryHistory(
            key = mac,
            name = rows.last().name,
            readings = rows.map { it.toBatteryReading() }
        )
    }
