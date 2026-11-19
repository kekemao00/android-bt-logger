package com.xingkeqi.btlogger.service.playback

import android.util.Log
import com.xingkeqi.btlogger.domain.playback.AUTO_RESUME_CHECK_DELAYS_MS
import com.xingkeqi.btlogger.domain.playback.AutoResumeOutcome
import com.xingkeqi.btlogger.domain.playback.PlaybackSnapshot
import com.xingkeqi.btlogger.domain.playback.ResumeAction
import com.xingkeqi.btlogger.domain.playback.resumeActionOf
import com.xingkeqi.btlogger.domain.playback.shouldResumeAfterReconnect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** 续播用到的系统能力；抽成接口便于单测注入 */
interface MediaPlaybackControl {
    fun snapshot(): PlaybackSnapshot

    /** 向当前（或最近一个）媒体会话发送“播放” */
    fun sendPlay()

    /** 检查期间保持 CPU 运行，带超时自动释放 */
    fun keepAwake(timeoutMs: Long)

    fun releaseAwake()
}

/**
 * 播放中断后自动续播。
 *
 * Why: 续航测试要求耳机一直播放到电量耗尽，但来电、其他 App 抢占音频焦点、耳机短暂断开重连、
 * 播放器被系统回收等情况都会让音乐停下且不再自己恢复，整晚的测试因此作废。
 *
 * 规则：
 * - 只有开关打开后确实在蓝牙耳机上播放过（armed），才会在中断后续播，不会凭空开始放歌；
 * - 每次播放状态变化或耳机重连都重新开始一轮检查（节奏见 [AUTO_RESUME_CHECK_DELAYS_MS]），
 *   未连接蓝牙音频或通话中只等待，绝不外放；
 * - 断开超过重连窗口后不再续播，见 [shouldResumeAfterReconnect]。
 *
 * 状态读写都在 [lock] 内完成：播放回调在主线程，重连事件来自服务的 IO 协程。
 */
internal class PlaybackAutoResumer(
    private val scope: CoroutineScope,
    private val control: MediaPlaybackControl,
    private val checkDelaysMs: List<Long> = AUTO_RESUME_CHECK_DELAYS_MS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onOutcome: (AutoResumeOutcome) -> Unit
) {

    private val tag = "PlaybackAutoResumer"
    private val lock = Any()
    private val awakeTimeoutMs = checkDelaysMs.sum() + AWAKE_MARGIN_MS

    private var enabled = false
    private var armed = false
    private var pausedAt: Long? = null
    private var attemptsSent = 0
    private var checkJob: Job? = null

    fun setEnabled(enabled: Boolean) {
        synchronized(lock) {
            if (this.enabled == enabled) return
            this.enabled = enabled
            Log.i(tag, "[PlaybackAutoResumer] setEnabled -> $enabled")
            if (enabled) {
                // 打开时若正在播放，第一轮检查会把它记为 armed
                restartChecks("enabled")
            } else {
                stopLocked()
            }
        }
    }

    fun onPlaybackChanged() {
        synchronized(lock) {
            if (enabled) restartChecks("playbackChanged")
        }
    }

    fun onBluetoothConnected() {
        synchronized(lock) {
            if (!enabled || !armed) return
            val now = clock()
            if (!shouldResumeAfterReconnect(pausedAt, now)) {
                Log.i(tag, "[PlaybackAutoResumer] onBluetoothConnected -> outside reconnect window, disarm: pausedAt=$pausedAt")
                armed = false
                resetEpisode()
                return
            }
            restartChecks("reconnected")
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        checkJob?.cancel()
        checkJob = null
        control.releaseAwake()
        armed = false
        resetEpisode()
    }

    private fun restartChecks(reason: String) {
        checkJob?.cancel()
        control.keepAwake(awakeTimeoutMs)
        checkJob = scope.launch { runChecks(reason) }
    }

    private suspend fun runChecks(reason: String) {
        val ownJob = currentCoroutineContext().job
        try {
            for (delayMs in checkDelaysMs) {
                delay(delayMs)
                if (!checkOnce(reason, ownJob)) return
            }
            finishExhausted()
        } finally {
            // 被新一轮取代时不释放，唤醒锁归新一轮管
            synchronized(lock) {
                if (checkJob === ownJob) {
                    checkJob = null
                    control.releaseAwake()
                }
            }
        }
    }

    /** @return 是否需要继续下一轮 */
    private fun checkOnce(reason: String, ownJob: Job): Boolean {
        var outcome: AutoResumeOutcome? = null
        val keepChecking = synchronized(lock) {
            // 取消发生在等锁期间时 delay 拦不住，这里再判一次，避免被取代的一轮多发一次“播放”
            if (!ownJob.isActive) return false
            val snapshot = control.snapshot()
            when (resumeActionOf(snapshot)) {
                ResumeAction.PLAYING -> {
                    if (snapshot.bluetoothOutput) armed = true
                    val pausedSince = pausedAt
                    if (attemptsSent > 0 && pausedSince != null) {
                        outcome = AutoResumeOutcome.Resumed(pausedSince, clock(), attemptsSent)
                    }
                    resetEpisode()
                    false
                }

                ResumeAction.WAIT -> {
                    if (armed) markPaused()
                    armed
                }

                ResumeAction.SEND_PLAY -> {
                    if (armed) {
                        markPaused()
                        attemptsSent++
                        Log.i(tag, "[PlaybackAutoResumer] checkOnce -> send play: reason=$reason, attempt=$attemptsSent, pausedAt=$pausedAt")
                        control.sendPlay()
                    }
                    armed
                }
            }
        }
        outcome?.let(::report)
        return keepChecking
    }

    private fun finishExhausted() {
        var outcome: AutoResumeOutcome? = null
        synchronized(lock) {
            val pausedSince = pausedAt
            if (attemptsSent > 0 && pausedSince != null) {
                outcome = AutoResumeOutcome.Failed(pausedSince, attemptsSent)
            }
            // 保留 pausedAt：重连窗口仍按这次中断的开始时间计算
            attemptsSent = 0
        }
        outcome?.let(::report) ?: Log.i(tag, "[PlaybackAutoResumer] finishExhausted -> no play sent, waiting for reconnect")
    }

    private fun report(outcome: AutoResumeOutcome) {
        when (outcome) {
            is AutoResumeOutcome.Resumed -> Log.i(tag, "[PlaybackAutoResumer] report -> resumed: $outcome")
            is AutoResumeOutcome.Failed -> Log.w(tag, "[PlaybackAutoResumer] report -> failed: $outcome")
        }
        runCatching { onOutcome(outcome) }
            .onFailure { Log.e(tag, "[PlaybackAutoResumer] report -> listener failed", it) }
    }

    private fun markPaused() {
        if (pausedAt == null) pausedAt = clock()
    }

    private fun resetEpisode() {
        pausedAt = null
        attemptsSent = 0
    }

    private companion object {
        const val AWAKE_MARGIN_MS = 10_000L
    }
}
