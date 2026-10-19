package com.xingkeqi.btlogger.domain.battery

/**
 * 耳机电量分析（纯 Kotlin，不依赖 Android 与 Room）。
 *
 * Why:
 * 续航测试要回答的是“这次连接从多少掉到多少、用了多久、每小时掉多少、还能撑多久”，
 * 以及“几台耳机谁更耐用”。过去详情页把设备的全部历史画成一条线，跨天、跨连接的点连在一起，
 * 看不出单次测试的掉电过程；这里先按连接切分，再在每段内计算掉电速率与预计续航。
 */

/** 一条记录在电量分析里的角色：连接/断开只用于切分“一次连接”，其余事件都是采样点 */
enum class ReadingKind { SESSION_START, SESSION_END, SAMPLE }

/**
 * 记录中与电量相关的字段；电量为 null 表示该条记录没有拿到有效值（如耳机未上报）。
 */
data class BatteryReading(
    val timestamp: Long,
    val kind: ReadingKind,
    val headsetLevel: Int?,
    val phoneLevel: Int?,
    val volume: Int = 0,
    val isPlaying: Boolean = false
)

/** 曲线上的一个实测点 */
data class LevelPoint(val timestamp: Long, val level: Int)

/**
 * 一次连接：最近一次断开后的首条记录到对应的断开记录。
 *
 * @param ended 是否已断开；仍在连接中时 [endTime] 为最后一条记录的时间
 */
data class BatterySession(
    val startTime: Long,
    val endTime: Long,
    val ended: Boolean,
    val readings: List<BatteryReading>
) {
    val headset: List<LevelPoint> = readings.mapNotNull { reading ->
        reading.headsetLevel?.takeIf(::isValidLevel)?.let { LevelPoint(reading.timestamp, it) }
    }
    val phone: List<LevelPoint> = readings.mapNotNull { reading ->
        reading.phoneLevel?.takeIf(::isValidLevel)?.let { LevelPoint(reading.timestamp, it) }
    }

    fun points(source: BatterySource): List<LevelPoint> = when (source) {
        BatterySource.HEADSET -> headset
        BatterySource.PHONE -> phone
    }
}

enum class BatterySource { HEADSET, PHONE }

/**
 * 掉电统计。
 *
 * 平均掉电速率 = 累计消耗 ÷ 记录时长：累计消耗只累加下降（中途回升不抵扣），
 * 记录时长是各段首尾采样的时间差之和，不含断开期间。记录不足 [MIN_RATE_WINDOW_MILLIS]
 * 或尚未掉电时速率为 null，界面显示“数据不足”，避免开头几分钟给出离谱的估算。
 *
 * @param consumed 累计消耗的百分点
 * @param trackedMillis 参与计算的记录时长
 */
data class DrainStats(
    val startLevel: Int,
    val endLevel: Int,
    val startTime: Long,
    val endTime: Long,
    val consumed: Int,
    val trackedMillis: Long,
    val sampleCount: Int
) {
    /** 平均掉电速率（%/小时） */
    val ratePerHour: Double? =
        if (trackedMillis >= MIN_RATE_WINDOW_MILLIS && consumed > 0) {
            consumed * HOUR_MILLIS.toDouble() / trackedMillis
        } else {
            null
        }

    /** 按当前速率从结束电量用到 0 的预计时长，仅对仍在连接中的那一段有意义 */
    val estimatedRemainingMillis: Long? = ratePerHour?.let { (endLevel / it * HOUR_MILLIS).toLong() }

    /** 按当前速率从 100% 用到 0 的预计续航 */
    val estimatedFullRuntimeMillis: Long? = ratePerHour?.let { (FULL_LEVEL / it * HOUR_MILLIS).toLong() }
}

/** 详情页曲线的时间范围 */
enum class BatteryRange { LATEST_SESSION, LAST_24_HOURS, ALL }

/**
 * 详情页电量概览：所选范围内的连接（已按范围裁剪）与掉电统计。
 *
 * @param ongoing 最后一段仍在连接中，此时展示“预计剩余”，否则展示“满电续航”
 */
data class BatteryOverview(
    val range: BatteryRange,
    val sessions: List<BatterySession>,
    val headsetStats: DrainStats?,
    val phoneStats: DrainStats?,
    val windowStart: Long,
    val windowEnd: Long,
    val ongoing: Boolean
) {
    val hasHeadsetData: Boolean get() = sessions.any { it.headset.isNotEmpty() }
    val hasPhoneData: Boolean get() = sessions.any { it.phone.isNotEmpty() }

    companion object {
        fun empty(range: BatteryRange) = BatteryOverview(
            range = range,
            sessions = emptyList(),
            headsetStats = null,
            phoneStats = null,
            windowStart = 0L,
            windowEnd = 0L,
            ongoing = false
        )
    }
}

