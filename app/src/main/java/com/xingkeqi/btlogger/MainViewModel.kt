package com.xingkeqi.btlogger

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingkeqi.btlogger.data.BtLoggerDatabase
import com.xingkeqi.btlogger.data.Device
import androidx.lifecycle.asLiveData
import com.xingkeqi.btlogger.data.DeviceConnectionRecord
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.data.RecordInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {

    /**
     * Record list
     */
    internal val recordList: List<RecordInfo> = mutableStateListOf()


    private val db = BtLoggerDatabase.getDatabase(BtLoggerApplication.instance)

    private val deviceDao = db.deviceDao()

    private val connectRecordDao = db.connectionRecordDao()

    /**
     * Device list
     */
    var deviceInfoList: LiveData<List<DeviceInfo>> =
        deviceDao.getDeviceInfosWithConnectionRecords().asLiveData()

    /**
     * Get all
     *
     */

    fun getAll() {
        viewModelScope.launch() {
            withContext(Dispatchers.IO) {
                deviceDao.getDeviceInfosWithConnectionRecords()
            }
        }
    }


    fun addNewRecord() {

    }


    // ****************************************************

    /**
     * Insert device
     *
     * @param device
     */
    fun insertDevice(device: Device) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val existingDevice = getDeviceByMac(device.mac).value
                if (existingDevice == null) deviceDao.insert(device)
            }
        }
    }

    private fun getAllDevices(): LiveData<List<Device>> {
        return deviceDao.getAllDevices().asLiveData()
    }


    private fun getDevicesInfoWithConnectionRecords(): Flow<List<DeviceInfo>> {
        return db.deviceDao().getDeviceInfosWithConnectionRecords()
    }


    fun getDeviceByMac(mac: String): LiveData<Device> {
        return deviceDao.getDeviceByMac(mac).asLiveData()
    }

    fun deleteDeviceById(mac: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                deviceDao.deleteDeviceByMac(mac)
            }
        }
    }

    fun deleteAllDevice() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                deviceDao.deleteAll()
            }
        }
    }

    /**
     * 添加记录
     *
     * @param record
     */
    fun insertRecord(record: DeviceConnectionRecord) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                connectRecordDao.insert(record)
            }
        }
    }

    fun getRecordsByDeviceMac(deviceMac: String): LiveData<List<DeviceConnectionRecord>> {
        return connectRecordDao.getRecordsByDeviceMac(deviceMac).asLiveData()
    }

    fun deleteRecordById(mac: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                connectRecordDao.deleteRecordByDeviceMac(mac)
            }
        }
    }

    fun deleteAllRecord() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                connectRecordDao.deleteAll()
            }
        }
    }

}
