package com.xingkeqi.btlogger.data.repo

import androidx.lifecycle.LiveData
import com.xingkeqi.btlogger.data.Device
import com.xingkeqi.btlogger.data.DeviceConnectionRecord
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.data.RecordInfo
import kotlinx.coroutines.flow.Flow

interface IDeviceRepository {
    suspend fun insertDevice(device: Device)
    suspend fun deleteDeviceByMac(mac: String)
    suspend fun deleteAllDevice()
    fun getAllDevice(): Flow<List<Device>>
    fun getDevicesInfoWithConnectionRecords(): Flow<List<DeviceInfo>>
    fun getDeviceByMac(mac: String): Flow<Device>

}

interface IRecordRepository{
    suspend fun insertRecord(record: DeviceConnectionRecord)
    fun getRecordsByDeviceMac(mac: String): Flow<List<DeviceConnectionRecord>>
//    fun getDeviceConnectionInfo(): Flow<List<RecordInfo>>

    suspend fun deleteRecordByMac(mac: String)
    suspend fun deleteAllRecord()
}
