package com.xingkeqi.btlogger

import android.bluetooth.BluetoothDevice
import com.xingkeqi.btlogger.utils.BluetoothBatteryUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothBatteryUtilsTest {

    @Test
    fun scaleBatteryLevel_returnsPercent() {
        assertEquals(50, BluetoothBatteryUtils.scaleBatteryLevel(level = 2, scale = 4))
        assertEquals(100, BluetoothBatteryUtils.scaleBatteryLevel(level = 15, scale = 15))
        assertNull(BluetoothBatteryUtils.scaleBatteryLevel(level = -1, scale = 10))
    }

    @Test
    fun parseVendorBatteryLevel_parsesIPhoneAccevBattery() {
        val level = BluetoothBatteryUtils.parseVendorBatteryLevel(
            command = "+IPHONEACCEV",
            args = listOf("1", "1", "4")
        )

        assertEquals(50, level)
    }

    @Test
    fun parseVendorBatteryLevel_parsesXEventCurrentAndMax() {
        val level = BluetoothBatteryUtils.parseVendorBatteryLevel(
            command = "+XEVENT",
            args = listOf("BATTERY", "3", "6", "0", "0")
        )

        // 6 档、档位从 0 起算：3 / (6 - 1) = 60%，与系统换算一致
        assertEquals(60, level)
    }

    @Test
    fun parseVendorBatteryLevel_rejectsInvalidXEventLevels() {
        assertNull(BluetoothBatteryUtils.parseVendorBatteryLevel(command = "+XEVENT", args = listOf("BATTERY", "7", "6")))
        assertNull(BluetoothBatteryUtils.parseVendorBatteryLevel(command = "+XEVENT", args = listOf("BATTERY", "1", "1")))
    }

    @Test
    fun parseVendorBatteryLevel_parsesXEventSingleDigitBatteryStep() {
        val level = BluetoothBatteryUtils.parseVendorBatteryLevel(
            command = "+XEVENT",
            args = listOf("BATTERY", "6")
        )

        assertEquals(70, level)
    }

    @Test
    fun parseVendorBatteryLevel_returnsNullForUnsupportedCommand() {
        val level = BluetoothBatteryUtils.parseVendorBatteryLevel(
            command = "+UNKNOWN",
            args = listOf("BATTERY", "60")
        )

        assertNull(level)
    }

    @Test
    fun parseBatteryServiceLevel_readsUnsignedPercent() {
        assertEquals(100, BluetoothBatteryUtils.parseBatteryServiceLevel(byteArrayOf(100.toByte())))
        assertEquals(90, BluetoothBatteryUtils.parseBatteryServiceLevel(byteArrayOf(90.toByte())))
        assertNull(BluetoothBatteryUtils.parseBatteryServiceLevel(byteArrayOf((-1).toByte())))
    }

    @Test
    fun shouldTryBatteryGatt_onlyForLeAndDualDevices() {
        assertTrue(BluetoothBatteryUtils.shouldTryBatteryGatt(BluetoothDevice.DEVICE_TYPE_LE))
        assertTrue(BluetoothBatteryUtils.shouldTryBatteryGatt(BluetoothDevice.DEVICE_TYPE_DUAL))
        assertFalse(BluetoothBatteryUtils.shouldTryBatteryGatt(BluetoothDevice.DEVICE_TYPE_CLASSIC))
    }
}
