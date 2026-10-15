package com.xingkeqi.btlogger.update

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

/**
 * 从 GitHub Releases 检查与下载新版本。
 *
 * Why: 发布由 `.github/workflows/release.yml` 自动完成（tag -> 构建 -> 上传 APK 与 SHA256SUMS.txt），
 * 应用直接读取最新 Release，发版后无需再手动上传到第三方分发平台。
 * 只用系统自带的 HttpURLConnection，避免为一个接口引入额外网络库。
 */
class GitHubUpdateRepository(
    private val downloadDir: File,
    private val owner: String = GITHUB_OWNER,
    private val repo: String = GITHUB_REPO
) {

    private val tag = "GitHubUpdateRepository"

    /**
     * 获取最新正式版（GitHub 的 latest 接口已排除草稿与预发布版本）。
     */
    suspend fun fetchLatestRelease(): Result<ReleaseInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
            val body = httpGetText(url, accept = "application/vnd.github+json")
            parseRelease(body)
        }.onSuccess {
            Log.i(tag, "[GitHubUpdateRepository] fetchLatestRelease -> tag=${it.tagName}, assets=${it.assets.size}")
        }.onFailure {
            Log.e(tag, "[GitHubUpdateRepository] fetchLatestRelease -> failed", it)
        }
    }

    /**
     * 读取 Release 附带的 SHA256SUMS.txt；没有该文件时返回 null（不阻断更新）。
     */
    suspend fun fetchExpectedSha256(release: ReleaseInfo, asset: ReleaseAsset): String? =
        withContext(Dispatchers.IO) {
            val sumsAsset = release.assets.firstOrNull { it.name == SHA256_ASSET_NAME } ?: return@withContext null
            runCatching { parseSha256Sums(httpGetText(sumsAsset.downloadUrl))[asset.name] }
                .onFailure { Log.e(tag, "[GitHubUpdateRepository] fetchExpectedSha256 -> failed", it) }
                .getOrNull()
        }

    /**
     * 下载 APK 并校验 SHA-256。已下载且校验通过的同名文件直接复用，避免重复下载。
     *
     * @param onProgress 已下载字节数、总字节数（未知时为 0）
     */
    suspend fun download(
        asset: ReleaseAsset,
        expectedSha256: String?,
        onProgress: (downloaded: Long, total: Long) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            if (!downloadDir.exists() && !downloadDir.mkdirs()) {
                throw IOException("无法创建下载目录: ${downloadDir.path}")
            }
            val target = File(downloadDir, asset.name)
            if (target.exists() && expectedSha256 != null && sha256Of(target) == expectedSha256) {
                Log.i(tag, "[GitHubUpdateRepository] download -> reuse cached ${asset.name}")
                onProgress(target.length(), target.length())
                return@runCatching target
            }
            // 只保留当前版本的安装包，旧版本的缓存一并清理
            downloadDir.listFiles()?.forEach { it.delete() }

            val partial = File(downloadDir, "${asset.name}.part")
            val connection = openConnection(asset.downloadUrl)
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: asset.size
                var downloaded = 0L
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, total)
                        }
                    }
                }
                if (total > 0 && downloaded != total) throw IOException("下载不完整: $downloaded / $total")
            } catch (e: Throwable) {
                partial.delete()
                throw e
            } finally {
                connection.disconnect()
            }

            if (expectedSha256 != null) {
                val actual = sha256Of(partial)
                if (actual != expectedSha256) {
                    partial.delete()
                    throw IOException("SHA-256 校验失败")
                }
            }
            if (!partial.renameTo(target)) throw IOException("无法保存安装包: ${target.path}")
            target
        }.onSuccess {
            Log.i(tag, "[GitHubUpdateRepository] download -> success: ${it.name}, ${it.length()} bytes, verified=${expectedSha256 != null}")
        }.onFailure {
            Log.e(tag, "[GitHubUpdateRepository] download -> failed: ${asset.name}", it)
        }
    }

    private fun httpGetText(url: String, accept: String? = null): String {
        val connection = openConnection(url, accept)
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("HTTP $code: $url")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Release 附件地址会 302 跳转到 GitHub 的对象存储，HttpURLConnection 默认会跟随同协议跳转。
     */
    private fun openConnection(url: String, accept: String? = null): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MILLIS
            readTimeout = READ_TIMEOUT_MILLIS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "BtLogger-Android")
            accept?.let { setRequestProperty("Accept", it) }
        }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 8)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val GITHUB_OWNER = "kekemao00"
        const val GITHUB_REPO = "android-bt-logger"
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val READ_TIMEOUT_MILLIS = 20_000
    }
}
