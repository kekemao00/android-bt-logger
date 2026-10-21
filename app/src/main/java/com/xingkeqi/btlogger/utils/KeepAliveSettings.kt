package com.xingkeqi.btlogger.utils

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.xingkeqi.btlogger.domain.keepalive.RomVendor
import com.xingkeqi.btlogger.domain.keepalive.romVendorOf

private const val TAG = "KeepAliveSettings"

/**
 * 后台运行相关的系统状态，界面每次回到前台时重新读取（用户可能刚在系统设置里改过）
 */
data class KeepAliveSystemStatus(
    val ignoringBatteryOptimizations: Boolean = true,
    val notificationsEnabled: Boolean = true
)

/**
 * 读取系统后台限制状态与跳转对应设置页。
 *
 * Why: 原生 Android 上前台服务基本不会被杀，但 vivo、小米、OPPO、华为等系统另有一套“自启动 / 后台耗电 / 省电策略”管理，
 * 默认会在息屏或划掉任务后清理进程，只能引导用户手动放行。厂商页面没有公开 API，组件名随系统版本变化，
 * 这里按已知的组件名依次尝试，全部失败时退回应用详情页。
 */
object KeepAliveSettings {

    val currentVendor: RomVendor by lazy { romVendorOf(Build.MANUFACTURER, Build.BRAND) }

    fun readStatus(context: Context): KeepAliveSystemStatus = KeepAliveSystemStatus(
        ignoringBatteryOptimizations = isIgnoringBatteryOptimizations(context),
        notificationsEnabled = runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }
            .onFailure { Log.e(TAG, "[KeepAliveSettings] readStatus -> notifications check failed", it) }
            .getOrDefault(true)
    )

    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }.onFailure { Log.e(TAG, "[KeepAliveSettings] isIgnoringBatteryOptimizations -> failed", it) }
        .getOrDefault(true)

    /**
     * 弹出系统“允许应用始终在后台运行”确认框；部分 ROM 屏蔽了该弹框，退回电池优化列表页。
     *
     * Why: 忽略电池优化后，Doze 不再推迟广播与网络，Android 12+ 也允许从后台重新启动前台服务（看门狗与开机自启依赖它）。
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimizations(context: Context): Boolean {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        return launchFirst(context, "batteryOptimization", direct, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    fun openNotificationSettings(context: Context): Boolean {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            appDetailsIntent(context)
        }
        return launchFirst(context, "notification", intent, appDetailsIntent(context))
    }

    /**
     * 打开厂商的自启动 / 后台运行管理页；都打不开时打开应用详情页（多数 ROM 的“耗电”“自启动”入口也在这里）。
     */
    fun openVendorBackgroundSettings(context: Context, vendor: RomVendor = currentVendor): Boolean {
        val candidates = vendorIntents(context, vendor) + appDetailsIntent(context)
        return launchFirst(context, "vendor[$vendor]", *candidates.toTypedArray())
    }

    private fun vendorIntents(context: Context, vendor: RomVendor): List<Intent> {
        val pkg = context.packageName
        fun component(packageName: String, className: String) =
            Intent().setComponent(ComponentName(packageName, className))
        return when (vendor) {
            RomVendor.XIAOMI -> listOf(
                component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                // 省电策略（无限制 / 智能限制）
                component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    .putExtra("package_name", pkg)
                    .putExtra("package_label", context.applicationInfo.loadLabel(context.packageManager).toString())
            )

            RomVendor.VIVO -> listOf(
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")
            )

            RomVendor.OPPO -> listOf(
                component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
                component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
                component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                component("com.coloros.oppoguardelf", "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity")
            )

            RomVendor.HONOR -> listOf(
                component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            )

            RomVendor.HUAWEI -> listOf(
                component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            )

            RomVendor.SAMSUNG -> listOf(
                component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                component("com.samsung.android.sm_cn", "com.samsung.android.sm.ui.ram.AutoRunActivity")
            )

            RomVendor.MEIZU -> listOf(
                Intent("com.meizu.safe.security.SHOW_APPSEC").addCategory(Intent.CATEGORY_DEFAULT).putExtra("packageName", pkg),
                component("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")
            )

            RomVendor.OTHER -> emptyList()
        }
    }

    private fun appDetailsIntent(context: Context) =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /**
     * 依次尝试拉起，返回是否有一个成功。
     *
     * Why: Android 11+ 有包可见性限制，直接 startActivity 并捕获异常比先 resolveActivity 更可靠；
     * 厂商安全中心的部分页面未导出，会抛 SecurityException，同样跳过换下一个。
     */
    private fun launchFirst(context: Context, purpose: String, vararg intents: Intent): Boolean {
        intents.forEachIndexed { index, intent ->
            try {
                if (context !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Log.i(TAG, "[KeepAliveSettings] launchFirst -> opened: purpose=$purpose, candidate=$index, component=${intent.component?.className ?: intent.action}")
                return true
            } catch (e: ActivityNotFoundException) {
                Log.d(TAG, "[KeepAliveSettings] launchFirst -> not found: purpose=$purpose, candidate=$index")
            } catch (e: SecurityException) {
                Log.w(TAG, "[KeepAliveSettings] launchFirst -> not allowed: purpose=$purpose, candidate=$index", e)
            } catch (e: Exception) {
                Log.e(TAG, "[KeepAliveSettings] launchFirst -> failed: purpose=$purpose, candidate=$index", e)
            }
        }
        Log.w(TAG, "[KeepAliveSettings] launchFirst -> nothing opened: purpose=$purpose")
        return false
    }
}
