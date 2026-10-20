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
 * 记录中与耳机电量相关的字段；电量为 null 表示该条记录没有拿到有效值（如耳机未上报）。
 *
 * 分析与曲线只看 [headsetLevel]：按左耳或右耳分析时先用 [forChannel] 把对应电量放到这个字段上。
 *
 * @param headsetLevel 系统蓝牙上报的耳机电量（多为主耳）
 * @param leftLevel SOUNDPEATS 私有协议读到的左耳电量；未开启读取或左耳不在线时为 null
 * @param rightLevel 同上，右耳
 * @param caseLevel 同上，充电仓；只用于导出，不参与曲线
 */
data class BatteryReading(
    val timestamp: Long,
    val kind: ReadingKind,
    val headsetLevel: Int?,
    val volume: Int = 0,
    val isPlaying: Boolean = false,
    val leftLevel: Int? = null,
    val rightLevel: Int? = null,
    val caseLevel: Int? = null
)

/**
 * 电量来源，声明顺序即界面上的排列顺序。
 *
 * Why: 系统蓝牙只上报一个电量（SOUNDPEATS 耳机报的是主耳）；开启私有协议读取后还有左耳、右耳各自的电量，
 * 两只耳机掉电快慢不同，续航测试要分开统计。三种来源共用同一套切分、测速与曲线代码，只是取值字段不同。
 */
enum class BatteryChannel { LEFT, RIGHT, HEADSET }

/** 这条记录在 [channel] 上的有效电量 */
fun BatteryReading.levelOf(channel: BatteryChannel): Int? = when (channel) {
    BatteryChannel.HEADSET -> headsetLevel
    BatteryChannel.LEFT -> leftLevel
    BatteryChannel.RIGHT -> rightLevel
}?.takeIf(::isValidLevel)

/** 把 [channel] 的电量放到 [BatteryReading.headsetLevel] 上，供只认这个字段的分析代码使用 */
fun BatteryReading.forChannel(channel: BatteryChannel): BatteryReading =
    if (channel == BatteryChannel.HEADSET) this else copy(headsetLevel = levelOf(channel))

/**
 * 记录里出现过有效电量的来源，按 [BatteryChannel] 的顺序；一个都没有时只有系统上报（界面显示“没有上报电量”）。
 */
fun availableChannels(readings: List<BatteryReading>): List<BatteryChannel> =
    BatteryChannel.entries
        .filter { channel -> readings.any { it.levelOf(channel) != null } }
        .ifEmpty { listOf(BatteryChannel.HEADSET) }

/** 曲线上的一个实测点 */
data class LevelPoint(val timestamp: Long, val level: Int)

/**
 * 一次连接：最近一次断开后的首条记录到对应的断开记录。
 *
 * 耳机电量只在变化时记录，最后一个电量一直有效到 [endTime]：已断开时是断开时间，
 * 仍在连接中时是计算时的当前时间（未提供当前时间则为最后一条记录的时间）。
 *
 * @param ended 是否已断开（连接记录缺少对应的断开、被下一次连接截断时也视为已结束）
 * @param connectedAt 实际连接时间；按时间范围裁剪后 [startTime] 是范围起点，这里仍保留原值
 * @param carriedSince 按时间范围裁剪时，起点沿用的电量实际开始的时间；未裁剪时为 null
 */
data class BatterySession(
    val startTime: Long,
    val endTime: Long,
    val ended: Boolean,
    val readings: List<BatteryReading>,
    val connectedAt: Long = startTime,
    val carriedSince: Long? = null
) {
    /** 耳机电量实测点，按时间升序 */
    val headset: List<LevelPoint> = readings.mapNotNull { reading ->
        reading.headsetLevel?.takeIf(::isValidLevel)?.let { LevelPoint(reading.timestamp, it) }
    }

    /**
     * 最后一个电量保持到何时。
     *
     * @param stopAtUnknown 为 true 时保持到其后第一条明确未知的记录（左右耳放回充电仓或读取通道断开），
     *   之后不再知道该耳机电量；为 false 时一直保持到 [endTime]（系统上报的未知只是还没上报）
     */
    fun holdEnd(stopAtUnknown: Boolean): Long {
        if (!stopAtUnknown) return endTime
        // 整段都不知道左右耳电量：没有可保持的电量，也不算“仍在记录”
        val last = headset.lastOrNull() ?: return startTime
        return readings.firstOrNull { it.timestamp > last.timestamp && it.headsetLevel == null }?.timestamp ?: endTime
    }
}

