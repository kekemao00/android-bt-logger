package com.xingkeqi.btlogger

import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
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

    private val recordDao = db.connectionRecordDao()

    /**
     * 列表，包括当前状态，首次 尾次连接时间
     */
    val deviceInfoList: LiveData<List<DeviceInfo>> =
        deviceDao.getDeviceInfosWithConnectionRecords().asLiveData()

    /**
     * Curr device
     */
    val currDevice = MutableLiveData<DeviceInfo>()

    /**
     * 当前设备的详细记录
     */
    // TODO:  查询返回的结果
    val recordInfoList: LiveData<List<RecordInfo>> =
//        recordDao.getRecordInfoListByMac(currDevice.value?.mac ?: "").asLiveData()
        recordDao.getRecordInfoListByMac("28:52:E0:18:39:E8").asLiveData()

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

    private fun getAllDevices(): Flow<List<Device>> {
        return deviceDao.getAllDevices()
    }


    private fun getDevicesInfoWithConnectionRecords(): Flow<List<DeviceInfo>> {
        return db.deviceDao().getDeviceInfosWithConnectionRecords()
    }


    fun getDeviceByMac(mac: String): LiveData<Device> {
        return deviceDao.getDeviceByMac(mac).asLiveData()
    }


    fun deleteDevice(mac: String) {
        deleteDeviceById(mac)
        deleteRecordById(mac)
    }

    fun cleanAll() {
        deleteAllRecord()
        deleteAllDevice()
    }

    private fun deleteDeviceById(mac: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                deviceDao.deleteDeviceByMac(mac)
            }
        }
    }

    private fun deleteAllDevice() {
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
                recordDao.insert(record)
            }
        }
    }

    fun getRecordsByDeviceMac(deviceMac: String): LiveData<List<DeviceConnectionRecord>> {
        return recordDao.getRecordsByDeviceMac(deviceMac).asLiveData()
    }

    fun deleteRecordById(mac: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                recordDao.deleteRecordByDeviceMac(mac)
            }
        }
    }


    private fun deleteAllRecord() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                recordDao.deleteAll()
            }
        }
    }

}
