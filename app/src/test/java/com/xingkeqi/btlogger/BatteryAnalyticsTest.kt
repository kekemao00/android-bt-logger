package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.domain.battery.BatteryRange
import com.xingkeqi.btlogger.domain.battery.BatteryReading
import com.xingkeqi.btlogger.domain.battery.DeviceBatteryHistory
import com.xingkeqi.btlogger.domain.battery.LevelPoint
import com.xingkeqi.btlogger.domain.battery.ReadingKind
import com.xingkeqi.btlogger.domain.battery.buildBatteryOverview
import com.xingkeqi.btlogger.domain.battery.buildComparisonCandidates
import com.xingkeqi.btlogger.domain.battery.clippedFrom
import com.xingkeqi.btlogger.domain.battery.compressPlateaus
import com.xingkeqi.btlogger.domain.battery.computeDrainStats
import com.xingkeqi.btlogger.domain.battery.lastPointAtOrBefore
import com.xingkeqi.btlogger.domain.battery.nearestPointIndex
import com.xingkeqi.btlogger.domain.battery.splitBatterySessions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryAnalyticsTest {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L
    private val hour = 60 * minute

    private fun connect(at: Long, headset: Int? = null) =
        BatteryReading(at, ReadingKind.SESSION_START, headset)

    private fun sample(at: Long, headset: Int?) =
        BatteryReading(at, ReadingKind.SAMPLE, headset)

    private fun disconnect(at: Long, headset: Int? = null) =
        BatteryReading(at, ReadingKind.SESSION_END, headset)

    @Test
    fun splitBatterySessions_mergesRepeatedConnectsAndIgnoresLoneDisconnect() {
        val sessions = splitBatterySessions(
            listOf(
                disconnect(t0 - hour),
                connect(t0, headset = 100),
                connect(t0 + minute, headset = 100),
                sample(t0 + 30 * minute, headset = 90),
                disconnect(t0 + hour, headset = 80),
                connect(t0 + 2 * hour, headset = 100),
                sample(t0 + 3 * hour, headset = 70)
            ).shuffled(java.util.Random(7))
        )

        assertEquals(2, sessions.size)
        val first = sessions[0]
        assertTrue(first.ended)
        assertEquals(t0, first.startTime)
        assertEquals(t0 + hour, first.endTime)
        assertEquals(listOf(100, 100, 90, 80), first.headset.map { it.level })

        val second = sessions[1]
        assertFalse(second.ended)
        assertEquals(t0 + 3 * hour, second.endTime)
        assertEquals(listOf(100, 70), second.headset.map { it.level })
    }

    @Test
    fun splitBatterySessions_dropsUnknownLevels() {
        val session = splitBatterySessions(
            listOf(connect(t0, headset = null), sample(t0 + minute, headset = 101), sample(t0 + 2 * minute, headset = 60))
        ).single()

        assertEquals(listOf(LevelPoint(t0 + 2 * minute, 60)), session.headset)
        assertEquals(3, session.readings.size)
    }

    @Test
    fun computeDrainStats_usesConsumedOverTrackedTime() {
        val stats = computeDrainStats(
            listOf(
                listOf(LevelPoint(t0, 100), LevelPoint(t0 + hour, 90), LevelPoint(t0 + 2 * hour, 80))
            )
        )!!

        assertEquals(100, stats.startLevel)
        assertEquals(80, stats.endLevel)
        assertEquals(20, stats.consumed)
        assertEquals(2 * hour, stats.trackedMillis)
        assertEquals(10.0, stats.ratePerHour!!, 1e-9)
        assertEquals(8 * hour, stats.estimatedRemainingMillis)
        assertEquals(10 * hour, stats.estimatedFullRuntimeMillis)
    }

    @Test
    fun computeDrainStats_ignoresRisesAndGapsBetweenSegments() {
        val stats = computeDrainStats(
            listOf(
                listOf(LevelPoint(t0, 100), LevelPoint(t0 + hour, 80)),
                // 两段之间断开 5 小时，期间回充到 100%，不应计入消耗或时长
                listOf(LevelPoint(t0 + 6 * hour, 100), LevelPoint(t0 + 6 * hour + 30 * minute, 90), LevelPoint(t0 + 7 * hour, 95))
            )
        )!!

        assertEquals(30, stats.consumed)
        assertEquals(2 * hour, stats.trackedMillis)
        assertEquals(15.0, stats.ratePerHour!!, 1e-9)
        assertEquals(95, stats.endLevel)
    }

    @Test
    fun computeDrainStats_needsEnoughTimeAndAnyDrop() {
        val tooShort = computeDrainStats(listOf(listOf(LevelPoint(t0, 100), LevelPoint(t0 + 5 * minute, 90))))!!
        assertNull(tooShort.ratePerHour)
        assertNull(tooShort.estimatedRemainingMillis)

        val flat = computeDrainStats(listOf(listOf(LevelPoint(t0, 100), LevelPoint(t0 + hour, 100))))!!
        assertNull(flat.ratePerHour)

        assertNull(computeDrainStats(listOf(emptyList())))
    }

    @Test
    fun clippedFrom_carriesLevelAtWindowStart() {
        val session = splitBatterySessions(
            listOf(connect(t0, headset = 100), sample(t0 + 2 * hour, headset = 70))
        ).single()

        val clipped = session.clippedFrom(t0 + hour)!!
        assertEquals(t0 + hour, clipped.startTime)
        assertEquals(listOf(LevelPoint(t0 + hour, 100), LevelPoint(t0 + 2 * hour, 70)), clipped.headset)

        assertNull(session.clippedFrom(t0 + 3 * hour))
        assertEquals(session, session.clippedFrom(t0))
    }

    @Test
    fun buildBatteryOverview_selectsRange() {
        val readings = listOf(
            connect(t0, headset = 100),
            disconnect(t0 + hour, headset = 90),
            connect(t0 + 30 * hour, headset = 100),
            sample(t0 + 31 * hour, headset = 90),
            sample(t0 + 32 * hour, headset = 80)
        )
        val now = t0 + 32 * hour

        val latest = buildBatteryOverview(readings, BatteryRange.LATEST_SESSION, now)
        assertEquals(1, latest.sessions.size)
        assertTrue(latest.ongoing)
        assertEquals(t0 + 30 * hour, latest.windowStart)
        assertEquals(10.0, latest.stats!!.ratePerHour!!, 1e-9)

        val lastDay = buildBatteryOverview(readings, BatteryRange.LAST_24_HOURS, now)
        assertEquals(1, lastDay.sessions.size)

        val all = buildBatteryOverview(readings, BatteryRange.ALL, now)
        assertEquals(2, all.sessions.size)
        assertEquals(30, all.stats!!.consumed)
        assertEquals(3 * hour, all.stats!!.trackedMillis)

        val empty = buildBatteryOverview(emptyList(), BatteryRange.ALL, now)
        assertFalse(empty.hasData)
        assertNull(empty.stats)
    }

    @Test
    fun buildComparisonCandidates_prefersLatestMeaningfulSessionAndSkipsDevicesWithoutHeadset() {
        val buds = DeviceBatteryHistory(
            key = "AA",
            name = "Buds",
            readings = listOf(
                connect(t0, headset = 100),
                sample(t0 + hour, headset = 80),
                disconnect(t0 + 2 * hour, headset = 60),
                // 测完后短暂连接一次，不应挤掉上面的测试
                connect(t0 + 5 * hour, headset = 100),
                disconnect(t0 + 5 * hour + minute, headset = 100)
            )
        )
        val car = DeviceBatteryHistory(
            key = "BB",
            name = "Car",
            readings = listOf(connect(t0, headset = null), disconnect(t0 + hour, headset = null))
        )
        val speaker = DeviceBatteryHistory(
            key = "CC",
            name = "Speaker",
            readings = listOf(connect(t0 + 3 * hour, headset = 50), sample(t0 + 4 * hour, headset = 45))
        )

        val candidates = buildComparisonCandidates(listOf(buds, car, speaker))

        assertEquals(listOf("CC", "AA"), candidates.map { it.key })
        val budsCandidate = candidates.last()
        assertEquals(t0, budsCandidate.origin)
        assertEquals(listOf(0L, hour, 2 * hour), budsCandidate.relativeHeadset.map { it.timestamp })
        assertEquals(20.0, budsCandidate.stats!!.ratePerHour!!, 1e-9)
    }

    @Test
    fun compressPlateaus_keepsRunBoundaries() {
        val points = listOf(100, 100, 100, 90, 90, 90, 90, 80).mapIndexed { index, level ->
            LevelPoint(t0 + index * minute, level)
        }

        val compressed = compressPlateaus(points)

        assertEquals(listOf(0, 2, 3, 6, 7), compressed.map { ((it.timestamp - t0) / minute).toInt() })
    }

    @Test
    fun nearestAndLastPoint_lookups() {
        val points = listOf(LevelPoint(t0, 100), LevelPoint(t0 + 10 * minute, 90), LevelPoint(t0 + 20 * minute, 80))

        assertEquals(0, nearestPointIndex(points, t0 - hour))
        assertEquals(0, nearestPointIndex(points, t0 + 4 * minute))
        assertEquals(1, nearestPointIndex(points, t0 + 6 * minute))
        assertEquals(2, nearestPointIndex(points, t0 + hour))
        assertEquals(-1, nearestPointIndex(emptyList(), t0))

        assertEquals(90, lastPointAtOrBefore(points, t0 + 19 * minute)?.level)
        assertEquals(80, lastPointAtOrBefore(points, t0 + 20 * minute)?.level)
        assertNull(lastPointAtOrBefore(points, t0 - 1))
        assertNull(lastPointAtOrBefore(points, t0 + 21 * minute))
    }
}