/**
 * 按时间顺序切分连接。
 *
 * - 连续多条“已连接”（服务重建时补记）视为同一次连接，与列表“已连接时长”的口径一致；
 * - 没有对应连接记录的“已断开”不构成一次连接；
 * - 连接与断开之间的任意事件都是采样点，断开记录本身带有断开时的电量，作为这一段的终点。
 *
 * @param readings 任意顺序；时间相同的记录保持传入顺序
 */
fun splitBatterySessions(readings: List<BatteryReading>): List<BatterySession> {
    val sessions = mutableListOf<BatterySession>()
    var current: MutableList<BatteryReading>? = null
    readings.sortedBy { it.timestamp }.forEach { reading ->
        when (reading.kind) {
            ReadingKind.SESSION_START, ReadingKind.SAMPLE -> {
                val open = current ?: mutableListOf<BatteryReading>().also { current = it }
                open += reading
            }

            ReadingKind.SESSION_END -> {
                val open = current ?: return@forEach
                open += reading
                sessions += BatterySession(
                    startTime = open.first().timestamp,
                    endTime = reading.timestamp,
                    ended = true,
                    readings = open.toList()
                )
                current = null
            }
        }
    }
    current?.let { open ->
        sessions += BatterySession(
            startTime = open.first().timestamp,
            endTime = open.last().timestamp,
            ended = false,
            readings = open.toList()
        )
    }
    return sessions
}

/**
 * 计算若干段曲线合在一起的掉电统计；各段之间（断开期间）不计时、不计消耗。
 *
 * @return 没有任何实测点时为 null
 */
fun computeDrainStats(segments: List<List<LevelPoint>>): DrainStats? {
    val nonEmpty = segments.filter { it.isNotEmpty() }
    if (nonEmpty.isEmpty()) return null
    var consumed = 0
    var tracked = 0L
    nonEmpty.forEach { points ->
        points.zipWithNext { previous, next ->
            consumed += (previous.level - next.level).coerceAtLeast(0)
        }
        tracked += points.last().timestamp - points.first().timestamp
    }
    val first = nonEmpty.first().first()
    val last = nonEmpty.last().last()
    return DrainStats(
        startLevel = first.level,
        endLevel = last.level,
        startTime = first.timestamp,
        endTime = last.timestamp,
        consumed = consumed,
        trackedMillis = tracked,
        sampleCount = nonEmpty.sumOf { it.size }
    )
}

fun computeDrainStats(session: BatterySession, source: BatterySource): DrainStats? =
    computeDrainStats(listOf(session.points(source)))

/**
 * 只保留 [since] 之后的部分。范围起点落在连接中间时，用起点之前最后一个电量补一个起点，
 * 曲线从窗口左边缘开始而不是凭空缺一截（电量在两次上报之间保持不变）。
 *
 * @return 整段都在 [since] 之前时为 null
 */
fun BatterySession.clippedFrom(since: Long): BatterySession? {
    if (endTime < since) return null
    if (startTime >= since) return this
    val before = readings.filter { it.timestamp < since }
    val after = readings.filter { it.timestamp >= since }
    val carriedHeadset = before.lastOrNull { it.headsetLevel?.let(::isValidLevel) == true }?.headsetLevel
    val carriedPhone = before.lastOrNull { it.phoneLevel?.let(::isValidLevel) == true }?.phoneLevel
    val carried = BatteryReading(
        timestamp = since,
        kind = ReadingKind.SAMPLE,
        headsetLevel = carriedHeadset,
        phoneLevel = carriedPhone,
        volume = before.lastOrNull()?.volume ?: 0,
        isPlaying = before.lastOrNull()?.isPlaying ?: false
    )
    return copy(startTime = since, readings = listOf(carried) + after)
}

/**
 * 详情页曲线与统计。
 *
 * @param now 用于“最近 24 小时”的窗口起点
 */
fun buildBatteryOverview(readings: List<BatteryReading>, range: BatteryRange, now: Long): BatteryOverview {
    val sessions = splitBatterySessions(readings)
    val selected = when (range) {
        BatteryRange.LATEST_SESSION -> listOfNotNull(sessions.lastOrNull())
        BatteryRange.LAST_24_HOURS -> sessions.mapNotNull { it.clippedFrom(now - DAY_MILLIS) }
        BatteryRange.ALL -> sessions
    }
    if (selected.isEmpty()) return BatteryOverview.empty(range)
    return BatteryOverview(
        range = range,
        sessions = selected,
        headsetStats = computeDrainStats(selected.map { it.headset }),
        phoneStats = computeDrainStats(selected.map { it.phone }),
        windowStart = selected.first().startTime,
        windowEnd = selected.last().endTime,
        ongoing = !selected.last().ended
    )
}

