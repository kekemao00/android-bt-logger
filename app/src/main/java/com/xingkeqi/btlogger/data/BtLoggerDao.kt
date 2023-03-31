package com.xingkeqi.btlogger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow


@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: Device)

    @Query("SELECT * FROM devices")
    fun getAllDevices(): Flow<List<Device>>

    @Query("SELECT devices.mac, devices.name, devices.deviceType, devices.uuids, MIN(device_connection_records.timestamp) AS firstRecordTime, MAX(device_connection_records.timestamp) AS lastRecordTime, device_connection_records.connectStatus AS connectStatus FROM devices INNER JOIN device_connection_records ON devices.mac = device_connection_records.deviceMac GROUP BY devices.mac")
    fun getDeviceInfosWithConnectionRecords(): Flow<List<DeviceInfo>>

    @Query("SELECT * FROM devices WHERE mac = :mac")
    fun getDeviceByMac(mac: String): Flow<Device>

    @Query("DELETE  FROM devices WHERE mac = :mac")
    fun deleteDeviceByMac(mac: String)

    @Query("DELETE FROM devices")
    suspend fun deleteAll()
}

@Dao
interface RecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(connectionRecord: DeviceConnectionRecord)

    @Query("SELECT * FROM device_connection_records WHERE deviceMac = :deviceMac ORDER BY timestamp DESC")
    fun getRecordsByDeviceMac(deviceMac: String): Flow<List<DeviceConnectionRecord>>

    @Query("DELETE FROM device_connection_records WHERE deviceMac= :mac")
    suspend fun deleteRecordByDeviceMac(mac: String)

    @Query("DELETE FROM device_connection_records")
    suspend fun deleteAll()
}

