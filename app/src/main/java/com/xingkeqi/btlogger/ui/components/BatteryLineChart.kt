package com.xingkeqi.btlogger.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xingkeqi.btlogger.domain.battery.CurvePoint
import com.xingkeqi.btlogger.domain.battery.LevelPoint
import com.xingkeqi.btlogger.domain.battery.lastPointAtOrBefore
import com.xingkeqi.btlogger.domain.battery.monotoneCubicSegments
import com.xingkeqi.btlogger.domain.battery.nearestPointIndex
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.EaseOutCubic
import com.xingkeqi.btlogger.ui.theme.MonoTextStyle
import com.xingkeqi.btlogger.ui.theme.Springs
import com.xingkeqi.btlogger.utils.getCompactDurationString
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.ceil

/**
 * 一条耳机电量曲线。
 *
 * @param segments 每段是一次连接，段与段之间（断开期间）不连线；段内按时间升序
 * @param emphasized 是否画面积渐变；单条曲线时打开，多条叠加时关闭避免互相遮挡
 */
@Immutable
data class ChartSeries(
    val key: String,
    val label: String,
    val color: Color,
    val segments: List<List<LevelPoint>>,
    val emphasized: Boolean = false
) {
    /** 全部实测点，按时间升序（各段时间不重叠） */
    val points: List<LevelPoint> = segments.flatten()
}

/** 横轴含义：实际时间，或相对各自开始时间的时长 */
enum class ChartTimeAxis { ABSOLUTE, RELATIVE }

/** 游标吸附到的实测点 */
@Immutable
data class ChartCursor(val seriesKey: String, val point: LevelPoint)

/**
 * 曲线的缩放范围与游标，由调用方持有，便于在图表外展示读数、在切换数据时重置。
 */
@Stable
class BatteryChartState {
    /** 可见范围在整个横轴上的起止比例（0..1） */
    var viewStart by mutableDoubleStateOf(0.0)
        private set
    var viewEnd by mutableDoubleStateOf(1.0)
        private set

    /** null 表示没有选中任何实测点 */
    var cursor by mutableStateOf<ChartCursor?>(null)
        internal set

    val isZoomed: Boolean get() = viewEnd - viewStart < 1.0 - ZOOM_EPSILON

    fun clearCursor() {
        cursor = null
    }

    /** 弹簧动画回到完整范围 */
    suspend fun animateResetZoom() {
        val fromStart = viewStart
        val fromEnd = viewEnd
        animate(0f, 1f, animationSpec = Springs.smooth()) { progress, _ ->
            setView(fromStart * (1 - progress), fromEnd + (1.0 - fromEnd) * progress)
        }
        setView(0.0, 1.0)
    }

    /**
     * 双指缩放与平移：以手指中心为锚点缩放，再按中心的位移平移
     *
     * @param anchor 手指中心在绘图区内的比例位置
     * @param minSpan 允许的最小可见比例
     */
    internal fun transform(zoom: Float, panFraction: Double, anchor: Double, minSpan: Double) {
        val span = viewEnd - viewStart
        val safeZoom = if (zoom.isFinite() && zoom > 0f) zoom.toDouble() else 1.0
        val newSpan = (span / safeZoom).coerceIn(minSpan.coerceAtMost(1.0), 1.0)
        val clampedAnchor = anchor.coerceIn(0.0, 1.0)
        val anchorValue = viewStart + clampedAnchor * span
        val start = anchorValue - clampedAnchor * newSpan - panFraction * newSpan
        setView(start, start + newSpan)
    }

    private fun setView(start: Double, end: Double) {
        val span = (end - start).coerceIn(ZOOM_EPSILON, 1.0)
        val clampedStart = start.coerceIn(0.0, 1.0 - span)
        viewStart = clampedStart
        viewEnd = clampedStart + span
    }

    private companion object {
        const val ZOOM_EPSILON = 1e-6
    }
}

/**
 * @param keys 任一变化时重建状态（清空缩放与游标），如切换设备、时间范围或对齐方式
 */
@Composable
fun rememberBatteryChartState(vararg keys: Any?): BatteryChartState = remember(*keys) { BatteryChartState() }

/**
 * [timestamp] 时这条曲线最近一次上报的电量；不在任何一段连接内时为 null
 */
