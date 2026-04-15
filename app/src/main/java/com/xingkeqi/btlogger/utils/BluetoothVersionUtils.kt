package com.xingkeqi.btlogger.utils

import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanResult
import android.util.Log
import com.xingkeqi.btlogger.data.BLUETOOTH_VERSION_UNKNOWN
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID

data class BluetoothVersionSnapshot(
    val version: String,
    val source: String,
    val rawValue: String? = null
)

data class DeviceInformationSnapshot(
    val modelNumber: String? = null,
    val manufacturerName: String? = null,
    val firmwareRevision: String? = null,
    val hardwareRevision: String? = null,
    val softwareRevision: String? = null,
    val serialNumber: String? = null,
    val pnpId: String? = null
) {
    fun summary(): String {
        return listOfNotNull(
            manufacturerName?.let { "manufacturer=$it" },
            modelNumber?.let { "model=$it" },
            firmwareRevision?.let { "firmware=$it" },
            hardwareRevision?.let { "hardware=$it" },
            softwareRevision?.let { "software=$it" },
            serialNumber?.let { "serial=$it" },
            pnpId?.let { "pnp=$it" }
        ).joinToString(separator = ", ")
    }

    fun hasData(): Boolean {
        return listOf(
            modelNumber,
            manufacturerName,
            firmwareRevision,
            hardwareRevision,
            softwareRevision,
            serialNumber,
            pnpId
        ).any { !it.isNullOrBlank() }
    }
}

data class AdvertisementSnapshot(
    val localName: String? = null,
    val manufacturerDataHex: Map<Int, String> = emptyMap(),
    val manufacturerDataText: Map<Int, String> = emptyMap(),
    val serviceDataHex: Map<String, String> = emptyMap(),
    val serviceDataText: Map<String, String> = emptyMap(),
    val serviceUuids: List<String> = emptyList(),
    val rawBytesHex: String? = null
) {
    fun summary(): String {
        return listOfNotNull(
            localName?.let { "advName=$it" },
            manufacturerDataText.takeIf { it.isNotEmpty() }?.entries
                ?.sortedBy { it.key }
                ?.joinToString(separator = ";") {
                    "mfgText=${formatManufacturerId(it.key)}:${preview(it.value)}"
                },
            manufacturerDataHex.takeIf { it.isNotEmpty() }?.entries
                ?.sortedBy { it.key }
                ?.joinToString(separator = ";") {
                    "mfgHex=${formatManufacturerId(it.key)}:${preview(it.value)}"
                },
            serviceDataText.takeIf { it.isNotEmpty() }?.entries
                ?.sortedBy { it.key }
                ?.joinToString(separator = ";") {
                    "svcText=${it.key}:${preview(it.value)}"
                },
            serviceDataHex.takeIf { it.isNotEmpty() }?.entries
                ?.sortedBy { it.key }
                ?.joinToString(separator = ";") {
                    "svcHex=${it.key}:${preview(it.value)}"
                },
            serviceUuids.takeIf { it.isNotEmpty() }?.joinToString(
                prefix = "uuids=",
                separator = ";"
            ),
            rawBytesHex?.let { "raw=${preview(it)}" }
        ).joinToString(separator = ", ")
    }

    fun hasData(): Boolean {
        return !localName.isNullOrBlank() ||
            manufacturerDataHex.isNotEmpty() ||
            manufacturerDataText.isNotEmpty() ||
            serviceDataHex.isNotEmpty() ||
            serviceDataText.isNotEmpty() ||
            serviceUuids.isNotEmpty() ||
            !rawBytesHex.isNullOrBlank()
    }

    companion object {
        private fun formatManufacturerId(id: Int): String {
            return "0x%04X".format(Locale.US, id)
        }

        private fun preview(value: String, maxLength: Int = 48): String {
            return if (value.length <= maxLength) {
                value
            } else {
                value.take(maxLength) + "..."
            }
        }
    }
}

