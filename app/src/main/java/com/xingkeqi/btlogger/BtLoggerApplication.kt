package com.xingkeqi.btlogger

import android.app.Application

class BtLoggerApplication : Application() {
    companion object {
        lateinit var instance: BtLoggerApplication
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}