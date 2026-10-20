package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.data.DeviceInfo
import com.xingkeqi.btlogger.service.earbud.EarbudReaderStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DualEarUiStateTest {

    private val soundpeats = DeviceInfo(mac = "98:80:BB:40:C3:DD", name = "SOUNDPEATS Aura Nebula")

    @Test
    fun dualEarUiStateOf_showsSwitchOnlyForSupportedOrEnabledDevices() {
        assertEquals(DualEarUiState(), dualEarUiStateOf(null, emptySet(), emptyMap()))
        assertEquals(DualEarUiState(supported = true), dualEarUiStateOf(soundpeats, emptySet(), emptyMap()))
        assertEquals(DualEarUiState(), dualEarUiStateOf(DeviceInfo(mac = "AA", name = "Galaxy Buds2"), emptySet(), emptyMap()))
        // 已开启的设备即使不再识别为 SOUNDPEATS 也保留开关，便于关闭
        assertEquals(
            DualEarUiState(supported = true, enabled = true),
            dualEarUiStateOf(DeviceInfo(mac = "AA", name = "Renamed"), setOf("AA"), emptyMap())
        )
    }

    @Test
    fun dualEarUiStateOf_readsStatusOfThisDevice() {
        val statuses = mapOf(soundpeats.mac to EarbudReaderStatus.Connecting, "BB" to EarbudReaderStatus.Connecting)

        assertEquals(
            DualEarUiState(supported = true, enabled = true, status = EarbudReaderStatus.Connecting),
            dualEarUiStateOf(soundpeats, setOf(soundpeats.mac), statuses)
        )
    }
}
