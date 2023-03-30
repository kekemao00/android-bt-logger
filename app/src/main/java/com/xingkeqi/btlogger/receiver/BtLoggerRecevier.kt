package com.xingkeqi.btlogger.receiver

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log


class BtLoggerReceiver : BroadcastReceiver() {

    val tag = this.javaClass.simpleName

    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent!!.action

        if (action == BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED) {
            val state = intent.getIntExtra(
                BluetoothAdapter.EXTRA_CONNECTION_STATE,
                BluetoothAdapter.ERROR
            )
            when (state) {
                BluetoothAdapter.STATE_CONNECTED -> {
                    Log.i(tag, "onReceive: 已连接+")
                }

                BluetoothAdapter.STATE_DISCONNECTED -> {

                }
            }
        }
    }
}

/**
 * BluetoothReceiver receiver = new BluetoothReceiver();
 * IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
 * registerReceiver(receiver, filter);
 *
 * // 当您不再需要接收通知时，取消注册
 * unregisterReceiver(receiver);
 */