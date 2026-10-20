package com.xingkeqi.btlogger.service.earbud

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * 用 RFCOMM 串口服务建立 SOUNDPEATS 读取通道。
 */
object RfcommEarbudLink {

    private const val TAG = "SoundpeatsReader"

    /**
     * 依次尝试加密与非加密 RFCOMM，与 App 的前两种方式一致。
     *
     * Why: 不使用 App 的反射 `createRfcommSocket(1)` 兜底：固定通道 1 可能连到 HFP 等其他服务，向它发厂商指令后果不可控。
     *
     * @return 连不上时为 null；缺少蓝牙连接权限时抛出 [SecurityException]
     */
    @SuppressLint("MissingPermission")
    suspend fun open(device: BluetoothDevice, address: String): EarbudLink? {
        val uuid = UUID.fromString(SoundpeatsProtocol.SPP_UUID)
        cancelDiscoverySafely()
        for (secure in listOf(true, false)) {
            currentCoroutineContext().ensureActive()
            val socket = try {
                if (secure) device.createRfcommSocketToServiceRecord(uuid) else device.createInsecureRfcommSocketToServiceRecord(uuid)
            } catch (e: IOException) {
                Log.w(TAG, "[RfcommEarbudLink] open -> create failed: device=$address, secure=$secure", e)
                continue
            }
            try {
                connectCancellable(socket)
                return SocketLink(socket)
            } catch (e: CancellationException) {
                closeQuietly(socket, address)
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "[RfcommEarbudLink] open -> connect failed: device=$address, secure=$secure", e)
                closeQuietly(socket, address)
            }
        }
        return null
    }

    /**
     * connect 是阻塞调用且不响应线程中断；停止读取时关闭 socket 让它立即返回。
     */
    private suspend fun connectCancellable(socket: BluetoothSocket) = coroutineScope {
        val connecting = async(Dispatchers.IO) {
            try {
                socket.connect()
            } catch (e: IOException) {
                if (isActive) throw e
            }
        }
        try {
            connecting.await()
        } catch (e: CancellationException) {
            closeQuietly(socket, "connect")
            throw e
        }
    }

    /** 正在搜索设备时 RFCOMM 连接会明显变慢甚至失败 */
    @SuppressLint("MissingPermission")
    private fun cancelDiscoverySafely() {
        try {
            @Suppress("DEPRECATION")
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
            if (adapter.isDiscovering) adapter.cancelDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "[RfcommEarbudLink] cancelDiscoverySafely -> no scan permission", e)
        }
    }

    private fun closeQuietly(socket: BluetoothSocket, address: String) {
        try {
            socket.close()
        } catch (e: IOException) {
            Log.w(TAG, "[RfcommEarbudLink] closeQuietly -> failed: device=$address", e)
        }
    }

    private class SocketLink(private val socket: BluetoothSocket) : EarbudLink {
        override val input: InputStream = socket.inputStream
        override val output: OutputStream = socket.outputStream
        override fun close() = socket.close()
    }
}
