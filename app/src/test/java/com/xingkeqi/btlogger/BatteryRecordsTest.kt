package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.data.DEVICE_BATTERY_LEVEL_UNKNOWN
import com.xingkeqi.btlogger.data.LevelChange
import com.xingkeqi.btlogger.data.RecordEventType
import com.xingkeqi.btlogger.data.RecordInfo
import com.xingkeqi.btlogger.data.RecordLevelChanges
import com.xingkeqi.btlogger.data.recordLevelChanges
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryRecordsTest {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun record(
        id: Int,
        at: Long,
        event: String,
        headset: Int,
        left: Int = DEVICE_BATTERY_LEVEL_UNKNOWN,
        right: Int = DEVICE_BATTERY_LEVEL_UNKNOWN
    ) = RecordInfo(
        id = id,
        mac = "AA",
        timestamp = t0 + at * minute,
        eventType = event,
        headsetBatteryLevel = headset,
        leftBatteryLevel = left,
        rightBatteryLevel = right
    )

    @Test
    fun recordLevelChanges_describesEachHeadsetStepWithinAConnection() {
        val records = listOf(
            record(1, 0, RecordEventType.CONNECTED, 100),
            record(2, 1, RecordEventType.CODEC_CHANGED, 100),
            record(3, 34, RecordEventType.BATTERY_CHANGED, 90),
            // 改版前的定时采样：电量没变，不算一次跳档
            record(4, 50, RecordEventType.BATTERY_CHANGED, 90),
            record(5, 70, RecordEventType.BATTERY_CHANGED, 80),
            record(6, 80, RecordEventType.DISCONNECTED, 80),
            // 下一次连接重新计算，不与上一次连接的电量比较
            record(7, 200, RecordEventType.CONNECTED, DEVICE_BATTERY_LEVEL_UNKNOWN),
            record(8, 205, RecordEventType.BATTERY_CHANGED, 100),
            record(9, 240, RecordEventType.BATTERY_CHANGED, 90)
        ).shuffled(java.util.Random(7))

        val changes = recordLevelChanges(records).mapValues { it.value.headset }

        assertEquals(
            mapOf(
                3 to LevelChange(previousLevel = 100, heldMillis = 34 * minute),
                5 to LevelChange(previousLevel = 90, heldMillis = 36 * minute),
                9 to LevelChange(previousLevel = 100, heldMillis = 35 * minute)
            ),
            changes
        )
    }

    @Test
    fun recordLevelChanges_tracksEachEarSeparately() {
        val records = listOf(
            record(1, 0, RecordEventType.CONNECTED, 100),
            record(2, 1, RecordEventType.BATTERY_CHANGED, 100, left = 100, right = 100),
            record(3, 20, RecordEventType.BATTERY_CHANGED, 100, left = 95, right = 100),
            record(4, 30, RecordEventType.BATTERY_CHANGED, 100, left = 95, right = 95),
            // 右耳放回充电仓：读不到不算变化，取出后电量相同也不算
            record(5, 40, RecordEventType.BATTERY_CHANGED, 100, left = 95),
            record(6, 45, RecordEventType.BATTERY_CHANGED, 100, left = 95, right = 95),
            record(7, 50, RecordEventType.BATTERY_CHANGED, 90, left = 90, right = 95)
        )

        assertEquals(
            mapOf(
                3 to RecordLevelChanges(left = LevelChange(previousLevel = 100, heldMillis = 19 * minute)),
                4 to RecordLevelChanges(right = LevelChange(previousLevel = 100, heldMillis = 29 * minute)),
                7 to RecordLevelChanges(
                    headset = LevelChange(previousLevel = 100, heldMillis = 50 * minute),
                    left = LevelChange(previousLevel = 95, heldMillis = 30 * minute)
                )
            ),
            recordLevelChanges(records)
        )
    }

    @Test
    fun recordLevelChanges_doesNotCountTimeInCaseAsHoldTime() {
        val records = listOf(
            record(1, 0, RecordEventType.CONNECTED, 80),
            record(2, 1, RecordEventType.BATTERY_CHANGED, 80, left = 60, right = 60),
            // 左耳放回充电仓，充满后取出：不写“60% → 100% · 上一档持续 40 分”
            record(3, 5, RecordEventType.BATTERY_CHANGED, 80, right = 60),
            record(4, 40, RecordEventType.BATTERY_CHANGED, 80, left = 100, right = 60),
            record(5, 50, RecordEventType.BATTERY_CHANGED, 80, left = 95, right = 60)
        )

        assertEquals(
            mapOf(5 to RecordLevelChanges(left = LevelChange(previousLevel = 100, heldMillis = 10 * minute))),
            recordLevelChanges(records)
        )
    }
}
