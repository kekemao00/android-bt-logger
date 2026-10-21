package com.xingkeqi.btlogger.domain.keepalive

/**
 * 前台服务最近一次“还活着”的心跳。
 *
 * @param aliveAt 心跳时间（墙上时钟）
 * @param bootCount 心跳时的开机次数；读不到时为 -1
 * @param cleanStop 服务是否经 onDestroy 正常结束（被系统直接杀掉时不会走到 onDestroy）
 */
data class ServiceHeartbeat(
    val aliveAt: Long,
    val bootCount: Int,
    val cleanStop: Boolean = false
)

/**
 * 一段后台记录中断：服务所在进程被系统清理，到再次启动之间的时间。
 *
 * Why: vivo、小米等系统会在后台直接杀掉进程，期间的连接、断开与电量变化都收不到；
 * 中断本身无法避免时，至少要让用户知道哪段时间的数据不完整，并引导去做后台设置。
 *
 * @param startAt 最后一次心跳的时间；息屏深睡时心跳会推迟，所以这是中断开始的最早可能时间
 * @param endAt 服务重新启动的时间
 */
data class ServiceGap(val startAt: Long, val endAt: Long) {
    val durationMs: Long get() = (endAt - startAt).coerceAtLeast(0L)
}

/** 心跳间隔：中断时长的误差大约就是这么长 */
const val SERVICE_HEARTBEAT_INTERVAL_MS = 60_000L

/** 短于这个时长的重启（例如 START_STICKY 几秒内拉起）不当作中断，避免误报 */
const val SERVICE_GAP_MIN_MS = 2 * SERVICE_HEARTBEAT_INTERVAL_MS

/** 最多保留的中断条数 */
const val SERVICE_GAP_HISTORY_LIMIT = 20

/**
 * 服务启动时判断上一次运行是否被系统中断。
 *
 * - 没有心跳（首次安装）、上次正常结束、间隔太短都不算中断；
 * - 跨开机（开机次数变化）是关机或重启，不是被系统清理，也不算中断。
 */
fun detectServiceGap(
    previous: ServiceHeartbeat?,
    now: Long,
    bootCount: Int,
    minGapMs: Long = SERVICE_GAP_MIN_MS
): ServiceGap? {
    if (previous == null || previous.cleanStop) return null
    val rebooted = previous.bootCount >= 0 && bootCount >= 0 && previous.bootCount != bootCount
    if (rebooted) return null
    if (now - previous.aliveAt < minGapMs) return null
    return ServiceGap(startAt = previous.aliveAt, endAt = now)
}

/**
 * 追加一段中断，按开始时间倒序保留最近 [limit] 条。
 */
fun appendServiceGap(
    history: List<ServiceGap>,
    gap: ServiceGap,
    limit: Int = SERVICE_GAP_HISTORY_LIMIT
): List<ServiceGap> = (history + gap).distinct().sortedByDescending { it.startAt }.take(limit)

/**
 * 推断一台“库里仍是已连接、实际已断开”的设备的断开时间。
 *
 * Why: 服务被杀期间耳机断开收不到广播，库里一直停在“已连接”，连接时长会一路累加到下次连接。
 * 进程最后活着的时刻是能确定的最晚“仍在记录”的时间，断开最可能发生在那之后不久；
 * 不知道最后心跳时退回到当前时间。结果不早于该设备最后一条记录，也不晚于当前时间。
 *
 * @param lastRecordAt 该设备最后一条记录的时间
 * @param lastAliveAt 上一次运行最后一次心跳的时间；null 表示未知（首次升级到带心跳的版本，或服务一直在运行）
 */
fun staleDisconnectAt(lastRecordAt: Long, lastAliveAt: Long?, now: Long): Long {
    val floor = lastRecordAt.coerceAtMost(now)
    return (lastAliveAt ?: now).coerceIn(floor, now)
}

/**
 * 中断记录的存储格式：`start-end` 用逗号分隔；解析失败的条目直接丢弃。
 */
fun encodeServiceGaps(gaps: List<ServiceGap>): String =
    gaps.joinToString(",") { "${it.startAt}-${it.endAt}" }

fun decodeServiceGaps(raw: String?): List<ServiceGap> {
    if (raw.isNullOrBlank()) return emptyList()
    return raw.split(',').mapNotNull { entry ->
        val parts = entry.split('-')
        val start = parts.getOrNull(0)?.toLongOrNull()
        val end = parts.getOrNull(1)?.toLongOrNull()
        if (parts.size != 2 || start == null || end == null || end < start) null else ServiceGap(start, end)
    }
}

/**
 * 系统定制 ROM 厂商：决定跳转哪个自启动/后台管理页，以及给出哪段操作提示。
 */
enum class RomVendor { XIAOMI, VIVO, OPPO, HUAWEI, HONOR, SAMSUNG, MEIZU, OTHER }

/**
 * 按 `Build.MANUFACTURER` 与 `Build.BRAND` 识别 ROM 厂商（不区分大小写）。
 *
 * Redmi、POCO 用小米的系统；iQOO 用 vivo 的系统；一加、realme 近几年都换成了 ColorOS，按 OPPO 处理。
 */
fun romVendorOf(manufacturer: String?, brand: String?): RomVendor {
    val names = listOfNotNull(manufacturer, brand).map { it.trim().lowercase() }
    fun matches(vararg keys: String) = names.any { name -> keys.any { name.contains(it) } }
    return when {
        matches("xiaomi", "redmi", "poco", "blackshark") -> RomVendor.XIAOMI
        matches("vivo", "iqoo") -> RomVendor.VIVO
        matches("oppo", "oneplus", "realme") -> RomVendor.OPPO
        matches("honor") -> RomVendor.HONOR
        matches("huawei") -> RomVendor.HUAWEI
        matches("samsung") -> RomVendor.SAMSUNG
        matches("meizu") -> RomVendor.MEIZU
        else -> RomVendor.OTHER
    }
}

/**
 * 列表页顶部的后台运行提示。
 */
sealed interface KeepAliveBanner {
    /**
     * 有用户还没看过的中断
     *
     * @param latest 最近一次中断
     * @param unseenCount 没看过的中断条数
     */
    data class Interrupted(val latest: ServiceGap, val unseenCount: Int) : KeepAliveBanner

    /** 还没忽略电池优化：建议去做后台设置 */
    data object Suggest : KeepAliveBanner
}

/**
 * 判定列表页显示哪条提示：没看过的中断优先；否则在没有忽略电池优化、且用户没关掉建议时提示一次。
 *
 * @param acknowledgedAt 用户最近一次查看中断（打开设置面板或关掉中断提示）的时间
 */
fun keepAliveBannerOf(
    gaps: List<ServiceGap>,
    acknowledgedAt: Long,
    ignoringBatteryOptimizations: Boolean,
    suggestionDismissed: Boolean
): KeepAliveBanner? {
    val unseen = gaps.filter { it.endAt > acknowledgedAt }
    if (unseen.isNotEmpty()) {
        return KeepAliveBanner.Interrupted(latest = unseen.maxBy { it.startAt }, unseenCount = unseen.size)
    }
    if (!ignoringBatteryOptimizations && !suggestionDismissed) return KeepAliveBanner.Suggest
    return null
}
