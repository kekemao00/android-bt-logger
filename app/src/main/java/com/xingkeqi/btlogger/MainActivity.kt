package com.xingkeqi.btlogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.xingkeqi.btlogger.data.MessageEvent
import com.xingkeqi.btlogger.data.RecordEventType
import com.xingkeqi.btlogger.service.BtLoggerForegroundService
import com.xingkeqi.btlogger.ui.BtLoggerApp
import com.xingkeqi.btlogger.ui.screens.PermissionScreen
import com.xingkeqi.btlogger.ui.theme.BtLoggerTheme
import com.xingkeqi.btlogger.utils.readMediaVolumeSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MainActivity : ComponentActivity() {

    private val tag: String = "MainActivity"

    private val viewModel: MainViewModel by viewModels {
        MainViewModel.provideFactory(applicationContext)
    }

    private var bluetoothPermissionGranted by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        Log.i(tag, "[MainActivity] permissionLauncher -> result=$result")
        onPermissionStateChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        bluetoothPermissionGranted = hasBluetoothPermission()

        setContent {
            BtLoggerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    if (bluetoothPermissionGranted) {
                        BtLoggerApp(viewModel = viewModel, onExit = { finish() })
                    } else {
                        PermissionScreen(
                            showNotificationNote = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU,
                            onRequestPermission = ::requestRuntimePermissions,
                            onOpenAppSettings = ::openAppSettings
                        )
                    }
                }
            }
        }

        if (savedInstanceState == null) {
            if (bluetoothPermissionGranted) {
                // 已授权时仍补申请通知权限（Android 13+ 前台服务通知依赖它）
                requestNotificationPermissionIfNeeded()
            } else {
                requestRuntimePermissions()
            }
            viewModel.checkUpdate(manual = false)
        }
        onPermissionStateChanged()

        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
    }

    override fun onResume() {
        super.onResume()
        // 用户可能从系统设置页授权后返回
        onPermissionStateChanged()
        refreshMediaVolumeSnapshot()
    }

    override fun onDestroy() {
        // 不停止前台服务：关闭界面后仍需在后台持续记录
        EventBus.getDefault().unregister(this)
        super.onDestroy()
    }

    /**
     * 连接状态变化后刷新弹框中的音量快照；固定音量本身由前台服务执行
     */
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onMessageEvent(event: MessageEvent) {
        val type = event.record.eventType
        if (type == RecordEventType.CONNECTED || type == RecordEventType.DISCONNECTED) {
            refreshMediaVolumeSnapshot(delayMillis = 350L)
        }
    }

    private fun onPermissionStateChanged() {
        bluetoothPermissionGranted = hasBluetoothPermission()
        if (bluetoothPermissionGranted) {
            // 权限授予后才启动前台服务，避免 Android 12+ SecurityException
            runCatching { BtLoggerForegroundService.start(applicationContext) }
                .onFailure { Log.e(tag, "[MainActivity] startService -> failed", it) }
        }
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestRuntimePermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // 连接依赖 BLUETOOTH_CONNECT，版本广播探测额外依赖 BLUETOOTH_SCAN
                add(Manifest.permission.BLUETOOTH_CONNECT)
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (permissions.isNotEmpty()) {
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            )
        }.onFailure { Log.e(tag, "[MainActivity] openAppSettings -> failed", it) }
    }

    private fun refreshMediaVolumeSnapshot(delayMillis: Long = 0L) {
        lifecycleScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            runCatching { readMediaVolumeSnapshot(this@MainActivity) }
                .onSuccess { viewModel.updateMediaVolumeSnapshot(it) }
                .onFailure { Log.e(tag, "[MainActivity] refreshMediaVolumeSnapshot -> failed", it) }
        }
    }
}
