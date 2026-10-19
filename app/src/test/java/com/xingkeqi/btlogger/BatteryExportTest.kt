package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.data.DEVICE_BATTERY_LEVEL_UNKNOWN
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.data.RecordEventType
import com.xingkeqi.btlogger.data.RecordInfo
import com.xingkeqi.btlogger.data.buildBatteryExport
import com.xingkeqi.btlogger.data.parseExportedRows
import com.xingkeqi.btlogger.utils.readExportSheet
import com.xingkeqi.btlogger.utils.saveDataToSheet
import jxl.Workbook
import org.junit.Assert.assertEquals
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
        assertEquals(17.1, session.ratePerHour!!, 1e-9)
        assertEquals(5.8, session.fullRuntimeHours!!, 1e-9)
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
            assertEquals("17.1", stats.getCell(9, 1).contents)
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

    private companion object {
        const val BUDS = "AA:BB:CC:DD:EE:01"
        const val CAR = "AA:BB:CC:DD:EE:02"
    }
}
