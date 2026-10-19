package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.domain.battery.CurvePoint
import com.xingkeqi.btlogger.domain.battery.monotoneCubicSegments
import com.xingkeqi.btlogger.domain.battery.pointAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min

class MonotoneCurveTest {

    /** 典型的耳机电量：10% 一档的台阶，中间夹着定时采样的平台 */
    private val steps = listOf(
        CurvePoint(0.0, 100.0),
        CurvePoint(10.0, 100.0),
        CurvePoint(14.0, 90.0),
        CurvePoint(40.0, 90.0),
        CurvePoint(42.0, 80.0),
        CurvePoint(43.0, 70.0),
        CurvePoint(90.0, 60.0),
        CurvePoint(95.0, 65.0)
    )

    @Test
    fun segments_passThroughEveryPoint() {
        val segments = monotoneCubicSegments(steps)

        assertEquals(steps.size - 1, segments.size)
        segments.forEachIndexed { index, segment ->
            assertEquals(steps[index], segment.start)
            assertEquals(steps[index + 1], segment.end)
        }
    }

    @Test
    fun segments_neverOvershootNeighbours() {
        monotoneCubicSegments(steps).forEach { segment ->
            val low = min(segment.start.y, segment.end.y)
            val high = max(segment.start.y, segment.end.y)
            for (step in 0..100) {
                val point = segment.pointAt(step / 100.0)
                assertTrue("y=${point.y} 超出 [$low, $high]", point.y in (low - 1e-9)..(high + 1e-9))
                assertTrue(point.x in (segment.start.x - 1e-9)..(segment.end.x + 1e-9))
            }
        }
    }

    @Test
    fun plateaus_stayFlat() {
        val plateau = monotoneCubicSegments(steps)[2]

        assertEquals(90.0, plateau.control1.y, 1e-9)
        assertEquals(90.0, plateau.control2.y, 1e-9)
    }

    @Test
    fun affineTransform_givesSameCurve() {
        // 映射到屏幕坐标（缩放 + 平移 + 翻转 y 轴）后再算，应与先算后映射一致
        val toScreen = { p: CurvePoint -> CurvePoint(12.0 + p.x * 3.5, 200.0 - p.y * 1.8) }
        val direct = monotoneCubicSegments(steps.map(toScreen))
        val mapped = monotoneCubicSegments(steps).map {
            listOf(toScreen(it.start), toScreen(it.control1), toScreen(it.control2), toScreen(it.end))
        }

        direct.zip(mapped).forEach { (segment, expected) ->
            val actual = listOf(segment.start, segment.control1, segment.control2, segment.end)
            actual.zip(expected).forEach { (a, e) ->
                assertEquals(e.x, a.x, 1e-6)
                assertEquals(e.y, a.y, 1e-6)
            }
        }
    }

    @Test
    fun edgeCases() {
        assertTrue(monotoneCubicSegments(emptyList()).isEmpty())
        assertTrue(monotoneCubicSegments(listOf(CurvePoint(0.0, 50.0))).isEmpty())

        val line = monotoneCubicSegments(listOf(CurvePoint(0.0, 100.0), CurvePoint(30.0, 70.0))).single()
        assertEquals(90.0, line.control1.y, 1e-9)
        assertEquals(80.0, line.control2.y, 1e-9)

        // 同一时刻两条记录取后一条
        val deduped = monotoneCubicSegments(
            listOf(CurvePoint(0.0, 100.0), CurvePoint(5.0, 95.0), CurvePoint(5.0, 90.0), CurvePoint(10.0, 80.0))
        )
        assertEquals(2, deduped.size)
        assertEquals(90.0, deduped[0].end.y, 1e-9)
    }
}