/**
 * 掉电统计。
 *
 * 平均掉电速率 = 测速区间内的下降 ÷ 测速区间时长，见 [computeDrainStats]。
 * 测速区间不足 [MIN_RATE_WINDOW_MILLIS] 或没有下降时速率为 null，界面显示“数据不足”，
 * 避免开头几分钟给出离谱的估算。
 *
 * @param consumed 累计消耗的百分点（全部下降之和，中途回升不抵扣）
 * @param trackedMillis 记录时长：各段首尾实测点的时间差之和，不含断开期间
 * @param rateDrop 测速区间内的下降百分点
 * @param rateWindowMillis 测速区间时长
 */
data class DrainStats(
    val startLevel: Int,
    val endLevel: Int,
    val startTime: Long,
    val endTime: Long,
    val consumed: Int,
    val trackedMillis: Long,
    val sampleCount: Int,
    val rateDrop: Int = consumed,
    val rateWindowMillis: Long = trackedMillis
) {
    /** 平均掉电速率（%/小时） */
    val ratePerHour: Double? =
        if (rateWindowMillis >= MIN_RATE_WINDOW_MILLIS && rateDrop > 0) {
            rateDrop * HOUR_MILLIS.toDouble() / rateWindowMillis
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
 * 详情页耳机电量概览：所选范围内的连接（已按范围裁剪）与掉电统计。
 *
 * @param ongoing 最后一段仍在连接中且电量仍然已知，此时展示“预计剩余”，否则展示“满电续航”
 * @param holdStopsAtUnknown 曲线末端的保持段是否在明确未知处结束，见 [BatterySession.holdEnd]
 */
data class BatteryOverview(
    val range: BatteryRange,
    val sessions: List<BatterySession>,
    val stats: DrainStats?,
    val windowStart: Long,
    val windowEnd: Long,
    val ongoing: Boolean,
    val holdStopsAtUnknown: Boolean = false
) {
    val hasData: Boolean get() = sessions.any { it.headset.isNotEmpty() }

    fun holdEndOf(session: BatterySession): Long = session.holdEnd(holdStopsAtUnknown)

    /**
     * 仍在连接中时的预计剩余：按平均速率从最后一次掉电算起，再扣掉此后已经过去的时间。
     *
     * Why: 只在电量变化时记录，最后一次掉电之后可能已过去很久；直接用“结束电量 ÷ 速率”会把这段时间重复算进剩余。
     * 已超出估算（耳机长时间停在同一档）时为 null，界面显示“数据不足”而不是 0。
     */
    val estimatedRemainingMillis: Long?
        get() {
            if (!ongoing) return null
            val stats = stats ?: return null
            val remaining = stats.estimatedRemainingMillis ?: return null
            return (remaining - (windowEnd - stats.endTime).coerceAtLeast(0L)).takeIf { it > 0L }
        }

    companion object {
        fun empty(range: BatteryRange) = BatteryOverview(
            range = range,
            sessions = emptyList(),
            stats = null,
            windowStart = 0L,
            windowEnd = 0L,
            ongoing = false
        )
    }
}

/**
 * 按时间顺序切分连接。
 *
 * - 紧挨着的多条“已连接”（广播与服务启动同步几乎同时写入）视为同一次连接；与上一条记录相隔超过
 *   [SESSION_MERGE_WINDOW_MILLIS] 的“已连接”说明上一次断开没记下来（如服务被系统杀掉），上一段到此结束，
 *   否则下一次连接会并进上一段，跨过整段断开期间计算掉电；
 * - 没有对应连接记录的“已断开”不构成一次连接；
 * - 连接与断开之间的任意事件都是采样点，断开记录本身带有断开时的电量，作为这一段的终点。
 *
 * @param readings 任意顺序；时间相同的记录保持传入顺序
 * @param now 当前时间；最后一段仍在连接中时以它为结束时间（电量没变化就不会有新记录，但连接仍在继续）
 */
fun splitBatterySessions(readings: List<BatteryReading>, now: Long? = null): List<BatterySession> {
    val sessions = mutableListOf<BatterySession>()
    var current: MutableList<BatteryReading>? = null
    readings.sortedBy { it.timestamp }.forEach { reading ->
        when (reading.kind) {
            ReadingKind.SESSION_START, ReadingKind.SAMPLE -> {
                val previous = current
                if (reading.kind == ReadingKind.SESSION_START && previous != null &&
                    reading.timestamp - previous.last().timestamp > SESSION_MERGE_WINDOW_MILLIS
                ) {
                    sessions += BatterySession(
                        startTime = previous.first().timestamp,
                        endTime = previous.last().timestamp,
                        ended = true,
                        readings = previous.toList()
                    )
                    current = null
                }
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
            endTime = maxOf(open.last().timestamp, now ?: Long.MIN_VALUE),
            ended = false,
            readings = open.toList()
        )
    }
    return sessions
}

/**
 * 计算若干段曲线合在一起的掉电统计；各段之间（断开期间）不计时、不计消耗。
 *
 * 测速区间：一段里有至少两次电量变化、且首末两次变化相隔不少于 [MIN_RATE_WINDOW_MILLIS] 时，
 * 只取“第一次变化 → 最后一次变化”；否则取整段。区间内电量回升（充电）的那几段既不计下降也不计时长。
 *
 * Why: 耳机多为 10% 一档上报，连接时刻（或时间范围的起点）往往落在某一档的中间，
 * 用整段计算会把这一档的 10% 记在不到一档的时间上，速率偏高；最后一次变化之后到断开的时间又没有下降，
 * 速率偏低。两台相同的耳机只因连接时机不同就会算出不同的续航。从一次跳档量到另一次跳档，
 * 两端都落在档位边界上，才是这段时间真实的掉电速度。
 *
 * @return 没有任何实测点时为 null
 */
fun computeDrainStats(segments: List<List<LevelPoint>>): DrainStats? {
    val nonEmpty = segments.filter { it.isNotEmpty() }
    if (nonEmpty.isEmpty()) return null
    var consumed = 0
    var tracked = 0L
    var rateDrop = 0
    var rateWindow = 0L
    nonEmpty.forEach { points ->
        points.zipWithNext { previous, next ->
            consumed += (previous.level - next.level).coerceAtLeast(0)
        }
        tracked += points.last().timestamp - points.first().timestamp

        val changes = points.indices.filter { it > 0 && points[it].level != points[it - 1].level }
        val window = if (
            changes.size >= 2 &&
            points[changes.last()].timestamp - points[changes.first()].timestamp >= MIN_RATE_WINDOW_MILLIS
        ) {
            points.subList(changes.first(), changes.last() + 1)
        } else {
            points
        }
        window.zipWithNext { previous, next ->
            if (next.level <= previous.level) {
                rateDrop += previous.level - next.level
                rateWindow += next.timestamp - previous.timestamp
            }
        }
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
        sampleCount = nonEmpty.sumOf { it.size },
        rateDrop = rateDrop,
        rateWindowMillis = rateWindow
    )
}

/**
 * 只保留 [since] 之后的部分。范围起点落在连接中间时，用起点之前最后一个电量补一个起点，
 * 曲线从窗口左边缘开始而不是凭空缺一截（电量在两次上报之间保持不变）。
 *
 * @param stopAtUnknown 与 [BatterySession.holdEnd] 一致：左右耳在起点之前已明确未知（放回充电仓）时不补起点
 * @return 整段都在 [since] 之前时为 null
 */
fun BatterySession.clippedFrom(since: Long, stopAtUnknown: Boolean = false): BatterySession? {
    if (endTime < since) return null
    if (startTime >= since) return this
    val before = readings.filter { it.timestamp < since }
    val after = readings.filter { it.timestamp >= since }
    val beforeLevels = before.filter { it.headsetLevel?.let(::isValidLevel) == true }
    val carriedHeadset = beforeLevels.lastOrNull()
        ?.takeIf { last -> !stopAtUnknown || before.none { it.timestamp > last.timestamp && it.headsetLevel == null } }
        ?.headsetLevel
    // 沿用的电量从哪一刻开始：往前找到同一档连续记录的第一条，游标读数“上一档持续”据此计算
    val carriedSince = carriedHeadset?.let { level ->
        beforeLevels.asReversed().takeWhile { it.headsetLevel == level }.lastOrNull()?.timestamp
    }
    val carried = BatteryReading(
        timestamp = since,
        kind = ReadingKind.SAMPLE,
        headsetLevel = carriedHeadset,
        volume = before.lastOrNull()?.volume ?: 0,
        isPlaying = before.lastOrNull()?.isPlaying ?: false
    )
    return copy(startTime = since, readings = listOf(carried) + after, carriedSince = carriedSince)
}

/**
 * 详情页曲线与统计。
 *
 * @param now 用于“最近 24 小时”的窗口起点，也是仍在连接中那一段的结束时间
 * @param holdStopsAtUnknown 左右耳传 true：放回充电仓后不再画保持段，也不再估算剩余
 */
fun buildBatteryOverview(
    readings: List<BatteryReading>,
    range: BatteryRange,
    now: Long,
    holdStopsAtUnknown: Boolean = false
): BatteryOverview {
    val sessions = splitBatterySessions(readings, now)
    val selected = when (range) {
        BatteryRange.LATEST_SESSION -> listOfNotNull(sessions.lastOrNull())
        BatteryRange.LAST_24_HOURS -> sessions.mapNotNull { it.clippedFrom(now - DAY_MILLIS, holdStopsAtUnknown) }
        BatteryRange.ALL -> sessions
    }
    if (selected.isEmpty()) return BatteryOverview.empty(range)
    // 横轴只覆盖有耳机电量的连接，前后没上报电量的连接不在图上留出空白
    val withData = selected.filter { it.headset.isNotEmpty() }.ifEmpty { selected }
    return BatteryOverview(
        range = range,
        sessions = selected,
        stats = computeDrainStats(selected.map { it.headset }),
        windowStart = withData.first().startTime,
        windowEnd = withData.last().endTime,
        ongoing = selected.last().let { !it.ended && it.holdEnd(holdStopsAtUnknown) == it.endTime },
        holdStopsAtUnknown = holdStopsAtUnknown
    )
}

/**
 * 详情页电量卡片：当前来源的概览，以及曲线上要叠加的其他来源。
 *
 * @param channels 有数据的来源；只有一个时界面不显示切换
 * @param overviews 至少包含 [selected] 与 [plotted] 中的来源
 */
data class BatteryChannelOverview(
    val selected: BatteryChannel,
    val channels: List<BatteryChannel>,
    val overviews: Map<BatteryChannel, BatteryOverview>
) {
    val overview: BatteryOverview
        get() = overviews[selected] ?: BatteryOverview.empty(overviews.values.firstOrNull()?.range ?: BatteryRange.LATEST_SESSION)

    /**
     * 曲线上画哪些来源：选中左耳或右耳时左右耳叠加，两只耳机的掉电差异一眼可见；
     * 选中系统上报时只画它（它通常与主耳重合，叠在一起反而难分辨）。所选范围内没有数据的来源不画。
     */
    val plotted: List<BatteryChannel>
        get() {
            val candidates = if (selected == BatteryChannel.HEADSET) {
                listOf(BatteryChannel.HEADSET)
            } else {
                channels.filter { it != BatteryChannel.HEADSET }
            }
            return candidates.filter { overviews[it]?.hasData == true }
        }

    companion object {
        fun empty(range: BatteryRange) = BatteryChannelOverview(
            selected = BatteryChannel.HEADSET,
            channels = listOf(BatteryChannel.HEADSET),
            overviews = mapOf(BatteryChannel.HEADSET to BatteryOverview.empty(range))
        )
    }
}

/**
 * 按来源分别计算详情页概览。
 *
 * @param requested 用户选择的来源；为 null 或记录里没有该来源时，取所选范围内第一个有数据的来源
 *   （有左右耳数据时默认左耳）。Why: 只有早先几次连接开过左右耳读取时，默认选左耳会让“本次连接”显示为空。
 */
fun buildBatteryChannelOverview(
    readings: List<BatteryReading>,
    range: BatteryRange,
    now: Long,
    requested: BatteryChannel? = null
): BatteryChannelOverview {
    val channels = availableChannels(readings)
    val overviews = HashMap<BatteryChannel, BatteryOverview>()
    fun overviewOf(channel: BatteryChannel) = overviews.getOrPut(channel) {
        buildBatteryOverview(
            readings = readings.map { it.forChannel(channel) },
            range = range,
            now = now,
            holdStopsAtUnknown = channel != BatteryChannel.HEADSET
        )
    }
    val selected = requested?.takeIf { it in channels }
        ?: channels.firstOrNull { overviewOf(it).hasData }
        ?: channels.first()
    val needed = if (selected == BatteryChannel.HEADSET) {
        listOf(BatteryChannel.HEADSET)
    } else {
        channels.filter { it != BatteryChannel.HEADSET }
    }
    return BatteryChannelOverview(
        selected = selected,
        channels = channels,
        overviews = needed.associateWith(::overviewOf)
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

    /** 这一段的结束时间（相对 [origin]），最后一个电量保持到这里 */
    val relativeEnd: Long = session.endTime - origin
}

/**
 * 选出用于对比的那一次连接，依次取最近一次：
 * 能算出掉电速率的连接 → 耳机电量跨度不少于 [MIN_RATE_WINDOW_MILLIS] 的连接 → 有耳机电量的连接。
 *
 * Why: 测完一轮后耳机常会再连一会儿（哪怕十几分钟、电量没掉），只取“最近一次”会把真正的测试挤掉。
 */
fun pickComparisonSession(sessions: List<BatterySession>): BatterySession? =
    sessions.lastOrNull { computeDrainStats(listOf(it.headset))?.ratePerHour != null }
        ?: sessions.lastOrNull { session ->
            session.headset.size >= 2 &&
                session.headset.last().timestamp - session.headset.first().timestamp >= MIN_RATE_WINDOW_MILLIS
        }
        ?: sessions.lastOrNull { it.headset.isNotEmpty() }

/**
 * 多设备对比候选，按最近一次对比连接的开始时间倒序（最近测试的在前）。
 * 没有任何耳机电量的设备（如车机）不参与。
 *
 * @param now 仍在连接中的那一段以它为结束时间
 */
fun buildComparisonCandidates(devices: List<DeviceBatteryHistory>, now: Long? = null): List<ComparisonCandidate> =
    devices.mapNotNull { device ->
        val session = pickComparisonSession(splitBatterySessions(device.readings, now)) ?: return@mapNotNull null
        ComparisonCandidate(
            key = device.key,
            name = device.name,
            session = session,
            stats = computeDrainStats(listOf(session.headset))
        )
    }.sortedByDescending { it.session.startTime }

/**
 * 去掉平台中段的重复点，只保留每段相同电量的首尾两点。
 *
 * 连接、编解码切换等记录也带着当时的耳机电量，会在平台上留下重复点；首尾两点已经决定了这一段水平线，
 * 去掉中间点后折线与单调插值曲线的形状都完全不变（平台上的切线恒为 0），绘制量却小得多。
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

/**
 * 实测点相对上一档电量的变化。
 *
 * @param delta 与前一个实测点的电量差；0 表示仍停在同一档
 * @param millis 上一档电量持续的时长；[delta] 为 0 时是当前这一档已持续的时长
 */
data class LevelStep(val delta: Int, val millis: Long)

/**
 * 游标读数用：耳机多为 10% 一档上报，“这一档用了多久”比相邻两点的时间差更能说明掉电快慢。
 *
 * @param points 按时间升序；平台中段可以已被 [compressPlateaus] 去掉
 * @param firstRunStart 首个点所在那一档实际开始的时间（按时间范围裁剪时首点是沿用的电量，见 [BatterySession.carriedSince]）
 * @return 首个点或下标越界时为 null
 */
fun levelStepAt(points: List<LevelPoint>, index: Int, firstRunStart: Long? = null): LevelStep? {
    if (index !in 1..points.lastIndex) return null
    val current = points[index]
    val previous = points[index - 1]
    var runStart = index - 1
    while (runStart > 0 && points[runStart - 1].level == previous.level) runStart--
    val since = if (runStart == 0 && firstRunStart != null) minOf(firstRunStart, points[0].timestamp) else points[runStart].timestamp
    return LevelStep(delta = current.level - previous.level, millis = current.timestamp - since)
}

fun isValidLevel(level: Int): Boolean = level in 0..FULL_LEVEL

const val MIN_RATE_WINDOW_MILLIS = 10 * 60_000L

/** 相隔不超过它的两条“已连接”视为同一次连接（广播与服务启动同步几乎同时写入） */
const val SESSION_MERGE_WINDOW_MILLIS = 2 * 60_000L
private const val HOUR_MILLIS = 3_600_000L
private const val DAY_MILLIS = 24 * HOUR_MILLIS
private const val FULL_LEVEL = 100
