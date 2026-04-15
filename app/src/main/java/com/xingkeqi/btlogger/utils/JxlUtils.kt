package com.xingkeqi.btlogger.utils

import android.bluetooth.BluetoothDevice
import com.blankj.utilcode.util.TimeUtils
import com.xingkeqi.btlogger.BtLoggerApplication
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


fun saveDataToSheet(
    currDevice: DeviceInfo,
    records: List<RecordInfo>,
    exportSucceeded: (file: File) -> Unit
) {

//
//// 创建Excel工作簿
//    val workbook = Workbook.createWorkbook(File("BtLogger_${getCurrentDateTime()}.xls"))

    val fileName = "BtLogger_${currDevice.name.replace(" ", "_").lowercase(Locale.getDefault())}_${getCurrentDateTime()}.xls"
    val filePath = "${BtLoggerApplication.instance.filesDir}/$fileName"
    val file = File(filePath)

// 创建Excel工作簿
    val workbook = Workbook.createWorkbook(file)


// 创建一个工作表
    val sheet = workbook.createSheet("连接记录", 0)


    // 在工作表中添加表头
    val labelName = Label(0, 0, "设备名称")
    val labelAlias = Label(1, 0, "设备别名")
    val labelMac = Label(2, 0, "蓝牙地址")
    val labelTime = Label(3, 0, "记录时间")
    val labelBondState = Label(4, 0, "绑定状态")
    val labelDeviceType = Label(5, 0, "设备类型")
    val labelBluetoothVersion = Label(6, 0, "蓝牙版本")
    val labelConnState = Label(7, 0, "连接状态")
    val labelBatteryLevel = Label(8, 0, "手机电量")
    val labelHeadsetBatteryLevel = Label(9, 0, "耳机电量")
    val labelIsPlaying = Label(10, 0, "正在播放")
    val labelUuid = Label(11, 0, "UUID")
    val labelPhoneCodecs = Label(12, 0, "手机支持编解码")
    val labelNegotiableCodecs = Label(13, 0, "双方可用编解码")
    val labelActiveCodec = Label(14, 0, "当前使用编解码")

    sheet.addCell(labelName)
    sheet.addCell(labelAlias)
    sheet.addCell(labelMac)
    sheet.addCell(labelTime)
    sheet.addCell(labelBondState)
    sheet.addCell(labelDeviceType)
    sheet.addCell(labelBluetoothVersion)
    sheet.addCell(labelConnState)
    sheet.addCell(labelBatteryLevel)
    sheet.addCell(labelHeadsetBatteryLevel)
    sheet.addCell(labelIsPlaying)
    sheet.addCell(labelUuid)
    sheet.addCell(labelPhoneCodecs)
    sheet.addCell(labelNegotiableCodecs)
    sheet.addCell(labelActiveCodec)

// 迭代数据并将其添加到表格中
    for (i in records.indices) {
        val item = records.get(i)
        val name = Label(0, i + 1, item.name)
        val alias = Label(1, i + 1, item.alias)
        val mac = Label(2, i + 1, item.mac)
        val time = Label(3, i + 1, TimeUtils.millis2String(item.timestamp))
        val boundState = Label(
            4, i + 1, when (item.bondState) {
                BluetoothDevice.BOND_NONE -> "未配对"
                BluetoothDevice.BOND_BONDING -> "正在配对"
                BluetoothDevice.BOND_BONDED -> "已配对"
                BluetoothDevice.ERROR -> "出错"
                else -> "其他"
            }
        )
        val deviceType = Label(
            5, i + 1, when (item.deviceType) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> "经典蓝牙设备"
                BluetoothDevice.DEVICE_TYPE_LE -> "低功耗蓝牙设备"
                BluetoothDevice.DEVICE_TYPE_DUAL -> "支持经典蓝牙和低功耗蓝牙的设备"
                else -> "其他"
            }
        )
        val bluetoothVersion = Label(
            6,
            i + 1,
            item.bluetoothVersion
                .takeUnless { it == BLUETOOTH_VERSION_UNKNOWN }
                ?: currDevice.bluetoothVersion
        )
        val connState = Label(7, i + 1, getRecordEventLabel(item))
        val batteryLevel = Label(8, i + 1, item.batteryLevel.toString())
        val headsetBatteryLevel = Label(
            9,
            i + 1,
            item.headsetBatteryLevel.takeIf { it in 0..100 }?.toString() ?: "未上报"
        )
        val isPlaying = Label(10, i + 1, if (item.isPlaying == 1) "是" else "否")
        val uuids = Label(11, i + 1, item.uuids)
        val phoneSupportedCodecs = Label(12, i + 1, item.phoneSupportedCodecs)
        val negotiableCodecs = Label(13, i + 1, item.negotiableCodecs)
        val activeCodec = Label(14, i + 1, item.activeCodec)

        sheet.addCell(name)
        sheet.addCell(alias)
        sheet.addCell(mac)
        sheet.addCell(time)
        sheet.addCell(boundState)
        sheet.addCell(deviceType)
        sheet.addCell(bluetoothVersion)
        sheet.addCell(connState)
        sheet.addCell(batteryLevel)
        sheet.addCell(headsetBatteryLevel)
        sheet.addCell(isPlaying)
        sheet.addCell(uuids)
        sheet.addCell(phoneSupportedCodecs)
        sheet.addCell(negotiableCodecs)
        sheet.addCell(activeCodec)
    }

// 将工作簿保存为Excel文件
    workbook.write()
    workbook.close()

    exportSucceeded(file)
}


private fun getCurrentDateTime(): String {
    val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss", Locale.getDefault())
    val currentDate = Date()
    return sdf.format(currentDate)
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
