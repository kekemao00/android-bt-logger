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

    @Query("SELECT devices.mac AS mac, devices.name AS name, devices.device_type AS deviceType, devices.uuids as uuids, MIN(device_connection_records.timestamp) AS firstRecordTime, MAX(device_connection_records.timestamp) AS lastRecordTime, device_connection_records.connect_state AS connectState FROM devices INNER JOIN device_connection_records ON devices.mac = device_connection_records.device_mac GROUP BY devices.mac")
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

    @Query("SELECT * FROM device_connection_records WHERE device_mac = :deviceMac ORDER BY timestamp DESC")
    fun getRecordsByDeviceMac(deviceMac: String): Flow<List<DeviceConnectionRecord>>

    @Query("SELECT devices.mac AS mac,devices.name AS name, devices.device_type AS deviceType,devices.uuids AS uuids,devices.bond_state AS bondState,devices.alias AS rssi,devices.alias AS alias, device_connection_records.timestamp AS timestamp, device_connection_records.connect_state AS connectState, device_connection_records.volume AS volume, device_connection_records.is_playing AS isPlaying, device_connection_records.battery_level AS batteryLevel FROM devices INNER JOIN device_connection_records ON devices.mac = device_connection_records.device_mac WHERE devices.mac = :mac ORDER BY device_connection_records.timestamp DESC")
    fun getRecordInfoListByMac(mac: String): Flow<List<RecordInfo>>

    @Query("DELETE FROM device_connection_records WHERE device_mac= :mac")
    suspend fun deleteRecordByDeviceMac(mac: String)

    @Query("DELETE FROM device_connection_records")
    suspend fun deleteAll()
}

