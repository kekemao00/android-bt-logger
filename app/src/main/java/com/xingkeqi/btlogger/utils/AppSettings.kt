package com.xingkeqi.btlogger.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 应用级用户设置。
 *
 * Why:
 * 固定音量开关与目标音量过去只保存在 ViewModel 内存里，App 被杀后即丢失，
 * 且前台服务无法读取。这里统一落到 SharedPreferences，并暴露 StateFlow 供 UI 订阅。
 */
class AppSettings private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _fixedVolumeEnabled = MutableStateFlow(prefs.getBoolean(KEY_FIXED_VOLUME_ENABLED, false))
    val fixedVolumeEnabled: StateFlow<Boolean> = _fixedVolumeEnabled.asStateFlow()

    private val _fixedVolumePercent = MutableStateFlow(
        prefs.getInt(KEY_FIXED_VOLUME_PERCENT, DEFAULT_FIXED_VOLUME_PERCENT).coerceIn(0, 100)
    )
    val fixedVolumePercent: StateFlow<Int> = _fixedVolumePercent.asStateFlow()

    private val _batterySampleIntervalMinutes = MutableStateFlow(
        prefs.getInt(KEY_BATTERY_SAMPLE_INTERVAL_MINUTES, DEFAULT_BATTERY_SAMPLE_INTERVAL_MINUTES)
            .takeIf { it in BATTERY_SAMPLE_INTERVAL_OPTIONS }
            ?: DEFAULT_BATTERY_SAMPLE_INTERVAL_MINUTES
    )

    /**
     * 定时采样间隔（分钟），0 表示关闭；前台服务每次检查时读取，修改后立即生效
     */
    val batterySampleIntervalMinutes: StateFlow<Int> = _batterySampleIntervalMinutes.asStateFlow()

    fun setBatterySampleIntervalMinutes(minutes: Int) {
        if (minutes !in BATTERY_SAMPLE_INTERVAL_OPTIONS || _batterySampleIntervalMinutes.value == minutes) return
        _batterySampleIntervalMinutes.value = minutes
        prefs.edit().putInt(KEY_BATTERY_SAMPLE_INTERVAL_MINUTES, minutes).apply()
    }

    fun setFixedVolumeEnabled(enabled: Boolean) {
        _fixedVolumeEnabled.value = enabled
        prefs.edit().putBoolean(KEY_FIXED_VOLUME_ENABLED, enabled).apply()
    }

    fun setFixedVolumePercent(percent: Int) {
        val resolved = percent.coerceIn(0, 100)
        if (_fixedVolumePercent.value == resolved) return
        _fixedVolumePercent.value = resolved
        prefs.edit().putInt(KEY_FIXED_VOLUME_PERCENT, resolved).apply()
    }

    /**
     * 用户选择“跳过此版本”的 tag；启动时的自动检查不再提示该版本，手动检查仍会提示
     */
    var skippedUpdateTag: String?
        get() = prefs.getString(KEY_SKIPPED_UPDATE_TAG, null)
        set(value) {
            prefs.edit().putString(KEY_SKIPPED_UPDATE_TAG, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "bt_logger_settings"
        private const val KEY_FIXED_VOLUME_ENABLED = "fixed_volume_enabled"
        private const val KEY_FIXED_VOLUME_PERCENT = "fixed_volume_percent"
        private const val KEY_SKIPPED_UPDATE_TAG = "skipped_update_tag"
        private const val KEY_BATTERY_SAMPLE_INTERVAL_MINUTES = "battery_sample_interval_minutes"
        const val DEFAULT_FIXED_VOLUME_PERCENT = 60
        const val DEFAULT_BATTERY_SAMPLE_INTERVAL_MINUTES = 5

        /** 可选的定时采样间隔（分钟），0 为关闭 */
        val BATTERY_SAMPLE_INTERVAL_OPTIONS = listOf(0, 1, 5, 15)

        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings {
            return instance ?: synchronized(this) {
                instance ?: AppSettings(context).also { instance = it }
            }
        }
    }
}