fun ChartSeries.valueAt(timestamp: Long): LevelPoint? =
    segments.firstOrNull { it.isNotEmpty() && timestamp in it.first().timestamp..it.last().timestamp }
        ?.let { lastPointAtOrBefore(it, timestamp) }

/**
 * 耳机电量曲线：单调分段三次贝塞尔 + 面积渐变，支持轻触吸附实测点、拖动查看、双指缩放平移、双击还原。
 *
 * Why:
 * - 曲线严格经过每个实测点且不过冲（见 `monotoneCubicSegments`），在屏幕坐标上直接计算，缩放后依旧平滑；
 * - 游标只停在真实记录上，读数不会是插值估算；拖动时每跨过一个实测点轻震一下；
 * - 单指横向拖动查看读数，纵向拖动交还给列表滚动；双指负责缩放与平移，互不冲突。
 *
 * @param domainStart 横轴起点（[ChartTimeAxis.RELATIVE] 时为 0）
 * @param revealKey 变化时重新播放从左到右的展开动画
 * @param description 无障碍描述
 */
@Composable
fun BatteryLineChart(
    series: List<ChartSeries>,
    domainStart: Long,
    domainEnd: Long,
    axis: ChartTimeAxis,
    state: BatteryChartState,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 200.dp,
    revealKey: Any? = series.map { it.key }
) {
    val colors = BtTheme.colors
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = remember(colors.ink3) { MonoTextStyle.copy(fontSize = 11.sp, color = colors.ink3) }
    val tickFormats = remember { TickFormats() }

    val reveal = remember { Animatable(0f) }
    LaunchedEffect(revealKey) {
        reveal.snapTo(0f)
        reveal.animateTo(1f, tween(REVEAL_DURATION_MILLIS, easing = EaseOutCubic))
    }

    val currentSeries by rememberUpdatedState(series)
    val currentDomain by rememberUpdatedState(domainStart to domainEnd)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(state) {
                val slop = viewConfiguration.touchSlop
                val doubleTapTimeout = viewConfiguration.doubleTapTimeoutMillis
                var lastTapAt = 0L
                var lastTapX = 0f

                fun plotLeft() = PLOT_LEFT.toPx()
                fun plotWidth() = (size.width - PLOT_LEFT.toPx() - PLOT_RIGHT.toPx()).coerceAtLeast(1f)

                fun timeAt(x: Float): Long {
                    val (start, end) = currentDomain
                    val span = (end - start).coerceAtLeast(1L).toDouble()
                    val fraction = ((x - plotLeft()) / plotWidth()).toDouble().coerceIn(0.0, 1.0)
                    return (start + (state.viewStart + fraction * (state.viewEnd - state.viewStart)) * span).toLong()
                }

                fun visibleRange(): LongRange {
                    val (start, end) = currentDomain
                    val span = (end - start).coerceAtLeast(1L).toDouble()
                    return (start + state.viewStart * span).toLong()..(start + state.viewEnd * span).toLong()
                }

                fun moveCursor(x: Float, toggle: Boolean) {
                    val snapped = snapCursor(currentSeries, timeAt(x), visibleRange())
                    val next = if (toggle && snapped == state.cursor) null else snapped
                    if (next != state.cursor) {
                        state.cursor = next
                        if (next != null) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    }
                }

                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var mode = GestureMode.UNDECIDED
                    var dragX = 0f
                    var dragY = 0f
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break

                        if (pressed.size >= 2 || mode == GestureMode.TRANSFORM) {
                            // 双指：缩放与平移；抬起一指后剩下的手势也不再当作拖动
                            mode = GestureMode.TRANSFORM
                            if (pressed.size >= 2) {
                                val (start, end) = currentDomain
                                val minSpan = MIN_VISIBLE_MILLIS / (end - start).coerceAtLeast(1L).toDouble()
                                val centroid = event.calculateCentroid(useCurrent = true)
                                state.transform(
                                    zoom = event.calculateZoom(),
                                    panFraction = event.calculatePan().x / plotWidth().toDouble(),
                                    anchor = ((centroid.x - plotLeft()) / plotWidth()).toDouble(),
                                    minSpan = minSpan.coerceAtLeast(MIN_VISIBLE_FRACTION)
                                )
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }

                        val change = pressed.firstOrNull { it.id == down.id } ?: pressed.first()
                        when (mode) {
                            GestureMode.UNDECIDED -> {
                                val delta = change.positionChange()
                                dragX += delta.x
                                dragY += delta.y
                                if (abs(dragX) > slop && abs(dragX) >= abs(dragY)) {
                                    mode = GestureMode.SCRUB
                                    change.consume()
                                    moveCursor(change.position.x, toggle = false)
                                } else if (abs(dragY) > slop) {
                                    mode = GestureMode.SCROLL
                                }
                            }

                            GestureMode.SCRUB -> {
                                change.consume()
                                moveCursor(change.position.x, toggle = false)
                            }

                            GestureMode.SCROLL, GestureMode.TRANSFORM -> Unit
                        }
                    }

                    if (mode == GestureMode.UNDECIDED) {
                        val isDoubleTap = down.uptimeMillis - lastTapAt < doubleTapTimeout &&
                            abs(down.position.x - lastTapX) < slop * 2
                        if (isDoubleTap) {
                            lastTapAt = 0L
                            state.clearCursor()
                            if (state.isZoomed) scope.launch { state.animateResetZoom() }
                        } else {
                            lastTapAt = down.uptimeMillis
                            lastTapX = down.position.x
                            moveCursor(down.position.x, toggle = true)
                        }
                    }
                }
            }
    ) {
        val plot = Rect(
            left = PLOT_LEFT.toPx(),
            top = PLOT_TOP.toPx(),
            right = size.width - PLOT_RIGHT.toPx(),
            bottom = size.height - PLOT_BOTTOM.toPx()
        )
        if (plot.width <= 0f || plot.height <= 0f) return@Canvas

        val span = (domainEnd - domainStart).coerceAtLeast(1L).toDouble()
        val viewMin = domainStart + state.viewStart * span
        val viewMax = domainStart + state.viewEnd * span
        val viewSpan = (viewMax - viewMin).coerceAtLeast(1.0)
        val mapper = ChartMapper(plot, viewMin, viewSpan)

        drawLevelGrid(plot, colors.line, colors.lineStrong, textMeasurer, labelStyle)
        drawTimeTicks(plot, mapper, viewMin, viewMax, axis, colors.line, textMeasurer, labelStyle, tickFormats)

        val multiple = series.size > 1
        val stroke = LINE_WIDTH.toPx()
        val ring = RING_WIDTH.toPx()
        val revealRight = plot.left + (plot.width + ring * 2) * reveal.value
        clipRect(plot.left - ring, plot.top - ring * 2, revealRight, plot.bottom + ring) {
            series.forEach { line ->
                line.segments.forEach { segment ->
                    val visible = visibleSlice(segment, viewMin, viewMax)
                    if (visible.isEmpty()) return@forEach
                    val screen = visible.map { CurvePoint(mapper.x(it.timestamp).toDouble(), mapper.y(it.level).toDouble()) }
                    val path = curvePath(screen)
                    if (path != null) {
                        if (line.emphasized) {
                            drawPath(
                                path = curvePath(screen, closeToY = plot.bottom) ?: path,
                                brush = Brush.verticalGradient(
                                    colors = listOf(line.color.copy(alpha = AREA_TOP_ALPHA), line.color.copy(alpha = 0f)),
                                    startY = plot.top,
                                    endY = plot.bottom
                                )
                            )
                        }
                        if (multiple) {
                            // 曲线交叉处留一圈表面色描边，叠加时也能分清前后
                            drawPath(path, colors.surface, style = lineStroke(stroke + ring * 2))
                        }
                        drawPath(path, line.color, style = lineStroke(stroke))
                    }
                    drawSampleDots(visible, mapper, plot, line.color, colors.surface)
                }
            }
        }

        state.cursor?.let { cursor ->
            drawCursor(cursor, series, mapper, plot, colors.ink3, colors.surface)
        }
    }
}

