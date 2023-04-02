package com.xingkeqi.btlogger.utils

import android.bluetooth.BluetoothDevice
import androidx.compose.ui.text.toLowerCase
import com.blankj.utilcode.util.TimeUtils
import com.blankj.utilcode.util.ToastUtils
import com.xingkeqi.btlogger.BtLoggerApplication
import com.xingkeqi.btlogger.data.DeviceInfo
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
    val labelConnState = Label(6, 0, "连接状态")
    val labelBatteryLevel = Label(7, 0, "手机电量")
    val labelIsPlaying = Label(8, 0, "正在播放")
    val labelUuid = Label(9, 0, "UUID")

    sheet.addCell(labelName)
    sheet.addCell(labelAlias)
    sheet.addCell(labelMac)
    sheet.addCell(labelTime)
    sheet.addCell(labelBondState)
    sheet.addCell(labelDeviceType)
    sheet.addCell(labelConnState)
    sheet.addCell(labelBatteryLevel)
    sheet.addCell(labelIsPlaying)
    sheet.addCell(labelUuid)

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
        val connState = Label(6, i + 1, if (item.connectState == 2) "已连接" else "已断开")
        val batteryLevel = Label(7, i + 1, item.batteryLevel.toString())
        val isPlaying = Label(8, i + 1, if (item.isPlaying == 1) "是" else "否")
        val uuids = Label(9, i + 1, item.uuids)

        sheet.addCell(name)
        sheet.addCell(alias)
        sheet.addCell(mac)
        sheet.addCell(time)
        sheet.addCell(boundState)
        sheet.addCell(deviceType)
        sheet.addCell(connState)
        sheet.addCell(batteryLevel)
        sheet.addCell(isPlaying)
        sheet.addCell(uuids)
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