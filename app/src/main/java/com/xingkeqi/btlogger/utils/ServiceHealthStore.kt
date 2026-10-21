package com.xingkeqi.btlogger.utils

import android.content.Context
import android.content.SharedPreferences
import com.xingkeqi.btlogger.domain.keepalive.ServiceGap
import com.xingkeqi.btlogger.domain.keepalive.ServiceHeartbeat
import com.xingkeqi.btlogger.domain.keepalive.appendServiceGap
import com.xingkeqi.btlogger.domain.keepalive.decodeServiceGaps
import com.xingkeqi.btlogger.domain.keepalive.encodeServiceGaps
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 前台服务的运行状况：心跳与检测到的后台中断。
 *
 * Why: 进程被系统杀掉时来不及写任何东西，只能靠“最后一次心跳”在下次启动时还原出中断时段；
 * 单独一个 SharedPreferences 文件，避免每分钟一次的心跳写入和用户设置挤在一起。
 */
class ServiceHealthStore private constructor(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _gaps = MutableStateFlow(decodeServiceGaps(prefs.getString(KEY_GAPS, null)))

    /** 最近的后台中断，按开始时间倒序 */
    val gaps: StateFlow<List<ServiceGap>> = _gaps.asStateFlow()

    private val _acknowledgedAt = MutableStateFlow(prefs.getLong(KEY_ACKNOWLEDGED_AT, 0L))

    /** 用户最近一次查看中断的时间，此后结束的中断才会再次提示 */
    val acknowledgedAt: StateFlow<Long> = _acknowledgedAt.asStateFlow()

    fun readHeartbeat(): ServiceHeartbeat? {
        val aliveAt = prefs.getLong(KEY_ALIVE_AT, 0L).takeIf { it > 0L } ?: return null
        return ServiceHeartbeat(
            aliveAt = aliveAt,
            bootCount = prefs.getInt(KEY_BOOT_COUNT, -1),
            cleanStop = prefs.getBoolean(KEY_CLEAN_STOP, false)
        )
    }

    fun writeHeartbeat(heartbeat: ServiceHeartbeat) {
        prefs.edit()
            .putLong(KEY_ALIVE_AT, heartbeat.aliveAt)
            .putInt(KEY_BOOT_COUNT, heartbeat.bootCount)
            .putBoolean(KEY_CLEAN_STOP, heartbeat.cleanStop)
            .apply()
    }

    fun addGap(gap: ServiceGap) {
        val updated = appendServiceGap(_gaps.value, gap)
        _gaps.value = updated
        prefs.edit().putString(KEY_GAPS, encodeServiceGaps(updated)).apply()
    }

    fun acknowledge(at: Long = System.currentTimeMillis()) {
        if (_acknowledgedAt.value >= at) return
        _acknowledgedAt.value = at
        prefs.edit().putLong(KEY_ACKNOWLEDGED_AT, at).apply()
    }

    companion object {
        private const val PREFS_NAME = "bt_logger_service_health"
        private const val KEY_ALIVE_AT = "alive_at"
        private const val KEY_BOOT_COUNT = "boot_count"
        private const val KEY_CLEAN_STOP = "clean_stop"
        private const val KEY_GAPS = "gaps"
        private const val KEY_ACKNOWLEDGED_AT = "acknowledged_at"

        @Volatile
        private var instance: ServiceHealthStore? = null

        fun get(context: Context): ServiceHealthStore {
            return instance ?: synchronized(this) {
                instance ?: ServiceHealthStore(context).also { instance = it }
            }
        }
    }
}
