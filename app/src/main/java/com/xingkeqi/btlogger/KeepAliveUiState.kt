package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.domain.keepalive.KeepAliveBanner
import com.xingkeqi.btlogger.domain.keepalive.RomVendor
import com.xingkeqi.btlogger.domain.keepalive.ServiceGap
import com.xingkeqi.btlogger.utils.KeepAliveSystemStatus

/**
 * “后台运行设置”面板与列表页提示条的状态。
 *
 * @param recentGaps 最近的后台中断，按开始时间倒序
 * @param banner 列表页顶部要显示的提示；null 时不显示
 */
data class KeepAliveUiState(
    val keepScreenOn: Boolean = true,
    val status: KeepAliveSystemStatus = KeepAliveSystemStatus(),
    val vendor: RomVendor = RomVendor.OTHER,
    val recentGaps: List<ServiceGap> = emptyList(),
    val banner: KeepAliveBanner? = null
)
