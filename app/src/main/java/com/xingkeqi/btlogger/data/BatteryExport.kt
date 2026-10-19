package com.xingkeqi.btlogger.data

import com.xingkeqi.btlogger.domain.battery.computeDrainStats
import com.xingkeqi.btlogger.domain.battery.splitBatterySessions
import kotlin.math.roundToLong

/**
 * 导出“电量统计”表的一行：一台耳机的一次连接。
 *
 * 数值列保持为数字（而不是带单位的文本），测试同事可以直接在 Excel 里排序、求平均或画图。
 */
data class BatterySessionRow(
    val name: String,
    val mac: String,
    val connectedAt: Long,
    val endedAt: Long,
    val ended: Boolean,
    val durationMinutes: Double,
    val startLevel: Int?,
    val endLevel: Int?,
    val consumed: Int?,
    val ratePerHour: Double?,
    val fullRuntimeHours: Double?,
    val sampleCount: Int
)

/** 导出“耳机电量明细”表的一行：一条带耳机电量的记录 */
data class HeadsetSampleRow(
    val name: String,
    val mac: String,
    val connectedAt: Long,
    val timestamp: Long,
    val minutesSinceConnect: Double,
    val level: Int,
    val volume: Int,
    val isPlaying: Boolean
)

data class BatteryExport(
    val sessions: List<BatterySessionRow>,
    val samples: List<HeadsetSampleRow>
)

/**
 * 按设备、按连接整理耳机电量，供导出使用；没有任何耳机电量的连接（如车机）不输出。
 *
 * @param records 任意顺序，可包含多台设备
 * @param now 导出时间；仍在连接中的那一段以它为结束时间
 */
fun buildBatteryExport(records: List<RecordInfo>, now: Long? = null): BatteryExport {
    val sessions = mutableListOf<BatterySessionRow>()
    val samples = mutableListOf<HeadsetSampleRow>()
    records.groupBy { it.mac }.forEach { (mac, deviceRecords) ->
        val ordered = deviceRecords.sortedWith(compareBy<RecordInfo> { it.timestamp }.thenBy { it.id })
        val name = ordered.last().name
        splitBatterySessions(ordered.map { it.toBatteryReading() }, now).forEach { session ->
            if (session.headset.isEmpty()) return@forEach
            val stats = computeDrainStats(listOf(session.headset))
            sessions += BatterySessionRow(
                name = name,
                mac = mac,
                connectedAt = session.connectedAt,
                endedAt = session.endTime,
                ended = session.ended,
                durationMinutes = minutes(session.endTime - session.connectedAt),
                startLevel = stats?.startLevel,
                endLevel = stats?.endLevel,
                consumed = stats?.consumed,
                ratePerHour = stats?.ratePerHour?.let(::oneDecimal),
                fullRuntimeHours = stats?.estimatedFullRuntimeMillis?.let { oneDecimal(it / HOUR_MILLIS) },
                sampleCount = session.headset.size
            )
            session.readings.forEach { reading ->
                val level = reading.headsetLevel ?: return@forEach
                samples += HeadsetSampleRow(
                    name = name,
                    mac = mac,
                    connectedAt = session.connectedAt,
                    timestamp = reading.timestamp,
                    minutesSinceConnect = minutes(reading.timestamp - session.connectedAt),
                    level = level,
                    volume = reading.volume,
                    isPlaying = reading.isPlaying
                )
            }
        }
    }
    return BatteryExport(sessions, samples)
}

private fun minutes(millis: Long): Double = oneDecimal(millis / 60_000.0)

private fun oneDecimal(value: Double): Double = (value * 10).roundToLong() / 10.0

private const val HOUR_MILLIS = 3_600_000.0
