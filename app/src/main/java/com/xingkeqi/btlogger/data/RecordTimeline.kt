package com.xingkeqi.btlogger.data

import android.bluetooth.BluetoothA2dp

/**
 * 单个设备连接记录的统计摘要。
 *
 * Why:
 * 续航/稳定性测试最常问的是“断了几次、最长连了多久、平均一次多久”，
 * 这些值之前需要导出 Excel 手算，这里直接在详情页给出。
 */
data class RecordStats(
    val connectCount: Int = 0,
    val disconnectCount: Int = 0,
    val totalConnectedMillis: Long = 0L,
    val totalDisconnectedMillis: Long = 0L,
    val longestSessionMillis: Long = 0L,
    val averageSessionMillis: Long = 0L
)

/**
 * 为记录补齐“距上一次状态变化的时间”和累计连接/断开时长，返回按时间倒序的列表。
 *
 * 编解码切换与电量采样不是状态变化，不参与累计，但同样会带上最近一次状态变化时间。
 */
fun buildRecordTimeline(records: List<RecordInfo>): List<RecordInfo> {
    var lastStateTimestamp = 0L
    var connectionTime = 0L
    var disconnectionTime = 0L
    return records.sortedWith(compareBy<RecordInfo> { it.timestamp }.thenBy { it.id }).map { record ->
        record.lastRecordTime = if (lastStateTimestamp < 1) record.timestamp else lastStateTimestamp
        if (record.isStateEvent()) {
            val timeDiff = record.timestamp - (record.lastRecordTime ?: record.timestamp)
            // 本条为“已连接”，说明上一段是断开时长；反之为连接时长
            if (record.connectState == BluetoothA2dp.STATE_CONNECTED) {
                disconnectionTime += timeDiff
            } else {
                connectionTime += timeDiff
            }
            lastStateTimestamp = record.timestamp
        }
        record.totalConnectionTime = connectionTime
        record.totalDisConnectionTime = disconnectionTime
        record
    }.asReversed()
}

/**
 * 基于 [buildRecordTimeline] 的结果计算统计摘要。
 */
fun computeRecordStats(timeline: List<RecordInfo>): RecordStats {
    val stateEvents = timeline.filter { it.isStateEvent() }
    // 断开记录的 lastRecordTime 指向对应的连接时间，二者之差即一次完整连接时长
    val sessions = stateEvents
        .filter { it.eventType == RecordEventType.DISCONNECTED && it.lastRecordTime != it.timestamp }
        .map { it.timestamp - (it.lastRecordTime ?: it.timestamp) }
        .filter { it > 0 }
    val latest = timeline.firstOrNull()
    return RecordStats(
        connectCount = stateEvents.count { it.eventType == RecordEventType.CONNECTED },
        disconnectCount = stateEvents.count { it.eventType == RecordEventType.DISCONNECTED },
        totalConnectedMillis = latest?.totalConnectionTime ?: 0L,
        totalDisconnectedMillis = latest?.totalDisConnectionTime ?: 0L,
        longestSessionMillis = sessions.maxOrNull() ?: 0L,
        averageSessionMillis = if (sessions.isEmpty()) 0L else sessions.sum() / sessions.size
    )
}

fun RecordInfo.isStateEvent(): Boolean =
    eventType == RecordEventType.CONNECTED || eventType == RecordEventType.DISCONNECTED
