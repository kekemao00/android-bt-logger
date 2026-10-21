package com.xingkeqi.btlogger.ui.dialogs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.blankj.utilcode.util.TimeUtils
import com.xingkeqi.btlogger.KeepAliveUiState
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.domain.keepalive.RomVendor
import com.xingkeqi.btlogger.ui.components.GAP_TIME_PATTERN
import com.xingkeqi.btlogger.ui.components.SectionCaption
import com.xingkeqi.btlogger.ui.components.WmButton
import com.xingkeqi.btlogger.ui.components.WmButtonStyle
import com.xingkeqi.btlogger.ui.components.WmChip
import com.xingkeqi.btlogger.ui.components.WmPanel
import com.xingkeqi.btlogger.ui.components.WmSwitch
import com.xingkeqi.btlogger.ui.components.gapDurationText
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.Dimens
import com.xingkeqi.btlogger.ui.theme.MonoTextStyle

/** 面板里最多列出的中断条数 */
private const val GAP_LIST_LIMIT = 5

/**
 * 后台运行设置（无状态）：前台常亮开关、系统放行项与最近的后台中断。
 *
 * 放行项按“影响从大到小”排列：电池优化决定能否被重新拉起，通知决定是否被当作前台服务，厂商页面决定会不会被一键清理。
 */
@Composable
fun KeepAliveDialog(
    state: KeepAliveUiState,
    showNotificationItem: Boolean,
    onKeepScreenOnChange: (Boolean) -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onOpenVendorSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = BtTheme.colors
    WmPanel(
        title = stringResource(id = R.string.keep_alive_title),
        onDismissRequest = onDismiss,
        closeContentDescription = stringResource(id = R.string.action_close),
        actions = {
            WmButton(text = stringResource(id = R.string.action_done), onClick = onDismiss)
        }
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                text = stringResource(id = R.string.keep_alive_intro),
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink2
            )

            Spacer(modifier = Modifier.height(Dimens.spacingLg))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(id = R.string.keep_alive_screen_on),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.ink,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(Dimens.spacingMd))
                WmSwitch(checked = state.keepScreenOn, onCheckedChange = onKeepScreenOnChange)
            }
            Spacer(modifier = Modifier.height(Dimens.spacingXs))
            Text(
                text = stringResource(id = R.string.keep_alive_screen_on_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3
            )

            Spacer(modifier = Modifier.height(Dimens.spacingLg))
            KeepAliveItem(
                title = stringResource(id = R.string.keep_alive_battery_title),
                enabled = state.status.ignoringBatteryOptimizations,
                hint = stringResource(id = R.string.keep_alive_battery_hint),
                onAction = onRequestBatteryOptimization
            )
            if (showNotificationItem) {
                Spacer(modifier = Modifier.height(Dimens.spacingLg))
                KeepAliveItem(
                    title = stringResource(id = R.string.keep_alive_notification_title),
                    enabled = state.status.notificationsEnabled,
                    hint = stringResource(id = R.string.keep_alive_notification_hint),
                    onAction = onOpenNotificationSettings
                )
            }
            Spacer(modifier = Modifier.height(Dimens.spacingLg))
            KeepAliveItem(
                title = stringResource(id = R.string.keep_alive_vendor_title),
                // 厂商的自启动开关读不到，只能给出操作提示，按钮一直可点
                enabled = null,
                hint = stringResource(id = vendorHintRes(state.vendor)) + "\n" +
                    stringResource(id = R.string.keep_alive_lock_task),
                onAction = onOpenVendorSettings
            )

            Spacer(modifier = Modifier.height(Dimens.spacingMd))
            Text(
                text = stringResource(id = R.string.keep_alive_earbud_note),
                style = MaterialTheme.typography.bodySmall,
                color = colors.ink3
            )

            Spacer(modifier = Modifier.height(Dimens.spacingXl))
            SectionCaption(text = stringResource(id = R.string.keep_alive_gaps_title))
            Spacer(modifier = Modifier.height(Dimens.spacingSm))
            if (state.recentGaps.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.keep_alive_gaps_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink2
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    state.recentGaps.take(GAP_LIST_LIMIT).forEach { gap ->
                        Text(
                            text = stringResource(
                                id = R.string.keep_alive_gap_item,
                                TimeUtils.millis2String(gap.startAt, GAP_TIME_PATTERN),
                                gapDurationText(gap.durationMs)
                            ),
                            style = MonoTextStyle,
                            color = colors.ink
                        )
                    }
                }
                Spacer(modifier = Modifier.height(Dimens.spacingSm))
                Text(
                    text = stringResource(id = R.string.keep_alive_gaps_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink3
                )
            }
        }
    }
}

/**
 * 一项放行设置：标题 + 状态 chip + 去设置按钮，下方是说明。
 *
 * @param enabled 是否已放行；null 表示读不到状态，不显示 chip
 */
@Composable
private fun KeepAliveItem(
    title: String,
    enabled: Boolean?,
    hint: String,
    onAction: () -> Unit
) {
    val colors = BtTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = colors.ink,
            modifier = Modifier.weight(1f, fill = false)
        )
        if (enabled != null) {
            Spacer(modifier = Modifier.width(Dimens.spacingSm))
            WmChip(
                text = stringResource(id = if (enabled) R.string.keep_alive_status_ok else R.string.keep_alive_status_off),
                dotColor = if (enabled) colors.accent else colors.danger,
                contentColor = if (enabled) colors.ink else colors.ink2
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (enabled != true) {
            WmButton(
                text = stringResource(id = R.string.keep_alive_go_settings),
                onClick = onAction,
                style = WmButtonStyle.Secondary
            )
        }
    }
    Spacer(modifier = Modifier.height(Dimens.spacingXs))
    Text(
        text = hint,
        style = MaterialTheme.typography.bodySmall,
        color = colors.ink3
    )
}

private fun vendorHintRes(vendor: RomVendor): Int = when (vendor) {
    RomVendor.XIAOMI -> R.string.keep_alive_vendor_xiaomi
    RomVendor.VIVO -> R.string.keep_alive_vendor_vivo
    RomVendor.OPPO -> R.string.keep_alive_vendor_oppo
    RomVendor.HUAWEI -> R.string.keep_alive_vendor_huawei
    RomVendor.HONOR -> R.string.keep_alive_vendor_honor
    RomVendor.SAMSUNG -> R.string.keep_alive_vendor_samsung
    RomVendor.MEIZU -> R.string.keep_alive_vendor_meizu
    RomVendor.OTHER -> R.string.keep_alive_vendor_other
}
