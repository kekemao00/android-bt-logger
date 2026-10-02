package com.xingkeqi.btlogger.receiver

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.xingkeqi.btlogger.service.BtLoggerForegroundService

/**
 * 开机或应用更新后自动拉起前台记录服务。
 *
 * Why:
 * 过去必须手动打开一次 App 才会开始记录，重启手机后的连接事件全部丢失；
 * Manifest 早已声明 RECEIVE_BOOT_COMPLETED 权限，但没有对应的接收器。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!hasBluetoothPermission(context)) {
            Log.i(TAG, "[BootCompletedReceiver] onReceive -> skip, bluetooth permission not granted: action=$action")
            return
        }
        try {
            BtLoggerForegroundService.start(context.applicationContext)
            Log.i(TAG, "[BootCompletedReceiver] onReceive -> service started: action=$action")
        } catch (e: Exception) {
            Log.e(TAG, "[BootCompletedReceiver] onReceive -> start service failed: action=$action", e)
        }
    }

    private fun hasBluetoothPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        const val TAG = "BootCompletedReceiver"
    }
}
