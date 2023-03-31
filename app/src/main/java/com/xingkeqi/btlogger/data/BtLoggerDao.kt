package com.xingkeqi.btlogger.data

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query


@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: Device)

    @Query("SELECT * FROM devices")
    fun getAllDevices(): LiveData<List<Device>>

    @Query("SELECT * FROM devices WHERE mac = :mac")
    fun getDeviceByMac(mac: String): LiveData<Device>

    @Query("SELECT * FROM devices WHERE mac = :mac")
    fun deleteDeviceByMac(mac: Int): LiveData<Device>

    @Query("DELETE FROM devices")
    suspend fun deleteAll()
}

@Dao
interface ConnectionRecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(connectionRecord: DeviceConnectionRecord)

    @Query("SELECT * FROM device_connection_records WHERE deviceMac = :deviceMac ORDER BY timestamp DESC")
    fun getRecordsByDeviceId(deviceMac: String): LiveData<List<DeviceConnectionRecord>>

    @Query("DELETE FROM device_connection_records WHERE deviceMac= :deviceId")
    suspend fun deleteRecordByDeviceId(deviceId: Int)

    @Query("DELETE FROM device_connection_records")
    suspend fun deleteAll()
}

