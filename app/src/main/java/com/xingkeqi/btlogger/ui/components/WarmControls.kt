package com.xingkeqi.btlogger.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.ui.theme.EaseOutCubic
import com.xingkeqi.btlogger.ui.theme.Springs
import com.xingkeqi.btlogger.ui.theme.SwapTimings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

private val CapsuleShape = RoundedCornerShape(percent = 50)

/** 拖动分段标签页松手时的最大速度（每秒移动的标签数） */
private const val MAX_TAB_FLING_TABS_PER_SECOND = 8f

/** 松手后按速度预测落点的时间窗口（秒） */
private const val TAB_FLING_PROJECTION_SECONDS = 0.08f

private const val ENTRANCE_STAGGER_MILLIS = 22L
private const val ENTRANCE_DURATION_MILLIS = 200
private const val ENTRANCE_MAX_STAGGERED_ITEMS = 12

/**
 * 内容切换：旧内容快速模糊淡出并上移，新内容稍后从下方模糊淡入。
 *
 * Why:
 * 规范要求同一个容器换内容时不能直接替换，也不能新旧叠在一起；
 * 标题、图标、通知文字等所有“会换内容”的地方都走这里，保证节奏一致。
 */
@Composable
fun <S> BlurSwap(
    targetState: S,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.CenterStart,
    contentKey: (S) -> Any? = { it },
    label: String = "BlurSwap",
    content: @Composable (S) -> Unit
) {
    val shiftPx = with(LocalDensity.current) { SwapTimings.SHIFT_DP.dp.roundToPx() }
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        contentAlignment = contentAlignment,
        contentKey = contentKey,
        transitionSpec = {
            val enterSpec = tween<Float>(SwapTimings.ENTER, SwapTimings.ENTER_DELAY, EaseOutCubic)
            val enter = fadeIn(enterSpec) + slideInVertically(
                tween(SwapTimings.ENTER, SwapTimings.ENTER_DELAY, EaseOutCubic)
            ) { shiftPx }
            val exit = fadeOut(tween(SwapTimings.EXIT)) + slideOutVertically(tween(SwapTimings.EXIT)) { -shiftPx }
            (enter togetherWith exit).using(SizeTransform(clip = false) { _, _ -> Springs.default() })
        },
        label = label
    ) { state ->
        val blurRadius by transition.animateFloat(
            transitionSpec = {
                // 显式用 this：外层函数参数同名 targetState 会遮蔽 Segment 的成员
                if (this.targetState == EnterExitState.Visible) {
                    tween(SwapTimings.ENTER, SwapTimings.ENTER_DELAY, EaseOutCubic)
                } else {
                    tween(SwapTimings.EXIT)
                }
            },
            label = "BlurSwapRadius"
        ) { if (it == EnterExitState.Visible) 0f else SwapTimings.BLUR_DP }
        Box(modifier = Modifier.blur(blurRadius.dp, BlurredEdgeTreatment.Unbounded)) {
            content(state)
        }
    }
}

enum class WmButtonStyle { Primary, Secondary, Ghost, Danger }

/**
 * 按钮：按下整体缩到 96.5%，颜色用 snappy 弹簧混合；不使用 Material 涟漪。
 */
