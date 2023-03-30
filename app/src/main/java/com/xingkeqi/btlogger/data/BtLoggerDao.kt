package com.xingkeqi.btlogger.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: Device)

    @Delete
    suspend fun delete(device: Device)

    @Query("SELECT * FROM device WHERE id = :id")
    suspend fun getDeviceById(id: Int): Device?

    @Query("SELECT * FROM device")
    fun getAllDevices(): Flow<List<Device>>
}

@Dao
interface PlaybackDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(playback: Playback)

    @Query("SELECT * FROM playback WHERE device_id = :deviceId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLastPlayback(deviceId: Int): Playback?
}

@Dao
interface VolumeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(volume: Volume)

    @Query("SELECT * FROM volume WHERE device_id = :deviceId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLastVolume(deviceId: Int): Volume?
}