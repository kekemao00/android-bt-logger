package com.xingkeqi.btlogger.utils

import android.bluetooth.BluetoothDevice
import com.blankj.utilcode.util.TimeUtils
import com.xingkeqi.btlogger.data.BLUETOOTH_VERSION_UNKNOWN
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.data.RecordEventType
import com.xingkeqi.btlogger.data.RecordInfo
import com.xingkeqi.btlogger.data.buildBatteryExport
import jxl.Workbook
import jxl.write.Label
import jxl.write.WritableWorkbook
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

const val EXCEL_MIME_TYPE = "application/vnd.ms-excel"

/** 只记录耳机电量后不再导出“手机电量”列；导入按表头名称读取，旧表格里的该列仍可导入 */
private val EXPORT_HEADERS = listOf(
    "设备名称", "设备别名", "蓝牙地址", "记录时间", "绑定状态", "设备类型", "蓝牙版本",
    "事件", "耳机电量", "媒体音量", "正在播放", "UUID",
    "手机支持编解码", "双方可用编解码", "当前使用编解码"
)

private val BATTERY_SESSION_HEADERS = listOf(
    "设备名称", "蓝牙地址", "连接时间", "结束时间", "状态", "时长(分钟)",
    "起始电量(%)", "结束电量(%)", "累计消耗(%)", "平均掉电(%/小时)", "满电续航(小时)", "电量记录数"
)

private val HEADSET_SAMPLE_HEADERS = listOf(
    "设备名称", "蓝牙地址", "连接时间", "记录时间", "连接后(分钟)", "耳机电量(%)", "媒体音量(%)", "正在播放"
)

private const val RECORD_SHEET_NAME = "连接记录"
private const val BATTERY_SESSION_SHEET_NAME = "电量统计"
private const val HEADSET_SAMPLE_SHEET_NAME = "耳机电量明细"

/** .xls 单个工作表最多 65536 行，留出余量后自动分表 */
internal const val MAX_ROWS_PER_SHEET = 60_000

/** 单元格：文本或数字；数字单元格在 Excel 里可以直接计算与绘图 */
private sealed interface ExportCell {
    data class Text(val value: String) : ExportCell
    data class Num(val value: Double) : ExportCell
}

private fun text(value: String) = ExportCell.Text(value)
private fun num(value: Number?) = value?.let { ExportCell.Num(it.toDouble()) }

/**
 * 将记录导出为 .xls 文件：“连接记录”为全部原始记录，“电量统计”按连接汇总耳机掉电，
 * “耳机电量明细”只列带耳机电量的记录并换算成连接后的分钟数，方便直接画续航曲线。
 * 任一表超过 [MAX_ROWS_PER_SHEET] 行时拆成“连接记录 2”等后续工作表。
 *
 * 必须在 IO 线程调用：写文件是阻塞操作，过去在主线程执行，记录多时会卡顿甚至 ANR。
 *
 * @param exportDir 导出目录（需在 FileProvider 的 files-path 范围内）
 * @param currDevice 设备快照，单条记录蓝牙版本未知时用于兜底；导出全部时仅使用其名称
 * @param maxRowsPerSheet 单张工作表的数据行上限，仅测试时调小
 * @return 写好的文件
 */
