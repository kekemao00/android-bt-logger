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
    version = 8
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
                    .addMigrations(MIGRATION_7_8)
                    // 更早的版本没有迁移：schema 不兼容时清空数据重建
                    // 原因：MIGRATION_2_3 无法通过 ALTER TABLE 添加外键约束
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

/**
 * 7 → 8：记录表新增左耳、右耳与充电仓电量列。
 *
 * Why: 测试同事手机上已有续航测试记录，只靠破坏性迁移会在升级时清空全部数据；
 * 新增列只需 ALTER TABLE，旧记录取默认值 -1（未知）。
 */
internal val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf("left_battery_level", "right_battery_level", "case_battery_level").forEach { column ->
            db.execSQL("ALTER TABLE device_connection_records ADD COLUMN $column INTEGER NOT NULL DEFAULT -1")
        }
    }
}
