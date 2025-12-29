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
@Database(
    entities = [Device::class, DeviceConnectionRecord::class],
    version = 4
)
abstract class BtLoggerDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun connectionRecordDao(): RecordDao
    abstract fun deviceWithRecordsDao(): DeviceWithRecordsDao

    companion object {
        @Volatile
        private var INSTANCE: BtLoggerDatabase? = null

        fun getDatabase(context: Context): BtLoggerDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext, BtLoggerDatabase::class.java, "bt_logger_database"
                )
                    // 使用破坏性迁移：schema 不兼容时清空数据重建
                    // 原因：MIGRATION_2_3 无法通过 ALTER TABLE 添加外键约束
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
