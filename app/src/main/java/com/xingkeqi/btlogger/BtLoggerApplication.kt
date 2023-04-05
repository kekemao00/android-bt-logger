package com.xingkeqi.btlogger

import android.app.Application
import android.content.Context
import com.pgyer.pgyersdk.PgyerSDKManager
import com.pgyer.pgyersdk.pgyerenum.Features
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
    }

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        initPgyerSDK(this);
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