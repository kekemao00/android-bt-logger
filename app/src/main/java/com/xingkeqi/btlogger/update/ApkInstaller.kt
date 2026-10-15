package com.xingkeqi.btlogger.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.security.MessageDigest

/**
 * 安装包相关的系统交互：签名比对、未知来源授权与拉起系统安装器。
 */
object ApkInstaller {

    private const val TAG = "ApkInstaller"
    private const val APK_MIME = "application/vnd.android.package-archive"

    /** 当前安装的是否为 debug 包，用于选择同类型（同签名）的更新包 */
    fun isDebuggable(context: Context): Boolean =
        context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /**
     * 判断安装包签名是否与当前应用一致。
     *
     * Why: 签名不一致时系统安装器只会提示“安装失败/与已安装应用冲突”，用户无从下手；
     * 提前比对可以直接告诉用户需要先导出数据再卸载重装。
     *
     * @return null 表示无法判断（旧系统读取不到 v2 签名等），此时照常交给系统安装器
     */
    fun signatureMatches(context: Context, apk: File): Boolean? = runCatching {
        val pm = context.packageManager
        val archive = archiveInfo(pm, apk) ?: return@runCatching null
        if (archive.packageName != context.packageName) return@runCatching false
        val installed = installedInfo(pm, context.packageName)
        val archiveSigners = signerDigests(archive)
        val installedSigners = signerDigests(installed)
        if (archiveSigners.isEmpty() || installedSigners.isEmpty()) null else archiveSigners == installedSigners
    }.onFailure {
        Log.e(TAG, "[ApkInstaller] signatureMatches -> failed", it)
    }.getOrNull()

    /** Android 8.0+ 安装 APK 需要用户为本应用开启“安装未知应用” */
    fun canRequestInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun openUnknownSourcesSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return startSafely(context, intent, "openUnknownSourcesSettings")
    }

    fun install(context: Context, apk: File): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileProvider", apk)
        }.onFailure {
            Log.e(TAG, "[ApkInstaller] install -> FileProvider path not configured", it)
        }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        return startSafely(context, intent, "install")
    }

    fun openUrl(context: Context, url: String): Boolean =
        startSafely(
            context,
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            "openUrl"
        )

    private fun startSafely(context: Context, intent: Intent, action: String): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: ActivityNotFoundException) {
        Log.e(TAG, "[ApkInstaller] $action -> no activity", e)
        false
    } catch (e: SecurityException) {
        Log.e(TAG, "[ApkInstaller] $action -> denied", e)
        false
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager, packageName: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
        }

    @Suppress("DEPRECATION")
    private fun signerDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners
        } else {
            info.signatures
        }
        return signatures.orEmpty().map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.toSet()
    }
}