/** 一台设备的全部电量记录，用于多设备对比 */
data class DeviceBatteryHistory(
    val key: String,
    val name: String,
    val readings: List<BatteryReading>
)

/**
 * 参与对比的一台耳机：取它最近一次有效连接。
 *
 * @param origin 对齐基准：这一段第一个耳机电量的时间
 */
data class ComparisonCandidate(
    val key: String,
    val name: String,
    val session: BatterySession,
    val stats: DrainStats?
) {
    val origin: Long = session.headset.firstOrNull()?.timestamp ?: session.startTime

    /** 以 [origin] 为 0 的相对时间曲线，用于“按开始时间对齐” */
    val relativeHeadset: List<LevelPoint> = session.headset.map { LevelPoint(it.timestamp - origin, it.level) }
}

/**
 * 选出用于对比的那一次连接：最近一次耳机电量有至少两个点、且跨度不少于 [MIN_RATE_WINDOW_MILLIS] 的连接；
 * 没有时退回最近一次有耳机电量的连接。
 *
 * Why: 测完一轮后耳机常会再短暂连一下，只取“最近一次”会把真正的测试挤掉。
 */
fun pickComparisonSession(sessions: List<BatterySession>): BatterySession? =
    sessions.lastOrNull { session ->
        session.headset.size >= 2 &&
            session.headset.last().timestamp - session.headset.first().timestamp >= MIN_RATE_WINDOW_MILLIS
    } ?: sessions.lastOrNull { it.headset.isNotEmpty() }

/**
 * 多设备对比候选，按最近一次对比连接的开始时间倒序（最近测试的在前）。
 * 没有任何耳机电量的设备（如车机）不参与。
 */
fun buildComparisonCandidates(devices: List<DeviceBatteryHistory>): List<ComparisonCandidate> =
    devices.mapNotNull { device ->
        val session = pickComparisonSession(splitBatterySessions(device.readings)) ?: return@mapNotNull null
        ComparisonCandidate(
            key = device.key,
            name = device.name,
            session = session,
            stats = computeDrainStats(session, BatterySource.HEADSET)
        )
    }.sortedByDescending { it.session.startTime }

/**
 * 去掉平台中段的重复点，只保留每段相同电量的首尾两点。
 *
 * 定时采样会产生大量相同电量的点；首尾两点已经决定了这一段水平线，去掉中间点后
 * 折线与单调插值曲线的形状都完全不变（平台上的切线恒为 0），绘制量却小得多。
 */
fun compressPlateaus(points: List<LevelPoint>): List<LevelPoint> {
    if (points.size <= 2) return points
    return points.filterIndexed { index, point ->
        index == 0 || index == points.lastIndex ||
            point.level != points[index - 1].level || point.level != points[index + 1].level
    }
}

/**
 * 时间上离 [timestamp] 最近的实测点下标；[points] 需按时间升序，为空时返回 -1
 */
fun nearestPointIndex(points: List<LevelPoint>, timestamp: Long): Int {
    if (points.isEmpty()) return -1
    var low = 0
    var high = points.lastIndex
    while (low < high) {
        val mid = (low + high) ushr 1
        if (points[mid].timestamp < timestamp) low = mid + 1 else high = mid
    }
    // low 是第一个不早于 timestamp 的点，再与前一个点比较距离
    if (low > 0 && timestamp - points[low - 1].timestamp <= points[low].timestamp - timestamp) return low - 1
    return low
}

/**
 * [timestamp] 时最近一次上报的电量：电量在两次上报之间保持不变；早于首点或晚于末点时为 null
 */
fun lastPointAtOrBefore(points: List<LevelPoint>, timestamp: Long): LevelPoint? {
    if (points.isEmpty() || timestamp < points.first().timestamp || timestamp > points.last().timestamp) return null
    var low = 0
    var high = points.lastIndex
    while (low < high) {
        val mid = (low + high + 1) ushr 1
        if (points[mid].timestamp <= timestamp) low = mid else high = mid - 1
    }
    return points[low]
}

fun isValidLevel(level: Int): Boolean = level in 0..FULL_LEVEL

const val MIN_RATE_WINDOW_MILLIS = 10 * 60_000L
private const val HOUR_MILLIS = 3_600_000L
private const val DAY_MILLIS = 24 * HOUR_MILLIS
private const val FULL_LEVEL = 100
