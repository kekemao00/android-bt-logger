package com.xingkeqi.btlogger.domain.battery

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/** 曲线坐标（数据坐标或屏幕坐标均可） */
data class CurvePoint(val x: Double, val y: Double)

/** 一段三次贝塞尔：从 [start] 经控制点 [control1]、[control2] 到 [end] */
data class CubicSegment(
    val start: CurvePoint,
    val control1: CurvePoint,
    val control2: CurvePoint,
    val end: CurvePoint
)

/**
 * 单调分段三次贝塞尔曲线（Steffen 单调插值，与 d3 `curveMonotoneX` 同一方法）。
 *
 * Why:
 * 电量曲线需要平滑好看，但不能“编造”数据：普通样条或 Catmull-Rom 在台阶处会冲过实测值，
 * 画出电量回升或低于真实最低点的假象。这里每两个相邻实测点之间用一段三次贝塞尔连接：
 * - 曲线严格经过每个实测点；
 * - 相邻两点之间单调，取值不超出这两点的电量范围（控制点被限制在两点之间），不会过冲；
 * - 电量不变的平台保持水平，切线为 0。
 *
 * 控制点的横坐标固定在三等分处，因此 x 随参数线性变化；对坐标做平移、缩放（含翻转 y 轴）后再计算，
 * 与先计算再变换得到的是同一条曲线，可以直接在屏幕坐标上调用。
 *
 * @param points 按 x 升序；x 相同的点只保留最后一个
 * @return 少于两个点时为空
 */
fun monotoneCubicSegments(points: List<CurvePoint>): List<CubicSegment> {
    val sorted = dedupeSameX(points)
    val n = sorted.size
    if (n < 2) return emptyList()

    val slopes = DoubleArray(n - 1) { i ->
        (sorted[i + 1].y - sorted[i].y) / (sorted[i + 1].x - sorted[i].x)
    }
    val tangents = DoubleArray(n)
    for (i in 1 until n - 1) {
        val h0 = sorted[i].x - sorted[i - 1].x
        val h1 = sorted[i + 1].x - sorted[i].x
        val s0 = slopes[i - 1]
        val s1 = slopes[i]
        val p = (s0 * h1 + s1 * h0) / (h0 + h1)
        tangents[i] = (sign(s0) + sign(s1)) * min(min(abs(s0), abs(s1)), 0.5 * abs(p))
    }
    if (n == 2) {
        tangents[0] = slopes[0]
        tangents[1] = slopes[0]
    } else {
        // 端点切线按相邻内部切线外推，并保持在单调范围内
        tangents[0] = endpointTangent(slopes[0], tangents[1])
        tangents[n - 1] = endpointTangent(slopes[n - 2], tangents[n - 2])
    }

    return (0 until n - 1).map { i ->
        val start = sorted[i]
        val end = sorted[i + 1]
        val third = (end.x - start.x) / 3.0
        CubicSegment(
            start = start,
            control1 = CurvePoint(start.x + third, start.y + tangents[i] * third),
            control2 = CurvePoint(end.x - third, end.y - tangents[i + 1] * third),
            end = end
        )
    }
}

/**
 * 在三次贝塞尔上取参数 t 处的点，用于测试与命中检测
 */
fun CubicSegment.pointAt(t: Double): CurvePoint {
    val u = 1 - t
    val a = u * u * u
    val b = 3 * u * u * t
    val c = 3 * u * t * t
    val d = t * t * t
    return CurvePoint(
        x = a * start.x + b * control1.x + c * control2.x + d * end.x,
        y = a * start.y + b * control1.y + c * control2.y + d * end.y
    )
}

/**
 * d3 的端点切线 `(3 * slope - neighbor) / 2`，再限制在 [0, 3 * slope] 内保证单调
 */
private fun endpointTangent(slope: Double, neighborTangent: Double): Double {
    if (slope == 0.0) return 0.0
    val tangent = (3 * slope - neighborTangent) / 2
    return if (sign(tangent) != sign(slope)) 0.0 else if (abs(tangent) > 3 * abs(slope)) 3 * slope else tangent
}

private fun dedupeSameX(points: List<CurvePoint>): List<CurvePoint> {
    val result = ArrayList<CurvePoint>(points.size)
    points.forEach { point ->
        if (result.isNotEmpty() && result.last().x >= point.x) {
            // 同一时刻的多条记录取最后一条；乱序点直接丢弃，避免曲线回折
            if (result.last().x == point.x) result[result.lastIndex] = point
        } else {
            result += point
        }
    }
    return result
}