data class BluetoothVersionProbeResult(
    val versionSnapshot: BluetoothVersionSnapshot,
    val deviceInformation: DeviceInformationSnapshot = DeviceInformationSnapshot(),
    val advertisementSnapshot: AdvertisementSnapshot = AdvertisementSnapshot()
)

/**
 * 统一做耳机蓝牙版本的尽力读取与格式规范化。
 *
 * Why:
 * Android 公有 SDK 没有稳定的远端耳机蓝牙版本接口，这里优先接受设备显式上报的版本文本；
 * 当设备只暴露型号/芯片指纹时，再走保守的本地目录映射兜底，避免通过编解码或 UUID 做高误判猜测。
 */
object BluetoothVersionUtils {

    private const val TAG = "BtVersionUtils"
    private const val METADATA_SOFTWARE_VERSION = 2
    private const val METADATA_HARDWARE_VERSION = 3
    private const val FINGERPRINT_UNAVAILABLE_SOURCE = "fingerprint:unavailable"
    private val getMetadataMethod by lazy {
        runCatching {
            BluetoothDevice::class.java.getMethod("getMetadata", Int::class.javaPrimitiveType).apply {
                isAccessible = true
            }
        }.getOrNull()
    }

    @Volatile
    private var metadataAccessBlocked = false

    private data class MetadataSource(
        val key: Int,
        val label: String
    )

    private data class ManufacturerModelCatalogRule(
        val manufacturerKey: String,
        val modelKey: String,
        val version: String
    )

    private val metadataSources = listOf(
        MetadataSource(METADATA_SOFTWARE_VERSION, "software-version"),
        MetadataSource(METADATA_HARDWARE_VERSION, "hardware-version")
    )

    private val explicitVersionPattern = Regex("^\\s*([1-6])(?:\\.(\\d))?\\s*$")
    private val labeledPrefixPattern = Regex(
        pattern = "(?i)\\b(?:bluetooth|bt|ble)\\s*(?:core(?:\\s+spec(?:ification)?)?\\s*)?(?:version|ver|v)?\\s*[:：=_-]?\\s*([1-6])(?:[._ ]?(\\d))?\\b"
    )
    private val labeledSuffixPattern = Regex(
        pattern = "(?i)\\b([1-6])(?:[._ ]?(\\d))?\\s*(?:bluetooth|bt|ble)\\b"
    )
    private val supportedVersions = setOf(
        "1.0",
        "1.1",
        "1.2",
        "2.0",
        "2.1",
        "3.0",
        "4.0",
        "4.1",
        "4.2",
        "5.0",
        "5.1",
        "5.2",
        "5.3",
        "5.4",
        "6.0"
    )
    private val exactDeviceNameCatalog = mapOf(
        normalizeCatalogKey("SOUNDPEATS Air5") to "5.4",
        normalizeCatalogKey("SOUNDPEATS Air5 Pro") to "5.4",
        normalizeCatalogKey("SOUNDPEATS Air5 Pro+") to "5.4",
        normalizeCatalogKey("SOUNDPEATS Clip1") to "5.4",
        normalizeCatalogKey("SOUNDPEATS C30") to "6.0"
    )
    private val manufacturerModelCatalog = listOf(
        ManufacturerModelCatalogRule(
            manufacturerKey = requireNotNull(normalizeCatalogKey("Qualcomm")),
            modelKey = requireNotNull(normalizeCatalogKey("QCC3091")),
            version = "5.4"
        )
    )

