package com.xingkeqi.btlogger

import androidx.compose.ui.graphics.Color
import com.xingkeqi.btlogger.domain.battery.LevelPoint
import com.xingkeqi.btlogger.ui.components.ChartCursor
import com.xingkeqi.btlogger.ui.components.ChartSeries
import com.xingkeqi.btlogger.ui.components.retargetCursor
import com.xingkeqi.btlogger.ui.components.snapCursor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BatteryChartCursorTest {

    private val minute = 60_000L
    private val visible = 0L..120 * minute

    private fun series(key: String, emphasized: Boolean, vararg points: Pair<Long, Int>) = ChartSeries(
        key = key,
        label = key,
        color = Color.Black,
        segments = listOf(points.map { (at, level) -> LevelPoint(at * minute, level) }),
        emphasized = emphasized
    )

    // 右耳在 30 分时有一个点，比左耳 25 分的点离手指更近
    private fun ears(leftSelected: Boolean) = listOf(
        series("left", emphasized = leftSelected, 0L to 100, 25L to 95, 60L to 90),
        series("right", emphasized = !leftSelected, 0L to 100, 30L to 96, 60L to 92)
    )

    @Test
    fun snapCursor_staysOnSelectedEarEvenWhenOtherEarIsCloser() {
        assertEquals(ChartCursor("left", LevelPoint(25 * minute, 95)), snapCursor(ears(leftSelected = true), 29 * minute, visible))
        assertEquals(ChartCursor("right", LevelPoint(30 * minute, 96)), snapCursor(ears(leftSelected = false), 26 * minute, visible))
    }

    @Test
    fun snapCursor_withoutFocusPicksNearestAcrossSeries() {
        val compare = listOf(
            series("a", emphasized = false, 0L to 100, 25L to 95),
            series("b", emphasized = false, 0L to 100, 30L to 96)
        )

        assertEquals(ChartCursor("b", LevelPoint(30 * minute, 96)), snapCursor(compare, 29 * minute, visible))
    }

    @Test
    fun retargetCursor_movesToNewlySelectedEarAtSameTime() {
        val onLeft = ChartCursor("left", LevelPoint(25 * minute, 95))

        // 切换到右耳：游标留在 25 分附近，吸附到右耳最近的点
        assertEquals(ChartCursor("right", LevelPoint(30 * minute, 96)), retargetCursor(ears(leftSelected = false), onLeft, visible))
        // 仍选左耳、数据未变：不动
        assertEquals(onLeft, retargetCursor(ears(leftSelected = true), onLeft, visible))
    }

    @Test
    fun retargetCursor_clearsWhenNothingVisible() {
        val cursor = ChartCursor("left", LevelPoint(25 * minute, 95))

        assertNull(retargetCursor(ears(leftSelected = false), cursor, 200 * minute..300 * minute))
    }
}
