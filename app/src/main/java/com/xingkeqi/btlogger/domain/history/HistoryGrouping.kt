package com.xingkeqi.btlogger.domain.history

/** 历史记录里参与合并判断的事件类别：只有连接、断开两种状态事件会被合并 */
enum class HistoryEventKind { CONNECTED, DISCONNECTED, OTHER }

/**
 * 历史记录列表中的一行：一条记录，或若干条原始记录合并成的一组。
 *
 * Why: 测试中会出现连续多条“已断开”，或一两秒内反复连接/断开十几次，每条占一行会把真正的连接记录挤出屏幕。
 * 合并只发生在展示层：组内保留全部原始记录（新→旧），展开后逐条显示，数据库与导出仍是逐条原始记录。
 */
sealed interface HistoryEntry<out T> {
    /** 组内全部记录，新→旧 */
    val items: List<T>

    /** 最新一条，列表排序与右侧时间以它为准 */
    val newest: T get() = items.first()

    /** 最旧一条，作为分组的稳定标识：组内追加新记录时它不变，展开状态不会丢 */
    val oldest: T get() = items.last()

    data class Single<T>(val item: T) : HistoryEntry<T> {
        override val items: List<T> get() = listOf(item)
    }

    /** 连续多条同一种状态事件，中间没有另一种状态（例如系统重复上报的“已断开”） */
    data class Repeated<T>(val kind: HistoryEventKind, override val items: List<T>) : HistoryEntry<T>

    /**
     * 短时间内反复连接、断开（连接抖动）。期间夹杂的编解码、电量记录也收进组内，展开时按原顺序显示。
     *
     * @param finalKind 抖动结束时的状态，即组内最后一条状态事件
     */
    data class Flapping<T>(
        override val items: List<T>,
        val connectCount: Int,
        val disconnectCount: Int,
        val finalKind: HistoryEventKind
    ) : HistoryEntry<T>
}

/** 相邻两条状态事件间隔不超过它，才算同一次抖动 */
const val FLAP_GAP_MILLIS = 10_000L

/** 一次抖动至少包含的状态事件条数；一次连接紧跟一次断开（两条）仍逐条显示 */
const val FLAP_MIN_STATE_EVENTS = 3

/**
 * 把按时间倒序（新→旧）排好的历史记录合并为列表行，返回同样新→旧的行。
 *
 * 规则（先抖动、后重复）：
 * 1. 连接抖动：相邻状态事件间隔都不超过 [flapGapMillis] 的一串状态事件，条数不少于 [flapMinStateEvents]
 *    且连接、断开都有，合成一组；首尾两条状态事件之间的其他记录一并收进组内。
 * 2. 连续重复：剩下的记录里，列表中相邻且同为“已连接”或同为“已断开”的记录合成一组，不限间隔。
 * 其余记录逐条成行。合并只看传入的列表，按类型筛选后的列表同样适用。
 */
fun <T> groupHistory(
    items: List<T>,
    timestampOf: (T) -> Long,
    kindOf: (T) -> HistoryEventKind,
    flapGapMillis: Long = FLAP_GAP_MILLIS,
    flapMinStateEvents: Int = FLAP_MIN_STATE_EVENTS
): List<HistoryEntry<T>> {
    if (items.isEmpty()) return emptyList()
    val ascending = items.asReversed()
    val kinds = ascending.map(kindOf)
    val flapEnds = findFlapRanges(ascending, kinds, timestampOf, flapGapMillis, flapMinStateEvents)
        .associate { it.first to it.last }

    val entries = ArrayList<HistoryEntry<T>>()
    // 正在累积的连续同类状态事件（旧→新）
    val run = ArrayList<T>()
    var runKind = HistoryEventKind.OTHER
    fun flushRun() {
        when (run.size) {
            0 -> Unit
            1 -> entries += HistoryEntry.Single(run.single())
            else -> entries += HistoryEntry.Repeated(runKind, run.asReversed().toList())
        }
        run.clear()
        runKind = HistoryEventKind.OTHER
    }

    var index = 0
    while (index < ascending.size) {
        val flapEnd = flapEnds[index]
        if (flapEnd != null) {
            flushRun()
            val flapKinds = kinds.subList(index, flapEnd + 1)
            entries += HistoryEntry.Flapping(
                items = ascending.subList(index, flapEnd + 1).asReversed().toList(),
                connectCount = flapKinds.count { it == HistoryEventKind.CONNECTED },
                disconnectCount = flapKinds.count { it == HistoryEventKind.DISCONNECTED },
                finalKind = kinds[flapEnd]
            )
            index = flapEnd + 1
            continue
        }
        val kind = kinds[index]
        if (kind == HistoryEventKind.OTHER || kind != runKind) flushRun()
        if (kind == HistoryEventKind.OTHER) {
            entries += HistoryEntry.Single(ascending[index])
        } else {
            run += ascending[index]
            runKind = kind
        }
        index++
    }
    flushRun()
    return entries.asReversed()
}

/** 在旧→新的列表里找出连接抖动，返回每次抖动首尾两条状态事件的下标区间 */
private fun <T> findFlapRanges(
    ascending: List<T>,
    kinds: List<HistoryEventKind>,
    timestampOf: (T) -> Long,
    flapGapMillis: Long,
    flapMinStateEvents: Int
): List<IntRange> {
    val ranges = ArrayList<IntRange>()
    val cluster = ArrayList<Int>()
    fun closeCluster() {
        val clusterKinds = cluster.map { kinds[it] }
        if (cluster.size >= flapMinStateEvents &&
            HistoryEventKind.CONNECTED in clusterKinds &&
            HistoryEventKind.DISCONNECTED in clusterKinds
        ) {
            ranges += cluster.first()..cluster.last()
        }
        cluster.clear()
    }
    kinds.forEachIndexed { index, kind ->
        if (kind == HistoryEventKind.OTHER) return@forEachIndexed
        val previous = cluster.lastOrNull()
        if (previous != null && timestampOf(ascending[index]) - timestampOf(ascending[previous]) > flapGapMillis) {
            closeCluster()
        }
        cluster += index
    }
    closeCluster()
    return ranges
}
