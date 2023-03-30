package com.xingkeqi.btlogger.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ForeignKey.Companion.CASCADE
import androidx.room.PrimaryKey
import java.util.Date


@Entity(tableName = "device")
data class Device(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    val name: String,
    @ColumnInfo(name = "mac_address")
    val macAddress: String,
    val type: Int,
    val property: String
)

@Entity(tableName = "playback")
data class Playback(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "device_id")
    val deviceId: Int,
    val artist: String?,
    val album: String?,
    val title: String?,
    val duration: Long?,
    val position: Long?,
    val isPlaying: Boolean?,
    @ColumnInfo(name = "created_at")
    val createdAt: Date = Date()
)

@Entity(tableName = "volume")
data class Volume(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "device_id")
    val deviceId: Int,
    val value: Int,
    @ColumnInfo(name = "created_at")
    val createdAt: Date = Date()
)

@Entity(tableName = "bluetooth_devices")
data class BluetoothDevice(
    @PrimaryKey val id: String,
    val name: String?,
    val isConnected: Boolean
)

@Entity(
    tableName = "sound_delays",
    foreignKeys = [
        ForeignKey(
            entity = BluetoothDevice::class,
            parentColumns = ["id"],
            childColumns = ["deviceId"],
            onDelete = CASCADE
        )
    ]
)
data class SoundDelay(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val delay: Long,
    val deviceId: String
)


data class BluetoothDeviceConnectionLog(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    @ColumnInfo(name = "device_id")
    val deviceId: Int,
    val timestamp: Long,
    @ColumnInfo(name = "is_connected")
    val isConnected: Int
)