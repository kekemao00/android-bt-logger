package com.xingkeqi.btlogger.domain.playback

/**
 * 播放中断后每轮检查前的等待（毫秒），共约 2 分钟。
 *
 * Why: 首个 3 秒留给切歌间隙与短暂让出音频焦点（通知音、导航播报）后播放器自行恢复，避免多余的“播放”键；
 * 之后逐步拉长，给被系统拉起的播放器留出加载时间。
 */
val AUTO_RESUME_CHECK_DELAYS_MS: List<Long> = listOf(3_000L, 5_000L, 10_000L, 15_000L, 30_000L, 60_000L)

/**
 * 耳机断开后在这段时间内重连才续播。
 *
 * Why: 连接抖动通常几秒内就回连；超过这个时间多半是耳机已耗尽电量、测试已结束，
 * 第二天充好电连上耳机时不应突然开始放歌。
 */
const val AUTO_RESUME_RECONNECT_WINDOW_MS: Long = 10 * 60_000L

/**
 * 某一时刻的系统播放状况。
 *
 * @param playing 媒体流正在出声
 * @param bluetoothOutput 有已连接的蓝牙音频输出
 * @param inCall 处于通话、响铃等非普通音频模式
 */
data class PlaybackSnapshot(
    val playing: Boolean,
    val bluetoothOutput: Boolean,
    val inCall: Boolean
)

enum class ResumeAction {
    /** 已在播放，本次中断结束 */
    PLAYING,

    /** 暂不能续播：耳机未连接（避免外放）或正在通话，等下一轮 */
    WAIT,

    /** 向播放器发送“播放” */
    SEND_PLAY
}

fun resumeActionOf(snapshot: PlaybackSnapshot): ResumeAction = when {
    snapshot.playing -> ResumeAction.PLAYING
    !snapshot.bluetoothOutput || snapshot.inCall -> ResumeAction.WAIT
    else -> ResumeAction.SEND_PLAY
}

/**
 * 耳机重连时是否继续续播。
 *
 * @param pausedAt 本次中断首次被确认的时间；null 表示中断还没被确认（断开与暂停几乎同时发生），按刚中断处理
 */
fun shouldResumeAfterReconnect(pausedAt: Long?, now: Long): Boolean =
    pausedAt == null || now - pausedAt <= AUTO_RESUME_RECONNECT_WINDOW_MS

/** 一次中断的处理结果 */
sealed interface AutoResumeOutcome {

    /** 发出“播放”后恢复播放；[pausedAt] 到 [resumedAt] 是这次中断的时长 */
    data class Resumed(val pausedAt: Long, val resumedAt: Long, val attempts: Int) : AutoResumeOutcome

    /** 发出了“播放”但到最后一轮仍未恢复 */
    data class Failed(val pausedAt: Long, val attempts: Int) : AutoResumeOutcome
}

/**
 * 本轮测试（开关打开以来）的续播统计，供测试同事判断这次续航结果是否可信。
 *
 * @param totalPausedMs 已续播的中断累计时长，不含失败的中断
 */
data class AutoResumeStats(
    val resumedCount: Int = 0,
    val lastResumedAt: Long = 0L,
    val totalPausedMs: Long = 0L,
    val failedCount: Int = 0,
    val lastFailedAt: Long = 0L
) {
    fun plus(outcome: AutoResumeOutcome): AutoResumeStats = when (outcome) {
        is AutoResumeOutcome.Resumed -> copy(
            resumedCount = resumedCount + 1,
            lastResumedAt = outcome.resumedAt,
            totalPausedMs = totalPausedMs + (outcome.resumedAt - outcome.pausedAt).coerceAtLeast(0L)
        )

        is AutoResumeOutcome.Failed -> copy(
            failedCount = failedCount + 1,
            lastFailedAt = outcome.pausedAt
        )
    }
}
