package com.xingkeqi.btlogger.utils

import android.bluetooth.BluetoothDevice
import com.blankj.utilcode.util.TimeUtils
import com.xingkeqi.btlogger.data.BLUETOOTH_VERSION_UNKNOWN
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.data.RecordEventType
import com.xingkeqi.btlogger.data.RecordInfo
import jxl.Workbook
import jxl.write.Label
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val EXCEL_MIME_TYPE = "application/vnd.ms-excel"

private val EXPORT_HEADERS = listOf(
    "设备名称", "设备别名", "蓝牙地址", "记录时间", "绑定状态", "设备类型", "蓝牙版本",
    "事件", "手机电量", "耳机电量", "媒体音量", "正在播放", "UUID",
    "手机支持编解码", "双方可用编解码", "当前使用编解码"
)

/**
 * 将记录导出为 .xls 文件。
 *
 * 必须在 IO 线程调用：写文件是阻塞操作，过去在主线程执行，记录多时会卡顿甚至 ANR。
 *
 * @param exportDir 导出目录（需在 FileProvider 的 files-path 范围内）
 * @param currDevice 设备快照，单条记录蓝牙版本未知时用于兜底；导出全部时仅使用其名称
 * @return 写好的文件
 */
fun saveDataToSheet(
    exportDir: File,
    currDevice: DeviceInfo,
    records: List<RecordInfo>
): File {
    if (!exportDir.exists()) exportDir.mkdirs()
    val safeName = sanitizeFileName(currDevice.name).ifBlank { "device" }
    val file = File(exportDir, "BtLogger_${safeName}_${getCurrentDateTime()}.xls")

    val workbook = Workbook.createWorkbook(file)
    try {
        val sheet = workbook.createSheet("连接记录", 0)
        EXPORT_HEADERS.forEachIndexed { column, title ->
            sheet.addCell(Label(column, 0, title))
        }

        records.forEachIndexed { index, item ->
            val row = index + 1
            val bluetoothVersion = item.bluetoothVersion
                .takeUnless { it == BLUETOOTH_VERSION_UNKNOWN }
                ?: currDevice.bluetoothVersion
            val values = listOf(
                item.name,
                item.alias,
                item.mac,
                TimeUtils.millis2String(item.timestamp),
                bondStateLabel(item.bondState),
                deviceTypeLabel(item.deviceType),
                bluetoothVersion,
                getRecordEventLabel(item),
                item.batteryLevel.toString(),
                item.headsetBatteryLevel.takeIf { it in 0..100 }?.toString() ?: "未上报",
                "${item.volume}%",
                if (item.isPlaying == 1) "是" else "否",
                item.uuids,
                item.phoneSupportedCodecs,
                item.negotiableCodecs,
                item.activeCodec
            )
            values.forEachIndexed { column, value ->
                sheet.addCell(Label(column, row, value))
            }
        }
        workbook.write()
    } finally {
        workbook.close()
    }
    return file
}

/**
 * 设备名可能包含 / : * 等文件系统不允许的字符，统一替换为下划线
 */
internal fun sanitizeFileName(name: String): String =
    name.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\s]+"), "_")
        .take(48)

private fun bondStateLabel(state: Int): String = when (state) {
    BluetoothDevice.BOND_NONE -> "未配对"
    BluetoothDevice.BOND_BONDING -> "正在配对"
    BluetoothDevice.BOND_BONDED -> "已配对"
    BluetoothDevice.ERROR -> "出错"
    else -> "其他"
}

private fun deviceTypeLabel(type: Int): String = when (type) {
    BluetoothDevice.DEVICE_TYPE_CLASSIC -> "经典蓝牙设备"
    BluetoothDevice.DEVICE_TYPE_LE -> "低功耗蓝牙设备"
    BluetoothDevice.DEVICE_TYPE_DUAL -> "支持经典蓝牙和低功耗蓝牙的设备"
    else -> "其他"
}

private fun getCurrentDateTime(): String {
    val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault())
    return sdf.format(Date())
}

private fun getRecordEventLabel(record: RecordInfo): String {
    return when (record.eventType) {
        RecordEventType.CONNECTED -> "已连接"
        RecordEventType.DISCONNECTED -> "已断开"
        RecordEventType.CODEC_CHANGED -> "编解码切换"
        RecordEventType.BATTERY_CHANGED -> "电量更新"
        else -> "未知事件"
    }
}
