package com.xingkeqi.btlogger.service.earbud

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 一条已建立的读取通道
 */
interface EarbudLink : Closeable {
    val input: InputStream
    val output: OutputStream
}

/**
 * 轮询节奏与重试间隔；默认值用于真机，单测传入毫秒级的值。
 *
 * @param pollIntervalMs 每轮查询的间隔：变化在 10 秒内记下，请求量约为 SOUNDPEATS App（3 秒）的三分之一
 * @param maxSilentCycles 连续这么多轮没有任何电量回复就断开重连（默认约 30 秒）
 */
data class EarbudReaderTiming(
    val pollIntervalMs: Long = 10_000L,
    val settleDelayMs: Long = 300L,
    val commandGapMs: Long = 300L,
    val replyGraceMs: Long = 600L,
    val maxSilentCycles: Int = 3,
    val retryDelayMillis: (failure: EarbudReaderFailure, attempt: Int) -> Long = ::earbudRetryDelayMillis
)

/**
 * 按 SOUNDPEATS 私有协议定时读取左耳、右耳与充电仓电量，每台设备一个协程。
 *
 * Why:
 * - 系统蓝牙只上报主耳电量，左右耳分开记录只能自己去问耳机；
 * - 通道与 SOUNDPEATS App 使用同一个串口服务，同一时间通常只有一方能连上，连不上时按退避间隔重试，
 *   不影响连接、编解码与系统电量的记录；
 * - 轮询间隔比 App 长，请求更少，对续航测试本身的影响更小；
 * - 只把变化交给 [onLevels]，落库仍由前台服务按“变化即记录”处理；
 * - 通道如何建立由调用方传入（真机为 RFCOMM，见 [RfcommEarbudLink]），读取逻辑因此可以在 JVM 上单测。
 *
 * @param onLevels 某一部位的电量变化时调用；observedAt 为这一轮首个回复到达的时间
 */