    @Suppress("SwallowedException")
    fun resolveBluetoothVersion(
        device: BluetoothDevice,
        persistedVersion: String = BLUETOOTH_VERSION_UNKNOWN
    ): BluetoothVersionSnapshot {
        resolveBluetoothVersionFromFingerprint(
            deviceName = device.name,
            deviceInformation = DeviceInformationSnapshot(),
            advertisementSnapshot = AdvertisementSnapshot(),
            persistedVersion = BLUETOOTH_VERSION_UNKNOWN
        ).takeIf { it.source != FINGERPRINT_UNAVAILABLE_SOURCE }?.let { return it }

        val cachedVersion = normalizeBluetoothVersion(persistedVersion)
        val metadataMethod = getMetadataMethod
        if (!metadataAccessBlocked && metadataMethod != null) {
            metadataSources.forEach { source ->
                val rawValue = readMetadataValue(device, source.key) ?: return@forEach
                val normalizedVersion = normalizeBluetoothVersion(rawValue)
                if (normalizedVersion != null) {
                    Log.i(
                        TAG,
                        "[BluetoothVersionUtils] resolveBluetoothVersion -> Source/Result: device=${device.address}, source=${source.label}, version=$normalizedVersion, raw=$rawValue"
                    )
                    return BluetoothVersionSnapshot(
                        version = normalizedVersion,
                        source = "metadata:${source.label}",
                        rawValue = rawValue
                    )
                }

                Log.d(
                    TAG,
                    "[BluetoothVersionUtils] resolveBluetoothVersion -> ignore ambiguous metadata: device=${device.address}, source=${source.label}, raw=$rawValue"
                )
            }
        }

        if (cachedVersion != null) {
            return BluetoothVersionSnapshot(
                version = cachedVersion,
                source = "cache"
            )
        }

        return BluetoothVersionSnapshot(
            version = BLUETOOTH_VERSION_UNKNOWN,
            source = if (metadataAccessBlocked) "metadata-blocked" else "unavailable"
        )
    }

    fun resolveBluetoothVersionFromFingerprint(
        deviceName: String?,
        deviceInformation: DeviceInformationSnapshot,
        advertisementSnapshot: AdvertisementSnapshot = AdvertisementSnapshot(),
        persistedVersion: String = BLUETOOTH_VERSION_UNKNOWN
    ): BluetoothVersionSnapshot {
        explicitVersionCandidates(
            deviceName = deviceName,
            deviceInformation = deviceInformation,
            advertisementSnapshot = advertisementSnapshot
        ).forEach { (source, rawValue) ->
            val normalizedVersion = normalizeBluetoothVersion(rawValue)
            if (normalizedVersion != null) {
                return BluetoothVersionSnapshot(
                    version = normalizedVersion,
                    source = source,
                    rawValue = rawValue
                )
            }
        }

        resolveCatalogBluetoothVersion(
            deviceName = deviceName,
            deviceInformation = deviceInformation,
            advertisementSnapshot = advertisementSnapshot
        )?.let { return it }

        val cachedVersion = normalizeBluetoothVersion(persistedVersion)
        if (cachedVersion != null) {
            return BluetoothVersionSnapshot(
                version = cachedVersion,
                source = "cache"
            )
        }

        return BluetoothVersionSnapshot(
            version = BLUETOOTH_VERSION_UNKNOWN,
            source = FINGERPRINT_UNAVAILABLE_SOURCE
        )
    }

    fun resolveBluetoothVersionFromDeviceInformation(
        deviceName: String?,
        deviceInformation: DeviceInformationSnapshot,
        persistedVersion: String = BLUETOOTH_VERSION_UNKNOWN
    ): BluetoothVersionSnapshot {
        val result = resolveBluetoothVersionFromFingerprint(
            deviceName = deviceName,
            deviceInformation = deviceInformation,
            advertisementSnapshot = AdvertisementSnapshot(),
            persistedVersion = persistedVersion
        )
        return if (result.source == FINGERPRINT_UNAVAILABLE_SOURCE) {
            BluetoothVersionSnapshot(
                version = BLUETOOTH_VERSION_UNKNOWN,
                source = "dis:unavailable"
            )
        } else {
            result
        }
    }

    fun normalizeBluetoothVersion(rawValue: String?): String? {
        val sanitized = rawValue
            ?.replace("\u0000", "")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null

        explicitVersionPattern.matchEntire(sanitized)?.let { match ->
            return normalizeCapturedVersion(match.groupValues[1], match.groupValues[2])
        }

        labeledPrefixPattern.find(sanitized)?.let { match ->
            return normalizeCapturedVersion(match.groupValues[1], match.groupValues[2])
        }

        labeledSuffixPattern.find(sanitized)?.let { match ->
            return normalizeCapturedVersion(match.groupValues[1], match.groupValues[2])
        }

        return null
    }