@Composable
fun WmButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: WmButtonStyle = WmButtonStyle.Primary,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    val colors = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, Springs.snappy(), label = "buttonScale")

    val targetBackground = when (style) {
        WmButtonStyle.Primary -> if (pressed) colors.inkHover else colors.ink
        WmButtonStyle.Secondary -> if (pressed) colors.surface2 else colors.surface
        WmButtonStyle.Ghost -> if (pressed) colors.surface3.copy(alpha = 0.75f) else colors.surface3.copy(alpha = 0f)
        WmButtonStyle.Danger -> if (pressed) lerp(colors.danger, Color.Black, 0.18f) else colors.danger
    }
    val targetContent = when (style) {
        WmButtonStyle.Primary -> colors.onInk
        WmButtonStyle.Secondary -> colors.ink
        WmButtonStyle.Ghost -> if (pressed) colors.ink else colors.ink2
        WmButtonStyle.Danger -> Color.White
    }
    val background by animateColorAsState(targetBackground, Springs.snappy(), label = "buttonBackground")
    val content by animateColorAsState(targetContent, Springs.snappy(), label = "buttonContent")
    val borderColor by animateColorAsState(
        if (pressed) colors.lineStrong else colors.line,
        Springs.snappy(),
        label = "buttonBorder"
    )
    val shape = RoundedCornerShape(if (style == WmButtonStyle.Primary) Dimens.radiusPrimary else Dimens.radiusButton)

    Row(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.4f
            }
            .heightIn(min = Dimens.buttonHeight)
            .clip(shape)
            .background(background)
            .then(if (style == WmButtonStyle.Secondary) Modifier.border(Dimens.stroke, borderColor, shape) else Modifier)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(horizontal = Dimens.spacingLg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(6.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelLarge, color = content, maxLines = 1)
    }
}

/**
 * 纯图标的幽灵按钮：正方形，按下时出现 surface-3 底并缩放
 *
 * @param active 选中/开启态，图标使用强调色
 */
@Composable
fun WmIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    size: Dp = Dimens.iconButtonSize
) {
    val colors = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.965f else 1f, Springs.snappy(), label = "iconButtonScale")
    val background by animateColorAsState(
        if (pressed) colors.surface3 else colors.surface3.copy(alpha = 0f),
        Springs.snappy(),
        label = "iconButtonBackground"
    )
    val tint by animateColorAsState(
        when {
            active -> colors.accent
            pressed -> colors.ink
            else -> colors.ink2
        },
        Springs.snappy(),
        label = "iconButtonTint"
    )
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(Dimens.radiusButton))
            .background(background)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        BlurSwap(targetState = icon, contentAlignment = Alignment.Center, label = "iconSwap") { vector ->
            Icon(
                imageVector = vector,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(Dimens.iconSizeMd)
            )
        }
    }
}

/**
 * 搜索输入框：白底 1px 描边；聚焦时描边弹到强调色并加一圈焦点光环，图标由 ink-3 变 ink；
 * 占位符在开始输入时淡出并右移，清空按钮从 80% 缩放淡入。
 */
@Composable
fun WmSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearContentDescription: String,
    modifier: Modifier = Modifier
) {
    val colors = BtTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val borderColor by animateColorAsState(if (focused) colors.accent else colors.line, Springs.snappy(), label = "inputBorder")
    val ring by animateFloatAsState(if (focused) 1f else 0f, Springs.snappy(), label = "inputRing")
    val iconTint by animateColorAsState(if (focused) colors.ink else colors.ink3, Springs.snappy(), label = "inputIcon")
    val placeholderProgress by animateFloatAsState(
        if (value.isEmpty()) 1f else 0f,
        Springs.snappy(),
        label = "inputPlaceholder"
    )
    val shape = RoundedCornerShape(Dimens.radiusInput)
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.ink)
    val ringColor = colors.focusRing

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = textStyle,
        cursorBrush = SolidColor(colors.accent),
        interactionSource = interaction,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier
                    .drawBehind {
                        if (ring > 0.01f) {
                            val ringPx = Dimens.focusRing.toPx()
                            val radius = Dimens.radiusInput.toPx() + ringPx
                            drawRoundRect(
                                color = ringColor.copy(alpha = ringColor.alpha * ring.coerceIn(0f, 1f)),
                                topLeft = Offset(-ringPx, -ringPx),
                                size = Size(size.width + ringPx * 2, size.height + ringPx * 2),
                                cornerRadius = CornerRadius(radius, radius)
                            )
                        }
                    }
                    .height(Dimens.inputHeight)
                    .clip(shape)
                    .background(colors.surface)
                    .border(Dimens.stroke, borderColor, shape)
                    .padding(start = 14.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = LineIcons.Search,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(Dimens.iconSizeSm + 2.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (placeholderProgress > 0.01f) {
                        Text(
                            text = placeholder,
                            style = textStyle,
                            color = colors.ink3,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.graphicsLayer {
                                alpha = placeholderProgress.coerceIn(0f, 1f)
                                translationX = (1f - placeholderProgress) * 6.dp.toPx()
                            }
                        )
                    }
                    innerTextField()
                }
                AnimatedVisibility(
                    visible = value.isNotEmpty(),
                    enter = fadeIn(Springs.snappy()) + scaleIn(Springs.snappy(), initialScale = 0.8f),
                    exit = fadeOut(Springs.snappy()) + scaleOut(Springs.snappy(), targetScale = 0.8f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.Button,
                                onClick = { onValueChange("") }
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = LineIcons.Close,
                            contentDescription = clearContentDescription,
                            tint = colors.ink2,
                            modifier = Modifier.size(Dimens.iconSizeSm)
                        )
                    }
                }
            }
        }
    )
}

