package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.data.DEVICE_BATTERY_LEVEL_UNKNOWN
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.domain.battery.BatteryChannel
import com.xingkeqi.btlogger.data.RecordEventType
import com.xingkeqi.btlogger.data.RecordInfo
import com.xingkeqi.btlogger.data.buildBatteryExport
import com.xingkeqi.btlogger.data.parseExportedRows
import com.xingkeqi.btlogger.utils.readExportSheet
import com.xingkeqi.btlogger.utils.saveDataToSheet
import jxl.Workbook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class BatteryExportTest {

    private val t0 = 1_700_000_000_000L
    private val minute = 60_000L

    private fun record(id: Int, mac: String, at: Long, event: String, headset: Int) = RecordInfo(
        id = id,
        mac = mac,
        name = if (mac == BUDS) "Buds" else "Car",
        timestamp = at,
        connectState = if (event == RecordEventType.DISCONNECTED) 0 else 2,
        batteryLevel = DEVICE_BATTERY_LEVEL_UNKNOWN,
        headsetBatteryLevel = headset,
        eventType = event,
        volume = 60
    )

    private val records = listOf(
        record(1, BUDS, t0, RecordEventType.CONNECTED, 100),
        record(2, BUDS, t0 + 30 * minute, RecordEventType.BATTERY_CHANGED, 90),
        record(3, BUDS, t0 + 40 * minute, RecordEventType.CODEC_CHANGED, 90),
        record(4, BUDS, t0 + 60 * minute, RecordEventType.BATTERY_CHANGED, 80),
        record(5, BUDS, t0 + 70 * minute, RecordEventType.DISCONNECTED, 80),
        // 车机不上报电量，不应出现在电量统计里
        record(6, CAR, t0, RecordEventType.CONNECTED, DEVICE_BATTERY_LEVEL_UNKNOWN),
        record(7, CAR, t0 + 10 * minute, RecordEventType.DISCONNECTED, DEVICE_BATTERY_LEVEL_UNKNOWN)
    ).shuffled(java.util.Random(3))

    @Test
    fun buildBatteryExport_summarisesEachHeadsetSession() {
        val export = buildBatteryExport(records)

        val session = export.sessions.single()
        assertEquals(BUDS, session.mac)
        assertEquals(t0, session.connectedAt)
        assertTrue(session.ended)
        assertEquals(70.0, session.durationMinutes, 1e-9)
        assertEquals(100, session.startLevel)
        assertEquals(80, session.endLevel)
        assertEquals(20, session.consumed)
        // 测速区间：90%（30 分）→ 80%（60 分）
        assertEquals(20.0, session.ratePerHour!!, 1e-9)
        assertEquals(5.0, session.fullRuntimeHours!!, 1e-9)
        assertEquals(5, session.sampleCount)

        assertEquals(listOf(0.0, 30.0, 40.0, 60.0, 70.0), export.samples.map { it.minutesSinceConnect })
        assertEquals(listOf(100, 90, 90, 80, 80), export.samples.map { it.level })
    }

    @Test
    fun saveDataToSheet_splitsLargeTablesAndReadsThemBack() {
        val dir = Files.createTempDirectory("export").toFile()
        val file = saveDataToSheet(dir, DeviceInfo(name = "全部设备"), records, maxRowsPerSheet = 3)

        val workbook = Workbook.getWorkbook(file)
        try {
            assertEquals(
                listOf("连接记录", "连接记录 2", "连接记录 3", "电量统计", "耳机电量明细", "耳机电量明细 2"),
                workbook.sheetNames.toList()
            )
            val stats = workbook.getSheet("电量统计")
            assertEquals("系统上报", stats.getCell(2, 1).contents)
            assertEquals("20", stats.getCell(10, 1).contents)
        } finally {
            workbook.close()
        }

        val (header, rows) = file.inputStream().use { readExportSheet(it) }
        assertTrue("手机电量" !in header)
        val imported = parseExportedRows(header, rows)
        assertEquals(records.size, imported.size)
        assertEquals(
            records.filter { it.mac == BUDS }.map { it.headsetBatteryLevel }.sorted(),
            imported.filter { it.record.deviceMac == BUDS }.map { it.record.headsetBatteryLevel }.sorted()
        )
        dir.deleteRecursively()
    }

    /** SOUNDPEATS 私有协议读到左右耳与充电仓电量；系统上报跟着左耳 */
    private fun earbudRecord(id: Int, at: Long, event: String, headset: Int, left: Int, right: Int, case: Int = -1) =
        record(id, BUDS, t0 + at * minute, event, headset).copy(
            leftBatteryLevel = left,
            rightBatteryLevel = right,
            caseBatteryLevel = case
        )

    private val earbudRecords = listOf(
        earbudRecord(11, 0, RecordEventType.CONNECTED, 100, -1, -1),
        earbudRecord(12, 1, RecordEventType.BATTERY_CHANGED, 100, 100, 100, 60),
        earbudRecord(13, 20, RecordEventType.BATTERY_CHANGED, 95, 95, 100, 60),
        earbudRecord(14, 30, RecordEventType.BATTERY_CHANGED, 95, 95, 95, 60),
        earbudRecord(15, 40, RecordEventType.BATTERY_CHANGED, 90, 90, 95, 60),
        earbudRecord(16, 60, RecordEventType.BATTERY_CHANGED, 90, 90, 90, 60),
        earbudRecord(17, 70, RecordEventType.DISCONNECTED, 90, 90, 90, 60)
    ).shuffled(java.util.Random(5))

    @Test
    fun buildBatteryExport_splitsStatsByChannel() {
        val export = buildBatteryExport(earbudRecords)

        assertEquals(listOf(BatteryChannel.HEADSET, BatteryChannel.LEFT, BatteryChannel.RIGHT), export.sessions.map { it.channel })
        assertEquals(listOf(15.0, 15.0, 10.0), export.sessions.map { it.ratePerHour })
        assertEquals(listOf(7, 6, 6), export.sessions.map { it.sampleCount })

        val first = export.samples.first()
        assertEquals(100, first.level)
        assertNull(first.leftLevel)
        assertNull(first.caseLevel)
        assertEquals(listOf(100, 95, 95, 90, 90, 90), export.samples.mapNotNull { it.leftLevel })
        assertEquals(listOf(100, 100, 95, 95, 90, 90), export.samples.mapNotNull { it.rightLevel })
        assertEquals(60, export.samples.last().caseLevel)
    }

    @Test
    fun saveDataToSheet_roundTripsEarbudLevels() {
        val dir = Files.createTempDirectory("export-earbuds").toFile()
        val file = saveDataToSheet(dir, DeviceInfo(name = "Buds"), earbudRecords)

        val workbook = Workbook.getWorkbook(file)
        try {
            val stats = workbook.getSheet("电量统计")
            assertEquals(listOf("系统上报", "左耳", "右耳"), (1..3).map { stats.getCell(2, it).contents })
        } finally {
            workbook.close()
        }

        val (header, rows) = file.inputStream().use { readExportSheet(it) }
        val imported = parseExportedRows(header, rows).map { it.record }.sortedBy { it.timestamp }
        val expected = earbudRecords.sortedBy { it.timestamp }
        assertEquals(expected.map { it.leftBatteryLevel }, imported.map { it.leftBatteryLevel })
        assertEquals(expected.map { it.rightBatteryLevel }, imported.map { it.rightBatteryLevel })
        assertEquals(expected.map { it.caseBatteryLevel }, imported.map { it.caseBatteryLevel })
        dir.deleteRecursively()
    }

    private companion object {
        const val BUDS = "AA:BB:CC:DD:EE:01"
        const val CAR = "AA:BB:CC:DD:EE:02"
    }
}
