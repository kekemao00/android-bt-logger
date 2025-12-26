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
    version = 3
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
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
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

/**
 * 数据库迁移 2 to 3
 * 添加外键约束和索引
 * 注意：SQLite 不支持直接添加外键，需要重建表
 */
val MIGRATION_2_3: Migration = Migration(2, 3) { database ->
    // 创建索引以提升查询性能
    database.execSQL("CREATE INDEX IF NOT EXISTS index_device_connection_records_device_mac ON device_connection_records(device_mac)")
    // 注意：外键约束在 SQLite 中无法通过 ALTER TABLE 添加
    // 新安装的用户会自动使用带外键的表结构
    // 已有用户的数据完整性由应用层事务保护
}
