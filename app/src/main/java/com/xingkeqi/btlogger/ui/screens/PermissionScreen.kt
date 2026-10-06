package com.xingkeqi.btlogger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.xingkeqi.btlogger.R
import com.xingkeqi.btlogger.ui.components.LineIcons
import com.xingkeqi.btlogger.ui.components.WmButton
import com.xingkeqi.btlogger.ui.components.WmButtonStyle
import com.xingkeqi.btlogger.ui.theme.BtTheme
import com.xingkeqi.btlogger.ui.theme.Dimens

/**
 * 蓝牙权限引导页。
 *
 * Why:
 * 过去用户拒绝权限后界面一片空白且无法再次申请，只能卸载重装；
 * 这里给出说明并提供“重新授予”和“前往系统设置”（被永久拒绝时）两个出口。
 * 布局按规范的居中单列：图标、标题、说明、一个主按钮和一个次按钮。
 */
@Composable
fun PermissionScreen(
    showNotificationNote: Boolean,
    onRequestPermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = BtTheme.colors
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.canvas)
            .padding(horizontal = Dimens.spacingXl),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(colors.surface, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = LineIcons.Bluetooth,
                    contentDescription = null,
                    tint = colors.ink,
                    modifier = Modifier.size(Dimens.iconSizeLg)
                )
            }
            Spacer(modifier = Modifier.height(Dimens.spacingLg))
            Text(
                text = stringResource(id = R.string.permission_title),
                style = MaterialTheme.typography.headlineSmall,
                color = colors.ink,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(Dimens.spacingSm))
            Text(
                text = stringResource(id = R.string.permission_body),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.ink2,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(Dimens.spacingXl))
            WmButton(
                text = stringResource(id = R.string.permission_grant),
                onClick = onRequestPermission,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            )
            Spacer(modifier = Modifier.height(Dimens.spacingSm))
            WmButton(
                text = stringResource(id = R.string.permission_open_settings),
                onClick = onOpenAppSettings,
                style = WmButtonStyle.Secondary,
                modifier = Modifier.fillMaxWidth().height(48.dp)
            )
            if (showNotificationNote) {
                Spacer(modifier = Modifier.height(Dimens.spacingLg))
                Text(
                    text = stringResource(id = R.string.permission_notification_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.ink3,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}
