package com.xingkeqi.btlogger.service

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import com.xingkeqi.btlogger.utils.BluetoothBatteryUtils
import com.xingkeqi.btlogger.utils.HeadsetBatterySnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 主动补齐系统未广播给三方应用的耳机电量来源。
 *
 * Why:
 * 1. 反射 `BluetoothDevice.getBatteryLevel()` 可以读取部分系统蓝牙缓存值。
 * 2. 对支持标准 Battery Service 的 LE/双模设备，额外通过 GATT 读取或订阅电量变化。
 */
class BluetoothBatteryProbeManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onBatterySnapshot: (BluetoothDevice, HeadsetBatterySnapshot) -> Unit
) {

    private val tag = "BtBatteryProbeMgr"
    private val sessions = ConcurrentHashMap<String, ProbeSession>()

    @SuppressLint("MissingPermission")
    fun startMonitoring(device: BluetoothDevice) {
        val address = device.address
        val session = sessions[address]?.apply {
            this.device = device
        } ?: ProbeSession(
            device = device,
            callback = BatteryGattCallback(address)
        ).also { sessions[address] = it }

        if (session.pollJob?.isActive == true) {
            scope.launch {
                probe(address, reason = "refresh")
            }
            return
        }

        session.pollJob = scope.launch {
            Log.i(
                tag,
                "[BluetoothBatteryProbeManager] startMonitoring -> start poll loop: device=${device.name.orEmpty()}[$address], deviceType=${device.type}"
            )
            while (isActive) {
                probe(address, reason = "poll")
                delay(BATTERY_PROBE_INTERVAL_MS)
            }
        }
    }

    fun stopMonitoring(address: String) {
        val session = sessions.remove(address) ?: return
        session.pollJob?.cancel()
        closeGatt(session, reason = "stop monitoring", markUnsupported = false)
        Log.i(
            tag,
            "[BluetoothBatteryProbeManager] stopMonitoring -> stop poll loop: device=${session.device.name.orEmpty()}[$address]"
        )
    }

    fun stopAll() {
        sessions.keys.toList().forEach(::stopMonitoring)
    }

    @SuppressLint("MissingPermission")
    private suspend fun probe(address: String, reason: String) {
        val session = sessions[address] ?: return
        val device = session.device

        BluetoothBatteryUtils.readBatteryLevelReflectively(device)?.let { snapshot ->
            Log.d(
                tag,
                "[BluetoothBatteryProbeManager] probe -> reflection hit: device=${device.name.orEmpty()}[$address], battery=${snapshot.level}, reason=$reason"
            )
            onBatterySnapshot(device, snapshot)
            return
        }

        if (!BluetoothBatteryUtils.shouldTryBatteryGatt(device.type)) {
            Log.d(
                tag,
                "[BluetoothBatteryProbeManager] probe -> skip gatt for non LE/dual device: device=${device.name.orEmpty()}[$address], type=${device.type}, reason=$reason"
            )
            return
        }

        if (session.notificationEnabled) {
            Log.d(
                tag,
                "[BluetoothBatteryProbeManager] probe -> waiting for battery notifications: device=${device.name.orEmpty()}[$address], reason=$reason"
            )
            return
        }

        val characteristic = session.batteryCharacteristic
        val gatt = session.gatt
        when {
            gatt != null && characteristic != null -> {
                requestBatteryRead(session, gatt, characteristic, reason)
            }

            !session.gattUnsupported -> {
                connectGatt(session, reason)
            }

            else -> {
                Log.d(
                    tag,
                    "[BluetoothBatteryProbeManager] probe -> skip gatt after unsupported mark: device=${device.name.orEmpty()}[$address], reason=$reason"
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectGatt(session: ProbeSession, reason: String) {
        if (session.gatt != null || session.isConnecting) return
        val now = System.currentTimeMillis()
        if (now - session.lastGattAttemptAt < GATT_RETRY_INTERVAL_MS) {
            Log.d(
                tag,
                "[BluetoothBatteryProbeManager] connectGatt -> throttle retry: device=${session.device.name.orEmpty()}[${session.device.address}], reason=$reason"
            )
            return
        }

        session.lastGattAttemptAt = now
        session.isConnecting = true
        try {
            val transport = BluetoothBatteryUtils.resolveGattTransport(session.device.type)
            val gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                session.device.connectGatt(context, false, session.callback, transport)
            } else {
                session.device.connectGatt(context, false, session.callback)
            }
            session.gatt = gatt
            Log.i(
                tag,
                "[BluetoothBatteryProbeManager] connectGatt -> connect start: device=${session.device.name.orEmpty()}[${session.device.address}], transport=$transport, reason=$reason"
            )
        } catch (e: Exception) {
            session.isConnecting = false
            Log.e(
                tag,
                "[BluetoothBatteryProbeManager] connectGatt -> failed: device=${session.device.name.orEmpty()}[${session.device.address}], reason=$reason",
                e
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestBatteryRead(
        session: ProbeSession,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        reason: String
    ) {
        if (session.isConnecting) return
        val canRead = characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
        if (!canRead) {
            Log.d(
                tag,
                "[BluetoothBatteryProbeManager] requestBatteryRead -> characteristic is not readable: device=${session.device.name.orEmpty()}[${session.device.address}], reason=$reason"
            )
            return
        }
        val started = gatt.readCharacteristic(characteristic)
        Log.i(
            tag,
            "[BluetoothBatteryProbeManager] requestBatteryRead -> started=$started, device=${session.device.name.orEmpty()}[${session.device.address}], reason=$reason"
        )
        if (!started) {
            closeGatt(session, reason = "read request rejected", markUnsupported = false)
        }
    }

    private fun enableNotificationsIfPossible(
        session: ProbeSession,
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        val supportsNotify =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        val supportsIndicate =
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        if (!supportsNotify && !supportsIndicate) return false

        val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        if (descriptor == null) {
            Log.w(
                tag,
                "[BluetoothBatteryProbeManager] enableNotificationsIfPossible -> cccd missing: device=${session.device.name.orEmpty()}[${session.device.address}]"
            )
            return false
        }

        val notificationEnabled = gatt.setCharacteristicNotification(characteristic, true)
        if (!notificationEnabled) {
            Log.w(
                tag,
                "[BluetoothBatteryProbeManager] enableNotificationsIfPossible -> setCharacteristicNotification failed: device=${session.device.name.orEmpty()}[${session.device.address}]"
            )
            return false
        }

        descriptor.value = if (supportsNotify) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val writeStarted = gatt.writeDescriptor(descriptor)
        Log.i(
            tag,
            "[BluetoothBatteryProbeManager] enableNotificationsIfPossible -> descriptorWrite=$writeStarted, device=${session.device.name.orEmpty()}[${session.device.address}]"
        )
        return writeStarted
    }

    private fun closeGatt(session: ProbeSession, reason: String, markUnsupported: Boolean) {
        session.isConnecting = false
        session.notificationEnabled = false
        session.batteryCharacteristic = null
        if (markUnsupported) {
            session.gattUnsupported = true
        }
        runCatching {
            session.gatt?.close()
        }.onFailure {
            Log.w(
                tag,
                "[BluetoothBatteryProbeManager] closeGatt -> close failed: device=${session.device.name.orEmpty()}[${session.device.address}], reason=$reason",
                it
            )
        }
        session.gatt = null
        Log.i(
            tag,
            "[BluetoothBatteryProbeManager] closeGatt -> closed: device=${session.device.name.orEmpty()}[${session.device.address}], reason=$reason, unsupported=$markUnsupported"
        )
    }

    private fun onBatteryValue(
        address: String,
        source: String,
        value: ByteArray?,
        closeAfterRead: Boolean
    ) {
        val session = sessions[address] ?: return
        val snapshot = BluetoothBatteryUtils.parseBatteryServiceLevel(value)
            ?.let { HeadsetBatterySnapshot(level = it, source = source) }
        if (snapshot == null) {
            Log.d(
                tag,
                "[BluetoothBatteryProbeManager] onBatteryValue -> invalid payload: device=${session.device.name.orEmpty()}[$address], source=$source"
            )
            if (closeAfterRead) {
                closeGatt(session, reason = "invalid battery payload", markUnsupported = false)
            }
            return
        }

        Log.i(
            tag,
            "[BluetoothBatteryProbeManager] onBatteryValue -> battery=${snapshot.level}, device=${session.device.name.orEmpty()}[$address], source=$source"
        )
        scope.launch {
            onBatterySnapshot(session.device, snapshot)
        }

        if (closeAfterRead) {
            closeGatt(session, reason = "single battery read finished", markUnsupported = false)
        }
    }

    private inner class BatteryGattCallback(
        private val address: String
    ) : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val session = sessions[address] ?: return
            session.isConnecting = false
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(
                    tag,
                    "[BluetoothBatteryProbeManager] onConnectionStateChange -> connected: device=${session.device.name.orEmpty()}[$address]"
                )
                if (!gatt.discoverServices()) {
                    closeGatt(session, reason = "discoverServices failed", markUnsupported = false)
                }
                return
            }

            closeGatt(
                session,
                reason = "connection state change status=$status state=$newState",
                markUnsupported = false
            )
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val session = sessions[address] ?: return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                closeGatt(session, reason = "service discovery failed: $status", markUnsupported = false)
                return
            }

            val service = gatt.getService(BATTERY_SERVICE_UUID)
            val characteristic = service?.getCharacteristic(BATTERY_LEVEL_CHARACTERISTIC_UUID)
            if (service == null || characteristic == null) {
                closeGatt(session, reason = "battery service missing", markUnsupported = true)
                return
            }

            session.batteryCharacteristic = characteristic
            session.gattUnsupported = false
            val subscribed = enableNotificationsIfPossible(session, gatt, characteristic)
            if (subscribed) {
                return
            }

            requestBatteryRead(session, gatt, characteristic, reason = "service-discovered")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            val session = sessions[address] ?: return
            if (descriptor.uuid != CLIENT_CHARACTERISTIC_CONFIG_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(
                    tag,
                    "[BluetoothBatteryProbeManager] onDescriptorWrite -> failed: device=${session.device.name.orEmpty()}[$address], status=$status"
                )
                requestBatteryRead(
                    session = session,
                    gatt = gatt,
                    characteristic = session.batteryCharacteristic ?: return,
                    reason = "descriptor-write-failed"
                )
                return
            }

            session.notificationEnabled = true
            Log.i(
                tag,
                "[BluetoothBatteryProbeManager] onDescriptorWrite -> notifications enabled: device=${session.device.name.orEmpty()}[$address]"
            )
            val characteristic = session.batteryCharacteristic ?: return
            if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
                requestBatteryRead(session, gatt, characteristic, reason = "notifications-enabled")
            }
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

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCharacteristicChanged(
                characteristic = characteristic,
                value = characteristic.value
            )
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCharacteristicChanged(
                characteristic = characteristic,
                value = value
            )
        }

        override fun onServiceChanged(gatt: BluetoothGatt) {
            val session = sessions[address] ?: return
            Log.i(
                tag,
                "[BluetoothBatteryProbeManager] onServiceChanged -> rediscover services: device=${session.device.name.orEmpty()}[$address]"
            )
            if (!gatt.discoverServices()) {
                closeGatt(session, reason = "rediscover after service changed failed", markUnsupported = false)
            }
        }

        private fun handleCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray?,
            status: Int
        ) {
            val session = sessions[address] ?: return
            if (characteristic.uuid != BATTERY_LEVEL_CHARACTERISTIC_UUID) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(
                    tag,
                    "[BluetoothBatteryProbeManager] handleCharacteristicRead -> failed: device=${session.device.name.orEmpty()}[$address], status=$status"
                )
                closeGatt(session, reason = "battery read failed", markUnsupported = false)
                return
            }
            onBatteryValue(
                address = address,
                source = if (session.notificationEnabled) "gatt:initial-read" else "gatt:read",
                value = value,
                closeAfterRead = !session.notificationEnabled
            )
        }

        private fun handleCharacteristicChanged(
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray?
        ) {
            if (characteristic.uuid != BATTERY_LEVEL_CHARACTERISTIC_UUID) return
            onBatteryValue(
                address = address,
                source = "gatt:notify",
                value = value,
                closeAfterRead = false
            )
        }
    }

    private data class ProbeSession(
        @Volatile var device: BluetoothDevice,
        val callback: BluetoothGattCallback,
        @Volatile var gatt: BluetoothGatt? = null,
        @Volatile var batteryCharacteristic: BluetoothGattCharacteristic? = null,
        @Volatile var notificationEnabled: Boolean = false,
        @Volatile var gattUnsupported: Boolean = false,
        @Volatile var isConnecting: Boolean = false,
        @Volatile var lastGattAttemptAt: Long = 0L,
        var pollJob: Job? = null
    )

    private companion object {
        const val BATTERY_PROBE_INTERVAL_MS = 5 * 60 * 1000L
        const val GATT_RETRY_INTERVAL_MS = 60 * 1000L
        val BATTERY_SERVICE_UUID: UUID = UUID.fromString(BluetoothBatteryUtils.BATTERY_SERVICE_UUID)
        val BATTERY_LEVEL_CHARACTERISTIC_UUID: UUID =
            UUID.fromString(BluetoothBatteryUtils.BATTERY_LEVEL_CHARACTERISTIC_UUID)
        val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString(BluetoothBatteryUtils.CLIENT_CHARACTERISTIC_CONFIG_UUID)
    }
}
