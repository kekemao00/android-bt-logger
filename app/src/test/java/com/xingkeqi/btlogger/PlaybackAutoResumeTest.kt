package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.domain.playback.AUTO_RESUME_RECONNECT_WINDOW_MS
import com.xingkeqi.btlogger.domain.playback.AutoResumeOutcome
import com.xingkeqi.btlogger.domain.playback.AutoResumeStats
import com.xingkeqi.btlogger.domain.playback.PlaybackSnapshot
import com.xingkeqi.btlogger.domain.playback.ResumeAction
import com.xingkeqi.btlogger.domain.playback.resumeActionOf
import com.xingkeqi.btlogger.domain.playback.shouldResumeAfterReconnect
import com.xingkeqi.btlogger.service.playback.MediaPlaybackControl
import com.xingkeqi.btlogger.service.playback.PlaybackAutoResumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PlaybackAutoResumeTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val control = FakeControl()
    private val outcomes = CopyOnWriteArrayList<AutoResumeOutcome>()
    private val now = AtomicLong(1_000_000L)
    private val resumer = PlaybackAutoResumer(
        scope = scope,
        control = control,
        checkDelaysMs = listOf(20L, 20L, 20L),
        clock = { now.get() },
        onOutcome = { outcomes += it }
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `正在播放优先于其他条件`() {
        assertEquals(ResumeAction.PLAYING, resumeActionOf(PlaybackSnapshot(playing = true, bluetoothOutput = false, inCall = true)))
    }

    @Test
    fun `未连蓝牙或通话中只等待`() {
        assertEquals(ResumeAction.WAIT, resumeActionOf(PlaybackSnapshot(playing = false, bluetoothOutput = false, inCall = false)))
        assertEquals(ResumeAction.WAIT, resumeActionOf(PlaybackSnapshot(playing = false, bluetoothOutput = true, inCall = true)))
        assertEquals(ResumeAction.SEND_PLAY, resumeActionOf(PlaybackSnapshot(playing = false, bluetoothOutput = true, inCall = false)))
    }

    @Test
    fun `重连窗口内才续播`() {
        assertTrue(shouldResumeAfterReconnect(pausedAt = null, now = 0L))
        assertTrue(shouldResumeAfterReconnect(pausedAt = 0L, now = AUTO_RESUME_RECONNECT_WINDOW_MS))
        assertFalse(shouldResumeAfterReconnect(pausedAt = 0L, now = AUTO_RESUME_RECONNECT_WINDOW_MS + 1))
    }

    @Test
    fun `统计累加续播次数与中断时长`() {
        val stats = AutoResumeStats()
            .plus(AutoResumeOutcome.Resumed(pausedAt = 100L, resumedAt = 400L, attempts = 1))
            .plus(AutoResumeOutcome.Resumed(pausedAt = 1_000L, resumedAt = 1_200L, attempts = 2))
            .plus(AutoResumeOutcome.Failed(pausedAt = 5_000L, attempts = 3))
        assertEquals(2, stats.resumedCount)
        assertEquals(1_200L, stats.lastResumedAt)
        assertEquals(500L, stats.totalPausedMs)
        assertEquals(1, stats.failedCount)
        assertEquals(5_000L, stats.lastFailedAt)
    }

    @Test
    fun `没在蓝牙上播放过就不发播放`() {
        resumer.setEnabled(true)
        resumer.onPlaybackChanged()
        Thread.sleep(150)
        assertEquals(0, control.playSent.get())
    }

    @Test
    fun `播放中断后发送播放并记为续播成功`() {
        armWhilePlaying()
        control.resumeOnPlay = true
        control.playing = false
        now.addAndGet(5_000L)
        resumer.onPlaybackChanged()

        awaitUntil { outcomes.isNotEmpty() }
        assertEquals(1, control.playSent.get())
        val resumed = outcomes.single() as AutoResumeOutcome.Resumed
        assertEquals(1, resumed.attempts)
    }

    @Test
    fun `耳机未连接时不外放`() {
        armWhilePlaying()
        control.playing = false
        control.bluetoothOutput = false
        resumer.onPlaybackChanged()
        Thread.sleep(150)
        assertEquals(0, control.playSent.get())
        assertTrue(outcomes.isEmpty())
    }

    @Test
    fun `通话中不发播放`() {
        armWhilePlaying()
        control.playing = false
        control.inCall = true
        resumer.onPlaybackChanged()
        Thread.sleep(150)
        assertEquals(0, control.playSent.get())
    }

    @Test
    fun `播放器一直不响应时记为续播失败`() {
        armWhilePlaying()
        control.playing = false
        resumer.onPlaybackChanged()

        awaitUntil { outcomes.isNotEmpty() }
        assertEquals(3, control.playSent.get())
        assertEquals(3, (outcomes.single() as AutoResumeOutcome.Failed).attempts)
    }

    @Test
    fun `断开超过重连窗口后重连不再续播`() {
        armWhilePlaying()
        control.playing = false
        control.bluetoothOutput = false
        resumer.onPlaybackChanged()
        Thread.sleep(150)

        control.bluetoothOutput = true
        now.addAndGet(AUTO_RESUME_RECONNECT_WINDOW_MS + 1)
        resumer.onBluetoothConnected()
        Thread.sleep(150)
        assertEquals(0, control.playSent.get())
    }

    @Test
    fun `断开后短时间内重连会续播`() {
        armWhilePlaying()
        control.playing = false
        control.bluetoothOutput = false
        resumer.onPlaybackChanged()
        Thread.sleep(150)

        control.bluetoothOutput = true
        control.resumeOnPlay = true
        now.addAndGet(30_000L)
        resumer.onBluetoothConnected()

        awaitUntil { outcomes.isNotEmpty() }
        assertEquals(1, control.playSent.get())
    }

    @Test
    fun `关闭开关后停止续播并释放唤醒锁`() {
        armWhilePlaying()
        control.playing = false
        resumer.onPlaybackChanged()
        resumer.setEnabled(false)
        Thread.sleep(150)
        assertEquals(0, control.playSent.get())
        assertFalse(control.awake)
    }

    private fun armWhilePlaying() {
        control.playing = true
        resumer.setEnabled(true)
        awaitUntil { !control.awake }
    }

    private fun awaitUntil(timeoutMs: Long = 2_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met in ${timeoutMs}ms" }
            Thread.sleep(5)
        }
    }

    private class FakeControl : MediaPlaybackControl {
        @Volatile var playing = false
        @Volatile var bluetoothOutput = true
        @Volatile var inCall = false
        @Volatile var resumeOnPlay = false
        @Volatile var awake = false
        val playSent = AtomicInteger()

        override fun snapshot() = PlaybackSnapshot(playing, bluetoothOutput, inCall)

        override fun sendPlay() {
            playSent.incrementAndGet()
            if (resumeOnPlay) playing = true
        }

        override fun keepAwake(timeoutMs: Long) {
            awake = true
        }

        override fun releaseAwake() {
            awake = false
        }
    }
}
