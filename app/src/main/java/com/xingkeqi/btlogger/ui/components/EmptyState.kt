package com.xingkeqi.btlogger.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.ui.theme.Springs

/**
 * 通用空状态：surface-2 圆 + 线性图标 + 标题 + 说明 + 可选操作按钮，居中淡入
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val colors = BtTheme.colors
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, Springs.smooth()) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { alpha = appear.value.coerceIn(0f, 1f) }
            .padding(horizontal = Dimens.spacingXl, vertical = Dimens.spacingXxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(Dimens.emptyStateCircle)
                .background(colors.surface2, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = colors.ink2,
                modifier = Modifier.size(Dimens.iconSizeMd)
            )
        }
        Spacer(modifier = Modifier.height(Dimens.spacingLg))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = colors.ink,
            textAlign = TextAlign.Center
        )
        if (body.isNotBlank()) {
            Spacer(modifier = Modifier.height(Dimens.spacingSm))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink2,
                textAlign = TextAlign.Center
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(Dimens.spacingXl))
            WmButton(text = actionLabel, onClick = onAction, style = WmButtonStyle.Secondary, icon = LineIcons.Bluetooth)
        }
    }
}