    fun mergeBluetoothVersion(
        persistedVersion: String?,
        resolvedVersion: String?
    ): String {
        val normalizedResolved = normalizeBluetoothVersion(resolvedVersion)
        if (normalizedResolved != null) return normalizedResolved

        val normalizedPersisted = normalizeBluetoothVersion(persistedVersion)
        if (normalizedPersisted != null) return normalizedPersisted

        return BLUETOOTH_VERSION_UNKNOWN
    }

    fun parseDeviceInformationCharacteristic(
        characteristicUuid: UUID,
        value: ByteArray?
    ): DeviceInformationSnapshot? {
        return when (characteristicUuid) {
            DEVICE_INFORMATION_MODEL_NUMBER_UUID -> {
                decodeDeviceInformationText(value)?.let { DeviceInformationSnapshot(modelNumber = it) }
            }

            DEVICE_INFORMATION_MANUFACTURER_NAME_UUID -> {
                decodeDeviceInformationText(value)?.let { DeviceInformationSnapshot(manufacturerName = it) }
            }

            DEVICE_INFORMATION_FIRMWARE_REVISION_UUID -> {
                decodeDeviceInformationText(value)?.let { DeviceInformationSnapshot(firmwareRevision = it) }
            }

            DEVICE_INFORMATION_HARDWARE_REVISION_UUID -> {
                decodeDeviceInformationText(value)?.let { DeviceInformationSnapshot(hardwareRevision = it) }
            }

            DEVICE_INFORMATION_SOFTWARE_REVISION_UUID -> {
                decodeDeviceInformationText(value)?.let { DeviceInformationSnapshot(softwareRevision = it) }
            }

            DEVICE_INFORMATION_SERIAL_NUMBER_UUID -> {
                decodeDeviceInformationText(value)?.let { DeviceInformationSnapshot(serialNumber = it) }
            }

            DEVICE_INFORMATION_PNP_ID_UUID -> {
                parsePnpId(value)?.let { DeviceInformationSnapshot(pnpId = it) }
            }

            else -> null
        }
    }

    fun parseAdvertisementSnapshot(scanResult: ScanResult): AdvertisementSnapshot {
        val record = scanResult.scanRecord
        val manufacturerDataHex = linkedMapOf<Int, String>()
        val manufacturerDataText = linkedMapOf<Int, String>()
        val serviceDataHex = linkedMapOf<String, String>()
        val serviceDataText = linkedMapOf<String, String>()

        record?.manufacturerSpecificData?.let { sparseArray ->
            for (index in 0 until sparseArray.size()) {
                val manufacturerId = sparseArray.keyAt(index)
                val payload = sparseArray.valueAt(index)
                payload.toHexString()?.let { hexPayload ->
                    manufacturerDataHex[manufacturerId] = hexPayload
                }
                decodeLikelyTextPayload(payload)?.let { manufacturerDataText[manufacturerId] = it }
            }
        }

        record?.serviceData?.forEach { (parcelUuid, payload) ->
            val uuid = parcelUuid.uuid.toString()
            payload.toHexString()?.let { hexPayload ->
                serviceDataHex[uuid] = hexPayload
            }
            decodeLikelyTextPayload(payload)?.let { serviceDataText[uuid] = it }
        }

        return AdvertisementSnapshot(
            localName = record?.deviceName?.trim()?.takeIf(String::isNotEmpty),
            manufacturerDataHex = manufacturerDataHex,
            manufacturerDataText = manufacturerDataText,
            serviceDataHex = serviceDataHex,
            serviceDataText = serviceDataText,
            serviceUuids = record?.serviceUuids
                ?.map { it.uuid.toString() }
                ?.sorted()
                .orEmpty(),
            rawBytesHex = record?.bytes?.toHexString()
        )
    }

