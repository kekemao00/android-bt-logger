package com.xingkeqi.btlogger

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingkeqi.btlogger.data.BtLoggerDatabase
import com.xingkeqi.btlogger.data.Device
import com.xingkeqi.btlogger.data.DeviceConnectionRecord
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.data.RecordInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {
    /**
     * Device list
     */
    var deviceInfoList = MutableLiveData<List<DeviceInfo?>>()

    /**
     * Record list
     */
    internal val recordList: List<RecordInfo> = mutableStateListOf()


    private val db = BtLoggerDatabase.getDatabase(BtLoggerApplication.instance)

    private val deviceDao = db.deviceDao()

    private val connectRecordDao = db.connectionRecordDao()

//    init {
//        getDevicesInfoWithConnectionRecords()
//    }

    /**
     * Get all
     *
     */

    fun getAll() {

        deviceDao.getAllDevices()
        deviceDao.getDeviceByMac("01:02:03:04:05:06")
        // TODO: 没查到值

        viewModelScope.launch() {
            withContext(Dispatchers.IO) {
                deviceInfoList.value = getDevicesInfoWithConnectionRecords().value
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
        return deviceDao.getAllDevices()
    }


    private fun getDevicesInfoWithConnectionRecords(): LiveData<List<DeviceInfo>> {
        return db.deviceDao().getDeviceInfosWithConnectionRecords()
    }


    fun getDeviceByMac(mac: String): LiveData<Device> {
        return deviceDao.getDeviceByMac(mac)
    }

    fun deleteDeviceById(id: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                deviceDao.deleteDeviceByMac(id)
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
        return connectRecordDao.getRecordsByDeviceId(deviceMac)
    }

    fun deleteRecordById(deviceId: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                connectRecordDao.deleteRecordByDeviceId(deviceId)
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
