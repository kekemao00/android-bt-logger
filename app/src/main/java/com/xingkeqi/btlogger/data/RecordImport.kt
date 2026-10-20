package com.xingkeqi.btlogger.data

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 从导出的 Excel 还原出的一条记录：设备快照 + 连接记录
 */
data class ImportedRecord(
    val device: Device,
    val record: DeviceConnectionRecord
)

/**
 * 把导出表格（首行表头 + 数据行）还原为数据库实体。
 *
 * Why: v1.4.0 及更早的版本每次构建签名随机，升级到固定签名的新版本必须卸载重装，
 * 应用私有数据库会随卸载清空。旧版本唯一能带出数据的途径是“导出 Excel”，
 * 因此按表头名称（而不是列序号）读回，兼容 v1.2.x 的“连接状态”列名与后来的“事件”列名。
 * 时间只精确到秒，与导出格式一致。
 *
 * @param timeFormat 与导出时 `TimeUtils.millis2String` 相同的格式，时区取本机默认
 * @return 能解析出蓝牙地址与时间的行；其余行忽略
 */
fun parseExportedRows(
    header: List<String>,
    rows: List<List<String>>,
    timeFormat: SimpleDateFormat = SimpleDateFormat(EXPORT_TIME_PATTERN, Locale.getDefault())
): List<ImportedRecord> {
    val index = header.withIndex().associate { (i, title) -> title.trim() to i }
    fun List<String>.cell(vararg titles: String): String =
        titles.firstNotNullOfOrNull { title -> index[title]?.let { getOrNull(it) } }?.trim().orEmpty()

    return rows.mapNotNull { row ->
        val mac = row.cell("蓝牙地址")
        val timestamp = parseTime(row.cell("记录时间"), timeFormat)
        if (mac.isBlank() || timestamp == null) return@mapNotNull null

        val eventType = eventTypeOf(row.cell("事件", "连接状态"))
        val phoneCodecs = row.cell("手机支持编解码").ifBlank { CODEC_LIST_UNAVAILABLE }
        val negotiableCodecs = row.cell("双方可用编解码").ifBlank { CODEC_LIST_UNAVAILABLE }
        val activeCodec = row.cell("当前使用编解码").ifBlank { CODEC_UNKNOWN }
        val device = Device(
            mac = mac,
            name = row.cell("设备名称"),
            bondState = bondStateOf(row.cell("绑定状态")),
            rssi = null,
            alias = row.cell("设备别名"),
            deviceType = deviceTypeOf(row.cell("设备类型")),
            bluetoothVersion = row.cell("蓝牙版本").ifBlank { BLUETOOTH_VERSION_UNKNOWN },
            uuids = row.cell("UUID"),
            latestPhoneSupportedCodecs = phoneCodecs,
            latestNegotiableCodecs = negotiableCodecs,
            latestActiveCodec = activeCodec,
            latestCodecUpdatedAt = timestamp
        )
        val record = DeviceConnectionRecord(
            deviceMac = mac,
            timestamp = timestamp,
            connectState = if (eventType == RecordEventType.DISCONNECTED) {
                BluetoothA2dp.STATE_DISCONNECTED
            } else {
                BluetoothA2dp.STATE_CONNECTED
            },
            // 新导出的表格没有“手机电量”列，与新记录一样写未知值，而不是 0%
            batteryLevel = row.cell("手机电量").toIntOrNull()?.takeIf { it in 0..100 }
                ?: DEVICE_BATTERY_LEVEL_UNKNOWN,
            headsetBatteryLevel = row.cell("耳机电量").toLevelOrUnknown(),
            leftBatteryLevel = row.cell("左耳电量").toLevelOrUnknown(),
            rightBatteryLevel = row.cell("右耳电量").toLevelOrUnknown(),
            caseBatteryLevel = row.cell("充电仓电量").toLevelOrUnknown(),
            volume = row.cell("媒体音量").removeSuffix("%").trim().toIntOrNull() ?: 0,
            isPlaying = row.cell("正在播放") == "是",
            eventType = eventType,
            phoneSupportedCodecs = phoneCodecs,
            negotiableCodecs = negotiableCodecs,
            activeCodec = activeCodec
        )
        ImportedRecord(device, record)
    }
}

const val EXPORT_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss"

/** 导出时未知电量写“未上报”或留空，读回统一为未知 */
private fun String.toLevelOrUnknown(): Int = toIntOrNull()?.takeIf { it in 0..100 } ?: DEVICE_BATTERY_LEVEL_UNKNOWN

private fun parseTime(text: String, format: SimpleDateFormat): Long? =
    if (text.isBlank()) null else try {
        format.parse(text)?.time
    } catch (e: ParseException) {
        null
    }

private fun eventTypeOf(label: String): String = when (label) {
    "已断开" -> RecordEventType.DISCONNECTED
    "编解码切换" -> RecordEventType.CODEC_CHANGED
    "电量更新" -> RecordEventType.BATTERY_CHANGED
    else -> RecordEventType.CONNECTED
}

private fun bondStateOf(label: String): Int = when (label) {
    "未配对" -> BluetoothDevice.BOND_NONE
    "正在配对" -> BluetoothDevice.BOND_BONDING
    "已配对" -> BluetoothDevice.BOND_BONDED
    "出错" -> BluetoothDevice.ERROR
    else -> -1
}

private fun deviceTypeOf(label: String): Int = when (label) {
    "经典蓝牙设备" -> BluetoothDevice.DEVICE_TYPE_CLASSIC
    "低功耗蓝牙设备" -> BluetoothDevice.DEVICE_TYPE_LE
    "支持经典蓝牙和低功耗蓝牙的设备" -> BluetoothDevice.DEVICE_TYPE_DUAL
    else -> BluetoothDevice.DEVICE_TYPE_UNKNOWN
}
