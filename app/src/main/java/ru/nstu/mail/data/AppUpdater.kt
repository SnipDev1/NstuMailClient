package ru.nstu.mail.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ru.nstu.mail.BuildConfig
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val versionName: String,
    val tagName: String,
    val releaseNotes: String,
    val apkUrl: String,
    val apkName: String,
    val apkSizeBytes: Long
)

class AppUpdater(
    private val repo: String = BuildConfig.GITHUB_REPO,
    private val apiBaseUrl: String = "https://api.github.com",
    private val client: OkHttpClient = defaultClient()
) {

    suspend fun checkLatestRelease(
        currentVersion: String = BuildConfig.VERSION_NAME
    ): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val url = "${apiBaseUrl.trimEnd('/')}/repos/$repo/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "NstuMail-Android/$currentVersion")
            .build()

        client.newCall(request).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful) {
                throw IOException("GitHub API вернул HTTP ${resp.code}")
            }
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return@withContext null
            parseReleaseJson(body, currentVersion)
        }
    }

    suspend fun downloadApk(
        apkUrl: String,
        destFile: File,
        onProgress: (Float) -> Unit
    ) = withContext(Dispatchers.IO) {
        require(isTrustedGithubApkUrl(apkUrl)) {
            "Недопустимый источник загрузки APK"
        }

        destFile.parentFile?.mkdirs()
        val tempFile = File(destFile.parentFile, "${destFile.name}.tmp")
        if (tempFile.exists()) tempFile.delete()

        val request = Request.Builder()
            .url(apkUrl)
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "NstuMail-Android/${BuildConfig.VERSION_NAME}")
            .build()

        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IOException("Не удалось скачать обновление (HTTP ${resp.code})")
                }
                val finalUrl = resp.request.url.toString()
                if (!isTrustedGithubApkUrl(finalUrl)) {
                    throw SecurityException("Редирект на недоверенный домен: ${resp.request.url.host}")
                }
                val body = resp.body ?: throw IOException("Пустой ответ при загрузке APK")
                val totalBytes = body.contentLength()
                if (totalBytes > MAX_APK_BYTES) {
                    throw IOException("Файл обновления превышает допустимый размер")
                }

                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read == -1) break
                            downloaded += read
                            if (downloaded > MAX_APK_BYTES) {
                                throw IOException("Файл обновления превышает допустимый размер")
                            }
                            output.write(buffer, 0, read)
                            if (totalBytes > 0L) {
                                onProgress((downloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }

            if (destFile.exists()) destFile.delete()
            if (!tempFile.renameTo(destFile)) {
                throw IOException("Не удалось сохранить файл обновления")
            }
            onProgress(1f)
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    companion object {
        private const val MAX_APK_BYTES = 100L * 1024L * 1024L

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

        fun isTrustedGithubApkUrl(url: String): Boolean {
            val parsed = url.trim().toHttpUrlOrNull() ?: return false
            if (parsed.scheme != "https") return false
            if (parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) return false
            val host = parsed.host.lowercase()
            return host == "github.com" || host.endsWith(".githubusercontent.com")
        }

        fun isNewerVersion(remoteTag: String, currentVersion: String): Boolean {
            val remoteParts = parseVersionParts(remoteTag) ?: return false
            val currentParts = parseVersionParts(currentVersion) ?: return false
            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r != c) return r > c
            }
            return false
        }

        private fun parseVersionParts(raw: String): List<Int>? {
            val core = raw.trim()
                .removePrefix("v")
                .removePrefix("V")
                .substringBefore('-')
                .substringBefore('+')
                .trim()
            if (core.isEmpty()) return null
            val parts = core.split('.').map { it.toIntOrNull() ?: return null }
            return if (parts.isEmpty()) null else parts
        }

        fun parseReleaseJson(json: String, currentVersion: String): AppUpdateInfo? {
            val obj = JSONObject(json)
            if (obj.optBoolean("draft", false)) return null
            if (obj.optBoolean("prerelease", false)) return null

            val tagName = obj.optString("tag_name", "").trim()
            if (tagName.isEmpty() || !isNewerVersion(tagName, currentVersion)) return null

            val assets = obj.optJSONArray("assets") ?: return null
            val apkCandidates = mutableListOf<Triple<String, String, Long>>()
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val dlUrl = asset.optString("browser_download_url", "")
                val size = asset.optLong("size", 0L)
                if (name.endsWith(".apk", ignoreCase = true) && isTrustedGithubApkUrl(dlUrl)) {
                    apkCandidates += Triple(name, dlUrl, size)
                }
            }
            if (apkCandidates.isEmpty()) return null

            val chosen = apkCandidates.firstOrNull { !it.first.contains("debug", ignoreCase = true) }
                ?: apkCandidates.first()

            val cleanVersion = tagName.removePrefix("v").removePrefix("V")
            return AppUpdateInfo(
                versionName = cleanVersion,
                tagName = tagName,
                releaseNotes = obj.optString("body", "").trim(),
                apkUrl = chosen.second,
                apkName = chosen.first,
                apkSizeBytes = chosen.third
            )
        }
    }
}
