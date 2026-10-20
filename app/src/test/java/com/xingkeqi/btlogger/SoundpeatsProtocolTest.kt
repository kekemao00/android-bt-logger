package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.service.earbud.BatteryReply
import com.xingkeqi.btlogger.service.earbud.EarbudLevels
import com.xingkeqi.btlogger.service.earbud.EarbudPart
import com.xingkeqi.btlogger.service.earbud.SoundpeatsFrame
import com.xingkeqi.btlogger.service.earbud.SoundpeatsFrameParser
import com.xingkeqi.btlogger.service.earbud.SoundpeatsProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundpeatsProtocolTest {

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    /** 电量回复：`FF 04 00 02 00 0A 83 xx [状态][百分比]` */
    private fun reply(commandLow: Int, level: Int, status: Int = 0x00) =
        bytes(0xFF, 0x04, 0x00, 0x02, 0x00, 0x0A, 0x83, commandLow, status, level)

    private fun parseAll(vararg chunks: ByteArray): List<BatteryReply> {
        val parser = SoundpeatsFrameParser()
        return chunks.flatMap { parser.feed(it) }.mapNotNull(SoundpeatsProtocol::parseBatteryReply)
    }

    @Test
    fun request_matchesSoundpeatsAppFrames() {
        assertEquals(bytes(0xFF, 0x04, 0x00, 0x00, 0x00, 0x0A, 0x03, 0x06).toList(), SoundpeatsProtocol.request(SoundpeatsProtocol.CMD_LEFT_BATTERY).toList())
        assertEquals(bytes(0xFF, 0x04, 0x00, 0x00, 0x00, 0x0A, 0x03, 0x07).toList(), SoundpeatsProtocol.request(SoundpeatsProtocol.CMD_RIGHT_BATTERY).toList())
        assertEquals(bytes(0xFF, 0x04, 0x00, 0x00, 0x00, 0x0A, 0x03, 0x23).toList(), SoundpeatsProtocol.request(SoundpeatsProtocol.CMD_CASE_BATTERY).toList())
        assertEquals(bytes(0xFF, 0x04, 0x00, 0x00, 0x00, 0x0A, 0x03, 0x09).toList(), SoundpeatsProtocol.request(SoundpeatsProtocol.CMD_FIRMWARE_VERSION).toList())
    }

    @Test
    fun parser_readsLeftRightAndCaseFromOneChunk() {
        val replies = parseAll(reply(0x06, 85) + reply(0x07, 90) + reply(0x23, 60))

        assertEquals(
            listOf(
                BatteryReply(EarbudPart.LEFT, 85),
                BatteryReply(EarbudPart.RIGHT, 90),
                BatteryReply(EarbudPart.CASE, 60)
            ),
            replies
        )
    }

    @Test
    fun parser_waitsForFramesSplitAcrossReads() {
        val frame = reply(0x06, 42)
        val parser = SoundpeatsFrameParser()

        assertTrue(parser.feed(frame.copyOfRange(0, 3)).isEmpty())
        assertTrue(parser.feed(frame.copyOfRange(3, 9)).isEmpty())
        val frames = parser.feed(frame.copyOfRange(9, frame.size))

        assertEquals(listOf(SoundpeatsFrame(0x8306, listOf(0x00.toByte(), 42.toByte()))), frames)
    }

    @Test
    fun parser_skipsGarbageBeforeHeader() {
        assertEquals(listOf(BatteryReply(EarbudPart.RIGHT, 70)), parseAll(bytes(0x00, 0x12, 0x34) + reply(0x07, 70)))
    }

    @Test
    fun parser_resyncsOnNextHeaderWhenLengthFieldIsTooLarge() {
        // 长度字段写成 16，实际只发了 2 字节；等不齐时按后面紧跟的充电仓回复重新对齐，后者不能被吞掉
        val broken = bytes(0xFF, 0x04, 0x00, 0x10, 0x00, 0x0A, 0x83, 0x07, 0x00, 0x5A)

        assertEquals(
            listOf(BatteryReply(EarbudPart.RIGHT, 90), BatteryReply(EarbudPart.CASE, 60)),
            parseAll(broken + reply(0x23, 60))
        )
    }

    @Test
    fun parser_dropsOversizedLeftovers() {
        val parser = SoundpeatsFrameParser(maxBufferBytes = 16)
        // 长度 0xF0 的帧永远等不齐，超过上限后丢弃，之后的正常回复照常解析
        assertTrue(parser.feed(bytes(0xFF, 0x04, 0x00, 0xF0, 0x00, 0x0A, 0x83, 0x06) + ByteArray(12)).isEmpty())

        val frames = parser.feed(reply(0x06, 30))

        assertEquals(listOf(BatteryReply(EarbudPart.LEFT, 30)), frames.mapNotNull(SoundpeatsProtocol::parseBatteryReply))
    }

    @Test
    fun parseBatteryReply_treatsZeroAndInvalidAsOffline() {
        assertEquals(listOf(BatteryReply(EarbudPart.LEFT, null)), parseAll(reply(0x06, 0)))
        assertEquals(listOf(BatteryReply(EarbudPart.LEFT, null)), parseAll(reply(0x06, 0xFF)))
        assertEquals(listOf(BatteryReply(EarbudPart.RIGHT, 100)), parseAll(reply(0x07, 100)))
        assertEquals(listOf(BatteryReply(EarbudPart.RIGHT, 1)), parseAll(reply(0x07, 1)))
    }

    @Test
    fun parseBatteryReply_requiresTwoBytePayloadForCase() {
        // 不支持充电仓电量的型号会回一字节，App 视为无效
        val shortCase = bytes(0xFF, 0x04, 0x00, 0x01, 0x00, 0x0A, 0x83, 0x23, 0x01)
        val shortLeft = bytes(0xFF, 0x04, 0x00, 0x01, 0x00, 0x0A, 0x83, 0x06, 0x01)

        assertEquals(listOf(BatteryReply(EarbudPart.CASE, null), BatteryReply(EarbudPart.LEFT, null)), parseAll(shortCase + shortLeft))
    }

    @Test
    fun parseBatteryReply_ignoresOtherCommands() {
        val firmware = bytes(0xFF, 0x04, 0x00, 0x02, 0x00, 0x0A, 0x83, 0x09, 0x01, 0x02)
        val request = SoundpeatsProtocol.request(SoundpeatsProtocol.CMD_LEFT_BATTERY)

        assertEquals(emptyList<BatteryReply>(), parseAll(firmware + request))
    }

    @Test
    fun isSupported_onlySoundpeatsWithSerialPort() {
        val uuids = "00001101-0000-1000-8000-00805f9b34fb, 0000111e-0000-1000-8000-00805f9b34fb"

        assertTrue(SoundpeatsProtocol.isSupported("SOUNDPEATS Aura Nebula", uuids))
        assertTrue(SoundpeatsProtocol.isSupported("Soundpeats Air4 Pro", ""))
        assertFalse(SoundpeatsProtocol.isSupported("SOUNDPEATS Capsule3 Pro", uuids))
        assertFalse(SoundpeatsProtocol.isSupported("SOUNDPEATS QCY T13", uuids))
        assertFalse(SoundpeatsProtocol.isSupported("Galaxy Buds2", uuids))
        assertFalse(SoundpeatsProtocol.isSupported("SOUNDPEATS Aura Nebula", "0000111e-0000-1000-8000-00805f9b34fb"))
    }

    @Test
    fun earbudLevels_mergesRepliesPerPart() {
        val levels = EarbudLevels.UNKNOWN
            .with(BatteryReply(EarbudPart.LEFT, 80))
            .with(BatteryReply(EarbudPart.RIGHT, 90))
            .with(BatteryReply(EarbudPart.CASE, 50))
            .with(BatteryReply(EarbudPart.LEFT, null))

        assertEquals(EarbudLevels(left = null, right = 90, case = 50), levels)
        assertFalse(levels.isEmpty)
        assertTrue(EarbudLevels.UNKNOWN.isEmpty)
        assertNull(EarbudLevels.UNKNOWN.left)
    }
}
