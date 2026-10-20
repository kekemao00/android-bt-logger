package com.xingkeqi.btlogger.data

import com.xingkeqi.btlogger.domain.battery.BatteryChannel
import com.xingkeqi.btlogger.domain.battery.availableChannels
import com.xingkeqi.btlogger.domain.battery.computeDrainStats
import com.xingkeqi.btlogger.domain.battery.forChannel
import com.xingkeqi.btlogger.domain.battery.levelOf
import com.xingkeqi.btlogger.domain.battery.splitBatterySessions
import kotlin.math.roundToLong

/**
 * 导出“电量统计”表的一行：一台耳机的一次连接、一个电量来源。
 *
 * 数值列保持为数字（而不是带单位的文本），测试同事可以直接在 Excel 里排序、求平均或画图。
 *
 * @param channel 电量来源；有左右耳数据的连接按左耳、右耳、系统上报各占一行
 */
data class BatterySessionRow(
    val name: String,
    val mac: String,
    val channel: BatteryChannel,
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

/**
 * 导出“耳机电量明细”表的一行：一条带电量的记录。
 *
 * @param level 系统上报的耳机电量；只有左右耳电量的记录为 null
 */
data class HeadsetSampleRow(
    val name: String,
    val mac: String,
    val connectedAt: Long,
    val timestamp: Long,
    val minutesSinceConnect: Double,
    val level: Int?,
    val volume: Int,
    val isPlaying: Boolean,
    val leftLevel: Int? = null,
    val rightLevel: Int? = null,
    val caseLevel: Int? = null
)

data class BatteryExport(
    val sessions: List<BatterySessionRow>,
    val samples: List<HeadsetSampleRow>
)

/**
 * 按设备、按连接整理耳机电量，供导出使用；没有任何电量的连接（如车机）不输出。
 *
 * 统计按来源分行：每个来源单独切分测速，互不影响；连接与断开对所有来源相同，各来源的连接边界一致。
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
        val readings = ordered.map { it.toBatteryReading() }
        // 统计行按“系统上报、左耳、右耳”的顺序，与只有系统电量的旧导出保持一致的首行
        val channels = availableChannels(readings).sortedBy { it != BatteryChannel.HEADSET }
        val perChannel = channels.associateWith { channel ->
            splitBatterySessions(readings.map { it.forChannel(channel) }, now)
        }
        val baseSessions = splitBatterySessions(readings, now)
        baseSessions.forEachIndexed { index, session ->
            channels.forEach { channel ->
                val channelSession = perChannel.getValue(channel).getOrNull(index) ?: return@forEach
                if (channelSession.headset.isEmpty()) return@forEach
                val stats = computeDrainStats(listOf(channelSession.headset))
                sessions += BatterySessionRow(
                    name = name,
                    mac = mac,
                    channel = channel,
                    connectedAt = channelSession.connectedAt,
                    endedAt = channelSession.endTime,
                    ended = channelSession.ended,
                    durationMinutes = minutes(channelSession.endTime - channelSession.connectedAt),
                    startLevel = stats?.startLevel,
                    endLevel = stats?.endLevel,
                    consumed = stats?.consumed,
                    ratePerHour = stats?.ratePerHour?.let(::oneDecimal),
                    fullRuntimeHours = stats?.estimatedFullRuntimeMillis?.let { oneDecimal(it / HOUR_MILLIS) },
                    sampleCount = channelSession.headset.size
                )
            }
            session.readings.forEach { reading ->
                val levels = BatteryChannel.entries.associateWith { reading.levelOf(it) }
                if (levels.values.all { it == null } && reading.caseLevel == null) return@forEach
                samples += HeadsetSampleRow(
                    name = name,
                    mac = mac,
                    connectedAt = session.connectedAt,
                    timestamp = reading.timestamp,
                    minutesSinceConnect = minutes(reading.timestamp - session.connectedAt),
                    level = levels[BatteryChannel.HEADSET],
                    volume = reading.volume,
                    isPlaying = reading.isPlaying,
                    leftLevel = levels[BatteryChannel.LEFT],
                    rightLevel = levels[BatteryChannel.RIGHT],
                    caseLevel = reading.caseLevel
                )
            }
        }
    }
    return BatteryExport(sessions, samples)
}

private fun minutes(millis: Long): Double = oneDecimal(millis / 60_000.0)

private fun oneDecimal(value: Double): Double = (value * 10).roundToLong() / 10.0

private const val HOUR_MILLIS = 3_600_000.0
