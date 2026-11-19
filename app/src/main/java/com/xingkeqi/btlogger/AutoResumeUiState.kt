package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.domain.playback.AutoResumeStats

/**
 * “播放中断后自动续播”开关与本轮统计。
 *
 * @param supported Android 8.0 以下无法感知播放中断，开关置灰
 */
data class AutoResumeUiState(
    val supported: Boolean = true,
    val enabled: Boolean = false,
    val stats: AutoResumeStats = AutoResumeStats()
)
