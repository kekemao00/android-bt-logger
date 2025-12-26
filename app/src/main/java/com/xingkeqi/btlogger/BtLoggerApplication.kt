package com.xingkeqi.btlogger

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.pgyer.pgyersdk.PgyerSDKManager
import com.pgyer.pgyersdk.pgyerenum.Features
import com.xingkeqi.btlogger.service.BtLoggerForegroundService
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class BtLoggerApplication : Application() {
    val tag = this.javaClass.simpleName

    companion object {
        lateinit var instance: BtLoggerApplication
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        initPgyerSDK(this);
    }

    /**
     * 创建通知渠道（Android 8.0+ 必需）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BtLoggerForegroundService.CHANNEL_ID,
                "蓝牙日志记录",
                NotificationManager.IMPORTANCE_LOW // 低优先级，不发出声音
            ).apply {
                description = "用于显示蓝牙日志记录服务的运行状态"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     *  初始化蒲公英SDK
     * @param application
     *
     */
    private fun initPgyerSDK(application: Application) {
        PgyerSDKManager.Init()
            .setContext(application)
            .enable(Features.APP_LAUNCH_TIME)
            .enable(Features.APP_PAGE_CATON)
            .enable(Features.CHECK_UPDATE)
            .start()
    }
}