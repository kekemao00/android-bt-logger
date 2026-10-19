package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.domain.battery.ComparisonCandidate

/** 多耳机对比的横轴：按各自开始时间对齐（比续航），或按实际时间叠加（看同一时段） */
enum class CompareAlignment { RELATIVE, ABSOLUTE }

/**
 * 电量对比页状态。
 *
 * @param candidates 有耳机电量的设备，最近测试的在前
 * @param slots 已选设备 -> 颜色槽位；槽位跟随设备，取消其他设备时剩下的曲线不会变色
 */
data class BatteryComparisonUiState(
    val loading: Boolean = true,
    val candidates: List<ComparisonCandidate> = emptyList(),
    val slots: Map<String, Int> = emptyMap(),
    val alignment: CompareAlignment = CompareAlignment.RELATIVE
) {
    val selected: List<ComparisonCandidate> get() = candidates.filter { it.key in slots }
}

/** 对比图最多同时画几条曲线：再多颜色就难以区分 */
const val MAX_COMPARE_SERIES = 6