fun saveDataToSheet(
    exportDir: File,
    currDevice: DeviceInfo,
    records: List<RecordInfo>,
    maxRowsPerSheet: Int = MAX_ROWS_PER_SHEET
): File {
    if (!exportDir.exists()) exportDir.mkdirs()
    val safeName = sanitizeFileName(currDevice.name).ifBlank { "device" }
    val file = File(exportDir, "BtLogger_${safeName}_${getCurrentDateTime()}.xls")

    val workbook = Workbook.createWorkbook(file)
    try {
        val recordRows = records.map { item ->
            val bluetoothVersion = item.bluetoothVersion
                .takeUnless { it == BLUETOOTH_VERSION_UNKNOWN }
                ?: currDevice.bluetoothVersion
            listOf(
                item.name,
                item.alias,
                item.mac,
                TimeUtils.millis2String(item.timestamp),
                bondStateLabel(item.bondState),
                deviceTypeLabel(item.deviceType),
                bluetoothVersion,
                getRecordEventLabel(item),
                item.headsetBatteryLevel.takeIf { it in 0..100 }?.toString() ?: "未上报",
                "${item.volume}%",
                if (item.isPlaying == 1) "是" else "否",
                item.uuids,
                item.phoneSupportedCodecs,
                item.negotiableCodecs,
                item.activeCodec
            ).map(::text)
        }
        workbook.writeTable(RECORD_SHEET_NAME, EXPORT_HEADERS, recordRows, maxRowsPerSheet)

        val battery = buildBatteryExport(records)
        if (battery.sessions.isNotEmpty()) {
            val sessionRows = battery.sessions.map { row ->
                listOf(
                    text(row.name),
                    text(row.mac),
                    text(TimeUtils.millis2String(row.connectedAt)),
                    text(TimeUtils.millis2String(row.endedAt)),
                    text(if (row.ended) "已断开" else "连接中"),
                    num(row.durationMinutes),
                    num(row.startLevel),
                    num(row.endLevel),
                    num(row.consumed),
                    num(row.ratePerHour),
                    num(row.fullRuntimeHours),
                    num(row.sampleCount)
                )
            }
            val sampleRows = battery.samples.map { row ->
                listOf(
                    text(row.name),
                    text(row.mac),
                    text(TimeUtils.millis2String(row.connectedAt)),
                    text(TimeUtils.millis2String(row.timestamp)),
                    num(row.minutesSinceConnect),
                    num(row.level),
                    num(row.volume),
                    text(if (row.isPlaying) "是" else "否")
                )
            }
            workbook.writeTable(BATTERY_SESSION_SHEET_NAME, BATTERY_SESSION_HEADERS, sessionRows, maxRowsPerSheet)
            workbook.writeTable(HEADSET_SAMPLE_SHEET_NAME, HEADSET_SAMPLE_HEADERS, sampleRows, maxRowsPerSheet)
        }
        workbook.write()
    } finally {
        workbook.close()
    }
    return file
}

/**
 * 写入一张表；超过 [maxRowsPerSheet] 行时依次写入“名称 2”“名称 3”…，每张都带表头。
 * 空单元格（null）不写入。
 */
private fun WritableWorkbook.writeTable(
    baseName: String,
    headers: List<String>,
    rows: List<List<ExportCell?>>,
    maxRowsPerSheet: Int
) {
    val chunks = rows.chunked(maxRowsPerSheet.coerceAtLeast(1)).ifEmpty { listOf(emptyList()) }
    chunks.forEachIndexed { chunkIndex, chunk ->
        val sheetName = if (chunkIndex == 0) baseName else "$baseName ${chunkIndex + 1}"
        val sheet = createSheet(sheetName, numberOfSheets)
        headers.forEachIndexed { column, title -> sheet.addCell(Label(column, 0, title)) }
        chunk.forEachIndexed { index, cells ->
            val row = index + 1
            cells.forEachIndexed { column, cell ->
                when (cell) {
                    is ExportCell.Text -> sheet.addCell(Label(column, row, cell.value))
                    is ExportCell.Num -> sheet.addCell(jxl.write.Number(column, row, cell.value))
                    null -> Unit
                }
            }
        }
    }
}

/**
 * 读取导出的 .xls 中的连接记录：首行为表头，其余为数据行（全部按文本读取）。
 *
 * 记录多时会拆成“连接记录”“连接记录 2”…多个工作表，这里按顺序合并；
 * 没有这些工作表的旧文件读取第一个工作表。必须在 IO 线程调用。
 */
fun readExportSheet(input: InputStream): Pair<List<String>, List<List<String>>> {
    val workbook = Workbook.getWorkbook(input)
    try {
        val sheets = workbook.sheets
            .filter { it.name == RECORD_SHEET_NAME || it.name.startsWith("$RECORD_SHEET_NAME ") }
            .ifEmpty { listOf(workbook.getSheet(0)) }
        val tables = sheets.map { sheet ->
            (0 until sheet.rows).map { row -> sheet.getRow(row).map { it.contents.orEmpty() } }
        }
        val header = tables.first().firstOrNull().orEmpty()
        return header to tables.flatMap { it.drop(1) }
    } finally {
        workbook.close()
    }
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