    fun mergeDeviceInformationSnapshots(
        current: DeviceInformationSnapshot,
        update: DeviceInformationSnapshot
    ): DeviceInformationSnapshot {
        return DeviceInformationSnapshot(
            modelNumber = update.modelNumber ?: current.modelNumber,
            manufacturerName = update.manufacturerName ?: current.manufacturerName,
            firmwareRevision = update.firmwareRevision ?: current.firmwareRevision,
            hardwareRevision = update.hardwareRevision ?: current.hardwareRevision,
            softwareRevision = update.softwareRevision ?: current.softwareRevision,
            serialNumber = update.serialNumber ?: current.serialNumber,
            pnpId = update.pnpId ?: current.pnpId
        )
    }

    private fun explicitVersionCandidates(
        deviceName: String?,
        deviceInformation: DeviceInformationSnapshot,
        advertisementSnapshot: AdvertisementSnapshot
    ) = sequence {
        yield("dis:model-number" to deviceInformation.modelNumber)
        yield("dis:firmware-revision" to deviceInformation.firmwareRevision)
        yield("dis:hardware-revision" to deviceInformation.hardwareRevision)
        yield("dis:software-revision" to deviceInformation.softwareRevision)
        yield("dis:manufacturer-name" to deviceInformation.manufacturerName)
        yield("adv:local-name" to advertisementSnapshot.localName)
        for ((manufacturerId, payload) in advertisementSnapshot.manufacturerDataText.entries.sortedBy { it.key }) {
            yield("adv:manufacturer-data[${formatManufacturerId(manufacturerId)}]" to payload)
        }
        for ((uuid, payload) in advertisementSnapshot.serviceDataText.entries.sortedBy { it.key }) {
            yield("adv:service-data[$uuid]" to payload)
        }
        yield("device:name" to deviceName)
    }

    private fun resolveCatalogBluetoothVersion(
        deviceName: String?,
        deviceInformation: DeviceInformationSnapshot,
        advertisementSnapshot: AdvertisementSnapshot
    ): BluetoothVersionSnapshot? {
        resolveExactDeviceNameCatalog(deviceName, source = "catalog:device-name")?.let { return it }
        resolveExactDeviceNameCatalog(
            advertisementSnapshot.localName,
            source = "catalog:adv-local-name"
        )?.let { return it }

        val normalizedManufacturer = normalizeCatalogKey(deviceInformation.manufacturerName)
        val normalizedModel = normalizeCatalogKey(deviceInformation.modelNumber)
        if (!normalizedManufacturer.isNullOrBlank() && !normalizedModel.isNullOrBlank()) {
            manufacturerModelCatalog.firstOrNull { rule ->
                normalizedManufacturer.contains(rule.manufacturerKey) &&
                    normalizedModel.startsWith(rule.modelKey)
            }?.let { rule ->
                return BluetoothVersionSnapshot(
                    version = rule.version,
                    source = "catalog:manufacturer-model",
                    rawValue = "${deviceInformation.manufacturerName}/${deviceInformation.modelNumber}"
                )
            }
        }

        return null
    }

    private fun resolveExactDeviceNameCatalog(
        candidateName: String?,
        source: String
    ): BluetoothVersionSnapshot? {
        val normalizedName = normalizeCatalogKey(candidateName) ?: return null
        val version = exactDeviceNameCatalog[normalizedName] ?: return null
        return BluetoothVersionSnapshot(
            version = version,
            source = source,
            rawValue = candidateName
        )
    }