private enum class GestureMode { UNDECIDED, SCRUB, SCROLL, TRANSFORM }

/** 数据坐标 -> 屏幕坐标 */
private class ChartMapper(private val plot: Rect, private val viewMin: Double, private val viewSpan: Double) {
    fun x(timestamp: Long): Float = plot.left + ((timestamp - viewMin) / viewSpan * plot.width).toFloat()
    fun y(level: Int): Float = plot.bottom - level / 100f * plot.height
}

/**
 * 找离 [timestamp] 最近、且在可见范围内的实测点
 */
private fun snapCursor(series: List<ChartSeries>, timestamp: Long, visible: LongRange): ChartCursor? {
    var best: ChartCursor? = null
    var bestDistance = Long.MAX_VALUE
    series.forEach { line ->
        val points = line.points
        val index = nearestPointIndex(points, timestamp)
        if (index < 0) return@forEach
        val point = points[index]
        val distance = abs(point.timestamp - timestamp)
        if (point.timestamp in visible && distance < bestDistance) {
            bestDistance = distance
            best = ChartCursor(line.key, point)
        }
    }
    return best
}

/**
 * 只取可见范围内的点，两侧各多带两个点：
 * 单调插值在某点的切线只取决于它前后各一个点，多带两个点能保证边缘处曲线与完整计算时一致。
 */
