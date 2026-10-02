package com.xingkeqi.btlogger.utils

import java.util.concurrent.TimeUnit

/**
 * 获取“天 时 分 秒”格式的时间段字符串。
 *
 * Why:
 * 过去时长为 0 或不足 1 秒时返回空串，界面上会出现“本次连接：”后面空白的情况；
 * 超过 24 小时的长测也只显示小时数，可读性差。
 *
 * @param duration 时间戳的差值（毫秒），负数按 0 处理
 */
fun getDurationString(duration: Long): String {
    val safeDuration = duration.coerceAtLeast(0L)
    val days = TimeUnit.MILLISECONDS.toDays(safeDuration)
    val hours = TimeUnit.MILLISECONDS.toHours(safeDuration) % 24
    val minutes = TimeUnit.MILLISECONDS.toMinutes(safeDuration) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(safeDuration) % 60
    val parts = buildList {
        if (days > 0) add("$days 天")
        if (hours > 0) add("$hours 时")
        if (minutes > 0) add("$minutes 分")
        if (seconds > 0 || isEmpty()) add("$seconds 秒")
    }
    return parts.joinToString(" ")
}

/**
 * 紧凑时长格式（最多两个单位），用于统计卡片等空间有限的位置，如 “2h13m”“5m20s”“1d2h”
 */
fun getCompactDurationString(duration: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(duration.coerceAtLeast(0L))
    val days = totalSeconds / 86_400
    val hours = totalSeconds % 86_400 / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return when {
        days > 0 -> if (hours > 0) "${days}d${hours}h" else "${days}d"
        hours > 0 -> if (minutes > 0) "${hours}h${minutes}m" else "${hours}h"
        minutes > 0 -> if (seconds > 0) "${minutes}m${seconds}s" else "${minutes}m"
        else -> "${seconds}s"
    }
}
