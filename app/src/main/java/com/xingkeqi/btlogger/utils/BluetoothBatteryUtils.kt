package com.xingkeqi.btlogger.utils

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.Intent
import android.os.BatteryManager
import android.util.Log
import com.xingkeqi.btlogger.data.DEVICE_BATTERY_LEVEL_UNKNOWN
import kotlin.math.roundToInt

const val ACTION_BLUETOOTH_DEVICE_BATTERY_LEVEL_CHANGED =
    "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"
private const val EXTRA_BLUETOOTH_DEVICE_BATTERY_LEVEL =
    "android.bluetooth.device.extra.BATTERY_LEVEL"
private const val VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV = "+IPHONEACCEV"
private const val VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT = "+XEVENT"
private const val VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT_BATTERY = "BATTERY"
private const val VENDOR_SPECIFIC_HEADSET_EVENT_IPHONE_BATTERY_INDICATOR = 1
private const val BLUETOOTH_BATTERY_LOG_TAG = "BtBatteryUtils"

data class HeadsetBatterySnapshot(
    val level: Int,
    val source: String
)

/**
 * 统一解析系统蓝牙电量广播与 HFP 厂商事件。
 *
 * Why:
 * 蓝牙耳机电量没有稳定的公开 SDK API，部分机型通过隐藏广播上报，部分机型通过
 * HFP Vendor Specific 事件上报，这里集中做兼容解析，业务层只消费 0~100 的结果。
 */
object BluetoothBatteryUtils {

    const val BATTERY_SERVICE_UUID = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_LEVEL_CHARACTERISTIC_UUID = "00002a19-0000-1000-8000-00805f9b34fb"
    const val CLIENT_CHARACTERISTIC_CONFIG_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    fun extractBluetoothDevice(intent: Intent): BluetoothDevice? =
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    fun extractHeadsetBatterySnapshot(intent: Intent): HeadsetBatterySnapshot? {
        return when (intent.action) {
            ACTION_BLUETOOTH_DEVICE_BATTERY_LEVEL_CHANGED -> {
                val level = normalizeBatteryLevel(
                    intent.getIntExtra(
                        EXTRA_BLUETOOTH_DEVICE_BATTERY_LEVEL,
                        DEVICE_BATTERY_LEVEL_UNKNOWN
                    )
                ) ?: return null
                HeadsetBatterySnapshot(level = level, source = "system-broadcast")
            }

            BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT -> {
                val command = intent.getStringExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD)
                val args = readVendorArguments(intent)
                val level = parseVendorBatteryLevel(command, args) ?: return null
                HeadsetBatterySnapshot(level = level, source = "vendor:$command")
            }

            else -> null
        }
    }

    fun extractPhoneBatteryLevel(intent: Intent): Int? {
        if (intent.action != Intent.ACTION_BATTERY_CHANGED) return null
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return scaleBatteryLevel(level, scale)
    }

    fun isKnownBatteryLevel(level: Int): Boolean = level in 0..100

    /**
     * Why:
     * 某些机型不会把耳机电量广播给三方 App，但系统蓝牙服务内部已经缓存了当前值，
     * 这里通过反射尝试读取同一份缓存，尽量向系统设置页行为靠拢。
     */
    @Suppress("SwallowedException")
    fun readBatteryLevelReflectively(device: BluetoothDevice): HeadsetBatterySnapshot? {
        val method = reflectedBatteryLevelMethod ?: return null
        return try {
            val rawLevel = method.invoke(device) as? Int ?: return null
            val level = normalizeBatteryLevel(rawLevel) ?: return null
            HeadsetBatterySnapshot(level = level, source = "reflection:getBatteryLevel")
        } catch (e: Exception) {
            Log.w(
                BLUETOOTH_BATTERY_LOG_TAG,
                "[BluetoothBatteryUtils] readBatteryLevelReflectively -> failed for ${device.address}",
                e
            )
            null
        }
    }

    fun parseBatteryServiceLevel(value: ByteArray?): Int? {
        val rawLevel = value?.firstOrNull()?.toInt() ?: return null
        return normalizeBatteryLevel(rawLevel and 0xFF)
    }

    fun shouldTryBatteryGatt(deviceType: Int): Boolean {
        return deviceType == BluetoothDevice.DEVICE_TYPE_LE ||
            deviceType == BluetoothDevice.DEVICE_TYPE_DUAL
    }

    fun resolveGattTransport(deviceType: Int): Int {
        return if (shouldTryBatteryGatt(deviceType)) {
            BluetoothDevice.TRANSPORT_LE
        } else {
            BluetoothDevice.TRANSPORT_AUTO
        }
    }

    internal fun parseVendorBatteryLevel(command: String?, args: List<String>): Int? {
        val normalizedCommand = command?.trim()?.uppercase() ?: return null
        return when (normalizedCommand) {
            VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV -> parseIPhoneAccevBatteryLevel(args)
            VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT -> parseXEventBatteryLevel(args)
            else -> null
        }
    }

    internal fun scaleBatteryLevel(level: Int, scale: Int): Int? {
        if (level < 0 || scale <= 0) return null
        return ((level.toFloat() / scale.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
    }

    private fun parseIPhoneAccevBatteryLevel(args: List<String>): Int? {
        val values = args.mapNotNull(::extractInt)
        val pairCount = values.firstOrNull() ?: return null
        if (pairCount <= 0) return null

        var index = 1
        repeat(pairCount) {
            val indicator = values.getOrNull(index) ?: return null
            val value = values.getOrNull(index + 1) ?: return null
            if (indicator == VENDOR_SPECIFIC_HEADSET_EVENT_IPHONE_BATTERY_INDICATOR) {
                return normalizeBatteryLevel(if (value in 0..9) (value + 1) * 10 else value)
            }
            index += 2
        }
        return null
    }

    private fun parseXEventBatteryLevel(args: List<String>): Int? {
        if (args.isEmpty() || !args.first().equals(VENDOR_SPECIFIC_HEADSET_EVENT_XEVENT_BATTERY, ignoreCase = true)) {
            return null
        }

        val numericArgs = args.drop(1).mapNotNull(::extractInt)
        if (numericArgs.isEmpty()) return null

        if (numericArgs.size >= 2) {
            val current = numericArgs[0]
            val max = numericArgs[1]
            if (current >= 0 && max > 0) {
                return ((current.toFloat() / max.toFloat()) * 100f).roundToInt().coerceIn(0, 100)
            }
        }

        val rawLevel = numericArgs.first()
        return normalizeBatteryLevel(if (rawLevel in 0..9) (rawLevel + 1) * 10 else rawLevel)
    }

    private fun readVendorArguments(intent: Intent): List<String> {
        val rawValue = intent.extras?.get(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS)
        return when (rawValue) {
            is Array<*> -> rawValue.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is Iterable<*> -> rawValue.mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }
            is String -> rawValue.split(",").map { it.trim() }.filter(String::isNotEmpty)
            else -> emptyList()
        }
    }

    private fun extractInt(value: String): Int? {
        return INTEGER_PATTERN.find(value)?.value?.toIntOrNull()
    }

    private fun normalizeBatteryLevel(level: Int): Int? {
        return level.takeIf(::isKnownBatteryLevel)
    }

    private val INTEGER_PATTERN = Regex("-?\\d+")

    private val reflectedBatteryLevelMethod by lazy {
        runCatching {
            BluetoothDevice::class.java.getMethod("getBatteryLevel").apply {
                isAccessible = true
            }
        }.getOrNull()
    }
}