private fun visibleSlice(points: List<LevelPoint>, viewMin: Double, viewMax: Double): List<LevelPoint> {
    if (points.isEmpty() || points.last().timestamp < viewMin || points.first().timestamp > viewMax) return emptyList()
    val firstVisible = points.indexOfFirst { it.timestamp >= viewMin }.let { if (it < 0) points.lastIndex else it }
    val lastVisible = points.indexOfLast { it.timestamp <= viewMax }.let { if (it < 0) 0 else it }
    val from = (firstVisible - 2).coerceAtLeast(0)
    val to = (lastVisible + 2).coerceAtMost(points.lastIndex)
    return points.subList(from, to + 1)
}

/**
 * @param closeToY 不为 null 时向下闭合到该高度，作为面积填充
 */
private fun curvePath(screen: List<CurvePoint>, closeToY: Float? = null): Path? {
    val cubics = monotoneCubicSegments(screen)
    if (cubics.isEmpty()) return null
    return Path().apply {
        moveTo(cubics.first().start.x.toFloat(), cubics.first().start.y.toFloat())
        cubics.forEach { segment ->
            cubicTo(
                segment.control1.x.toFloat(), segment.control1.y.toFloat(),
                segment.control2.x.toFloat(), segment.control2.y.toFloat(),
                segment.end.x.toFloat(), segment.end.y.toFloat()
            )
        }
        if (closeToY != null) {
            lineTo(cubics.last().end.x.toFloat(), closeToY)
            lineTo(cubics.first().start.x.toFloat(), closeToY)
            close()
        }
    }
}

private fun lineStroke(width: Float) = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round)

/**
 * 实测点足够稀疏时画出圆点，让人一眼分清“记录到的值”和“两点之间的曲线”
 */
private fun DrawScope.drawSampleDots(
    points: List<LevelPoint>,
    mapper: ChartMapper,
    plot: Rect,
    color: Color,
    ringColor: Color
) {
    val onScreen = points.filter { mapper.x(it.timestamp) in plot.left..plot.right }
    if (onScreen.isEmpty()) return
    if (onScreen.size > 1 && plot.width / onScreen.size < DOT_MIN_SPACING.toPx()) return
    val outer = DOT_RADIUS.toPx() + RING_WIDTH.toPx()
    val inner = DOT_RADIUS.toPx()
    onScreen.forEach { point ->
        val center = Offset(mapper.x(point.timestamp), mapper.y(point.level))
        drawCircle(ringColor, outer, center)
        drawCircle(color, inner, center)
    }
}

private fun DrawScope.drawCursor(
    cursor: ChartCursor,
    series: List<ChartSeries>,
    mapper: ChartMapper,
    plot: Rect,
    lineColor: Color,
    ringColor: Color
) {
    val x = mapper.x(cursor.point.timestamp)
    if (x < plot.left - 1f || x > plot.right + 1f) return
    drawLine(
        color = lineColor,
        start = Offset(x, plot.top),
        end = Offset(x, plot.bottom),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
    )
    // 其他曲线标在“此刻最近一次上报”的实测点上，与读数一致
    series.forEach { line ->
        val point = if (line.key == cursor.seriesKey) cursor.point else line.valueAt(cursor.point.timestamp)
        point ?: return@forEach
        val center = Offset(mapper.x(point.timestamp), mapper.y(point.level))
        if (center.x < plot.left - 1f || center.x > plot.right + 1f) return@forEach
        drawCircle(ringColor, CURSOR_RADIUS.toPx() + RING_WIDTH.toPx(), center)
        drawCircle(line.color, CURSOR_RADIUS.toPx(), center)
    }
}