/**
 * 分段标签页。
 *
 * Why:
 * 规范的“两弹簧拉伸”：指示条左右两边各由一个弹簧驱动，前沿用更快的 lead、后沿用更慢的 trail，
 * 移动中被拉长，到位后收拢；文字在指示条内部被裁成反色（同一行文字画两遍，用指示条范围做裁剪）。
 * 指示条也可以直接拖动，按住时跟手，松手带着速度弹到最近的标签。
 *
 * 位置以“标签宽度”为单位保存，标签等宽，因此不需要逐个测量标签。
 */
@Composable
fun WmSegmentedTabs(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BtTheme.colors
    val count = labels.size.coerceAtLeast(1)
    val scope = rememberCoroutineScope()
    val leftEdge = remember { Animatable(selectedIndex.toFloat()) }
    val rightEdge = remember { Animatable(selectedIndex + 1f) }
    // 拖动手势只在 count 变化时重建，回调里需要读到最新的选中项与回调
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)

    fun animateEdgesTo(index: Int, velocity: Float = 0f) {
        val movingRight = index >= leftEdge.value
        scope.launch {
            leftEdge.animateTo(
                index.toFloat(),
                if (movingRight) Springs.trail() else Springs.lead(),
                initialVelocity = velocity
            )
        }
        scope.launch {
            rightEdge.animateTo(
                index + 1f,
                if (movingRight) Springs.lead() else Springs.trail(),
                initialVelocity = velocity
            )
        }
    }

    LaunchedEffect(selectedIndex) {
        if (leftEdge.targetValue != selectedIndex.toFloat()) animateEdgesTo(selectedIndex)
    }

    val indicatorColor = colors.ink
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.tabHeight)
            .clip(CapsuleShape)
            .background(colors.surface)
            .border(Dimens.stroke, colors.line, CapsuleShape)
            .padding(3.dp)
            .pointerInput(count) {
                val tracker = VelocityTracker()
                val tabWidth = { size.width / count.toFloat() }
                detectHorizontalDragGestures(
                    onDragStart = { tracker.resetTracking() },
                    onDragEnd = {
                        val velocity = (tracker.calculateVelocity().x / tabWidth())
                            .coerceIn(-MAX_TAB_FLING_TABS_PER_SECOND, MAX_TAB_FLING_TABS_PER_SECOND)
                        val target = (leftEdge.value + velocity * TAB_FLING_PROJECTION_SECONDS)
                            .roundToInt()
                            .coerceIn(0, count - 1)
                        animateEdgesTo(target, velocity)
                        if (target != currentSelectedIndex) currentOnSelect(target)
                    },
                    onDragCancel = { animateEdgesTo(currentSelectedIndex) },
                    onHorizontalDrag = { change, _ ->
                        tracker.addPosition(change.uptimeMillis, change.position)
                        val position = (change.position.x / tabWidth() - 0.5f).coerceIn(0f, count - 1f)
                        scope.launch {
                            leftEdge.snapTo(position)
                            rightEdge.snapTo(position + 1f)
                        }
                        change.consume()
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val tabWidth = size.width / count
                    val left = leftEdge.value * tabWidth
                    val right = rightEdge.value * tabWidth
                    drawRoundRect(
                        color = indicatorColor,
                        topLeft = Offset(left, 0f),
                        size = Size((right - left).coerceAtLeast(0f), size.height),
                        cornerRadius = CornerRadius(size.height / 2f, size.height / 2f)
                    )
                }
        )
        TabLabels(
            labels = labels,
            color = colors.ink2,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            modifier = Modifier.fillMaxSize()
        )
        // 反色文字层：只在指示条范围内可见，不参与无障碍和点击
        TabLabels(
            labels = labels,
            color = colors.onInk,
            selectedIndex = null,
            onSelect = null,
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
                .drawWithContent {
                    val tabWidth = size.width / count
                    clipRect(left = leftEdge.value * tabWidth, right = rightEdge.value * tabWidth) {
                        this@drawWithContent.drawContent()
                    }
                }
        )
    }
}

