package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.data.CODEC_LIST_UNAVAILABLE
import com.xingkeqi.btlogger.data.DEVICE_BATTERY_LEVEL_UNKNOWN
import com.xingkeqi.btlogger.data.EXPORT_TIME_PATTERN
import com.xingkeqi.btlogger.data.RecordEventType
import com.xingkeqi.btlogger.data.parseExportedRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class RecordImportTest {

    private val utc = SimpleDateFormat(EXPORT_TIME_PATTERN, Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /** v1.3.0 ~ v1.4.0 的导出表头 */
    private val header = listOf(
        "设备名称", "设备别名", "蓝牙地址", "记录时间", "绑定状态", "设备类型", "蓝牙版本",
        "事件", "手机电量", "耳机电量", "媒体音量", "正在播放", "UUID",
        "手机支持编解码", "双方可用编解码", "当前使用编解码"
    )

    @Test
    fun parseExportedRows_restoresRecordAndDevice() {
        val rows = listOf(
            listOf(
                "WH-1000XM4", "我的耳机", "AA:BB:CC:DD:EE:FF", "2026-10-01 08:30:15", "已配对", "经典蓝牙设备",
                "5.0", "已断开", "87", "未上报", "60%", "是", "uuid-1", "SBC, AAC, LDAC", "SBC, LDAC", "LDAC"
            )
        )
        val result = parseExportedRows(header, rows, utc).single()

        assertEquals("AA:BB:CC:DD:EE:FF", result.device.mac)
        assertEquals("WH-1000XM4", result.device.name)
        assertEquals("我的耳机", result.device.alias)
        assertEquals(12, result.device.bondState)
        assertEquals(1, result.device.deviceType)
        assertEquals("5.0", result.device.bluetoothVersion)
        assertEquals("LDAC", result.device.latestActiveCodec)

        val record = result.record
        assertEquals(1_790_843_415_000L, record.timestamp)
        assertEquals(RecordEventType.DISCONNECTED, record.eventType)
        assertEquals(0, record.connectState)
        assertEquals(87, record.batteryLevel)
        assertEquals(DEVICE_BATTERY_LEVEL_UNKNOWN, record.headsetBatteryLevel)
        assertEquals(60, record.volume)
        assertTrue(record.isPlaying)
        assertEquals("SBC, LDAC", record.negotiableCodecs)
    }

    @Test
    fun parseExportedRows_readsLegacyHeaderAndSkipsInvalidRows() {
        // v1.2.x：事件列名为“连接状态”，没有媒体音量列
        val legacyHeader = listOf("设备名称", "设备别名", "蓝牙地址", "记录时间", "连接状态", "耳机电量")
        val rows = listOf(
            listOf("Buds", "", "11:22:33:44:55:66", "2025-01-02 03:04:05", "电量更新", "45"),
            listOf("Buds", "", "", "2025-01-02 03:04:06", "已连接", "45"),
            listOf("Buds", "", "11:22:33:44:55:66", "不是时间", "已连接", "45")
        )
        val result = parseExportedRows(legacyHeader, rows, utc)

        assertEquals(1, result.size)
        val record = result.single().record
        assertEquals(RecordEventType.BATTERY_CHANGED, record.eventType)
        assertEquals(2, record.connectState)
        assertEquals(45, record.headsetBatteryLevel)
        assertEquals(0, record.volume)
        assertFalse(record.isPlaying)
        assertEquals(CODEC_LIST_UNAVAILABLE, record.phoneSupportedCodecs)
    }

    @Test
    fun parseExportedRows_readsEarbudLevelsAndTreatsBlankAsUnknown() {
        val earbudHeader = listOf("设备名称", "蓝牙地址", "记录时间", "事件", "耳机电量", "左耳电量", "右耳电量", "充电仓电量")
        val rows = listOf(
            listOf("SOUNDPEATS", "11:22:33:44:55:66", "2026-10-19 19:25:26", "电量更新", "95", "95", "100", "60"),
            listOf("SOUNDPEATS", "11:22:33:44:55:66", "2026-10-19 19:15:52", "已连接", "未上报", "", "", "")
        )
        val (update, connect) = parseExportedRows(earbudHeader, rows, utc).map { it.record }

        assertEquals(listOf(95, 95, 100, 60), listOf(update.headsetBatteryLevel, update.leftBatteryLevel, update.rightBatteryLevel, update.caseBatteryLevel))
        assertEquals(
            List(4) { DEVICE_BATTERY_LEVEL_UNKNOWN },
            listOf(connect.headsetBatteryLevel, connect.leftBatteryLevel, connect.rightBatteryLevel, connect.caseBatteryLevel)
        )
    }
}