/** 纵轴固定 0~100%：不同耳机、不同时段的曲线才能直接比较 */
private fun DrawScope.drawLevelGrid(
    plot: Rect,
    gridColor: Color,
    baselineColor: Color,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle
) {
    val labelGap = 6.dp.toPx()
    for (level in 0..100 step 25) {
        val y = plot.bottom - level / 100f * plot.height
        drawLine(
            color = if (level == 0) baselineColor else gridColor,
            start = Offset(plot.left, y),
            end = Offset(plot.right, y),
            strokeWidth = 1.dp.toPx()
        )
        val layout = textMeasurer.measure("$level%", labelStyle)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(plot.left - labelGap - layout.size.width, y - layout.size.height / 2f)
        )
    }
}

private fun DrawScope.drawTimeTicks(
    plot: Rect,
    mapper: ChartMapper,
    viewMin: Double,
    viewMax: Double,
    axis: ChartTimeAxis,
    gridColor: Color,
    textMeasurer: TextMeasurer,
    labelStyle: TextStyle,
    formats: TickFormats
) {
    val step = chooseTickStep(viewMax - viewMin, plot.width, TICK_MIN_SPACING.toPx())
    val offset = if (axis == ChartTimeAxis.ABSOLUTE) TimeZone.getDefault().getOffset(viewMin.toLong()).toLong() else 0L
    var tick = (ceil((viewMin + offset) / step) * step).toLong() - offset
    var lastLabelRight = Float.NEGATIVE_INFINITY
    val labelGap = 4.dp.toPx()
    while (tick <= viewMax) {
        val x = mapper.x(tick)
        drawLine(
            color = gridColor.copy(alpha = 0.6f),
            start = Offset(x, plot.top),
            end = Offset(x, plot.bottom),
            strokeWidth = 1.dp.toPx()
        )
        val layout = textMeasurer.measure(formats.format(tick, step, axis), labelStyle)
        val left = (x - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
        if (left > lastLabelRight + labelGap) {
            drawText(textLayoutResult = layout, topLeft = Offset(left, plot.bottom + labelGap))
            lastLabelRight = left + layout.size.width
        }
        tick += step
    }
}

/** 选一个让刻度不太密的整点间隔 */
private fun chooseTickStep(spanMillis: Double, widthPx: Float, minSpacingPx: Float): Long {
    val maxTicks = (widthPx / minSpacingPx).coerceAtLeast(1f)
    return TICK_STEPS.firstOrNull { spanMillis / it <= maxTicks } ?: TICK_STEPS.last()
}

private class TickFormats {
    private val time = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val date = SimpleDateFormat("MM-dd", Locale.getDefault())
    private val dayMillis = 24 * 60 * 60_000L

    fun format(tick: Long, step: Long, axis: ChartTimeAxis): String = when (axis) {
        ChartTimeAxis.RELATIVE -> if (tick <= 0L) "0" else getCompactDurationString(tick)
        ChartTimeAxis.ABSOLUTE -> {
            val localMidnight = (tick + TimeZone.getDefault().getOffset(tick)) % dayMillis == 0L
            if (step >= dayMillis || localMidnight) date.format(tick) else time.format(tick)
        }
    }
}

private val TICK_STEPS = longArrayOf(
    60_000L, 2 * 60_000L, 5 * 60_000L, 10 * 60_000L, 15 * 60_000L, 30 * 60_000L,
    3_600_000L, 2 * 3_600_000L, 3 * 3_600_000L, 6 * 3_600_000L, 12 * 3_600_000L,
    86_400_000L, 2 * 86_400_000L, 7 * 86_400_000L, 14 * 86_400_000L, 30 * 86_400_000L
)

private val PLOT_LEFT = 38.dp
private val PLOT_RIGHT = 10.dp
private val PLOT_TOP = 10.dp
private val PLOT_BOTTOM = 24.dp
private val LINE_WIDTH = 2.dp
private val RING_WIDTH = 2.dp
private val DOT_RADIUS = 2.5.dp
private val DOT_MIN_SPACING = 12.dp
private val CURSOR_RADIUS = 4.5.dp
private val TICK_MIN_SPACING = 64.dp
private const val AREA_TOP_ALPHA = 0.22f
private const val REVEAL_DURATION_MILLIS = 720

/** 最多放大到 2 分钟宽，再细已经分不清相邻记录 */
private const val MIN_VISIBLE_MILLIS = 2 * 60_000.0
private const val MIN_VISIBLE_FRACTION = 0.002