    private fun decodeDeviceInformationText(value: ByteArray?): String? {
        return value
            ?.toString(StandardCharsets.UTF_8)
            ?.replace("\u0000", "")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    private fun decodeLikelyTextPayload(value: ByteArray?): String? {
        val candidate = value
            ?.toString(StandardCharsets.UTF_8)
            ?.replace("\u0000", "")
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: return null
        val printableCount = candidate.count { it.code in 32..126 }
        val alphaNumericCount = candidate.count(Char::isLetterOrDigit)
        return candidate.takeIf {
            printableCount == candidate.length && alphaNumericCount >= 3
        }
    }

    private fun parsePnpId(value: ByteArray?): String? {
        val bytes = value ?: return null
        if (bytes.size < 7) return null
        val vendorIdSource = bytes[0].toInt() and 0xFF
        val vendorId = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
        val productId = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val productVersion = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
        return "vidSource=$vendorIdSource,vendor=0x%04X,product=0x%04X,version=0x%04X".format(
            Locale.US,
            vendorId,
            productId,
            productVersion
        )
    }

    private fun normalizeCapturedVersion(major: String, minor: String): String? {
        val normalizedMinor = minor.ifBlank { "0" }
        val normalizedVersion = "${major.toInt()}.${normalizedMinor.toInt()}"
        return normalizedVersion.takeIf { it in supportedVersions }
    }

    private fun readMetadataValue(device: BluetoothDevice, key: Int): String? {
        val metadataMethod = getMetadataMethod ?: return null
        if (metadataAccessBlocked) return null

        return try {
            val rawBytes = metadataMethod.invoke(device, key) as? ByteArray ?: return null
            rawBytes.toString(StandardCharsets.UTF_8)
                .replace("\u0000", "")
                .trim()
                .takeIf(String::isNotEmpty)
        } catch (securityException: SecurityException) {
            metadataAccessBlocked = true
            Log.w(
                TAG,
                "[BluetoothVersionUtils] readMetadataValue -> metadata access blocked for ${device.address}",
                securityException
            )
            null
        } catch (e: Exception) {
            val rootCause = generateSequence(e as Throwable?) { it.cause }
                .firstOrNull { it is SecurityException }
            if (rootCause is SecurityException) {
                metadataAccessBlocked = true
                Log.w(
                    TAG,
                    "[BluetoothVersionUtils] readMetadataValue -> metadata access blocked for ${device.address}",
                    rootCause
                )
            } else {
                Log.d(
                    TAG,
                    "[BluetoothVersionUtils] readMetadataValue -> key=$key unavailable for ${device.address}: ${e::class.java.simpleName}"
                )
            }
            null
        }
    }

    private fun normalizeCatalogKey(rawValue: String?): String? {
        return rawValue
            ?.lowercase(Locale.US)
            ?.replace(Regex("[^a-z0-9]+"), "")
            ?.takeIf(String::isNotEmpty)
    }

    private fun ByteArray?.toHexString(): String? {
        return this
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "") { byte ->
                "%02X".format(Locale.US, byte.toInt() and 0xFF)
            }
    }

    private fun formatManufacturerId(id: Int): String {
        return "0x%04X".format(Locale.US, id)
    }

    val DEVICE_INFORMATION_SERVICE_UUID: UUID =
        UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFORMATION_MODEL_NUMBER_UUID: UUID =
        UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFORMATION_SERIAL_NUMBER_UUID: UUID =
        UUID.fromString("00002a25-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFORMATION_FIRMWARE_REVISION_UUID: UUID =
        UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFORMATION_HARDWARE_REVISION_UUID: UUID =
        UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFORMATION_SOFTWARE_REVISION_UUID: UUID =
        UUID.fromString("00002a28-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFORMATION_MANUFACTURER_NAME_UUID: UUID =
        UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFORMATION_PNP_ID_UUID: UUID =
        UUID.fromString("00002a50-0000-1000-8000-00805f9b34fb")
    val DEVICE_INFORMATION_CHARACTERISTIC_UUIDS: List<UUID> = listOf(
        DEVICE_INFORMATION_MODEL_NUMBER_UUID,
        DEVICE_INFORMATION_FIRMWARE_REVISION_UUID,
        DEVICE_INFORMATION_HARDWARE_REVISION_UUID,
        DEVICE_INFORMATION_SOFTWARE_REVISION_UUID,
        DEVICE_INFORMATION_MANUFACTURER_NAME_UUID,
        DEVICE_INFORMATION_SERIAL_NUMBER_UUID,
        DEVICE_INFORMATION_PNP_ID_UUID
    )
}
