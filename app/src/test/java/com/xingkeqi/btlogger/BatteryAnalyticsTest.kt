package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.domain.battery.BatteryChannel
import com.xingkeqi.btlogger.domain.battery.BatteryRange
import com.xingkeqi.btlogger.domain.battery.BatteryReading
import com.xingkeqi.btlogger.domain.battery.DeviceBatteryHistory
import com.xingkeqi.btlogger.domain.battery.LevelPoint
import com.xingkeqi.btlogger.domain.battery.ReadingKind
import com.xingkeqi.btlogger.domain.battery.availableChannels
import com.xingkeqi.btlogger.domain.battery.buildBatteryChannelOverview
import com.xingkeqi.btlogger.domain.battery.buildBatteryOverview
import com.xingkeqi.btlogger.domain.battery.buildComparisonCandidates
import com.xingkeqi.btlogger.domain.battery.clippedFrom
import com.xingkeqi.btlogger.domain.battery.compressPlateaus
import com.xingkeqi.btlogger.domain.battery.computeDrainStats
import com.xingkeqi.btlogger.domain.battery.LevelStep
import com.xingkeqi.btlogger.domain.battery.lastPointAtOrBefore
import com.xingkeqi.btlogger.domain.battery.levelStepAt
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
    fun splitBatterySessions_closesSessionWhenDisconnectWasMissed() {
        val sessions = splitBatterySessions(
            listOf(
                connect(t0, headset = 100),
                sample(t0 + hour, headset = 90),
                // 断开没记下来（服务被杀），几小时后又连上
                connect(t0 + 5 * hour, headset = 100),
                sample(t0 + 6 * hour, headset = 90),
                disconnect(t0 + 7 * hour, headset = 80)
            ),
            now = t0 + 8 * hour
        )

        assertEquals(2, sessions.size)
        assertTrue(sessions[0].ended)
        assertEquals(t0 + hour, sessions[0].endTime)
        assertEquals(listOf(100, 90), sessions[0].headset.map { it.level })
        assertEquals(t0 + 5 * hour, sessions[1].startTime)
        assertEquals(listOf(100, 90, 80), sessions[1].headset.map { it.level })
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
        // 第二段的测速区间从第一次跳档（90%）开始，之后只有回升，不计下降也不计时长
        assertEquals(20.0, stats.ratePerHour!!, 1e-9)
        assertEquals(95, stats.endLevel)
    }

    @Test
    fun computeDrainStats_measuresBetweenLevelChanges() {
        // 连接时停在 100% 这一档的中间：整段计算会得到 30% / 2.5h = 12%/h，跳档之间才是真实的 10%/h
        val midStep = computeDrainStats(
            listOf(listOf(100 to 0, 90 to 30, 80 to 90, 70 to 150).map { (level, at) -> LevelPoint(t0 + at * minute, level) })
        )!!
        assertEquals(10.0, midStep.ratePerHour!!, 1e-9)
        assertEquals(30, midStep.consumed)
        assertEquals(150 * minute, midStep.trackedMillis)

        // 最后一次跳档之后到断开没有下降，也不计入
        val tail = computeDrainStats(
            listOf(listOf(100 to 0, 90 to 60, 80 to 120, 80 to 200).map { (level, at) -> LevelPoint(t0 + at * minute, level) })
        )!!
        assertEquals(10.0, tail.ratePerHour!!, 1e-9)

        // 区间内的回升（充电）不计下降也不计时长
        val charging = computeDrainStats(
            listOf(listOf(100 to 0, 90 to 60, 95 to 70, 85 to 130, 75 to 190).map { (level, at) -> LevelPoint(t0 + at * minute, level) })
        )!!
        assertEquals(10.0, charging.ratePerHour!!, 1e-9)

        // 只有一次跳档时退回整段
        val single = computeDrainStats(listOf(listOf(LevelPoint(t0, 100), LevelPoint(t0 + 40 * minute, 90))))!!
        assertEquals(15.0, single.ratePerHour!!, 1e-9)
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
        assertEquals(t0, clipped.connectedAt)
        assertEquals(t0, clipped.carriedSince)
        // 跳档前那一档从连接时就开始了，而不是从范围起点开始
        assertEquals(2 * hour, levelStepAt(clipped.headset, 1, clipped.carriedSince)!!.millis)
        assertEquals(hour, levelStepAt(clipped.headset, 1)!!.millis)
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

        // 横轴不为没上报电量的连接留空白
        val leadingCarKit = buildBatteryOverview(
            listOf(connect(t0, headset = null), disconnect(t0 + hour)) + readings.drop(2),
            BatteryRange.ALL,
            now
        )
        assertEquals(t0 + 30 * hour, leadingCarKit.windowStart)
    }

    @Test
    fun splitBatterySessions_extendsOngoingSessionToNow() {
        val readings = listOf(connect(t0, headset = 100), sample(t0 + hour, headset = 90))

        // 电量没变化就没有新记录，但连接仍在继续：仍在连接的一段以当前时间结束
        assertEquals(t0 + 3 * hour, splitBatterySessions(readings, now = t0 + 3 * hour).single().endTime)
        assertEquals(t0 + hour, splitBatterySessions(readings).single().endTime)
        // 时钟回拨时不缩短
        assertEquals(t0 + hour, splitBatterySessions(readings, now = t0).single().endTime)
        // 已断开的一段不受影响
        val ended = splitBatterySessions(readings + disconnect(t0 + 2 * hour, headset = 90), now = t0 + 5 * hour).single()
        assertEquals(t0 + 2 * hour, ended.endTime)
    }

    @Test
    fun batteryOverview_remainingDeductsTimeSinceLastDrop() {
        val readings = listOf(
            connect(t0, headset = 100),
            sample(t0 + hour, headset = 90),
            sample(t0 + 2 * hour, headset = 80)
        )

        val justDropped = buildBatteryOverview(readings, BatteryRange.LATEST_SESSION, now = t0 + 2 * hour)
        assertEquals(8 * hour, justDropped.estimatedRemainingMillis)

        // 最后一次掉电后又过了 30 分钟：窗口延伸到当前，剩余扣掉这 30 分钟，掉电统计仍只用实测点
        val later = buildBatteryOverview(readings, BatteryRange.LATEST_SESSION, now = t0 + 2 * hour + 30 * minute)
        assertEquals(t0 + 2 * hour + 30 * minute, later.windowEnd)
        assertEquals(7 * hour + 30 * minute, later.estimatedRemainingMillis)
        assertEquals(2 * hour, later.stats!!.trackedMillis)

        // 超出估算（长时间停在同一档）时不给出 0，而是数据不足
        assertNull(buildBatteryOverview(readings, BatteryRange.LATEST_SESSION, now = t0 + 11 * hour).estimatedRemainingMillis)

        val ended = buildBatteryOverview(readings + disconnect(t0 + 3 * hour, headset = 80), BatteryRange.LATEST_SESSION, now = t0 + 4 * hour)
        assertNull(ended.estimatedRemainingMillis)
        assertEquals(t0 + 3 * hour, ended.windowEnd)
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
                // 测完后又连了一会儿、电量没掉，不应挤掉上面的测试
                connect(t0 + 5 * hour, headset = 100),
                disconnect(t0 + 5 * hour + 20 * minute, headset = 100)
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
        assertEquals(2 * hour, budsCandidate.relativeEnd)
        assertEquals(20.0, budsCandidate.stats!!.ratePerHour!!, 1e-9)

        // 仍在连接的音箱：最后一个电量保持到当前时间
        val ongoing = buildComparisonCandidates(listOf(speaker), now = t0 + 6 * hour).single()
        assertEquals(3 * hour, ongoing.relativeEnd)
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

    @Test
    fun levelStepAt_measuresHowLongThePreviousLevelLasted() {
        val points = listOf(100 to 0, 100 to 10, 90 to 14, 90 to 40, 80 to 42).map { (level, at) ->
            LevelPoint(t0 + at * minute, level)
        }

        assertNull(levelStepAt(points, 0))
        assertEquals(LevelStep(delta = -10, millis = 14 * minute), levelStepAt(points, 2))
        assertEquals(LevelStep(delta = 0, millis = 26 * minute), levelStepAt(points, 3))
        assertEquals(LevelStep(delta = -10, millis = 28 * minute), levelStepAt(points, 4))
        assertNull(levelStepAt(points, 5))
    }

    /** 左右耳电量由私有协议读取：系统上报跟着左耳，右耳掉得更慢 */
    private fun earbuds(at: Long, kind: ReadingKind, headset: Int?, left: Int?, right: Int?) =
        BatteryReading(t0 + at * minute, kind, headset, leftLevel = left, rightLevel = right)

    private val earbudReadings = listOf(
        earbuds(0, ReadingKind.SESSION_START, null, null, null),
        earbuds(1, ReadingKind.SAMPLE, 100, 100, 100),
        earbuds(20, ReadingKind.SAMPLE, 95, 95, 100),
        earbuds(30, ReadingKind.SAMPLE, 95, 95, 95),
        earbuds(40, ReadingKind.SAMPLE, 90, 90, 95),
        earbuds(60, ReadingKind.SAMPLE, 90, 90, 90),
        earbuds(70, ReadingKind.SESSION_END, 90, 90, 90)
    )

    @Test
    fun availableChannels_listsEarsBeforeSystemLevel() {
        assertEquals(listOf(BatteryChannel.LEFT, BatteryChannel.RIGHT, BatteryChannel.HEADSET), availableChannels(earbudReadings))
        assertEquals(listOf(BatteryChannel.HEADSET), availableChannels(listOf(connect(t0, 80), sample(t0 + minute, 70))))
        // 一个电量都没有时仍给出系统上报，界面显示“没有上报电量”
        assertEquals(listOf(BatteryChannel.HEADSET), availableChannels(listOf(connect(t0), disconnect(t0 + minute))))
    }

    @Test
    fun buildBatteryChannelOverview_defaultsToLeftAndOverlaysBothEars() {
        val overview = buildBatteryChannelOverview(earbudReadings, BatteryRange.ALL, now = t0 + 2 * hour)

        assertEquals(BatteryChannel.LEFT, overview.selected)
        assertEquals(listOf(BatteryChannel.LEFT, BatteryChannel.RIGHT), overview.plotted)
        // 左耳：95%（20 分）→ 90%（40 分），右耳：95%（30 分）→ 90%（60 分）
        assertEquals(15.0, overview.overview.stats!!.ratePerHour!!, 1e-9)
        assertEquals(10.0, overview.overviews.getValue(BatteryChannel.RIGHT).stats!!.ratePerHour!!, 1e-9)
        assertEquals(listOf(100, 95, 95, 90, 90, 90), overview.overview.sessions.single().headset.map { it.level })
    }

    @Test
    fun buildBatteryChannelOverview_fallsBackWhenRequestedChannelHasNoData() {
        val systemOnly = listOf(connect(t0, 80), sample(t0 + 30 * minute, 70), disconnect(t0 + hour, 70))

        val fallback = buildBatteryChannelOverview(systemOnly, BatteryRange.ALL, now = t0 + 2 * hour, requested = BatteryChannel.RIGHT)
        assertEquals(BatteryChannel.HEADSET, fallback.selected)
        assertEquals(listOf(BatteryChannel.HEADSET), fallback.channels)
        assertEquals(listOf(BatteryChannel.HEADSET), fallback.plotted)

        val system = buildBatteryChannelOverview(earbudReadings, BatteryRange.ALL, now = t0 + 2 * hour, requested = BatteryChannel.HEADSET)
        assertEquals(BatteryChannel.HEADSET, system.selected)
        // 选中系统上报时只画它，不与左右耳叠加
        assertEquals(listOf(BatteryChannel.HEADSET), system.plotted)
        assertEquals(15.0, system.overview.stats!!.ratePerHour!!, 1e-9)
    }

    @Test
    fun buildBatteryChannelOverview_defaultsToChannelWithDataInRange() {
        // 第一次连接开过左右耳读取，最近一次没有开：“本次连接”默认显示系统上报，而不是空的左耳
        val readings = earbudReadings + listOf(
            connect(t0 + 3 * hour, 60),
            sample(t0 + 3 * hour + 30 * minute, 50),
            disconnect(t0 + 4 * hour, 50)
        )

        val latest = buildBatteryChannelOverview(readings, BatteryRange.LATEST_SESSION, now = t0 + 5 * hour)
        assertEquals(BatteryChannel.HEADSET, latest.selected)
        assertEquals(listOf(BatteryChannel.LEFT, BatteryChannel.RIGHT, BatteryChannel.HEADSET), latest.channels)
        assertTrue(latest.overview.hasData)

        // 用户明确选了左耳就保持左耳，即使这次连接没有数据
        val requested = buildBatteryChannelOverview(readings, BatteryRange.LATEST_SESSION, now = t0 + 5 * hour, requested = BatteryChannel.LEFT)
        assertEquals(BatteryChannel.LEFT, requested.selected)
        assertFalse(requested.overview.hasData)
        assertEquals(emptyList<BatteryChannel>(), requested.plotted)
    }
}
