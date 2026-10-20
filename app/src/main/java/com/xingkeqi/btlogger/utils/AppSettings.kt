package com.xingkeqi.btlogger.utils

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

    private val _dualEarDevices = MutableStateFlow(prefs.getStringSet(KEY_DUAL_EAR_DEVICES, null).orEmpty().toSet())

    /**
     * 开启 SOUNDPEATS 私有协议读取左右耳电量的设备（MAC）。
     *
     * Why: 读取通道与 SOUNDPEATS App 互斥，只在测试需要时按设备打开；前台服务订阅它，开关后立即开始或停止读取。
     */
    val dualEarDevices: StateFlow<Set<String>> = _dualEarDevices.asStateFlow()

    fun setDualEarEnabled(mac: String, enabled: Boolean) {
        _dualEarDevices.update { current -> if (enabled) current + mac else current - mac }
        prefs.edit().putStringSet(KEY_DUAL_EAR_DEVICES, _dualEarDevices.value).apply()
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
        private const val KEY_DUAL_EAR_DEVICES = "dual_ear_devices"
        const val DEFAULT_FIXED_VOLUME_PERCENT = 60

        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings {
            return instance ?: synchronized(this) {
                instance ?: AppSettings(context).also { instance = it }
            }
        }
    }
}
