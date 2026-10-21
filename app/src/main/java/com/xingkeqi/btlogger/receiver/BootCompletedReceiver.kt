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
 * 开机、应用更新或看门狗闹钟到点后自动拉起前台记录服务。
 *
 * Why:
 * 过去必须手动打开一次 App 才会开始记录，重启手机后的连接事件全部丢失；
 * Manifest 早已声明 RECEIVE_BOOT_COMPLETED 权限，但没有对应的接收器。
 * 看门狗（见 ServiceKeepAlive）用于部分 ROM 杀掉进程后不按 START_STICKY 重建服务的情况。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action !in HANDLED_ACTIONS) return
        if (action == ACTION_WATCHDOG && BtLoggerForegroundService.isRunning) {
            Log.d(TAG, "[BootCompletedReceiver] onReceive -> watchdog: service alive, skip")
            return
        }
        if (!hasBluetoothPermission(context)) {
            Log.i(TAG, "[BootCompletedReceiver] onReceive -> skip, bluetooth permission not granted: action=$action")
            return
        }
        try {
            BtLoggerForegroundService.start(context.applicationContext)
            Log.i(TAG, "[BootCompletedReceiver] onReceive -> service started: action=$action")
        } catch (e: Exception) {
            // Android 12+ 从后台启动前台服务受限：未忽略电池优化时看门狗可能被拒绝，等用户下次打开 App 再启动
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

    companion object {
        private const val TAG = "BootCompletedReceiver"

        /** 看门狗闹钟；只通过显式 PendingIntent 投递，接收器不导出 */
        const val ACTION_WATCHDOG = "com.xingkeqi.btlogger.action.SERVICE_WATCHDOG"

        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            ACTION_WATCHDOG
        )
    }
}
