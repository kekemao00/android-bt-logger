package com.xingkeqi.btlogger.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring

/**
 * 弹簧参数（质量 1）。
 *
 * Why:
 * 规范要求所有动画都是低回弹（≤ 约 1%）的弹簧，不用 ease-in-out 或弹跳曲线；
 * 统一放在这里，组件只按语义取用，调整整体节奏时只改一处。
 */
object Springs {
    /** 按下、焦点、透明度：约 0.19s 到位 */
    fun <T> snappy(): SpringSpec<T> = spring(dampingRatio = 0.86f, stiffness = 584f)

    /** 位置、尺寸、高亮块滑动：约 0.36s 到位 */
    fun <T> default(): SpringSpec<T> = spring(dampingRatio = 0.82f, stiffness = 273f)

    /** 面板展开、通知胶囊、页面入场 */
    fun <T> smooth(): SpringSpec<T> = spring(dampingRatio = 0.86f, stiffness = 158f)

    /** 指示条前沿 */
    fun <T> lead(): SpringSpec<T> = spring(dampingRatio = 0.84f, stiffness = 584f)

    /** 指示条后沿 */
    fun <T> trail(): SpringSpec<T> = spring(dampingRatio = 0.86f, stiffness = 187f)
}

/** 内容淡入、对勾绘制使用的 ease-out-cubic */
val EaseOutCubic: Easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)

/** 内容切换时长（毫秒）：旧内容淡出、新内容延迟后淡入 */
object SwapTimings {
    const val EXIT = 110
    const val ENTER = 170
    const val ENTER_DELAY = 80
    const val BLUR_DP = 4f
    const val SHIFT_DP = 3
}
