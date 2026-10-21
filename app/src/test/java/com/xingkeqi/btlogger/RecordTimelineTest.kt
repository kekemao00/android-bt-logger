package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.data.RecordEventType
import com.xingkeqi.btlogger.data.RecordInfo
import com.xingkeqi.btlogger.data.buildRecordTimeline
import com.xingkeqi.btlogger.data.computeRecordStats
import com.xingkeqi.btlogger.utils.getCompactDurationString
import com.xingkeqi.btlogger.utils.getDurationString
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordTimelineTest {

    private val t0 = 1_700_000_000_000L

    private fun connected(id: Int, ts: Long) =
        RecordInfo(id = id, timestamp = ts, connectState = 2, eventType = RecordEventType.CONNECTED)

    private fun disconnected(id: Int, ts: Long) =
        RecordInfo(id = id, timestamp = ts, connectState = 0, eventType = RecordEventType.DISCONNECTED)

    private fun battery(id: Int, ts: Long) =
        RecordInfo(id = id, timestamp = ts, connectState = 2, eventType = RecordEventType.BATTERY_CHANGED)

    @Test
    fun buildRecordTimeline_accumulatesDurationsAndIgnoresSamples() {
        val timeline = buildRecordTimeline(
            listOf(
                disconnected(4, t0 + 7_000),
                connected(1, t0),
                battery(2, t0 + 2_000),
                connected(5, t0 + 10_000),
                disconnected(3, t0 + 4_000)
            )
        )

        assertEquals(listOf(5, 4, 3, 2, 1), timeline.map { it.id })
        val latest = timeline.first()
        // 第二条“已断开”是重复上报：只有首次断开前的 4s 计为连接时长，之后到再次连接的 6s 都是断开时长
        assertEquals(4_000L, latest.totalConnectionTime)
        assertEquals(6_000L, latest.totalDisConnectionTime)
        assertEquals(true, timeline.first { it.id == 4 }.repeatsPreviousState)
        assertEquals(t0 + 4_000, timeline.first { it.id == 4 }.lastRecordTime)
        assertEquals(false, timeline.first { it.id == 5 }.repeatsPreviousState)
        // 电量采样不改变“上一次状态时间”
        assertEquals(t0, timeline.first { it.id == 2 }.lastRecordTime)
    }

    @Test
    fun computeRecordStats_reportsSessions() {
        val timeline = buildRecordTimeline(
            listOf(
                connected(1, t0),
                disconnected(2, t0 + 5_000),
                connected(3, t0 + 6_000),
                disconnected(4, t0 + 7_000)
            )
        )
        val stats = computeRecordStats(timeline)

        assertEquals(2, stats.connectCount)
        assertEquals(2, stats.disconnectCount)
        assertEquals(5_000L, stats.longestSessionMillis)
        assertEquals(3_000L, stats.averageSessionMillis)
        assertEquals(6_000L, stats.totalConnectedMillis)
        assertEquals(1_000L, stats.totalDisconnectedMillis)
    }

    @Test
    fun computeRecordStats_ignoresRepeatedStates() {
        val timeline = buildRecordTimeline(
            listOf(
                connected(1, t0),
                disconnected(2, t0 + 5_000),
                disconnected(3, t0 + 60_000),
                connected(4, t0 + 61_000),
                connected(5, t0 + 62_000),
                disconnected(6, t0 + 64_000)
            )
        )
        val stats = computeRecordStats(timeline)

        assertEquals(2, stats.connectCount)
        assertEquals(2, stats.disconnectCount)
        // 两段连接：5s 与 3s（从第一条“已连接”算起，重复的“已连接”不重新计时）
        assertEquals(5_000L, stats.longestSessionMillis)
        assertEquals(4_000L, stats.averageSessionMillis)
        assertEquals(8_000L, stats.totalConnectedMillis)
        assertEquals(56_000L, stats.totalDisconnectedMillis)
    }

    @Test
    fun getDurationString_formatsZeroAndDays() {
        assertEquals("0 秒", getDurationString(0))
        assertEquals("0 秒", getDurationString(-5))
        assertEquals("1 时", getDurationString(3_600_000))
        assertEquals("1 天 1 时 1 分 1 秒", getDurationString(90_061_000))
    }

    @Test
    fun getCompactDurationString_keepsTwoUnits() {
        assertEquals("0s", getCompactDurationString(0))
        assertEquals("5m20s", getCompactDurationString(320_000))
        assertEquals("2h", getCompactDurationString(7_200_000))
        assertEquals("1d1h", getCompactDurationString(90_061_000))
    }
}