class SoundpeatsBatteryReader(
    private val scope: CoroutineScope,
    private val statusStore: EarbudReaderStatusStore = EarbudReaderStatusStore,
    private val timing: EarbudReaderTiming = EarbudReaderTiming(),
    private val onLevels: suspend (address: String, levels: EarbudLevels, observedAt: Long) -> Unit
) {

    private val tag = "SoundpeatsReader"
    private val sessions = ConcurrentHashMap<String, Job>()

    /**
     * 开始读取；已在读取时不重复启动（编解码切换等也会触发探测启动）。
     *
     * @param openLink 建立一次通道；连不上返回 null，每次重连都会再调用
     */
    fun start(address: String, openLink: suspend () -> EarbudLink?) {
        var created: Job? = null
        sessions.compute(address) { _, current ->
            if (current != null && current.isActive) {
                current
            } else {
                scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) { runSession(address, openLink) }
                    .also { created = it }
            }
        }
        val job = created ?: return
        job.invokeOnCompletion { cause ->
            // 只有没被新会话替换时才清掉状态，避免关掉再打开时把新会话的状态抹掉
            if (sessions.remove(address, job)) statusStore.publish(address, null)
            Log.i(tag, "[SoundpeatsBatteryReader] session -> finished: device=$address, cause=${cause?.javaClass?.simpleName}")
        }
        Log.i(tag, "[SoundpeatsBatteryReader] start -> device=$address")
        job.start()
    }

    fun stop(address: String) {
        val job = sessions.remove(address) ?: return
        job.cancel()
        statusStore.publish(address, null)
        Log.i(tag, "[SoundpeatsBatteryReader] stop -> device=$address")
    }

    fun stopAll() {
        sessions.keys.toList().forEach(::stop)
    }

    fun isRunning(address: String): Boolean = sessions[address]?.isActive == true

    private suspend fun runSession(address: String, openLink: suspend () -> EarbudLink?) {
        val self = currentCoroutineContext().job
        fun publish(status: EarbudReaderStatus) {
            if (sessions[address] === self) statusStore.publish(address, status)
        }

        var failures = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            publish(EarbudReaderStatus.Connecting)
            val (failure, receivedAny) = try {
                val link = openLink()
                if (link == null) {
                    EarbudReaderFailure.CONNECT_FAILED to false
                } else {
                    Log.i(tag, "[SoundpeatsBatteryReader] runSession -> channel open: device=$address")
                    poll(link, address, ::publish)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(tag, "[SoundpeatsBatteryReader] runSession -> channel lost: device=$address", e)
                EarbudReaderFailure.CONNECTION_LOST to true
            } catch (e: SecurityException) {
                Log.e(tag, "[SoundpeatsBatteryReader] runSession -> permission denied: device=$address", e)
                EarbudReaderFailure.CONNECT_FAILED to false
            }
            // 读到过数据说明通道本身可用，重新从最短的间隔开始重连
            failures = if (receivedAny) 1 else failures + 1
            val wait = timing.retryDelayMillis(failure, failures)
            Log.i(tag, "[SoundpeatsBatteryReader] runSession -> retry later: device=$address, failure=$failure, attempt=$failures, waitMs=$wait")
            publish(EarbudReaderStatus.Retrying(failure, System.currentTimeMillis() + wait))
            delay(wait)
        }
    }

    /**
     * 在已建立的通道上轮询，直到通道断开（抛出 [IOException]）或连续几轮没有回复。
     *
     * @return 结束原因与是否收到过电量回复
     */
    private suspend fun poll(
        link: EarbudLink,
        address: String,
        publish: (EarbudReaderStatus) -> Unit
    ): Pair<EarbudReaderFailure, Boolean> = coroutineScope {
        val replies = Channel<Pair<BatteryReply, Long>>(Channel.UNLIMITED)
        val readerJob = launch(Dispatchers.IO) { readReplies(link, replies) }
        try {
            var levels = EarbudLevels.UNKNOWN
            var lastReplyAt = 0L
            var silentCycles = 0
            var receivedAny = false
            publish(EarbudReaderStatus.Reading(levels, lastReplyAt))
            // 与 App 一致：通道刚建立时耳机可能还没准备好，稍等再发握手；握手只是让固件进入会话，不等回复
            delay(timing.settleDelayMs)
            link.send(SoundpeatsProtocol.request(SoundpeatsProtocol.CMD_FIRMWARE_VERSION))
            delay(timing.settleDelayMs)
            while (silentCycles < timing.maxSilentCycles) {
                val cycleStart = System.currentTimeMillis()
                SoundpeatsProtocol.BATTERY_QUERIES.forEach { (_, command) ->
                    link.send(SoundpeatsProtocol.request(command))
                    delay(timing.commandGapMs)
                }
                delay(timing.replyGraceMs)

                var next = levels
                var observedAt: Long? = null
                while (true) {
                    val (reply, at) = replies.tryReceive().getOrNull() ?: break
                    next = next.with(reply)
                    observedAt = observedAt ?: at
                    lastReplyAt = at
                }
                if (observedAt == null) {
                    silentCycles++
                    Log.d(tag, "[SoundpeatsBatteryReader] poll -> no reply: device=$address, silentCycles=$silentCycles")
                } else {
                    silentCycles = 0
                    receivedAny = true
                    if (next != levels) {
                        Log.i(tag, "[SoundpeatsBatteryReader] poll -> levels changed: device=$address, $levels -> $next")
                        levels = next
                        deliver(address, levels, observedAt)
                    }
                    publish(EarbudReaderStatus.Reading(levels, lastReplyAt))
                }
                delay((timing.pollIntervalMs - (System.currentTimeMillis() - cycleStart)).coerceAtLeast(0L))
            }
            Log.w(tag, "[SoundpeatsBatteryReader] poll -> no battery reply in ${timing.maxSilentCycles} cycles: device=$address")
            EarbudReaderFailure.NO_RESPONSE to receivedAny
        } finally {
            // 先取消再关闭：关闭通道才能让阻塞在 read 上的读取协程退出，而它要先看到已取消，才会把随之抛出的异常当作正常退出
            readerJob.cancel()
            closeQuietly(link, address)
        }
    }

    private suspend fun readReplies(link: EarbudLink, replies: SendChannel<Pair<BatteryReply, Long>>) {
        val parser = SoundpeatsFrameParser()
        val buffer = ByteArray(READ_BUFFER_BYTES)
        while (true) {
            val count = try {
                link.input.read(buffer)
            } catch (e: IOException) {
                // 停止读取时主动关闭通道，read 随之抛出，属于正常退出
                if (!currentCoroutineContext().isActive) return
                throw e
            }
            if (count < 0) throw IOException("SPP stream closed by remote")
            val receivedAt = System.currentTimeMillis()
            parser.feed(buffer, count).forEach { frame ->
                SoundpeatsProtocol.parseBatteryReply(frame)?.let { replies.trySend(it to receivedAt) }
            }
        }
    }

    /** 落库失败不能中断读取，只记录日志 */
    private suspend fun deliver(address: String, levels: EarbudLevels, observedAt: Long) {
        try {
            onLevels(address, levels, observedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "[SoundpeatsBatteryReader] deliver -> failed: device=$address", e)
        }
    }

    private fun EarbudLink.send(frame: ByteArray) {
        output.write(frame)
        output.flush()
    }

    private fun closeQuietly(closeable: Closeable, address: String) {
        try {
            closeable.close()
        } catch (e: IOException) {
            Log.w(tag, "[SoundpeatsBatteryReader] closeQuietly -> failed: device=$address", e)
        }
    }

    private companion object {
        const val READ_BUFFER_BYTES = 256
    }
}

private const val MAX_RETRY_DELAY_MS = 5 * 60_000L

/**
 * 重试间隔：连不上从 15 秒起翻倍；中途断开先 5 秒快速重连；不回应从 1 分钟起；都不超过 5 分钟。
 *
 * Why: 连不上多半是 SOUNDPEATS App 正占用通道，频繁重试既没用又耗电；中途断开多是耳机短暂放回仓，值得尽快再试。
 *
 * @param attempt 从 1 开始
 */
internal fun earbudRetryDelayMillis(failure: EarbudReaderFailure, attempt: Int): Long {
    val base = when (failure) {
        EarbudReaderFailure.CONNECT_FAILED -> 15_000L
        EarbudReaderFailure.CONNECTION_LOST -> 5_000L
        EarbudReaderFailure.NO_RESPONSE -> 60_000L
    }
    val shift = (attempt - 1).coerceIn(0, 10)
    return (base shl shift).coerceAtMost(MAX_RETRY_DELAY_MS)
}
