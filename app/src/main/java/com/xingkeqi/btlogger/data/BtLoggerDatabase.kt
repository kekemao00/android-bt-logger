package com.xingkeqi.btlogger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase


/**
 * 创建数据库
 *
 * @constructor Create empty My database
 */
@Database(entities = [Device::class, DeviceConnectionRecord::class], version = 1)
abstract class BtLoggerDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun connectionRecordDao(): ConnectionRecordDao

    companion object {
        @Volatile
        private var INSTANCE: BtLoggerDatabase? = null

        fun getDatabase(context: Context): BtLoggerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    BtLoggerDatabase::class.java,
                    "my_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}