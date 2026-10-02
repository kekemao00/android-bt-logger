package com.xingkeqi.btlogger.utils

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

data class MediaVolumeSnapshot(
    val currentLevel: Int = 0,
    val maxLevel: Int = 0,
    val percent: Int = 0,
    val hasBluetoothOutput: Boolean = false,
    val isMusicActive: Boolean = false
) {
    val routeLabel: String
        get() = if (hasBluetoothOutput) "蓝牙音频设备已连接" else "当前未连接蓝牙音频设备"
}

fun readMediaVolumeSnapshot(context: Context): MediaVolumeSnapshot {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val currentLevel = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val maxLevel = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    return MediaVolumeSnapshot(
        currentLevel = currentLevel,
        maxLevel = maxLevel,
        percent = calculateVolumePercent(currentLevel, maxLevel),
        hasBluetoothOutput = hasBluetoothAudioOutput(audioManager),
        isMusicActive = isMediaPlaybackActive(audioManager)
    )
}

fun setMediaVolumePercent(context: Context, percent: Int, flags: Int = 0) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxLevel = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    audioManager.setStreamVolume(
        AudioManager.STREAM_MUSIC,
        resolveVolumeIndex(percent, maxLevel),
        flags
    )
}

fun calculateVolumePercent(currentLevel: Int, maxLevel: Int): Int {
    if (maxLevel <= 0) return 0
    val boundedCurrent = currentLevel.coerceIn(0, maxLevel)
    return ((boundedCurrent.toFloat() / maxLevel) * 100f).roundToInt().coerceIn(0, 100)
}

fun resolveVolumeIndex(percent: Int, maxLevel: Int): Int {
    if (maxLevel <= 0) return 0
    return ((percent.coerceIn(0, 100) / 100f) * maxLevel).roundToInt().coerceIn(0, maxLevel)
}

fun shouldApplyFixedVolumeForBluetooth(
    snapshot: MediaVolumeSnapshot,
    attempt: Int,
    maxAttempts: Int
): Boolean {
    if (!snapshot.hasBluetoothOutput || maxAttempts <= 0) return false
    val lastResortAttempt = attempt >= (maxAttempts - 2).coerceAtLeast(0)
    return snapshot.isMusicActive || lastResortAttempt
}

/**
 * 在蓝牙音频路由就绪后把媒体音量调到目标百分比。
 *
 * Why:
 * A2DP 连接广播到达时音频路由往往尚未切到蓝牙，系统随后还会恢复耳机自己的音量，
 * 因此需要短暂轮询：优先在开始播放时设置，最后两次尝试兜底强制设置。
 *
 * @return 是否成功达到目标音量
 */
suspend fun applyFixedMediaVolume(
    context: Context,
    targetPercent: Int,
    maxAttempts: Int = 8
): Boolean {
    repeat(maxAttempts) { attempt ->
        val snapshot = readMediaVolumeSnapshot(context)
        val shouldApply = shouldApplyFixedVolumeForBluetooth(
            snapshot = snapshot,
            attempt = attempt,
            maxAttempts = maxAttempts
        )
        Log.i(
            "MediaVolumeUtils",
            "[MediaVolumeUtils] applyFixedMediaVolume -> attempt=${attempt + 1}/$maxAttempts, bluetooth=${snapshot.hasBluetoothOutput}, playing=${snapshot.isMusicActive}, current=${snapshot.percent}%, target=$targetPercent%"
        )
        if (shouldApply) {
            setMediaVolumePercent(context, targetPercent)
            delay(160L)
            val applied = readMediaVolumeSnapshot(context)
            // 按音量档位比较：部分百分比无法精确映射到整数档位，比较百分比会一直判定失败
            val targetLevel = resolveVolumeIndex(targetPercent, applied.maxLevel)
            if (applied.hasBluetoothOutput && applied.currentLevel == targetLevel) {
                return true
            }
        }
        delay(320L)
    }
    Log.w("MediaVolumeUtils", "[MediaVolumeUtils] applyFixedMediaVolume -> timeout, target=$targetPercent%")
    return false
}

private fun hasBluetoothAudioOutput(audioManager: AudioManager): Boolean {
    val bluetoothDeviceTypes = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLE_BROADCAST
    )
    return audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        .any { it.type in bluetoothDeviceTypes }
}

private fun isMediaPlaybackActive(audioManager: AudioManager): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val activeMediaPlayback = audioManager.activePlaybackConfigurations.any { configuration ->
            configuration.audioAttributes?.usage in setOf(
                AudioAttributes.USAGE_MEDIA,
                AudioAttributes.USAGE_GAME,
                AudioAttributes.USAGE_ASSISTANT
            )
        }
        if (activeMediaPlayback) {
            return true
        }
    }
    return audioManager.isMusicActive
}
