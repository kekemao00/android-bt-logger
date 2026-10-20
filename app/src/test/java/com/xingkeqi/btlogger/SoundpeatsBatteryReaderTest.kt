package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.service.earbud.EarbudLevels
import com.xingkeqi.btlogger.service.earbud.EarbudLink
import com.xingkeqi.btlogger.service.earbud.EarbudReaderFailure
import com.xingkeqi.btlogger.service.earbud.EarbudReaderStatus
import com.xingkeqi.btlogger.service.earbud.EarbudReaderStatusStore
import com.xingkeqi.btlogger.service.earbud.EarbudReaderTiming
import com.xingkeqi.btlogger.service.earbud.SoundpeatsBatteryReader
import com.xingkeqi.btlogger.service.earbud.SoundpeatsProtocol
import com.xingkeqi.btlogger.service.earbud.earbudRetryDelayMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SoundpeatsBatteryReaderTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val delivered = CopyOnWriteArrayList<EarbudLevels>()

    /** 毫秒级节奏；重试间隔放长，便于停在“等待重试”状态上检查 */
    private val timing = EarbudReaderTiming(
        pollIntervalMs = 40,
        settleDelayMs = 1,
        commandGapMs = 1,
        replyGraceMs = 30,
        maxSilentCycles = 3,
        retryDelayMillis = { _, _ -> 60_000 }
    )

    private fun reader(timing: EarbudReaderTiming = this.timing) =
        SoundpeatsBatteryReader(scope = scope, timing = timing) { _, levels, _ -> delivered += levels }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun reader_sendsHandshakeThenDeliversOnlyChanges() = runBlocking {
        val levels = mutableMapOf(LEFT to 85, RIGHT to 90, CASE to 60)
        val link = FakeLink { command -> levels[command]?.let { reply(command, it) } }
        val reader = reader()

        reader.start(ADDRESS_READ) { link }
        awaitUntil { delivered.size == 1 }
        // 再等几轮：电量没变就不再回调
        awaitUntil { link.commands.count { it == LEFT } >= 4 }

        assertEquals(listOf(SoundpeatsProtocol.CMD_FIRMWARE_VERSION, LEFT, RIGHT, CASE), link.commands.take(4))
        assertEquals(listOf(EarbudLevels(left = 85, right = 90, case = 60)), delivered.toList())

        levels[LEFT] = 80
        awaitUntil { delivered.size == 2 }
        assertEquals(EarbudLevels(left = 80, right = 90, case = 60), delivered.last())
        awaitUntil { (EarbudReaderStatusStore.statuses.value[ADDRESS_READ] as? EarbudReaderStatus.Reading)?.levels?.left == 80 }
        reader.stop(ADDRESS_READ)
    }

    @Test
    fun reader_reportsNoResponseAndClosesLink() = runBlocking {
        val link = FakeLink { null }
        val reader = reader()

        reader.start(ADDRESS_SILENT) { link }

        awaitUntil { failureOf(ADDRESS_SILENT) == EarbudReaderFailure.NO_RESPONSE }
        assertTrue(link.closed)
        assertTrue(delivered.isEmpty())
        // 三轮都发了查询，之后断开等待重试
        assertEquals(3, link.commands.count { it == LEFT })
        reader.stop(ADDRESS_SILENT)
    }

    @Test
    fun reader_reportsConnectFailureAndBacksOff() = runBlocking {
        val attempts = AtomicInteger()
        val reader = reader()

        reader.start(ADDRESS_UNREACHABLE) {
            attempts.incrementAndGet()
            null
        }

        awaitUntil { failureOf(ADDRESS_UNREACHABLE) == EarbudReaderFailure.CONNECT_FAILED }
        delay(100)
        assertEquals(1, attempts.get())
        assertTrue(reader.isRunning(ADDRESS_UNREACHABLE))
        reader.stop(ADDRESS_UNREACHABLE)
    }

    @Test
    fun reader_reconnectsAfterRemoteCloses() = runBlocking {
        val links = CopyOnWriteArrayList<FakeLink>()
        val failures = CopyOnWriteArrayList<EarbudReaderFailure>()
        val reader = reader(timing.copy(retryDelayMillis = { failure, _ -> failures += failure; 20 }))

        reader.start(ADDRESS_LOST) {
            FakeLink { command -> reply(command, 50) }.also { links += it }
        }
        awaitUntil { delivered.isNotEmpty() }
        links.first().closeFromRemote()

        awaitUntil { links.size >= 2 }
        assertEquals(EarbudReaderFailure.CONNECTION_LOST, failures.first())
        assertTrue(links.first().closed)
        reader.stop(ADDRESS_LOST)
    }

    @Test
    fun stop_closesLinkClearsStatusAndStopsDelivering() = runBlocking {
        val link = FakeLink { command -> reply(command, 70) }
        val reader = reader()

        reader.start(ADDRESS_STOP) { link }
        awaitUntil { delivered.size == 1 }
        reader.stop(ADDRESS_STOP)

        awaitUntil { link.closed }
        assertFalse(reader.isRunning(ADDRESS_STOP))
        assertNull(EarbudReaderStatusStore.statuses.value[ADDRESS_STOP])
        val sent = link.commands.size
        delay(150)
        assertEquals(sent, link.commands.size)
        assertEquals(1, delivered.size)
    }

    @Test
    fun start_isIdempotentWhileRunning() = runBlocking {
        val attempts = AtomicInteger()
        val reader = reader()
        val open: suspend () -> EarbudLink? = { attempts.incrementAndGet(); null }

        reader.start(ADDRESS_TWICE, open)
        reader.start(ADDRESS_TWICE, open)

        awaitUntil { failureOf(ADDRESS_TWICE) == EarbudReaderFailure.CONNECT_FAILED }
        assertEquals(1, attempts.get())
        reader.stop(ADDRESS_TWICE)
    }

    @Test
    fun retryDelay_backsOffPerFailureAndCapsAtFiveMinutes() {
        assertEquals(15_000L, earbudRetryDelayMillis(EarbudReaderFailure.CONNECT_FAILED, 1))
        assertEquals(30_000L, earbudRetryDelayMillis(EarbudReaderFailure.CONNECT_FAILED, 2))
        assertEquals(5_000L, earbudRetryDelayMillis(EarbudReaderFailure.CONNECTION_LOST, 1))
        assertEquals(60_000L, earbudRetryDelayMillis(EarbudReaderFailure.NO_RESPONSE, 1))
        assertEquals(300_000L, earbudRetryDelayMillis(EarbudReaderFailure.CONNECT_FAILED, 6))
        assertEquals(300_000L, earbudRetryDelayMillis(EarbudReaderFailure.NO_RESPONSE, 100))
    }

    private fun failureOf(address: String): EarbudReaderFailure? =
        (EarbudReaderStatusStore.statuses.value[address] as? EarbudReaderStatus.Retrying)?.failure

    private suspend fun awaitUntil(condition: () -> Boolean) {
        withTimeout(5_000) {
            while (!condition()) delay(5)
        }
    }

    /** 电量回复：`FF 04 00 02 00 0A 83 xx [状态][百分比]` */
    private fun reply(command: Int, level: Int): ByteArray? {
        if (command == SoundpeatsProtocol.CMD_FIRMWARE_VERSION) return null
        val opcode = command or 0x8000
        return byteArrayOf(0xFF.toByte(), 0x04, 0x00, 0x02, 0x00, 0x0A, (opcode shr 8).toByte(), opcode.toByte(), 0x00, level.toByte())
    }

    /**
     * 模拟耳机：每写入一条完整请求就按 [respond] 回一帧；[closed] 后 read 抛出，与 BluetoothSocket 一致。
     */
    private class FakeLink(private val respond: (command: Int) -> ByteArray?) : EarbudLink {
        val commands = CopyOnWriteArrayList<Int>()

        @Volatile
        var closed = false
        private val pending = LinkedBlockingQueue<ByteArray>()

        override val input: InputStream = object : InputStream() {
            override fun read(): Int = throw UnsupportedOperationException()

            override fun read(b: ByteArray, off: Int, len: Int): Int {
                while (true) {
                    if (closed) throw IOException("socket closed")
                    val chunk = pending.poll(5, TimeUnit.MILLISECONDS) ?: continue
                    if (chunk.isEmpty()) return -1
                    chunk.copyInto(b, off)
                    return chunk.size
                }
            }
        }

        override val output: OutputStream = object : OutputStream() {
            private val frame = ByteArrayOutputStream()

            override fun write(b: Int) {
                if (closed) throw IOException("socket closed")
                frame.write(b)
            }

            override fun flush() {
                val bytes = frame.toByteArray()
                frame.reset()
                val command = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
                commands += command
                respond(command)?.let(pending::put)
            }
        }

        /** 耳机端断开：read 返回 -1 */
        fun closeFromRemote() {
            pending.put(ByteArray(0))
        }

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val LEFT = SoundpeatsProtocol.CMD_LEFT_BATTERY
        const val RIGHT = SoundpeatsProtocol.CMD_RIGHT_BATTERY
        const val CASE = SoundpeatsProtocol.CMD_CASE_BATTERY
        const val ADDRESS_READ = "00:00:00:00:00:01"
        const val ADDRESS_SILENT = "00:00:00:00:00:02"
        const val ADDRESS_UNREACHABLE = "00:00:00:00:00:03"
        const val ADDRESS_LOST = "00:00:00:00:00:04"
        const val ADDRESS_STOP = "00:00:00:00:00:05"
        const val ADDRESS_TWICE = "00:00:00:00:00:06"
        const val ADDRESS_RETRACT = "00:00:00:00:00:07"
    }

    @Test
    fun reader_retractsLevelsWhenLinkEnds() = runBlocking {
        val links = CopyOnWriteArrayList<FakeLink>()
        val reader = reader()

        reader.start(ADDRESS_RETRACT) {
            FakeLink { command -> reply(command, 40) }.also { links += it }
        }
        awaitUntil { delivered.size == 1 }
        links.single().closeFromRemote()

        // 通道断开后不再知道电量：撤回为未知，等待重连期间的其他记录不会带上旧值
        awaitUntil { failureOf(ADDRESS_RETRACT) == EarbudReaderFailure.CONNECTION_LOST }
        assertEquals(listOf(EarbudLevels(left = 40, right = 40, case = 40), EarbudLevels.UNKNOWN), delivered.toList())
        reader.stop(ADDRESS_RETRACT)
    }

    @Test
    fun start_fromTwoThreadsOpensOneSession() = runBlocking {
        val reader = reader()
        repeat(200) { index ->
            val address = "AA:00:00:00:%02X:%02X".format(index / 256, index % 256)
            val attempts = AtomicInteger()
            val open: suspend () -> EarbudLink? = { attempts.incrementAndGet(); null }
            val barrier = CyclicBarrier(2)
            val threads = List(2) { Thread { barrier.await(); reader.start(address, open) } }
            threads.forEach(Thread::start)
            threads.forEach(Thread::join)

            awaitUntil { failureOf(address) == EarbudReaderFailure.CONNECT_FAILED }
            reader.stop(address)
            delay(5)
            assertEquals("duplicate session for $address", 1, attempts.get())
            assertFalse(reader.isRunning(address))
        }
    }
}
