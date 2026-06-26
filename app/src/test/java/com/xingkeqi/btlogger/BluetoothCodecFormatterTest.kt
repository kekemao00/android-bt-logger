package com.xingkeqi.btlogger

import android.bluetooth.BluetoothCodecConfig
import com.xingkeqi.btlogger.data.CODEC_LIST_UNAVAILABLE
import com.xingkeqi.btlogger.data.CODEC_UNKNOWN
import com.xingkeqi.btlogger.service.BluetoothCodecFormatter
import com.xingkeqi.btlogger.service.CodecSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothCodecFormatterTest {

    @Test
    fun formatCodecType_returnsReadableNames() {
        assertEquals("AAC", BluetoothCodecFormatter.formatCodecType(BluetoothCodecConfig.SOURCE_CODEC_TYPE_AAC))
        assertEquals("LDAC", BluetoothCodecFormatter.formatCodecType(BluetoothCodecConfig.SOURCE_CODEC_TYPE_LDAC))
        assertEquals(CODEC_UNKNOWN, BluetoothCodecFormatter.formatCodecType(BluetoothCodecConfig.SOURCE_CODEC_TYPE_INVALID))
        assertEquals("Unknown(99)", BluetoothCodecFormatter.formatCodecType(99))
    }

    @Test
    fun formatCodecList_returnsUnavailableWhenEmpty() {
        assertEquals(CODEC_LIST_UNAVAILABLE, BluetoothCodecFormatter.formatCodecList(emptyList()))
        assertEquals(CODEC_UNKNOWN, BluetoothCodecFormatter.formatActiveCodec(null))
    }

    @Test
    fun normalizeSnapshot_keepsActiveCodecWhenCapabilityListsUnavailable() {
        val normalized = BluetoothCodecFormatter.normalizeSnapshot(
            CodecSnapshot(
                phoneSupportedCodecs = CODEC_LIST_UNAVAILABLE,
                negotiableCodecs = CODEC_LIST_UNAVAILABLE,
                activeCodec = " LDAC "
            )
        )

        assertEquals(CODEC_LIST_UNAVAILABLE, normalized.phoneSupportedCodecs)
        assertEquals(CODEC_LIST_UNAVAILABLE, normalized.negotiableCodecs)
        assertEquals("LDAC", normalized.activeCodec)
    }

    @Test
    fun normalizeSnapshot_sortsAndDeduplicatesCodecLists() {
        val normalized = BluetoothCodecFormatter.normalizeSnapshot(
            CodecSnapshot(
                phoneSupportedCodecs = "LDAC, AAC, LDAC, SBC",
                negotiableCodecs = "SBC, AAC",
                activeCodec = " AAC ",
                updatedAt = 123L
            )
        )

        assertEquals("AAC, LDAC, SBC", normalized.phoneSupportedCodecs)
        assertEquals("AAC, SBC", normalized.negotiableCodecs)
        assertEquals("AAC", normalized.activeCodec)
        assertEquals(123L, normalized.updatedAt)
    }

    @Test
    fun hasCodecSnapshotChanged_ignoresTimestampAndCodecOrder() {
        val previous = CodecSnapshot(
            phoneSupportedCodecs = "AAC, LDAC, SBC",
            negotiableCodecs = "AAC, SBC",
            activeCodec = "AAC",
            updatedAt = 1L
        )
        val current = CodecSnapshot(
            phoneSupportedCodecs = "SBC, LDAC, AAC",
            negotiableCodecs = "SBC, AAC",
            activeCodec = "AAC",
            updatedAt = 2L
        )

        assertFalse(BluetoothCodecFormatter.hasCodecSnapshotChanged(previous, current))
    }

    @Test
    fun shouldPersistCodecHistory_onlyWhenKnownActiveCodecChanges() {
        val unknownPrevious = CodecSnapshot(activeCodec = CODEC_UNKNOWN)
        val previous = CodecSnapshot(activeCodec = "AAC")
        val current = CodecSnapshot(activeCodec = "LDAC")
        val unchanged = CodecSnapshot(activeCodec = "AAC")

        assertFalse(BluetoothCodecFormatter.shouldPersistCodecHistory(unknownPrevious, current))
        assertFalse(BluetoothCodecFormatter.shouldPersistCodecHistory(previous, unchanged))
        assertTrue(BluetoothCodecFormatter.shouldPersistCodecHistory(previous, current))
    }
}
