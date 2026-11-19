package com.xingkeqi.btlogger.service.playback

import android.content.Context
import android.media.AudioManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.xingkeqi.btlogger.domain.playback.PlaybackSnapshot
import com.xingkeqi.btlogger.utils.hasBluetoothAudioOutput

/**
 * 基于 AudioManager 的续播实现。
 *
 * Why: 用媒体按键而不是 MediaController，因为后者要用户额外授予“通知使用权”；
 * 系统会把按键投递给当前或最近一个播放过的媒体会话，播放器进程被回收时多数也会被重新拉起。
 * 发 KEYCODE_MEDIA_PLAY 而不是 PLAY_PAUSE，即使状态判断有偏差也不会把正在播放的音乐暂停。
 */
internal class AndroidMediaPlaybackControl(context: Context) : MediaPlaybackControl {

    private val tag = "MediaPlaybackControl"
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val wakeLock: PowerManager.WakeLock? = runCatching {
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            .apply { setReferenceCounted(false) }
    }.onFailure { Log.e(tag, "[MediaPlaybackControl] wakeLock -> create failed", it) }.getOrNull()

    override fun snapshot(): PlaybackSnapshot = PlaybackSnapshot(
        // 只认媒体流是否真正出声：activePlaybackConfigurations 里也会留着已暂停的播放器
        playing = audioManager.isMusicActive,
        bluetoothOutput = hasBluetoothAudioOutput(audioManager),
        inCall = audioManager.mode != AudioManager.MODE_NORMAL
    )

    override fun sendPlay() {
        runCatching {
            val now = SystemClock.uptimeMillis()
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
            audioManager.dispatchMediaKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY, 0))
        }.onFailure { Log.e(tag, "[MediaPlaybackControl] sendPlay -> failed", it) }
    }

    override fun keepAwake(timeoutMs: Long) {
        runCatching { wakeLock?.acquire(timeoutMs) }
            .onFailure { Log.e(tag, "[MediaPlaybackControl] keepAwake -> failed", it) }
    }

    override fun releaseAwake() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { Log.e(tag, "[MediaPlaybackControl] releaseAwake -> failed", it) }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "BtLogger:AutoResumePlayback"
    }
}
