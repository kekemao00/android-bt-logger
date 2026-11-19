package com.xingkeqi.btlogger.utils

import android.content.Context
import android.content.SharedPreferences
import com.xingkeqi.btlogger.domain.playback.AutoResumeOutcome
import com.xingkeqi.btlogger.domain.playback.AutoResumeStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 本轮测试的自动续播统计，打开续播开关时清零。
 *
 * Why: 测试同事第二天回来要据此判断夜里音乐是否中断过、补救是否成功；
 * 服务夜里可能被系统杀掉重建，只放内存会丢，因此落到 SharedPreferences。
 */
class PlaybackResumeStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _stats = MutableStateFlow(read())
    val stats: StateFlow<AutoResumeStats> = _stats.asStateFlow()

    fun record(outcome: AutoResumeOutcome) {
        _stats.update { it.plus(outcome) }
        write(_stats.value)
    }

    fun reset() {
        _stats.value = AutoResumeStats()
        write(_stats.value)
    }

    private fun read() = AutoResumeStats(
        resumedCount = prefs.getInt(KEY_RESUMED_COUNT, 0),
        lastResumedAt = prefs.getLong(KEY_LAST_RESUMED_AT, 0L),
        totalPausedMs = prefs.getLong(KEY_TOTAL_PAUSED_MS, 0L),
        failedCount = prefs.getInt(KEY_FAILED_COUNT, 0),
        lastFailedAt = prefs.getLong(KEY_LAST_FAILED_AT, 0L)
    )

    private fun write(stats: AutoResumeStats) {
        prefs.edit()
            .putInt(KEY_RESUMED_COUNT, stats.resumedCount)
            .putLong(KEY_LAST_RESUMED_AT, stats.lastResumedAt)
            .putLong(KEY_TOTAL_PAUSED_MS, stats.totalPausedMs)
            .putInt(KEY_FAILED_COUNT, stats.failedCount)
            .putLong(KEY_LAST_FAILED_AT, stats.lastFailedAt)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "bt_logger_playback_resume"
        private const val KEY_RESUMED_COUNT = "resumed_count"
        private const val KEY_LAST_RESUMED_AT = "last_resumed_at"
        private const val KEY_TOTAL_PAUSED_MS = "total_paused_ms"
        private const val KEY_FAILED_COUNT = "failed_count"
        private const val KEY_LAST_FAILED_AT = "last_failed_at"

        @Volatile
        private var instance: PlaybackResumeStore? = null

        fun get(context: Context): PlaybackResumeStore {
            return instance ?: synchronized(this) {
                instance ?: PlaybackResumeStore(context).also { instance = it }
            }
        }
    }
}
