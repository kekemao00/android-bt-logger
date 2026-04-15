package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.utils.calculateVolumePercent
import com.xingkeqi.btlogger.utils.resolveVolumeIndex
import com.xingkeqi.btlogger.utils.MediaVolumeSnapshot
import com.xingkeqi.btlogger.utils.shouldApplyFixedVolumeForBluetooth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaVolumeUtilsTest {

    @Test
    fun calculateVolumePercent_handlesBoundsAndRounding() {
        assertEquals(0, calculateVolumePercent(currentLevel = 0, maxLevel = 0))
        assertEquals(0, calculateVolumePercent(currentLevel = -1, maxLevel = 15))
        assertEquals(47, calculateVolumePercent(currentLevel = 7, maxLevel = 15))
        assertEquals(100, calculateVolumePercent(currentLevel = 30, maxLevel = 15))
    }

    @Test
    fun resolveVolumeIndex_handlesBoundsAndRounding() {
        assertEquals(0, resolveVolumeIndex(percent = 50, maxLevel = 0))
        assertEquals(0, resolveVolumeIndex(percent = -1, maxLevel = 15))
        assertEquals(7, resolveVolumeIndex(percent = 47, maxLevel = 15))
        assertEquals(15, resolveVolumeIndex(percent = 120, maxLevel = 15))
    }

    @Test
    fun shouldApplyFixedVolumeForBluetooth_waitsForPlaybackUnlessNearTimeout() {
        val bluetoothIdle = MediaVolumeSnapshot(
            hasBluetoothOutput = true,
            isMusicActive = false
        )
        val bluetoothPlaying = bluetoothIdle.copy(isMusicActive = true)
        val speakerPlaying = MediaVolumeSnapshot(
            hasBluetoothOutput = false,
            isMusicActive = true
        )

        assertFalse(
            shouldApplyFixedVolumeForBluetooth(
                snapshot = speakerPlaying,
                attempt = 0,
                maxAttempts = 8
            )
        )
        assertFalse(
            shouldApplyFixedVolumeForBluetooth(
                snapshot = bluetoothIdle,
                attempt = 0,
                maxAttempts = 8
            )
        )
        assertTrue(
            shouldApplyFixedVolumeForBluetooth(
                snapshot = bluetoothPlaying,
                attempt = 0,
                maxAttempts = 8
            )
        )
        assertTrue(
            shouldApplyFixedVolumeForBluetooth(
                snapshot = bluetoothIdle,
                attempt = 6,
                maxAttempts = 8
            )
        )
    }
}
