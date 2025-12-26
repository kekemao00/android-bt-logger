package com.xingkeqi.btlogger

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.blankj.utilcode.util.AppUtils
import com.pgyer.pgyersdk.PgyerSDKManager
import com.pgyer.pgyersdk.callback.CheckoutVersionCallBack
import com.pgyer.pgyersdk.model.CheckSoftModel
import com.xingkeqi.btlogger.data.Device
import com.xingkeqi.btlogger.data.DeviceConnectionRecord
import com.xingkeqi.btlogger.data.DeviceDao
import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.data.DeviceWithRecordsDao
import com.xingkeqi.btlogger.data.RecordDao
import com.xingkeqi.btlogger.data.RecordInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.rxkotlin.subscribeBy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zlc.season.rxdownload4.download
import zlc.season.rxdownload4.file
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val deviceDao: DeviceDao,
    private val recordDao: RecordDao,
    private val deviceWithRecordsDao: DeviceWithRecordsDao
) : ViewModel() {

    /**
     * 列表，包括当前状态，首次 尾次连接时间
     */
    val deviceInfoList: LiveData<List<DeviceInfo>> =
        deviceDao.getDeviceInfosWithConnectionRecords().asLiveData()

    /**
     * Curr device
     */
    val currDevice = MutableLiveData<DeviceInfo>()

    val pairTimeDuration = MutableLiveData(Pair(0L, 0L))

    var customVolumeSwitch = MutableLiveData(false)

    var presetTestVolume = 60


    /**
     * 当前设备的详细记录
     */
    val recordInfoList: LiveData<List<RecordInfo>> =
        currDevice.switchMap { device ->
            var lastTimestamp = 0L
            var connectionTime = 0L
            var disconnectionTime = 0L
            recordDao.getRecordInfoListByMac(device?.mac ?: "").map { it ->
                it.sortedBy { it.timestamp }.map {
                    it.lastRecordTime = if (lastTimestamp < 1) it.timestamp else lastTimestamp
                    val timeDiff = it.timestamp - (it.lastRecordTime ?: 0)
                    if (it.connectState == 2) {
                        disconnectionTime += timeDiff
                    } else {
                        connectionTime += timeDiff
                    }
                    it.totalConnectionTime = connectionTime
                    it.totalDisConnectionTime = disconnectionTime
                    pairTimeDuration.value = Pair(connectionTime, disconnectionTime)

                    lastTimestamp = it.timestamp
                    it
                }.sortedByDescending { it.timestamp }.also {
                    // 处理完成初始化临时变量，为下次计算做准备
                    lastTimestamp = 0L
                    connectionTime = 0L
                    disconnectionTime = 0L
                }
            }.asLiveData()
        }

    /**
     *
     * val connectionTime = 0L
     * val disconnectionTime = 0L
     * var lastTimestamp = 0L
     *
     * recordDao.getRecordInfoListByMac(device?.mac ?: "").map { it ->
     *     it.sortedBy { it.timestamp }.forEach { recordInfo ->
     *         val timeDiff = recordInfo.timestamp - lastTimestamp
     *         if (recordInfo.isConnected) {
     *             connectionTime += timeDiff
     *         } else {
     *             disconnectionTime += timeDiff
     *         }
     *         lastTimestamp = recordInfo.timestamp
     *     }
     * }.asLiveData()
     */


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


    /**
     * 删除设备及其所有记录
     * 使用事务保护确保数据一致性
     */
    fun deleteDevice(mac: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                deviceWithRecordsDao.deleteDeviceWithRecords(mac)
            }
        }
    }

    fun cleanAll() {
        deleteAllRecord()
        deleteAllDevice()
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

    /**
     * Version model
     * 0. 不显示弹框
     * 1. 显示升级弹框
     * 2. 显示下载进度提示框
     */
    val showDialogLD = MutableLiveData(0)

    /**
     * Download progress
     */
    val downloadProgressLD = MutableLiveData(0F)

    /**
     * Version model l d
     * 3. 版本信息
     *
     */
    val versionModelLD = MutableLiveData(CheckSoftModel())

    fun checkUpdate() {
        PgyerSDKManager.checkSoftwareUpdate(object :
            CheckoutVersionCallBack {
            override fun onSuccess(version: CheckSoftModel?) {
                Log.d("@@@", "onSuccess: ${version.toString()}")
                if (version?.isBuildHaveNewVersion == true) {
                    showDialogLD.value = 1
                    versionModelLD.value = version
                }
            }

            override fun onFail(p0: String?) =
                PgyerSDKManager.reportException(Exception("版本检测更新异常：$p0"))
        })
    }

    fun downLoadUpdate(version: CheckSoftModel?) {

        val uri = version?.downloadURL

        val disposable = uri?.download()?.observeOn(AndroidSchedulers.mainThread())
            ?.subscribeBy(
                onNext = {
                    Log.v("@@@", "downLoadUpdate: $it")
                    if (showDialogLD.value != 2) {
                        showDialogLD.value = 2
                    }
                    downloadProgressLD.value = it.downloadSize.toFloat() / it.totalSize.toFloat()
                },
                onComplete = {
                    Log.i("@@@", "downLoadUpdate: 下载完成！")
                    showDialogLD.value = 0
                    installApk(uri.file())
                },
                onError = {
                    PgyerSDKManager.reportException(Exception("下载新版本发生异常!!", it))
                    showDialogLD.value = 0
                }
            )

    }

    private fun installApk(file: File) {
        AppUtils.installApp(file)
    }
}
