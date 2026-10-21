package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.domain.keepalive.KeepAliveBanner
import com.xingkeqi.btlogger.domain.keepalive.RomVendor
import com.xingkeqi.btlogger.domain.keepalive.SERVICE_GAP_MIN_MS
import com.xingkeqi.btlogger.domain.keepalive.ServiceGap
import com.xingkeqi.btlogger.domain.keepalive.ServiceHeartbeat
import com.xingkeqi.btlogger.domain.keepalive.appendServiceGap
import com.xingkeqi.btlogger.domain.keepalive.decodeServiceGaps
import com.xingkeqi.btlogger.domain.keepalive.detectServiceGap
import com.xingkeqi.btlogger.domain.keepalive.encodeServiceGaps
import com.xingkeqi.btlogger.domain.keepalive.gapDurationParts
import com.xingkeqi.btlogger.domain.keepalive.keepAliveBannerOf
import com.xingkeqi.btlogger.domain.keepalive.romVendorOf
import com.xingkeqi.btlogger.domain.keepalive.staleDisconnectAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeepAliveTest {

    private val minute = 60_000L
    private val base = 1_760_000_000_000L

    @Test
    fun detectServiceGap_reportsKillWithinSameBoot() {
        val previous = ServiceHeartbeat(aliveAt = base, bootCount = 7)

        assertEquals(ServiceGap(base, base + 37 * minute), detectServiceGap(previous, base + 37 * minute, bootCount = 7))
    }

    @Test
    fun detectServiceGap_ignoresFirstRunCleanStopShortRestartAndReboot() {
        assertNull(detectServiceGap(null, base, bootCount = 7))
        assertNull(detectServiceGap(ServiceHeartbeat(base, 7, cleanStop = true), base + 60 * minute, bootCount = 7))
        // START_STICKY 几秒内拉起不算中断
        assertNull(detectServiceGap(ServiceHeartbeat(base, 7), base + SERVICE_GAP_MIN_MS - 1, bootCount = 7))
        // 关机重启不是被系统清理
        assertNull(detectServiceGap(ServiceHeartbeat(base, 7), base + 60 * minute, bootCount = 8))
    }

    @Test
    fun detectServiceGap_treatsUnknownBootCountAsSameBoot() {
        val gap = detectServiceGap(ServiceHeartbeat(base, -1), base + 10 * minute, bootCount = 8)

        assertEquals(ServiceGap(base, base + 10 * minute), gap)
    }

    @Test
    fun appendServiceGap_keepsNewestFirstWithinLimit() {
        val history = listOf(ServiceGap(base + 2, base + 3), ServiceGap(base, base + 1))

        val updated = appendServiceGap(history, ServiceGap(base + 4, base + 5), limit = 2)

        assertEquals(listOf(ServiceGap(base + 4, base + 5), ServiceGap(base + 2, base + 3)), updated)
        // 同一段不会记两次
        assertEquals(2, appendServiceGap(history, ServiceGap(base, base + 1)).size)
    }

    @Test
    fun serviceGaps_roundTripAndDropMalformedEntries() {
        val gaps = listOf(ServiceGap(base + 10, base + 20), ServiceGap(base, base + 5))

        assertEquals(gaps, decodeServiceGaps(encodeServiceGaps(gaps)))
        assertEquals(listOf(ServiceGap(1, 2)), decodeServiceGaps("1-2,abc,5-3,7"))
        assertEquals(emptyList<ServiceGap>(), decodeServiceGaps(null))
    }

    @Test
    fun staleDisconnectAt_usesLastHeartbeatWithinRecordAndNow() {
        val now = base + 120 * minute

        assertEquals(base + 30 * minute, staleDisconnectAt(lastRecordAt = base, lastAliveAt = base + 30 * minute, now = now))
        // 心跳早于该设备最后一条记录时，不早于最后一条记录
        assertEquals(base + 50 * minute, staleDisconnectAt(lastRecordAt = base + 50 * minute, lastAliveAt = base, now = now))
        // 不知道最后心跳时按当前时间
        assertEquals(now, staleDisconnectAt(lastRecordAt = base, lastAliveAt = null, now = now))
        // 时钟被往回调过也不会晚于当前时间
        assertEquals(now, staleDisconnectAt(lastRecordAt = now + minute, lastAliveAt = now + 2 * minute, now = now))
    }

    @Test
    fun romVendorOf_groupsSubBrandsBySystem() {
        assertEquals(RomVendor.XIAOMI, romVendorOf("Xiaomi", "Redmi"))
        assertEquals(RomVendor.XIAOMI, romVendorOf("Xiaomi", "POCO"))
        assertEquals(RomVendor.VIVO, romVendorOf("vivo", "iQOO"))
        assertEquals(RomVendor.OPPO, romVendorOf("OnePlus", "OnePlus"))
        assertEquals(RomVendor.OPPO, romVendorOf("realme", "realme"))
        assertEquals(RomVendor.HONOR, romVendorOf("HONOR", "HONOR"))
        assertEquals(RomVendor.HUAWEI, romVendorOf("HUAWEI", "HUAWEI"))
        assertEquals(RomVendor.SAMSUNG, romVendorOf("samsung", "samsung"))
        assertEquals(RomVendor.OTHER, romVendorOf("Google", "google"))
        assertEquals(RomVendor.OTHER, romVendorOf(null, null))
    }

    @Test
    fun keepAliveBannerOf_prefersUnseenInterruptions() {
        val older = ServiceGap(base, base + 10 * minute)
        val newer = ServiceGap(base + 60 * minute, base + 70 * minute)

        assertEquals(
            KeepAliveBanner.Interrupted(latest = newer, unseenCount = 2),
            keepAliveBannerOf(listOf(newer, older), acknowledgedAt = 0L, ignoringBatteryOptimizations = true, suggestionDismissed = true)
        )
        // 看过的中断不再提示
        assertEquals(
            KeepAliveBanner.Interrupted(latest = newer, unseenCount = 1),
            keepAliveBannerOf(listOf(newer, older), acknowledgedAt = older.endAt, ignoringBatteryOptimizations = true, suggestionDismissed = true)
        )
        assertNull(
            keepAliveBannerOf(listOf(newer, older), acknowledgedAt = newer.endAt, ignoringBatteryOptimizations = true, suggestionDismissed = false)
        )
    }

    @Test
    fun keepAliveBannerOf_suggestsSetupUntilDismissed() {
        assertEquals(
            KeepAliveBanner.Suggest,
            keepAliveBannerOf(emptyList(), acknowledgedAt = 0L, ignoringBatteryOptimizations = false, suggestionDismissed = false)
        )
        assertNull(keepAliveBannerOf(emptyList(), acknowledgedAt = 0L, ignoringBatteryOptimizations = false, suggestionDismissed = true))
    }

    @Test
    fun gapDurationParts_roundsUpToWholeMinutes() {
        assertEquals(0 to 1, gapDurationParts(0L))
        assertEquals(0 to 37, gapDurationParts(36 * minute + 1))
        assertEquals(2 to 5, gapDurationParts(125 * minute))
    }
}
