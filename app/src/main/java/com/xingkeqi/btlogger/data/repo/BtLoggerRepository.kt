package com.xingkeqi.btlogger.data.repo

import com.xingkeqi.btlogger.data.RecordDao
import com.xingkeqi.btlogger.data.Device
import com.xingkeqi.btlogger.data.DeviceConnectionRecord
import com.xingkeqi.btlogger.data.DeviceDao
import com.xingkeqi.btlogger.data.DeviceInfo
import kotlinx.coroutines.flow.Flow

class DeviceRepository(private val deviceDao: DeviceDao) : IDeviceRepository {
    override suspend fun insertDevice(device: Device) = deviceDao.insert(device)

    override suspend fun deleteDeviceByMac(mac: String) = deviceDao.deleteDeviceByMac(mac)

    override suspend fun deleteAllDevice() = deviceDao.deleteAll()

    override fun getAllDevice(): Flow<List<Device>> = deviceDao.getAllDevices()

    override fun getDevicesInfoWithConnectionRecords(): Flow<List<DeviceInfo>> =
        deviceDao.getDeviceInfosWithConnectionRecords()

    override fun getDeviceByMac(mac: String): Flow<Device> = deviceDao.getDeviceByMac(mac)
}

class RecordRepository(private val recordDao: RecordDao) : IRecordRepository {
    override suspend fun insertRecord(record: DeviceConnectionRecord) = recordDao.insert(record)

    override fun getRecordsByDeviceMac(mac: String): Flow<List<DeviceConnectionRecord>> =
        recordDao.getRecordsByDeviceMac(mac)

    override suspend fun deleteRecordByMac(mac: String) = recordDao.deleteRecordByDeviceMac(mac)

    override suspend fun deleteAllRecord() = recordDao.deleteAll()

}