package com.xingkeqi.btlogger.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.xingkeqi.btlogger.domain.keepalive.SERVICE_HEARTBEAT_INTERVAL_MS
import com.xingkeqi.btlogger.domain.keepalive.ServiceHeartbeat
import com.xingkeqi.btlogger.domain.keepalive.detectServiceGap
import com.xingkeqi.btlogger.receiver.BootCompletedReceiver
import com.xingkeqi.btlogger.utils.ServiceHealthStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务的保活与自检：心跳、中断检测、看门狗闹钟与读取期间的唤醒锁。
 *
 * Why: 在 vivo、小米等系统上，即使是前台服务也可能在息屏、划掉任务或“一键清理”后被直接杀掉，
 * 杀掉后既不会回调 onDestroy，START_STICKY 也常被系统忽略。这里分几层兜底：
 * - 心跳：每分钟记一次“还活着”，下次启动时据此还原出中断时段，并推断期间漏掉的断开；
 * - 看门狗：每 15 分钟一次不唤醒设备的闹钟，进程没了就重新拉起服务；划掉任务时再补一个几秒后的闹钟；
 * - 唤醒锁：只在读取左右耳电量（每 10 秒一轮）时持有，避免息屏深睡后轮询停住，其余时间完全依赖系统广播。
 */
internal class ServiceKeepAlive(
    private val context: Context,
    private val scope: CoroutineScope,
    private val healthStore: ServiceHealthStore = ServiceHealthStore.get(context)
) {

    private val tag = "ServiceKeepAlive"
    private val alarmManager by lazy { context.getSystemService(Context.ALARM_SERVICE) as AlarmManager }
    private val wakeLock: PowerManager.WakeLock? by lazy {
        runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { setReferenceCounted(false) }
        }.onFailure { Log.e(tag, "[ServiceKeepAlive] wakeLock -> create failed", it) }.getOrNull()
    }

    @Volatile
    private var wakeLockWanted = false

    /**
     * 服务创建时调用：检测并记下上一次运行的中断，随后开始本次的心跳。
     *
     * @return 上一次运行最后一次心跳的时间（正常结束时为 null），用于推断中断期间漏掉的断开时间
     */
    fun onServiceCreated(): Long? {
        val now = System.currentTimeMillis()
        val bootCount = readBootCount()
        val previous = healthStore.readHeartbeat()
        val gap = detectServiceGap(previous = previous, now = now, bootCount = bootCount)
        Log.i(
            tag,
            "[ServiceKeepAlive] onServiceCreated -> previousAliveAt=${previous?.aliveAt}, cleanStop=${previous?.cleanStop}, previousBoot=${previous?.bootCount}, boot=$bootCount, gapMs=${gap?.durationMs}"
        )
        gap?.let(healthStore::addGap)
        writeHeartbeat(cleanStop = false)
        startHeartbeat()
        scheduleWatchdog()
        return previous?.takeUnless { it.cleanStop }?.aliveAt
    }

    fun onServiceDestroyed() {
        writeHeartbeat(cleanStop = true)
        setWakeLockWanted(false)
    }

    /**
     * 用户从最近任务划掉 App：部分 ROM 会紧接着杀掉整个进程，提前约好几秒后由看门狗重新拉起。
     */
    fun onTaskRemoved() {
        writeHeartbeat(cleanStop = false)
        runCatching {
            alarmManager.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + TASK_REMOVED_RESTART_DELAY_MS,
                watchdogIntent(REQUEST_CODE_TASK_REMOVED)
            )
            Log.i(tag, "[ServiceKeepAlive] onTaskRemoved -> restart alarm scheduled")
        }.onFailure { Log.e(tag, "[ServiceKeepAlive] onTaskRemoved -> schedule failed", it) }
    }

    /**
     * 读取左右耳电量期间保持 CPU 运行。带超时获取、由心跳续期：服务被杀或忘记释放时也会自动失效。
     */
    fun setWakeLockWanted(wanted: Boolean) {
        wakeLockWanted = wanted
        val lock = wakeLock ?: return
        runCatching {
            if (wanted) {
                lock.acquire(WAKE_LOCK_TIMEOUT_MS)
            } else if (lock.isHeld) {
                lock.release()
            }
        }.onFailure { Log.e(tag, "[ServiceKeepAlive] setWakeLockWanted -> failed: wanted=$wanted", it) }
        Log.i(tag, "[ServiceKeepAlive] setWakeLockWanted -> wanted=$wanted, held=${runCatching { lock.isHeld }.getOrNull()}")
    }

    private fun startHeartbeat() {
        scope.launch {
            while (isActive) {
                delay(SERVICE_HEARTBEAT_INTERVAL_MS)
                writeHeartbeat(cleanStop = false)
                // 续期：未持有时不会新获取
                if (wakeLockWanted) {
                    runCatching { wakeLock?.acquire(WAKE_LOCK_TIMEOUT_MS) }
                        .onFailure { Log.e(tag, "[ServiceKeepAlive] heartbeat -> wake lock renew failed", it) }
                }
            }
        }
    }

    private fun writeHeartbeat(cleanStop: Boolean) {
        runCatching {
            healthStore.writeHeartbeat(
                ServiceHeartbeat(aliveAt = System.currentTimeMillis(), bootCount = readBootCount(), cleanStop = cleanStop)
            )
        }.onFailure { Log.e(tag, "[ServiceKeepAlive] writeHeartbeat -> failed", it) }
    }

    /**
     * 不唤醒设备的重复闹钟：设备醒着时才投递，不额外耗电；进程还在时接收器直接跳过。
     */
    private fun scheduleWatchdog() {
        runCatching {
            alarmManager.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
                WATCHDOG_INTERVAL_MS,
                watchdogIntent(REQUEST_CODE_WATCHDOG)
            )
        }.onFailure { Log.e(tag, "[ServiceKeepAlive] scheduleWatchdog -> failed", it) }
    }

    private fun watchdogIntent(requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, BootCompletedReceiver::class.java).setAction(BootCompletedReceiver.ACTION_WATCHDOG),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    @SuppressLint("InlinedApi")
    private fun readBootCount(): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    }.getOrDefault(-1)

    private companion object {
        const val WAKE_LOCK_TAG = "BtLogger:earbudPolling"
        const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
        const val WATCHDOG_INTERVAL_MS = AlarmManager.INTERVAL_FIFTEEN_MINUTES
        const val TASK_REMOVED_RESTART_DELAY_MS = 3_000L
        const val REQUEST_CODE_WATCHDOG = 2001
        const val REQUEST_CODE_TASK_REMOVED = 2002
    }
}
