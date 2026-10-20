package com.xingkeqi.btlogger.service.earbud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 私有协议读取的实时状态。
 *
 * Why: 读取通道可能被 SOUNDPEATS App 占用、耳机也可能不回应，测试同事需要在详情页看到“正在读取”还是“连不上”，
 * 否则开了开关却没有数据时无从判断原因。
 */
sealed interface EarbudReaderStatus {

    /** 正在建立通道 */
    data object Connecting : EarbudReaderStatus

    /**
     * 通道已建立。
     *
     * @param levels 最近一次读到的电量
     * @param updatedAt 最近一次收到电量回复的时间；0 表示还没收到
     */
    data class Reading(val levels: EarbudLevels, val updatedAt: Long) : EarbudReaderStatus

    /**
     * 失败后等待重试。
     *
     * @param retryAt 下一次尝试的时间
     */
    data class Retrying(val failure: EarbudReaderFailure, val retryAt: Long) : EarbudReaderStatus
}

enum class EarbudReaderFailure {
    /** 通道连不上：多为 SOUNDPEATS App 正占用，或耳机不提供该服务 */
    CONNECT_FAILED,

    /** 通道已连上但连续几轮都没有电量回复：型号不支持该协议，或固件暂未响应 */
    NO_RESPONSE,

    /** 读取中途断开 */
    CONNECTION_LOST
}

/**
 * 读取状态按设备 MAC 保存；前台服务与界面在同一进程，直接共享 StateFlow，不经过数据库。
 */
object EarbudReaderStatusStore {

    private val _statuses = MutableStateFlow<Map<String, EarbudReaderStatus>>(emptyMap())
    val statuses: StateFlow<Map<String, EarbudReaderStatus>> = _statuses.asStateFlow()

    /** [status] 为 null 时移除（停止读取） */
    fun publish(address: String, status: EarbudReaderStatus?) {
        _statuses.update { current -> if (status == null) current - address else current + (address to status) }
    }
}
