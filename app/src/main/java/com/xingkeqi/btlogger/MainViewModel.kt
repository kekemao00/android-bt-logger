package com.xingkeqi.btlogger

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xingkeqi.btlogger.data.BtLoggerDatabase
import com.xingkeqi.btlogger.data.Device
import androidx.lifecycle.asLiveData
import com.xingkeqi.btlogger.data.DeviceConnectionRecord
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.data.RecordInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {

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
    val recordInfoList: LiveData<List<RecordInfo>> =
        Transformations.switchMap(currDevice) { device ->
            var lastTime = 0L
            recordDao.getRecordInfoListByMac(device?.mac ?: "").map { it ->
                it.sortedBy { it.timestamp }.map {
                    it.lastRecordTime = if (lastTime < 1) it.timestamp else lastTime
                    lastTime = it.timestamp
                    it
                }.sortedByDescending { it.timestamp }
            }.asLiveData()
        }

    /**
     * Insert device
     *
     * @param device
     */
    fun insertDevice(device: Device) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                deviceDao.insert(device)
            }
        }
    }

    fun getDeviceByMac(mac: String): LiveData<Device> {
        return deviceDao.getDeviceByMac(mac).asLiveData()
    }


    fun deleteDevice(mac: String) {
        deleteDeviceById(mac)
        deleteRecordByMac(mac)
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

    private fun deleteAllRecord() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                recordDao.deleteAll()
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


    fun deleteRecordByMac(mac: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                recordDao.deleteRecordByDeviceMac(mac)
            }
        }
    }


    fun deleteRecordById(id: Int) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                recordDao.deleteRecordByDeviceId(id)
            }
        }
    }

}