@Composable
private fun TabLabels(
    labels: List<String>,
    color: Color,
    selectedIndex: Int?,
    onSelect: ((Int) -> Unit)?,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier) {
        labels.forEachIndexed { index, label ->
            val clickModifier = if (onSelect != null && selectedIndex != null) {
                Modifier.selectable(
                    selected = index == selectedIndex,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Tab,
                    onClick = { onSelect(index) }
                )
            } else {
                Modifier
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .then(clickModifier),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = color,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 开关：圆点左右两边各一个弹簧（lead / trail），切换时被拉长再收拢
 */
@Composable
fun WmSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BtTheme.colors
    val leftEdge = remember { Animatable(if (checked) 1f else 0f) }
    val rightEdge = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        launch { leftEdge.animateTo(target, if (checked) Springs.trail() else Springs.lead()) }
        launch { rightEdge.animateTo(target, if (checked) Springs.lead() else Springs.trail()) }
    }
    val track by animateColorAsState(if (checked) colors.ink else colors.lineStrong, Springs.snappy(), label = "switchTrack")
    val thumb by animateColorAsState(
        when {
            checked -> colors.onInk
            colors.isDark -> colors.ink2
            else -> colors.surface
        },
        Springs.snappy(),
        label = "switchThumb"
    )
    Box(
        modifier = modifier
            .size(width = 46.dp, height = 28.dp)
            .clip(CapsuleShape)
            .toggleable(
                value = checked,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .drawBehind {
                drawRoundRect(color = track, cornerRadius = CornerRadius(size.height / 2f, size.height / 2f))
                val inset = 3.dp.toPx()
                val diameter = size.height - inset * 2
                val travel = size.width - inset * 2 - diameter
                val left = inset + leftEdge.value * travel
                val right = inset + rightEdge.value * travel + diameter
                drawRoundRect(
                    color = thumb,
                    topLeft = Offset(left, inset),
                    size = Size((right - left).coerceAtLeast(diameter), diameter),
                    cornerRadius = CornerRadius(diameter / 2f, diameter / 2f)
                )
            }
    )
}

/**
 * 标签 chip：白底胶囊、line-strong 描边、caption 字号；可带一个状态圆点或线性图标
 */
@Composable
fun WmChip(
    text: String,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    icon: ImageVector? = null,
    contentColor: Color = BtTheme.colors.ink2
) {
    val colors = BtTheme.colors
    Row(
        modifier = modifier
            .height(Dimens.chipHeight)
            .clip(CapsuleShape)
            .background(colors.surface)
            .border(Dimens.stroke, colors.lineStrong, CapsuleShape)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(dotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(13.dp))
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = contentColor, maxLines = 1)
    }
}

/**
 * 白色内容卡片：圆角 16、1px line 描边，不使用阴影
 */
@Composable
fun WmCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = Dimens.cardPadding,
    content: @Composable ColumnScope.() -> Unit
) {
    val colors = BtTheme.colors
    val shape = RoundedCornerShape(Dimens.radiusPanel)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surface)
            .border(Dimens.stroke, colors.line, shape)
            .padding(contentPadding),
        content = content
    )
}

