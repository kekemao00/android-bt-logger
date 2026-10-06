package com.xingkeqi.btlogger.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.ui.theme.EaseOutCubic
import com.xingkeqi.btlogger.ui.theme.Springs
import kotlinx.coroutines.delay

private const val NOTIFICATION_BASE_MILLIS = 1_800L
private const val NOTIFICATION_PER_CHAR_MILLIS = 60L
private const val NOTIFICATION_MAX_EXTRA_MILLIS = 2_600L
private const val NOTIFICATION_EXIT_MILLIS = 200

/** 面板展开到这个进度后内容才开始出现 */
private const val PANEL_CONTENT_THRESHOLD = 0.55f

/**
 * 顶部居中的墨黑通知胶囊（替代 Material Snackbar）。
 *
 * Why:
 * 规范不用底部 Snackbar，而是同一个胶囊从小圆点展开成消息宽度；新消息到来时不新建，
 * 只改宽度并在模糊里换掉文字。停留时长 1.8s + 每字 60ms（最多再加 2.6s），到时主动 dismiss，
 * 调用方仍然用 [SnackbarHostState.showSnackbar] 发消息，排队逻辑不变。
 */
@Composable
fun IslandNotificationHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    val colors = BtTheme.colors
    val data = hostState.currentSnackbarData
    var lastMessage by remember { mutableStateOf("") }

    LaunchedEffect(data) {
        if (data != null) {
            val message = data.visuals.message
            lastMessage = message
            val extra = (message.length * NOTIFICATION_PER_CHAR_MILLIS).coerceAtMost(NOTIFICATION_MAX_EXTRA_MILLIS)
            delay(NOTIFICATION_BASE_MILLIS + extra)
            data.dismiss()
        }
    }

    AnimatedVisibility(
        visible = data != null && lastMessage.isNotEmpty(),
        modifier = modifier,
        enter = fadeIn(Springs.snappy()) + scaleIn(Springs.smooth(), initialScale = 0.25f),
        exit = fadeOut(tween(NOTIFICATION_EXIT_MILLIS)) +
            scaleOut(tween(NOTIFICATION_EXIT_MILLIS, easing = EaseOutCubic), targetScale = 0.2f)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = Dimens.pageGutter)
                .height(Dimens.notificationHeight)
                .clip(RoundedCornerShape(percent = 50))
                .background(colors.ink)
                .animateContentSize(Springs.smooth())
                .padding(start = 9.dp, end = 16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(colors.onInk.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = LineIcons.Info,
                    contentDescription = null,
                    tint = colors.onInk,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            BlurSwap(targetState = lastMessage, label = "notificationText") { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * 窗口内面板（替代 AlertDialog 的默认外观）：圆角 16、白底、1px 描边、不加阴影。
 *
 * 打开时用 smooth 弹簧从 96% 缩放、模糊淡入；标题右上角是幽灵关闭按钮，底部按钮右对齐。
 *
 * @param dismissible 下载中等不可中断的状态传 false，返回键与点遮罩都不会关闭
 * @param closeContentDescription 为 null 时不显示右上角关闭按钮
 */
@Composable
fun WmPanel(
    title: String,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dismissible: Boolean = true,
    closeContentDescription: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible,
            usePlatformDefaultWidth = false
        )
    ) {
        val colors = BtTheme.colors
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            // 系统遮罩换成规范的浅遮罩，不再是 Material 默认的大面积压暗
            window?.setDimAmount(if (colors.isDark) 0.5f else 0.26f)
        }
        val progress = remember { Animatable(0f) }
        LaunchedEffect(Unit) { progress.animateTo(1f, Springs.smooth()) }

        val shape = RoundedCornerShape(Dimens.radiusPanel)
        Column(
            modifier = modifier
                .padding(horizontal = 20.dp)
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    val p = progress.value
                    val scale = 0.96f + 0.04f * p
                    scaleX = scale
                    scaleY = scale
                    alpha = (p / PANEL_CONTENT_THRESHOLD).coerceIn(0f, 1f)
                }
                .blur(((1f - progress.value).coerceIn(0f, 1f) * 4f).dp, BlurredEdgeTreatment.Unbounded)
                .clip(shape)
                .background(colors.surface)
                .border(Dimens.stroke, colors.line, shape)
                .padding(start = 22.dp, end = 14.dp, top = 14.dp, bottom = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = colors.ink,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp)
                )
                if (closeContentDescription != null) {
                    WmIconButton(
                        icon = LineIcons.Close,
                        contentDescription = closeContentDescription,
                        onClick = onDismissRequest,
                        size = 32.dp
                    )
                }
            }
            Column(
                modifier = Modifier
                    .padding(end = 8.dp, top = 6.dp)
                    .fillMaxWidth(),
                content = content
            )
            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier
                    .padding(end = 8.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.spacingSm, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
                content = actions
            )
        }
    }
}
