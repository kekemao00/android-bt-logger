package com.xingkeqi.btlogger.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.xingkeqi.btlogger.data.BLUETOOTH_VERSION_UNKNOWN
import com.xingkeqi.btlogger.utils.BluetoothBatteryUtils
import com.xingkeqi.btlogger.utils.BluetoothVersionProbeResult
import com.xingkeqi.btlogger.utils.BluetoothVersionUtils
import com.xingkeqi.btlogger.utils.DeviceInformationSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap

/**
 * 尝试通过标准 Device Information Service 读取耳机型号/固件/硬件信息，提升蓝牙版本识别命中率。
 *
 * Why:
 * 公开 SDK 没有远端蓝牙核心版本接口，但部分耳机会在 DIS 特征里上报明确的 `Bluetooth 5.x`
 * 字样。这里做一次性 GATT 读取，只刷新设备最新快照，不写历史事件。
 */
class BluetoothVersionProbeManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onProbeResult: (BluetoothDevice, BluetoothVersionProbeResult) -> Unit
) {

    private val tag = "BtVersionProbeMgr"
    private val sessions = ConcurrentHashMap<String, ProbeSession>()

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
                "[BluetoothVersionProbeManager] startMonitoring -> skip known version: device=${device.name.orEmpty()}[$address], version=$normalizedPersisted"
            )
            return
        }

        if (!BluetoothBatteryUtils.shouldTryBatteryGatt(device.type)) {
            Log.d(
                tag,
                "[BluetoothVersionProbeManager] startMonitoring -> skip non LE/dual device: device=${device.name.orEmpty()}[$address], type=${device.type}"
            )
            return
        }

        val existingSession = sessions[address]
        if (existingSession != null) {
            existingSession.device = device
            existingSession.persistedVersion = persistedVersion
            if (
                existingSession.gatt != null ||
                existingSession.isConnecting ||
                existingSession.gattUnsupported ||
                existingSession.hasCompleted
            ) {
                return
            }
        }

        val session = existingSession ?: ProbeSession(
            device = device,
            persistedVersion = persistedVersion,
            callback = VersionGattCallback(address)
        ).also { sessions[address] = it }

        connectGatt(session, reason = "start")
    }

    fun stopMonitoring(address: String) {
        val session = sessions.remove(address) ?: return
        session.retryJob?.cancel()
        closeGatt(session, reason = "stop monitoring", removeSession = false, markUnsupported = false)
    }

    fun stopAll() {
        sessions.keys.toList().forEach(::stopMonitoring)
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(
        session: ProbeSession,
        reason: String,
        transportOverride: Int? = null
    ) {
        if (session.gatt != null || session.isConnecting || session.gattUnsupported) return
        session.retryJob?.cancel()
        session.retryJob = null
        session.isConnecting = true
        try {
            val transport = transportOverride ?: BluetoothBatteryUtils.resolveGattTransport(session.device.type)
            session.lastTransport = transport
            session.gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                session.device.connectGatt(context, false, session.callback, transport)
            } else {
                session.device.connectGatt(context, false, session.callback)
            }
            Log.i(
                tag,
                "[BluetoothVersionProbeManager] connectGatt -> connect start: device=${session.device.name.orEmpty()}[${session.device.address}], transport=$transport, reason=$reason"
            )
        } catch (e: Exception) {
            session.isConnecting = false
            Log.e(
                tag,
                "[BluetoothVersionProbeManager] connectGatt -> failed: device=${session.device.name.orEmpty()}[${session.device.address}], reason=$reason",
                e
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestNextRead(session: ProbeSession, gatt: BluetoothGatt, reason: String) {
        val characteristic = if (session.pendingCharacteristics.isEmpty()) {
            null
        } else {
            session.pendingCharacteristics.removeFirst()
        }
        if (characteristic == null) {
            emitResult(session, reason = reason)
            closeGatt(session, reason = "read complete", removeSession = false, markUnsupported = false)
            return
        }

        val started = gatt.readCharacteristic(characteristic)
        Log.i(
            tag,
            "[BluetoothVersionProbeManager] requestNextRead -> started=$started, device=${session.device.name.orEmpty()}[${session.device.address}], uuid=${characteristic.uuid}, reason=$reason"
        )
        if (!started) {
            closeGatt(session, reason = "read request rejected", removeSession = true, markUnsupported = false)
        }
    }

    private fun emitResult(session: ProbeSession, reason: String) {
        val deviceInformation = session.deviceInformation
        session.hasCompleted = true
        session.retryJob?.cancel()
        session.retryJob = null
        val result = BluetoothVersionProbeResult(
            versionSnapshot = BluetoothVersionUtils.resolveBluetoothVersionFromFingerprint(
                deviceName = session.device.name,
                deviceInformation = deviceInformation,
                advertisementSnapshot = com.xingkeqi.btlogger.utils.AdvertisementSnapshot(),
                persistedVersion = session.persistedVersion
            ),
            deviceInformation = deviceInformation
        )
        Log.i(
            tag,
            "[BluetoothVersionProbeManager] emitResult -> device=${session.device.name.orEmpty()}[${session.device.address}], version=${result.versionSnapshot.version}, source=${result.versionSnapshot.source}, reason=$reason, info=${deviceInformation.summary()}"
        )
        scope.launch {
            onProbeResult(session.device, result)
        }
    }

    private fun closeGatt(
        session: ProbeSession,
        reason: String,
        removeSession: Boolean,
        markUnsupported: Boolean
    ) {
        session.isConnecting = false
        session.pendingCharacteristics.clear()
        if (markUnsupported) {
            session.gattUnsupported = true
        }
        runCatching {
            session.gatt?.close()
        }.onFailure {
            Log.w(
                tag,
                "[BluetoothVersionProbeManager] closeGatt -> close failed: device=${session.device.name.orEmpty()}[${session.device.address}], reason=$reason",
                it
            )
        }
        session.gatt = null
        if (removeSession) {
            sessions.remove(session.device.address)
        }
        Log.i(
            tag,
            "[BluetoothVersionProbeManager] closeGatt -> closed: device=${session.device.name.orEmpty()}[${session.device.address}], reason=$reason, unsupported=$markUnsupported"
        )
    }

    private inner class VersionGattCallback(
        private val address: String
    ) : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val session = sessions[address] ?: return
            session.isConnecting = false
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(
                    tag,
                    "[BluetoothVersionProbeManager] onConnectionStateChange -> connected: device=${session.device.name.orEmpty()}[$address]"
                )
                if (!gatt.discoverServices()) {
                    closeGatt(session, reason = "discoverServices failed", removeSession = true, markUnsupported = false)
                }
                return
            }

            if (shouldRetryConnection(session, status, newState)) {
                closeGatt(
                    session = session,
                    reason = "prepare retry for status=$status state=$newState",
                    removeSession = false,
                    markUnsupported = false
                )
                scheduleRetry(session, status, newState)
                return
            }

            closeGatt(
                session = session,
                reason = "connection state change status=$status state=$newState",
                removeSession = true,
                markUnsupported = false
            )
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val session = sessions[address] ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGatt(session, reason = "service discovery failed: $status", removeSession = true, markUnsupported = false)
                return
            }

            val service = gatt.getService(BluetoothVersionUtils.DEVICE_INFORMATION_SERVICE_UUID)
            if (service == null) {
                closeGatt(session, reason = "device information service missing", removeSession = false, markUnsupported = true)
                return
            }

            val characteristics = BluetoothVersionUtils.DEVICE_INFORMATION_CHARACTERISTIC_UUIDS
                .mapNotNull(service::getCharacteristic)
                .filter {
                    it.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
                }

            if (characteristics.isEmpty()) {
                closeGatt(session, reason = "device information characteristics missing", removeSession = false, markUnsupported = true)
                return
            }

            session.gattUnsupported = false
            session.pendingCharacteristics.addAll(characteristics)
            requestNextRead(session, gatt, reason = "service-discovered")
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            handleCharacteristicRead(
                gatt = gatt,
                characteristic = characteristic,
                value = characteristic.value,
                status = status
            )
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            handleCharacteristicRead(
                gatt = gatt,
                characteristic = characteristic,
                value = value,
                status = status
            )
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            val session = sessions[address] ?: return
            if (!gatt.discoverServices()) {
                closeGatt(session, reason = "rediscover after service changed failed", removeSession = true, markUnsupported = false)
            }
        }

        private fun handleCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray?,
            status: Int
        ) {
            val session = sessions[address] ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(
                    tag,
                    "[BluetoothVersionProbeManager] handleCharacteristicRead -> failed: device=${session.device.name.orEmpty()}[$address], uuid=${characteristic.uuid}, status=$status"
                )
                requestNextRead(session, gatt, reason = "read-failed:${characteristic.uuid}")
                return
            }

            BluetoothVersionUtils.parseDeviceInformationCharacteristic(
                characteristicUuid = characteristic.uuid,
                value = value
            )?.let { parsedSnapshot ->
                session.deviceInformation = BluetoothVersionUtils.mergeDeviceInformationSnapshots(
                    current = session.deviceInformation,
                    update = parsedSnapshot
                )
                Log.i(
                    tag,
                    "[BluetoothVersionProbeManager] handleCharacteristicRead -> parsed: device=${session.device.name.orEmpty()}[$address], uuid=${characteristic.uuid}, info=${parsedSnapshot.summary()}"
                )
            }

            requestNextRead(session, gatt, reason = "read-success:${characteristic.uuid}")
        }
    }

    private fun shouldRetryConnection(
        session: ProbeSession,
        status: Int,
        newState: Int
    ): Boolean {
        return newState != BluetoothProfile.STATE_CONNECTED &&
            !session.hasCompleted &&
            !session.gattUnsupported &&
            status in RETRYABLE_CONNECTION_STATUSES &&
            session.retryAttemptCount < MAX_RETRY_ATTEMPTS
    }

    private fun scheduleRetry(
        session: ProbeSession,
        status: Int,
        newState: Int
    ) {
        session.retryAttemptCount += 1
        val nextTransport = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            session.lastTransport != BluetoothDevice.TRANSPORT_AUTO
        ) {
            BluetoothDevice.TRANSPORT_AUTO
        } else {
            session.lastTransport
        }
        session.retryJob?.cancel()
        session.retryJob = scope.launch {
            delay(RETRY_DELAY_MS)
            val currentSession = sessions[session.device.address] ?: return@launch
            connectGatt(
                session = currentSession,
                reason = "retry#${
                    session.retryAttemptCount
                } status=$status state=$newState",
                transportOverride = nextTransport
            )
        }
        Log.i(
            tag,
            "[BluetoothVersionProbeManager] scheduleRetry -> retry=${session.retryAttemptCount}, device=${session.device.name.orEmpty()}[${session.device.address}], status=$status, state=$newState, transport=$nextTransport"
        )
    }

    private data class ProbeSession(
        @Volatile var device: BluetoothDevice,
        @Volatile var persistedVersion: String,
        val callback: BluetoothGattCallback,
        @Volatile var gatt: BluetoothGatt? = null,
        @Volatile var isConnecting: Boolean = false,
        @Volatile var gattUnsupported: Boolean = false,
        @Volatile var hasCompleted: Boolean = false,
        @Volatile var retryAttemptCount: Int = 0,
        @Volatile var lastTransport: Int = BluetoothDevice.TRANSPORT_AUTO,
        @Volatile var retryJob: Job? = null,
        var deviceInformation: DeviceInformationSnapshot = DeviceInformationSnapshot(),
        val pendingCharacteristics: ArrayDeque<BluetoothGattCharacteristic> = ArrayDeque()
    )

    companion object {
        private const val MAX_RETRY_ATTEMPTS = 1
        private const val RETRY_DELAY_MS = 1_500L
        private val RETRYABLE_CONNECTION_STATUSES = setOf(133, 147)
    }
}
