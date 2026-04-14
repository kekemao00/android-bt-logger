package com.xingkeqi.btlogger

import android.bluetooth.BluetoothCodecConfig
import com.xingkeqi.btlogger.data.CODEC_LIST_UNAVAILABLE
import com.xingkeqi.btlogger.data.CODEC_UNKNOWN
import com.xingkeqi.btlogger.service.BluetoothCodecFormatter
import org.junit.Assert.assertEquals
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
}
