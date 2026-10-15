package com.xingkeqi.btlogger.update

import org.json.JSONObject

/**
 * GitHub Release 中与应用内更新相关的字段。
 *
 * Why: 版本号来自 Release 流水线的 tag（`v1.4.0`），与应用 versionName（`1.4.0`）同源，
 * 因此直接用 tag 比较即可判断是否有新版本，不需要额外维护版本清单文件。
 */
data class ReleaseInfo(
    val tagName: String,
    val notes: String,
    val htmlUrl: String,
    val assets: List<ReleaseAsset>
) {
    /** 去掉前缀 v 后的版本号，与 versionName 格式一致 */
    val versionName: String get() = tagName.removePrefix("v").removePrefix("V")
}

data class ReleaseAsset(
    val name: String,
    val downloadUrl: String,
    val size: Long
)

/** Release 流水线随 APK 一起上传的校验文件名 */
const val SHA256_ASSET_NAME = "SHA256SUMS.txt"

/**
 * 解析 GitHub `GET /repos/{owner}/{repo}/releases/latest` 的响应。
 */
fun parseRelease(json: String): ReleaseInfo {
    val root = JSONObject(json)
    val assetsJson = root.optJSONArray("assets")
    val assets = buildList {
        if (assetsJson != null) {
            for (i in 0 until assetsJson.length()) {
                val asset = assetsJson.getJSONObject(i)
                add(
                    ReleaseAsset(
                        name = asset.optString("name"),
                        downloadUrl = asset.optString("browser_download_url"),
                        size = asset.optLong("size", 0L)
                    )
                )
            }
        }
    }
    return ReleaseInfo(
        tagName = root.getString("tag_name"),
        notes = root.optString("body").takeUnless { root.isNull("body") }.orEmpty(),
        htmlUrl = root.optString("html_url"),
        assets = assets
    )
}

/**
 * 按数字逐段比较版本号（`1.10.0` > `1.9.3`），忽略前缀 v 与 `-beta` 等后缀。
 *
 * @return 正数表示 [a] 更新，负数表示 [b] 更新，0 表示相同
 */
fun compareVersions(a: String, b: String): Int {
    val left = versionParts(a)
    val right = versionParts(b)
    for (i in 0 until maxOf(left.size, right.size)) {
        val diff = left.getOrElse(i) { 0 }.compareTo(right.getOrElse(i) { 0 })
        if (diff != 0) return diff
    }
    return 0
}

fun isNewerVersion(remote: String, current: String): Boolean = compareVersions(remote, current) > 0

private fun versionParts(version: String): List<Int> =
    version.trim()
        .removePrefix("v").removePrefix("V")
        .substringBefore('-')
        .substringBefore('+')
        .split('.')
        .map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }

/**
 * 选择与当前安装包同类型的 APK。
 *
 * Why: debug 与 release 包签名不同，系统只允许同签名覆盖安装；
 * 当前是 debug 包就只升级到 debug 包，反之亦然，找不到同类型时才退回任意 APK。
 */
fun selectApkAsset(assets: List<ReleaseAsset>, debuggable: Boolean): ReleaseAsset? {
    val apks = assets.filter { it.name.endsWith(".apk", ignoreCase = true) && it.downloadUrl.isNotBlank() }
    val variant = if (debuggable) "-debug-" else "-release-"
    return apks.firstOrNull { it.name.contains(variant, ignoreCase = true) } ?: apks.firstOrNull()
}

/**
 * 解析 `sha256sum` 输出（`<hash>  <文件名>`，二进制模式文件名前带 `*`）。
 *
 * @return 文件名 -> 小写十六进制哈希
 */
fun parseSha256Sums(text: String): Map<String, String> =
    text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val hash = line.substringBefore(' ').lowercase()
            val name = line.substringAfter(' ').trim().removePrefix("*")
            if (hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' } && name.isNotEmpty()) {
                name to hash
            } else {
                null
            }
        }
        .toMap()

/**
 * 在 SHA256SUMS.txt 中查找附件的哈希。
 *
 * Why: GitHub 上传附件时会把括号等字符改写成 `.`（v1.5.0 的
 * `...-123(com.xingkeqi.btlogger).apk` 变成了 `...-123.com.xingkeqi.btlogger.apk`），
 * 而校验文件里记录的是构建时的原始文件名，因此精确匹配失败时按同样规则归一化后再比。
 */
fun findSha256(sums: Map<String, String>, assetName: String): String? =
    sums[assetName] ?: normalizeAssetName(assetName).let { target ->
        sums.entries.firstOrNull { normalizeAssetName(it.key) == target }?.value
    }

private fun normalizeAssetName(name: String): String =
    name.replace(Regex("[^A-Za-z0-9._-]"), ".").replace(Regex("\\.{2,}"), ".")

/**
 * 把 Release 说明精简为适合弹框展示的纯文本。
 *
 * Why: Release 说明由流水线生成，包含标题、下载说明、对比链接与构建信息，
 * 在弹框里只保留按类型分组的更新日志即可。
 */
fun simplifyReleaseNotes(body: String): String {
    val result = mutableListOf<String>()
    var skipSection = false
    for (raw in body.lineSequence()) {
        val line = raw.trimEnd()
        val trimmed = line.trim()
        if (trimmed == "---") break
        when {
            trimmed.startsWith("## ") -> continue
            trimmed.startsWith("### ") -> {
                skipSection = trimmed.contains("下载")
                if (!skipSection) result += trimmed.removePrefix("### ").trim()
            }
            skipSection -> continue
            trimmed.startsWith("**完整变更**") -> continue
            trimmed.startsWith("- ") || trimmed.startsWith("* ") -> result += "• " + trimmed.drop(2).replace("**", "")
            trimmed.isEmpty() -> if (result.isNotEmpty() && result.last().isNotEmpty()) result += ""
            else -> result += trimmed.replace("**", "")
        }
    }
    return result.joinToString("\n").trim()
}
