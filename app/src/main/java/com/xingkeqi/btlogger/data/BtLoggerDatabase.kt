package com.xingkeqi.btlogger.data

import android.content.Context
import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

abstract class BtLoggerDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun playbackDao(): PlaybackDao
    abstract fun volumeDao(): VolumeDao

    companion object {
        @Volatile
        private var INSTANCE: BtLoggerDatabase? = null

        fun getDatabase(context: Context): BtLoggerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BtLoggerDatabase::class.java,
                    "app_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

/**
 * // 获取数据库实例
 * val db = AppDatabase.getDatabase(context)
 *
 * // 获取 DeviceDao 实例
 * val deviceDao = db.deviceDao()
 *
 * // 插入设备信息
 * val device = Device(name = "TWS Earbuds", macAddress = "00:11:22:33:44:55", type = 1, property = "Property1")
 * deviceDao.insert(device)
 *
 * // 获取设备信息
 * val device = deviceDao.getDeviceById(deviceId)
 *
 * // 获取 PlaybackDao 实例
 * val playbackDao =
 */