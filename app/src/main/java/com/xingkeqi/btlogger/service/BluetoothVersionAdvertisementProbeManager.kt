package com.xingkeqi.btlogger.service

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.xingkeqi.btlogger.data.BLUETOOTH_VERSION_UNKNOWN
import com.xingkeqi.btlogger.utils.AdvertisementSnapshot
import com.xingkeqi.btlogger.utils.BluetoothBatteryUtils
import com.xingkeqi.btlogger.utils.BluetoothVersionProbeResult
import com.xingkeqi.btlogger.utils.BluetoothVersionUtils
import com.xingkeqi.btlogger.utils.DeviceInformationSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * 连接后短时抓取 BLE 广播指纹，为无法从系统元数据和 DIS 直接拿到版本的设备补齐型号线索。
 *
 * Why:
 * 部分耳机不会在 GATT Device Information Service 中暴露蓝牙版本，但广播包往往带有更稳定的型号、
 * 厂商或私有数据特征。这里把广播采样控制在短窗口内，只做设备快照刷新，不新增历史记录。
 */
class BluetoothVersionAdvertisementProbeManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onProbeResult: (BluetoothDevice, BluetoothVersionProbeResult) -> Unit
) {

    private val tag = "BtVersionAdvProbeMgr"
    private val sessions = ConcurrentHashMap<String, ProbeSession>()
    private val bluetoothManager by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }

    @Volatile
    private var isScanning = false

    @SuppressLint("MissingPermission")
    fun startMonitoring(
        device: BluetoothDevice,
        persistedVersion: String = BLUETOOTH_VERSION_UNKNOWN
    ) {
        val address = device.address
        val normalizedPersisted = BluetoothVersionUtils.normalizeBluetoothVersion(persistedVersion)
        if (normalizedPersisted != null) {
            Log.d(
                tag,
                "[BluetoothVersionAdvertisementProbeManager] startMonitoring -> skip known version: device=${device.name.orEmpty()}[$address], version=$normalizedPersisted"
            )
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.d(
                tag,
                "[BluetoothVersionAdvertisementProbeManager] startMonitoring -> skip below Android 12 scan permission model: device=${device.name.orEmpty()}[$address], sdk=${Build.VERSION.SDK_INT}"
            )
            return
        }

        if (!hasBluetoothScanPermission()) {
            Log.w(
                tag,
                "[BluetoothVersionAdvertisementProbeManager] startMonitoring -> missing BLUETOOTH_SCAN permission: device=${device.name.orEmpty()}[$address]"
            )
            return
        }

        if (!BluetoothBatteryUtils.shouldTryBatteryGatt(device.type)) {
            Log.d(
                tag,
                "[BluetoothVersionAdvertisementProbeManager] startMonitoring -> skip non LE/dual device: device=${device.name.orEmpty()}[$address], type=${device.type}"
            )
            return
        }

        val session = sessions[address]
        if (session != null) {
            session.device = device
            session.persistedVersion = persistedVersion
            return
        }

        sessions[address] = ProbeSession(
            device = device,
            persistedVersion = persistedVersion
        ).also { probeSession ->
            probeSession.timeoutJob = scope.launch {
                delay(ADVERTISEMENT_SCAN_TIMEOUT_MS)
                handleTimeout(address)
            }
        }

        refreshScanState(reason = "start")
    }

    fun stopMonitoring(address: String) {
        val session = sessions.remove(address) ?: return
        session.timeoutJob?.cancel()
        refreshScanState(reason = "stop:$address")
        Log.i(
            tag,
            "[BluetoothVersionAdvertisementProbeManager] stopMonitoring -> stop probe: device=${session.device.name.orEmpty()}[$address]"
        )
    }

    fun stopAll() {
        sessions.keys.toList().forEach(::stopMonitoring)
    }

    private fun handleTimeout(address: String) {
        val session = sessions.remove(address) ?: return
        refreshScanState(reason = "timeout:$address")
        Log.i(
            tag,
            "[BluetoothVersionAdvertisementProbeManager] handleTimeout -> no advertisement hit within window: device=${session.device.name.orEmpty()}[$address]"
        )
    }

    @Synchronized
    private fun refreshScanState(reason: String) {
        stopScanInternal(reason = "refresh:$reason")
        if (sessions.isEmpty()) {
            return
        }

        val scanner = bluetoothManager?.adapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.w(
                tag,
                "[BluetoothVersionAdvertisementProbeManager] refreshScanState -> scanner unavailable: reason=$reason"
            )
            return
        }

        val filters = sessions.keys
            .map { address ->
                ScanFilter.Builder()
                    .setDeviceAddress(address)
                    .build()
            }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            scanner.startScan(filters, settings, scanCallback)
            isScanning = true
            Log.i(
                tag,
                "[BluetoothVersionAdvertisementProbeManager] refreshScanState -> scan started: reason=$reason, targets=${sessions.keys.joinToString()}"
            )
        } catch (e: Exception) {
            Log.e(
                tag,
                "[BluetoothVersionAdvertisementProbeManager] refreshScanState -> start scan failed: reason=$reason",
                e
            )
        }
    }

    @Synchronized
    private fun stopScanInternal(reason: String) {
        if (!isScanning) return
        runCatching {
            bluetoothManager?.adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }.onFailure {
            Log.w(
                tag,
                "[BluetoothVersionAdvertisementProbeManager] stopScanInternal -> stop scan failed: reason=$reason",
                it
            )
        }
        isScanning = false
        Log.i(
            tag,
            "[BluetoothVersionAdvertisementProbeManager] stopScanInternal -> scan stopped: reason=$reason"
        )
    }

    private fun hasBluetoothScanPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun handleScanResult(scanResult: ScanResult) {
        val address = scanResult.device?.address ?: return
        val session = sessions.remove(address) ?: return
        session.timeoutJob?.cancel()

        val advertisementSnapshot = BluetoothVersionUtils.parseAdvertisementSnapshot(scanResult)
        if (!advertisementSnapshot.hasData()) {
            refreshScanState(reason = "empty-result:$address")
            Log.d(
                tag,
                "[BluetoothVersionAdvertisementProbeManager] handleScanResult -> ignore empty advertisement payload: device=${session.device.name.orEmpty()}[$address]"
            )
            return
        }

        val result = BluetoothVersionProbeResult(
            versionSnapshot = BluetoothVersionUtils.resolveBluetoothVersionFromFingerprint(
                deviceName = session.device.name,
                deviceInformation = DeviceInformationSnapshot(),
                advertisementSnapshot = advertisementSnapshot,
                persistedVersion = session.persistedVersion
            ),
            advertisementSnapshot = advertisementSnapshot
        )
        Log.i(
            tag,
            "[BluetoothVersionAdvertisementProbeManager] handleScanResult -> device=${session.device.name.orEmpty()}[$address], version=${result.versionSnapshot.version}, source=${result.versionSnapshot.source}, adv=${advertisementSnapshot.summary()}"
        )
        refreshScanState(reason = "result:$address")
        scope.launch {
            onProbeResult(session.device, result)
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handleScanResult(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach(::handleScanResult)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(
                tag,
                "[BluetoothVersionAdvertisementProbeManager] onScanFailed -> errorCode=$errorCode, targets=${sessions.keys.joinToString()}"
            )
            stopScanInternal(reason = "scanFailed:$errorCode")
        }
    }

    private data class ProbeSession(
        @Volatile var device: BluetoothDevice,
        @Volatile var persistedVersion: String,
        @Volatile var timeoutJob: Job? = null
    )

    companion object {
        private const val ADVERTISEMENT_SCAN_TIMEOUT_MS = 2_500L
    }
}