/**
 * 分组标题（caption、ink-3）
 */
@Composable
fun SectionCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = BtTheme.colors.ink3,
        modifier = modifier
    )
}

/**
 * 胶囊进度条：墨黑填充，宽度用 default 弹簧过渡
 */
@Composable
fun WmProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = BtTheme.colors.ink,
    trackColor: Color = BtTheme.colors.surface3
) {
    val animated by animateFloatAsState(progress.coerceIn(0f, 1f), Springs.default(), label = "progress")
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(Dimens.progressBarHeight)
            .clip(CapsuleShape)
            .background(trackColor)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(animated.coerceIn(0f, 1f))
                .clip(CapsuleShape)
                .background(color)
        )
    }
}

/** 列表中一组连续行在白色卡片里的位置 */
enum class SegmentPosition { Single, First, Middle, Last }

fun segmentPositionOf(index: Int, count: Int): SegmentPosition = when {
    count <= 1 -> SegmentPosition.Single
    index == 0 -> SegmentPosition.First
    index == count - 1 -> SegmentPosition.Last
    else -> SegmentPosition.Middle
}

/** 与分组卡片外形一致的裁剪形状，用于行内按下高亮 */
fun SegmentPosition.shape(radius: Dp = Dimens.radiusPanel): RoundedCornerShape {
    val top = if (this == SegmentPosition.Single || this == SegmentPosition.First) radius else 0.dp
    val bottom = if (this == SegmentPosition.Single || this == SegmentPosition.Last) radius else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/**
 * 把 LazyColumn 中连续的行画成同一张白色卡片：只有首尾行画圆角，行间是左右内缩的 1px 分隔线。
 *
 * Why:
 * 规范的列表是“一张白色主卡片 + 行间细线”，而不是每行一张卡片；
 * 惰性列表里每行独立绘制，这里把圆角矩形在开口方向上延伸到可见范围外再裁剪，拼接处不会出现双线。
 */
fun Modifier.cardSegment(
    position: SegmentPosition,
    background: Color,
    border: Color,
    divider: Color,
    radius: Dp = Dimens.radiusPanel,
    dividerInset: Dp = Dimens.rowDividerInset
): Modifier = drawBehind {
    val r = radius.toPx()
    val strokeWidth = Dimens.stroke.toPx()
    val openTop = position == SegmentPosition.Middle || position == SegmentPosition.Last
    val openBottom = position == SegmentPosition.Middle || position == SegmentPosition.First
    val top = if (openTop) -2 * r else 0f
    val bottom = if (openBottom) size.height + 2 * r else size.height
    clipRect(0f, 0f, size.width, size.height) {
        drawRoundRect(
            color = background,
            topLeft = Offset(0f, top),
            size = Size(size.width, bottom - top),
            cornerRadius = CornerRadius(r, r)
        )
        drawRoundRect(
            color = border,
            topLeft = Offset(strokeWidth / 2, top + strokeWidth / 2),
            size = Size(size.width - strokeWidth, bottom - top - strokeWidth),
            cornerRadius = CornerRadius(r - strokeWidth / 2, r - strokeWidth / 2),
            style = Stroke(strokeWidth)
        )
    }
    if (openTop) {
        val inset = dividerInset.toPx()
        drawLine(
            color = divider,
            start = Offset(inset, strokeWidth / 2),
            end = Offset(size.width - inset, strokeWidth / 2),
            strokeWidth = strokeWidth
        )
    }
}

/**
 * 列表入场：各行错开 22ms，从透明并下移 5dp 用 200ms 落位
 */
fun Modifier.staggeredEntrance(index: Int): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(min(index, ENTRANCE_MAX_STAGGERED_ITEMS) * ENTRANCE_STAGGER_MILLIS)
        progress.animateTo(1f, tween(ENTRANCE_DURATION_MILLIS, easing = EaseOutCubic))
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 5.dp.toPx()
    }
}
