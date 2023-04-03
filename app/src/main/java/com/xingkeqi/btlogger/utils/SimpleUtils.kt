package com.xingkeqi.btlogger.utils

import com.blankj.utilcode.constant.TimeConstants
import com.blankj.utilcode.util.TimeUtils
import java.util.concurrent.TimeUnit

/**
 * 获取时分秒格式的时间段字符串
 *
 * @param duration 时间戳的差值，表示时间差
 * @return
 */
fun getDurationString(duration: Long): String {
    val hours = TimeUnit.MILLISECONDS.toHours(duration)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(duration) % 60
    val seconds = TimeUnit.MILLISECONDS.toSeconds(duration) % 60
    return "${if (hours > 0) "$hours 时 " else ""}${if (minutes > 0) "$minutes 分 " else ""}${if (seconds > 0) "$seconds 秒" else ""}"
}

/**
 * Long long long triple
 *
 * @param pair 开始时间戳，结束时间戳
 * @return Triple 时，分，秒
 */
@Deprecated(
    "请使用统一格式的 getDurationString(Long)，计算时间间隔",
    replaceWith = ReplaceWith("getDurationString(Long)")
)
fun longLongLongTriple(pair: Pair<Long, Long>): Triple<Long, Long, Long> {
    val hours = TimeUtils.getTimeSpan(
        pair.second,
        pair.first,
        TimeConstants.HOUR
    )
    val minutes = TimeUtils.getTimeSpan(
        pair.second,
        pair.first,
        TimeConstants.MIN
    ) - (hours * 60)
    val seconds = TimeUtils.getTimeSpan(
        pair.second,
        pair.first,
        TimeConstants.SEC
    ) - (hours * 60 * 60) - (minutes * 60)
    return Triple(hours, minutes, seconds)
}


fun main() {
    println(getDurationString(3600000))
}