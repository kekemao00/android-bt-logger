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
    isPlaying = isPlaying == 1,
    leftLevel = leftBatteryLevel.takeIf { it in 0..100 },
    rightLevel = rightBatteryLevel.takeIf { it in 0..100 },
    caseLevel = caseBatteryLevel.takeIf { it in 0..100 }
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

/**
 * 历史记录里一条“电量更新”中某个来源相对上一档的变化。
 *
 * @param previousLevel 上一档电量
 * @param heldMillis 上一档从首次记录到这次变化持续的时长
 */
data class LevelChange(val previousLevel: Int, val heldMillis: Long)

/**
 * 一条“电量更新”里各来源的变化；没有变化的来源为 null。
 */
data class RecordLevelChanges(
    val headset: LevelChange? = null,
    val left: LevelChange? = null,
    val right: LevelChange? = null
)

/**
 * 为每条“电量更新”找出系统上报、左耳、右耳各自之前的那一档电量与持续时长，按记录 id 索引。
 *
 * Why: 只在电量变化时记录后，历史列表里每条电量记录就是一次跳档；直接写出
 * “左耳 80% → 70% · 上一档持续 34 分”，测试同事不用再上下比对相邻两条。
 * 每次连接单独计算，不跨断开比较；电量没变的旧采样记录（改版前的定时采样）不产生条目。
 *
 * @param records 同一台设备的记录，任意顺序
 */
fun recordLevelChanges(records: List<RecordInfo>): Map<Int, RecordLevelChanges> {
    val sorted = records.sortedWith(compareBy<RecordInfo> { it.timestamp }.thenBy { it.id })
    val headset = levelChanges(sorted) { it.headsetBatteryLevel }
    val left = levelChanges(sorted) { it.leftBatteryLevel }
    val right = levelChanges(sorted) { it.rightBatteryLevel }
    return (headset.keys + left.keys + right.keys).associateWith { id ->
        RecordLevelChanges(headset = headset[id], left = left[id], right = right[id])
    }
}

/**
 * @param sorted 按时间升序
 */
private fun levelChanges(sorted: List<RecordInfo>, levelOf: (RecordInfo) -> Int): Map<Int, LevelChange> {
    val changes = HashMap<Int, LevelChange>()
    var level: Int? = null
    var since = 0L
    sorted.forEach { record ->
        val current = levelOf(record).takeIf { it in 0..100 }
        val previous = level
        if (current != null && current != previous) {
            if (previous != null && record.eventType == RecordEventType.BATTERY_CHANGED) {
                changes[record.id] = LevelChange(previousLevel = previous, heldMillis = record.timestamp - since)
            }
            level = current
            since = record.timestamp
        }
        if (record.eventType == RecordEventType.DISCONNECTED) level = null
    }
    return changes
}
