package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.data.DEVICE_BATTERY_LEVEL_UNKNOWN
import com.xingkeqi.btlogger.data.HeadsetLevelChange
import com.xingkeqi.btlogger.data.RecordEventType
import com.xingkeqi.btlogger.data.RecordInfo
import com.xingkeqi.btlogger.data.headsetLevelChanges
import org.junit.Assert.assertEquals
import org.junit.Test

class BatteryRecordsTest {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun record(id: Int, at: Long, event: String, headset: Int) = RecordInfo(
        id = id,
        mac = "AA",
        timestamp = t0 + at * minute,
        eventType = event,
        headsetBatteryLevel = headset
    )

    @Test
    fun headsetLevelChanges_describesEachStepWithinAConnection() {
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

        val changes = headsetLevelChanges(records)

        assertEquals(
            mapOf(
                3 to HeadsetLevelChange(previousLevel = 100, heldMillis = 34 * minute),
                5 to HeadsetLevelChange(previousLevel = 90, heldMillis = 36 * minute),
                9 to HeadsetLevelChange(previousLevel = 100, heldMillis = 35 * minute)
            ),
            changes
        )
    }
}
