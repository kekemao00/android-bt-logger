package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.data.BLUETOOTH_VERSION_UNKNOWN
import com.xingkeqi.btlogger.utils.AdvertisementSnapshot
import com.xingkeqi.btlogger.utils.BluetoothVersionUtils
import com.xingkeqi.btlogger.utils.DeviceInformationSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BluetoothVersionUtilsTest {

    @Test
    fun normalizeBluetoothVersion_parsesExplicitBluetoothVersions() {
        assertEquals("5.3", BluetoothVersionUtils.normalizeBluetoothVersion("5.3"))
        assertEquals("5.0", BluetoothVersionUtils.normalizeBluetoothVersion("Bluetooth 5"))
        assertEquals("5.4", BluetoothVersionUtils.normalizeBluetoothVersion("BT version: 5.4"))
        assertEquals("5.3", BluetoothVersionUtils.normalizeBluetoothVersion("BT53"))
        assertEquals("5.2", BluetoothVersionUtils.normalizeBluetoothVersion("5.2 Bluetooth"))
    }

    @Test
    fun normalizeBluetoothVersion_rejectsAmbiguousOrUnsupportedValues() {
        assertNull(BluetoothVersionUtils.normalizeBluetoothVersion("Firmware 5.3"))
        assertNull(BluetoothVersionUtils.normalizeBluetoothVersion("7.0"))
        assertNull(BluetoothVersionUtils.normalizeBluetoothVersion("v5.3"))
    }

    @Test
    fun mergeBluetoothVersion_prefersResolvedAndPreservesKnownCache() {
        assertEquals(
            "5.3",
            BluetoothVersionUtils.mergeBluetoothVersion(
                persistedVersion = "5.0",
                resolvedVersion = "5.3"
            )
        )
        assertEquals(
            "5.0",
            BluetoothVersionUtils.mergeBluetoothVersion(
                persistedVersion = "5.0",
                resolvedVersion = null
            )
        )
        assertEquals(
            BLUETOOTH_VERSION_UNKNOWN,
            BluetoothVersionUtils.mergeBluetoothVersion(
                persistedVersion = BLUETOOTH_VERSION_UNKNOWN,
                resolvedVersion = null
            )
        )
    }

    @Test
    fun resolveBluetoothVersionFromDeviceInformation_prefersExplicitDeviceInformationFields() {
        val result = BluetoothVersionUtils.resolveBluetoothVersionFromDeviceInformation(
            deviceName = "Test Headset",
            deviceInformation = DeviceInformationSnapshot(
                manufacturerName = "Vendor",
                firmwareRevision = "Bluetooth 5.3",
                softwareRevision = "v2.1.0"
            ),
            persistedVersion = BLUETOOTH_VERSION_UNKNOWN
        )

        assertEquals("5.3", result.version)
        assertEquals("dis:firmware-revision", result.source)
    }

    @Test
    fun resolveBluetoothVersionFromDeviceInformation_fallsBackToCacheWhenNoExplicitVersion() {
        val result = BluetoothVersionUtils.resolveBluetoothVersionFromDeviceInformation(
            deviceName = "Test Headset",
            deviceInformation = DeviceInformationSnapshot(
                manufacturerName = "Vendor",
                modelNumber = "AB-123",
                softwareRevision = "v2.1.0"
            ),
            persistedVersion = "5.2"
        )

        assertEquals("5.2", result.version)
        assertEquals("cache", result.source)
    }

    @Test
    fun resolveBluetoothVersionFromFingerprint_usesExactDeviceCatalogWhenMatched() {
        val result = BluetoothVersionUtils.resolveBluetoothVersionFromFingerprint(
            deviceName = "SOUNDPEATS Clip1",
            deviceInformation = DeviceInformationSnapshot(),
            advertisementSnapshot = AdvertisementSnapshot(),
            persistedVersion = BLUETOOTH_VERSION_UNKNOWN
        )

        assertEquals("5.4", result.version)
        assertEquals("catalog:device-name", result.source)
    }

    @Test
    fun resolveBluetoothVersionFromFingerprint_usesManufacturerModelCatalogWhenMatched() {
        val result = BluetoothVersionUtils.resolveBluetoothVersionFromFingerprint(
            deviceName = "Unknown Earbuds",
            deviceInformation = DeviceInformationSnapshot(
                manufacturerName = "Qualcomm",
                modelNumber = "QCC3091"
            ),
            advertisementSnapshot = AdvertisementSnapshot(),
            persistedVersion = BLUETOOTH_VERSION_UNKNOWN
        )

        assertEquals("5.4", result.version)
        assertEquals("catalog:manufacturer-model", result.source)
    }
}
