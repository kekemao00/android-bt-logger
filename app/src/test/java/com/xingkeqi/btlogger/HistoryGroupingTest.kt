package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.domain.history.HistoryEntry
import com.xingkeqi.btlogger.domain.history.HistoryEventKind
import com.xingkeqi.btlogger.domain.history.HistoryEventKind.CONNECTED
import com.xingkeqi.btlogger.domain.history.HistoryEventKind.DISCONNECTED
import com.xingkeqi.btlogger.domain.history.HistoryEventKind.OTHER
import com.xingkeqi.btlogger.domain.history.groupHistory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryGroupingTest {

    private data class Event(val id: Int, val at: Long, val kind: HistoryEventKind)

    private val t0 = 1_700_000_000_000L

    /** 按旧→新写用例更直观，传给 [groupHistory] 前反转为列表顺序（新→旧） */
    private fun group(vararg ascending: Event): List<HistoryEntry<Event>> =
        groupHistory(ascending.toList().asReversed(), timestampOf = { it.at }, kindOf = { it.kind })

    private fun HistoryEntry<Event>.ids() = items.map { it.id }

    @Test
    fun normalSessions_stayOneRowEach() {
        // 与 v1.7.0 导出中 09:10–09:14 的手动连断一致：间隔 20 秒左右，逐条显示
        val entries = group(
            Event(1, t0, CONNECTED),
            Event(2, t0 + 1_000, OTHER),
            Event(3, t0 + 21_000, DISCONNECTED),
            Event(4, t0 + 43_000, CONNECTED),
            Event(5, t0 + 45_000, DISCONNECTED)
        )

        assertTrue(entries.all { it is HistoryEntry.Single })
        assertEquals(listOf(5, 4, 3, 2, 1), entries.map { it.newest.id })
    }

    @Test
    fun consecutiveDisconnects_mergeRegardlessOfGap() {
        val entries = group(
            Event(1, t0, CONNECTED),
            Event(2, t0 + 60_000, DISCONNECTED),
            Event(3, t0 + 3_600_000, DISCONNECTED),
            Event(4, t0 + 7_200_000, DISCONNECTED)
        )

        assertEquals(2, entries.size)
        val repeated = entries.first() as HistoryEntry.Repeated
        assertEquals(DISCONNECTED, repeated.kind)
        assertEquals(listOf(4, 3, 2), repeated.ids())
        assertEquals(2, repeated.oldest.id)
        assertEquals(1, (entries.last() as HistoryEntry.Single).item.id)
    }

    @Test
    fun repeatedRun_breaksOnOtherRecord() {
        // 两条“已连接”之间夹着电量记录：合并会把电量记录藏起来，因此不合并
        val entries = group(
            Event(1, t0, CONNECTED),
            Event(2, t0 + 60_000, OTHER),
            Event(3, t0 + 120_000, CONNECTED)
        )

        assertEquals(3, entries.size)
        assertTrue(entries.all { it is HistoryEntry.Single })
    }

    @Test
    fun burstOfStateEvents_collapsesIntoFlapping() {
        // 还原导出中 19:48:19–20 的抖动：两秒内反复连接/断开，夹杂一条编解码
        val entries = group(
            Event(1, t0, CONNECTED),
            Event(2, t0 + 50, OTHER),
            Event(3, t0 + 120, DISCONNECTED),
            Event(4, t0 + 300, DISCONNECTED),
            Event(5, t0 + 450, CONNECTED),
            Event(6, t0 + 900, DISCONNECTED),
            Event(7, t0 + 1_400, CONNECTED),
            Event(8, t0 + 60_000, OTHER)
        )

        assertEquals(2, entries.size)
        assertEquals(8, (entries.first() as HistoryEntry.Single).item.id)
        val flapping = entries.last() as HistoryEntry.Flapping
        assertEquals(listOf(7, 6, 5, 4, 3, 2, 1), flapping.ids())
        assertEquals(3, flapping.connectCount)
        assertEquals(3, flapping.disconnectCount)
        assertEquals(CONNECTED, flapping.finalKind)
        assertEquals(1, flapping.oldest.id)
    }

    @Test
    fun quickConnectThenDisconnect_isNotFlapping() {
        val entries = group(
            Event(1, t0, CONNECTED),
            Event(2, t0 + 2_000, DISCONNECTED)
        )

        assertTrue(entries.all { it is HistoryEntry.Single })
    }

    @Test
    fun burstOfSameState_isRepeatedNotFlapping() {
        val entries = group(
            Event(1, t0, DISCONNECTED),
            Event(2, t0 + 100, DISCONNECTED),
            Event(3, t0 + 200, DISCONNECTED)
        )

        val repeated = entries.single() as HistoryEntry.Repeated
        assertEquals(listOf(3, 2, 1), repeated.ids())
    }

    @Test
    fun flappingSplitsWhenGapExceedsWindow() {
        val entries = group(
            Event(1, t0, CONNECTED),
            Event(2, t0 + 1_000, DISCONNECTED),
            Event(3, t0 + 2_000, CONNECTED),
            // 30 秒后另一次抖动
            Event(4, t0 + 32_000, DISCONNECTED),
            Event(5, t0 + 33_000, CONNECTED),
            Event(6, t0 + 34_000, DISCONNECTED)
        )

        assertEquals(2, entries.size)
        assertEquals(listOf(6, 5, 4), entries[0].ids())
        assertEquals(listOf(3, 2, 1), entries[1].ids())
        assertTrue(entries.all { it is HistoryEntry.Flapping })
        assertEquals(DISCONNECTED, (entries[0] as HistoryEntry.Flapping).finalKind)
    }

    @Test
    fun groupKeepsOldestWhenNewRecordsArrive() {
        val before = group(
            Event(1, t0, DISCONNECTED),
            Event(2, t0 + 1_000, DISCONNECTED)
        )
        val after = group(
            Event(1, t0, DISCONNECTED),
            Event(2, t0 + 1_000, DISCONNECTED),
            Event(3, t0 + 5_000, DISCONNECTED)
        )

        assertEquals(before.single().oldest.id, after.single().oldest.id)
    }

    @Test
    fun everyRecordAppearsExactlyOnce() {
        var at = t0
        val events = (0 until 40).map { i ->
            val kind = when (i % 5) {
                0, 3 -> CONNECTED
                1, 2 -> DISCONNECTED
                else -> OTHER
            }
            at += if (i % 7 == 0) 30_000L else 400L
            Event(i, at, kind)
        }

        val entries = group(*events.toTypedArray())

        assertEquals(events.map { it.id }.reversed(), entries.flatMap { it.ids() })
    }
}
