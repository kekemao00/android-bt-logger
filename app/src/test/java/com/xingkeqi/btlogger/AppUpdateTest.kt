package com.xingkeqi.btlogger

import com.xingkeqi.btlogger.update.ReleaseAsset
import com.xingkeqi.btlogger.update.compareVersions
import com.xingkeqi.btlogger.update.findSha256
import com.xingkeqi.btlogger.update.isNewerVersion
import com.xingkeqi.btlogger.update.parseRelease
import com.xingkeqi.btlogger.update.parseSha256Sums
import com.xingkeqi.btlogger.update.selectApkAsset
import com.xingkeqi.btlogger.update.simplifyReleaseNotes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateTest {

    @Test
    fun compareVersions_comparesNumericSegments() {
        assertTrue(compareVersions("1.10.0", "1.9.3") > 0)
        assertTrue(compareVersions("v1.4.1", "1.4.0") > 0)
        assertEquals(0, compareVersions("v1.4", "1.4.0"))
        assertEquals(0, compareVersions("1.4.0-beta", "1.4.0"))
        assertTrue(compareVersions("1.3.9", "1.4.0") < 0)
    }

    @Test
    fun isNewerVersion_falseForSameOrOlder() {
        assertTrue(isNewerVersion("v1.5.0", "1.4.0"))
        assertFalse(isNewerVersion("v1.4.0", "1.4.0"))
        assertFalse(isNewerVersion("v1.3.0", "1.4.0"))
        // 读不到当前版本号时视为最旧，仍能提示更新
        assertTrue(isNewerVersion("v1.4.0", ""))
    }

    @Test
    fun selectApkAsset_prefersSameBuildType() {
        val debug = asset("BtLogger-debug-v1.5.0-123(com.xingkeqi.btlogger).apk")
        val release = asset("BtLogger-release-v1.5.0-123(com.xingkeqi.btlogger).apk")
        val sums = asset("SHA256SUMS.txt")
        val assets = listOf(sums, release, debug)

        assertEquals(debug, selectApkAsset(assets, debuggable = true))
        assertEquals(release, selectApkAsset(assets, debuggable = false))
        assertEquals(debug, selectApkAsset(listOf(sums, debug), debuggable = false))
        assertNull(selectApkAsset(listOf(sums), debuggable = true))
    }

    @Test
    fun parseSha256Sums_readsTextAndBinaryMode() {
        val hashA = "a".repeat(64)
        val hashB = "B".repeat(64)
        val sums = parseSha256Sums("$hashA  BtLogger-debug.apk\n$hashB *BtLogger-release.apk\nbroken line\n")
        assertEquals(hashA, sums["BtLogger-debug.apk"])
        assertEquals("b".repeat(64), sums["BtLogger-release.apk"])
        assertEquals(2, sums.size)
    }

    @Test
    fun findSha256_matchesAssetRenamedByGitHub() {
        val hash = "c".repeat(64)
        val sums = mapOf("BtLogger-debug-v1.5.0-11167596(com.xingkeqi.btlogger).apk" to hash)
        assertEquals(hash, findSha256(sums, "BtLogger-debug-v1.5.0-11167596.com.xingkeqi.btlogger.apk"))
        assertEquals(hash, findSha256(sums, "BtLogger-debug-v1.5.0-11167596(com.xingkeqi.btlogger).apk"))
        assertNull(findSha256(sums, "BtLogger-release-v1.5.0-11167596.com.xingkeqi.btlogger.apk"))
    }

    @Test
    fun parseRelease_readsTagNotesAndAssets() {
        val json = """
            {
              "tag_name": "v1.5.0",
              "html_url": "https://github.com/kekemao00/android-bt-logger/releases/tag/v1.5.0",
              "body": "notes",
              "assets": [
                {"name": "a.apk", "browser_download_url": "https://example.com/a.apk", "size": 1024}
              ]
            }
        """.trimIndent()
        val release = parseRelease(json)
        assertEquals("v1.5.0", release.tagName)
        assertEquals("1.5.0", release.versionName)
        assertEquals("notes", release.notes)
        assertEquals(listOf(ReleaseAsset("a.apk", "https://example.com/a.apk", 1024)), release.assets)
    }

    @Test
    fun parseRelease_toleratesNullBodyAndMissingAssets() {
        val release = parseRelease("""{"tag_name": "v1.5.0", "body": null}""")
        assertEquals("", release.notes)
        assertTrue(release.assets.isEmpty())
    }

    @Test
    fun simplifyReleaseNotes_keepsOnlyChangelog() {
        val body = """
            ## BtLogger v1.5.0

            ### 📱 下载
            - 本次未配置正式签名，仅提供 debug APK
            - **Debug APK**：调试版本，日志更完整

            ### ✨ 新功能
            - feat: 应用内检查更新

            ### 🐛 问题修复
            - fix: 修复闪退

            **完整变更**：https://github.com/x/y/compare/v1.4.0...v1.5.0

            ---
            构建时间：2026-10-15
        """.trimIndent()
        assertEquals(
            "✨ 新功能\n• feat: 应用内检查更新\n\n🐛 问题修复\n• fix: 修复闪退",
            simplifyReleaseNotes(body)
        )
    }

    private fun asset(name: String) = ReleaseAsset(name, "https://example.com/$name", 1L)
}
