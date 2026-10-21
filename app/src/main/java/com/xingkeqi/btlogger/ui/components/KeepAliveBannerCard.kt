package com.xingkeqi.btlogger.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.blankj.utilcode.util.TimeUtils
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.domain.keepalive.KeepAliveBanner
import com.xingkeqi.btlogger.domain.keepalive.gapDurationParts
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.Dimens

/** 中断开始时间的展示格式：只到分钟，跨年概率很低 */
const val GAP_TIME_PATTERN = "MM-dd HH:mm"

/**
 * 中断时长文案，例如“37 分钟”“2 小时 5 分钟”
 */
@Composable
fun gapDurationText(durationMs: Long): String {
    val (hours, minutes) = gapDurationParts(durationMs)
    return if (hours == 0) {
        stringResource(id = R.string.keep_alive_duration_minutes, minutes)
    } else {
        stringResource(id = R.string.keep_alive_duration_hours, hours, minutes)
    }
}

/**
 * 列表页顶部的后台运行提示（无状态）：整卡可点开“后台运行设置”，右侧关闭只隐藏这一次的提示。
 */
@Composable
fun KeepAliveBannerCard(
    banner: KeepAliveBanner,
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BtTheme.colors
    val text = when (banner) {
        is KeepAliveBanner.Interrupted -> {
            val start = TimeUtils.millis2String(banner.latest.startAt, GAP_TIME_PATTERN)
            val duration = gapDurationText(banner.latest.durationMs)
            if (banner.unseenCount > 1) {
                stringResource(id = R.string.keep_alive_banner_interrupted_more, banner.unseenCount, start, duration)
            } else {
                stringResource(id = R.string.keep_alive_banner_interrupted, start, duration)
            }
        }

        KeepAliveBanner.Suggest -> stringResource(id = R.string.keep_alive_banner_suggest)
    }
    WmCard(modifier = modifier, contentPadding = 0.dp) {
        Row(
            modifier = Modifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button,
                    onClick = onOpen
                )
                .padding(start = Dimens.cardPadding, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = LineIcons.Shield,
                contentDescription = null,
                tint = if (banner is KeepAliveBanner.Interrupted) colors.danger else colors.ink2,
                modifier = Modifier.size(Dimens.iconSizeSm + 2.dp)
            )
            Spacer(modifier = Modifier.width(Dimens.spacingMd))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(Dimens.spacingSm))
            Text(
                text = stringResource(id = R.string.keep_alive_banner_action),
                style = MaterialTheme.typography.labelLarge,
                color = colors.ink
            )
            WmIconButton(
                icon = LineIcons.Close,
                contentDescription = stringResource(id = R.string.action_close),
                onClick = onDismiss,
                size = 36.dp
            )
        }
    }
}
