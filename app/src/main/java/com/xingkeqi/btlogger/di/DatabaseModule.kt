package com.xingkeqi.btlogger.di

import android.content.Context
import com.xingkeqi.btlogger.data.BtLoggerDatabase
import com.xingkeqi.btlogger.data.DeviceDao
import com.xingkeqi.btlogger.data.DeviceWithRecordsDao
import com.xingkeqi.btlogger.data.RecordDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 数据库模块
 * 提供数据库和 DAO 的依赖注入
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): BtLoggerDatabase {
        return BtLoggerDatabase.getDatabase(context)
    }

    @Provides
    fun provideDeviceDao(database: BtLoggerDatabase): DeviceDao {
        return database.deviceDao()
    }

    @Provides
    fun provideRecordDao(database: BtLoggerDatabase): RecordDao {
        return database.connectionRecordDao()
    }

    @Provides
    fun provideDeviceWithRecordsDao(database: BtLoggerDatabase): DeviceWithRecordsDao {
        return database.deviceWithRecordsDao()
    }
}
