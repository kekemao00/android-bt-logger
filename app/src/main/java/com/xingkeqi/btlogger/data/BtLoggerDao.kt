package com.xingkeqi.btlogger.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow


@Dao
interface DeviceDao {
    /**
     * 插入设备，如果设备已存在则忽略
     * 使用 Upsert 以便同步刷新最新编解码缓存，同时保持外键关系不被破坏
     */
    @Upsert
    suspend fun insert(device: Device)

    @Query("SELECT * FROM devices")
    fun getAllDevices(): Flow<List<Device>>

    @Query(
        """
        SELECT
            devices.mac AS mac,
            devices.name AS name,
            devices.device_type AS deviceType,
            devices.bluetooth_version AS bluetoothVersion,
            devices.uuids AS uuids,
            devices.latest_phone_supported_codecs AS latestPhoneSupportedCodecs,
            devices.latest_negotiable_codecs AS latestNegotiableCodecs,
            devices.latest_active_codec AS latestActiveCodec,
            MIN(device_connection_records.timestamp) AS firstRecordTime,
            MAX(device_connection_records.timestamp) AS lastRecordTime,
            (
                SELECT latest_record.connect_state
                FROM device_connection_records AS latest_record
                WHERE latest_record.device_mac = devices.mac
                ORDER BY latest_record.timestamp DESC, latest_record.id DESC
                LIMIT 1
            ) AS connectState
        FROM devices
        INNER JOIN device_connection_records
            ON devices.mac = device_connection_records.device_mac
        GROUP BY devices.mac
        """
    )
    fun getDeviceInfosWithConnectionRecords(): Flow<List<DeviceInfo>>

    @Query("SELECT * FROM devices WHERE mac = :mac")
    fun getDeviceByMac(mac: String): Flow<Device>

    @Query("SELECT * FROM devices WHERE mac = :mac LIMIT 1")
    suspend fun getDeviceByMacSnapshot(mac: String): Device?

    @Query("DELETE  FROM devices WHERE mac = :mac")
    suspend fun deleteDeviceByMac(mac: String)

    @Query("DELETE FROM devices")
    suspend fun deleteAll()
}

/**
 * 设备与记录联合操作 DAO
 * 提供事务保护的删除操作
 */
@Dao
abstract class DeviceWithRecordsDao {
    @Query("DELETE FROM device_connection_records WHERE device_mac = :mac")
    abstract suspend fun deleteRecordsByMac(mac: String)

    @Query("DELETE FROM devices WHERE mac = :mac")
    abstract suspend fun deleteDeviceByMac(mac: String)

    /**
     * 事务删除设备及其所有记录
     * 确保数据一致性
     */
    @Transaction
    open suspend fun deleteDeviceWithRecords(mac: String) {
        deleteRecordsByMac(mac)
        deleteDeviceByMac(mac)
    }
}

@Dao
interface RecordDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(connectionRecord: DeviceConnectionRecord)

    @Update
    suspend fun update(connectionRecord: DeviceConnectionRecord)

    @Query("SELECT * FROM device_connection_records WHERE device_mac = :deviceMac ORDER BY timestamp DESC")
    fun getRecordsByDeviceMac(deviceMac: String): Flow<List<DeviceConnectionRecord>>

    @Query(
        """
        SELECT *
        FROM device_connection_records
        WHERE device_mac = :deviceMac
            AND connect_state = :connectState
        ORDER BY timestamp DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getLatestRecordSnapshotByConnectState(
        deviceMac: String,
        connectState: Int
    ): DeviceConnectionRecord?

    @Query(
        """
        SELECT *
        FROM device_connection_records
        WHERE device_mac = :deviceMac
            AND event_type = :eventType
            AND connect_state = :connectState
        ORDER BY timestamp DESC, id DESC
        LIMIT 1
        """
    )
    suspend fun getLatestRecordSnapshotByEventAndState(
        deviceMac: String,
        eventType: String,
        connectState: Int
    ): DeviceConnectionRecord?

    @Query(
        """
        SELECT
            devices.mac AS mac,
            devices.name AS name,
            devices.device_type AS deviceType,
            devices.bluetooth_version AS bluetoothVersion,
            devices.uuids AS uuids,
            devices.bond_state AS bondState,
            devices.rssi AS rssi,
            devices.alias AS alias,
            device_connection_records.id AS id,
            device_connection_records.timestamp AS timestamp,
            device_connection_records.connect_state AS connectState,
            device_connection_records.volume AS volume,
            device_connection_records.is_playing AS isPlaying,
            device_connection_records.battery_level AS batteryLevel,
            device_connection_records.headset_battery_level AS headsetBatteryLevel,
            device_connection_records.event_type AS eventType,
            device_connection_records.phone_supported_codecs AS phoneSupportedCodecs,
            device_connection_records.negotiable_codecs AS negotiableCodecs,
            device_connection_records.active_codec AS activeCodec
        FROM devices
        INNER JOIN device_connection_records
            ON devices.mac = device_connection_records.device_mac
        WHERE devices.mac = :mac
        ORDER BY device_connection_records.timestamp DESC, device_connection_records.id DESC
        """
    )
    fun getRecordInfoListByMac(mac: String): Flow<List<RecordInfo>>

    @Query(
        """
        SELECT
            devices.mac AS mac,
            devices.name AS name,
            devices.device_type AS deviceType,
            devices.bluetooth_version AS bluetoothVersion,
            devices.uuids AS uuids,
            devices.bond_state AS bondState,
            devices.rssi AS rssi,
            devices.alias AS alias,
            device_connection_records.id AS id,
            device_connection_records.timestamp AS timestamp,
            device_connection_records.connect_state AS connectState,
            device_connection_records.volume AS volume,
            device_connection_records.is_playing AS isPlaying,
            device_connection_records.battery_level AS batteryLevel,
            device_connection_records.headset_battery_level AS headsetBatteryLevel,
            device_connection_records.event_type AS eventType,
            device_connection_records.phone_supported_codecs AS phoneSupportedCodecs,
            device_connection_records.negotiable_codecs AS negotiableCodecs,
            device_connection_records.active_codec AS activeCodec
        FROM devices
        INNER JOIN device_connection_records
            ON devices.mac = device_connection_records.device_mac
        ORDER BY device_connection_records.timestamp DESC, device_connection_records.id DESC
        """
    )
    fun getRecordInfoListAll(): Flow<List<RecordInfo>>

    @Query("DELETE FROM device_connection_records WHERE device_mac= :mac")
    suspend fun deleteRecordByDeviceMac(mac: String)
    @Query("DELETE FROM device_connection_records WHERE id= :id")
    suspend fun deleteRecordByDeviceId(id: Int)

    @Query("DELETE FROM device_connection_records")
    suspend fun deleteAll()
}
