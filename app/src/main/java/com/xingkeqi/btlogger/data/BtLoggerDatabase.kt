package com.xingkeqi.btlogger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase


/**
 * 创建数据库
 *
 * @constructor Create empty My database
 */
@Database(
    entities = [Device::class, DeviceConnectionRecord::class],
    version = 2
)
abstract class BtLoggerDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao
    abstract fun connectionRecordDao(): RecordDao

    companion object {
        @Volatile
        private var INSTANCE: BtLoggerDatabase? = null

        fun getDatabase(context: Context): BtLoggerDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext, BtLoggerDatabase::class.java, "bt_logger_database"
                ).addMigrations(MIGRATION_1_2)
                    .build()
                    //在 build() 之后，添加一个 also 代码块并分配 Instance = it 以保留对最近创建的数据库实例的引用。
                    .also { INSTANCE = it }
            }
        }
    }
}


/**
 * 数据库迁移 1 to 2
 * 因为不做什么 ，方法参数为空
 */
val MIGRATION_1_2: Migration = Migration(1, 2) {}
