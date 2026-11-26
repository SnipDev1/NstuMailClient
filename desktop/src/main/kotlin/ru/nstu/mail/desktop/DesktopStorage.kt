package ru.nstu.mail.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import ru.nstu.mail.data.Credentials
import ru.nstu.mail.data.DEFAULT_SERVER_URL
import ru.nstu.mail.data.DEFAULT_TAG_TEMPLATES
import ru.nstu.mail.data.TagSortRule
import ru.nstu.mail.data.formatTagPath
import ru.nstu.mail.data.parseTagPath
import ru.nstu.mail.data.sortTagTemplatesHierarchically
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class DesktopThemeMode(val labelRu: String) {
    SYSTEM("Системная"),
    LIGHT("Светлая"),
    DARK("Тёмная")
}

enum class DesktopUpdateChannel(val labelRu: String, val descriptionRu: String) {
    STABLE("Актуальная", "Стабильные релизы (ветка main)"),
    TEST("Тестовая", "Ранние тестовые сборки (ветка test)")
}

data class DesktopUpdateInfo(
    val versionName: String,
    val tagName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val assetName: String,
    val htmlUrl: String,
    val isPrerelease: Boolean
)

data class DesktopSettings(
    val themeMode: DesktopThemeMode = DesktopThemeMode.SYSTEM,
    val alwaysLightEmailBody: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val mutedFolderIds: Set<String> = emptySet(),
    val mutedSenders: Set<String> = emptySet(),
    val tagTemplates: List<String> = DEFAULT_TAG_TEMPLATES,
    val tagSortRules: List<TagSortRule> = emptyList(),
    val inboxFolderId: String? = null,
    val updateChannel: DesktopUpdateChannel = if ('-' in DesktopBuildInfo.VERSION_NAME) {
        DesktopUpdateChannel.TEST
    } else {
        DesktopUpdateChannel.STABLE
    }
)

object DesktopConfigPaths {
    fun defaultConfigDir(): File {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return if ("win" in os) {
            val appData = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
                ?: File(System.getProperty("user.home"), "AppData/Roaming").absolutePath
            File(appData, "NstuMail")
        } else {
            val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
                ?: File(System.getProperty("user.home"), ".config").absolutePath
            File(xdg, "nstumail")
        }
    }
}

private fun restrictOwnerOnly(file: File) {
    runCatching {
        val perms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        Files.setPosixFilePermissions(file.toPath(), perms)
    }
}

class DesktopCredentialStore(private val configDir: File = DesktopConfigPaths.defaultConfigDir()) {
    private val keyFile = File(configDir, "key.bin")
    private val credFile = File(configDir, "credentials.bin")

    fun save(creds: Credentials) {
        configDir.mkdirs()
        val payload = JSONObject().apply {
            put("email", creds.email)
            put("password", creds.password)
            put("serverUrl", creds.serverUrl)
        }.toString().toByteArray(Charsets.UTF_8)

        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        }
        val enc = cipher.doFinal(payload)
        credFile.writeBytes(iv + enc)
        restrictOwnerOnly(credFile)
    }

    fun load(): Credentials? = runCatching {
        if (!credFile.exists() || !keyFile.exists()) return null
        val raw = credFile.readBytes()
        if (raw.size <= 12) return null
        val iv = raw.copyOfRange(0, 12)
        val enc = raw.copyOfRange(12, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        }
        val plain = String(cipher.doFinal(enc), Charsets.UTF_8)
        val obj = JSONObject(plain)
        val email = obj.optString("email", "")
        val password = obj.optString("password", "")
        val serverUrl = obj.optString("serverUrl", DEFAULT_SERVER_URL)
        if (email.isBlank() || password.isEmpty()) null
        else Credentials(email, password, serverUrl)
    }.getOrNull()

    fun clear() {
        runCatching { credFile.delete() }
    }

    private fun getOrCreateKey(): SecretKey {
        // shortcut: local random AES-256 key file with 0600 permissions; upgrade to OS keyring/DPAPI for shared hosts
        val bytes = if (keyFile.exists() && keyFile.length() == 32L) {
            keyFile.readBytes()
        } else {
            ByteArray(32).also { generated ->
                SecureRandom().nextBytes(generated)
                keyFile.parentFile?.mkdirs()
                keyFile.writeBytes(generated)
                restrictOwnerOnly(keyFile)
            }
        }
        return SecretKeySpec(bytes, "AES")
    }
}

class DesktopSettingsStore(private val configDir: File = DesktopConfigPaths.defaultConfigDir()) {
    private val settingsFile = File(configDir, "settings.json")

    @Synchronized
    fun load(): DesktopSettings = runCatching {
        if (!settingsFile.exists()) return DesktopSettings()
        val obj = JSONObject(settingsFile.readText(Charsets.UTF_8))
        val theme = runCatching {
            DesktopThemeMode.valueOf(obj.optString("themeMode", DesktopThemeMode.SYSTEM.name))
        }.getOrDefault(DesktopThemeMode.SYSTEM)
        val channel = runCatching {
            DesktopUpdateChannel.valueOf(obj.optString("updateChannel", ""))
        }.getOrElse {
            if ('-' in DesktopBuildInfo.VERSION_NAME) DesktopUpdateChannel.TEST else DesktopUpdateChannel.STABLE
        }
        val mutedFolders = obj.optJSONArray("mutedFolderIds").toStringSet()
        val mutedSenders = obj.optJSONArray("mutedSenders").toStringSet().map { it.lowercase() }.toSet()
        val templates = obj.optJSONArray("tagTemplates")?.let { arr ->
            val raw = (0 until arr.length()).mapNotNull { i ->
                arr.optString(i, "").trim().takeIf { it.isNotEmpty() }
            }
            sortTagTemplatesHierarchically(raw)
        } ?: DEFAULT_TAG_TEMPLATES
        val rules = obj.optJSONArray("tagSortRules")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                val tag = formatTagPath(parseTagPath(item.optString("tag", "")))
                val fid = item.optString("folderId", "").trim()
                val fname = item.optString("folderName", "").trim()
                val autoSub = item.optBoolean("autoSubfolders", true)
                if (tag.isEmpty() || fid.isEmpty()) null else TagSortRule(tag, fid, fname, autoSub)
            }
        } ?: emptyList()

        DesktopSettings(
            themeMode = theme,
            alwaysLightEmailBody = obj.optBoolean("alwaysLightEmailBody", false),
            notificationsEnabled = obj.optBoolean("notificationsEnabled", true),
            mutedFolderIds = mutedFolders,
            mutedSenders = mutedSenders,
            tagTemplates = templates,
            tagSortRules = rules,
            inboxFolderId = obj.optString("inboxFolderId", "").takeIf { it.isNotBlank() },
            updateChannel = channel
        )
    }.getOrDefault(DesktopSettings())

    @Synchronized
    fun save(settings: DesktopSettings) {
        configDir.mkdirs()
        val obj = JSONObject().apply {
            put("themeMode", settings.themeMode.name)
            put("alwaysLightEmailBody", settings.alwaysLightEmailBody)
            put("notificationsEnabled", settings.notificationsEnabled)
            put("mutedFolderIds", JSONArray(settings.mutedFolderIds.sorted()))
            put("mutedSenders", JSONArray(settings.mutedSenders.sorted()))
            put("tagTemplates", JSONArray(sortTagTemplatesHierarchically(settings.tagTemplates)))
            put("tagSortRules", JSONArray().apply {
                settings.tagSortRules.forEach { r ->
                    put(JSONObject().apply {
                        put("tag", r.tag)
                        put("folderId", r.targetFolderId)
                        put("folderName", r.targetFolderName)
                        put("autoSubfolders", r.autoCreateSubfolders)
                    })
                }
            })
            if (!settings.inboxFolderId.isNullOrBlank()) {
                put("inboxFolderId", settings.inboxFolderId)
            }
            put("updateChannel", settings.updateChannel.name)
        }
        settingsFile.writeText(obj.toString(2), Charsets.UTF_8)
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return (0 until length()).mapNotNull { i ->
            optString(i, "").trim().takeIf { it.isNotEmpty() }
        }.toSet()
    }
}

class DesktopUpdater(
    private val repo: String = DesktopBuildInfo.GITHUB_REPO,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
) {
    suspend fun checkLatestRelease(
        currentVersion: String = DesktopBuildInfo.VERSION_NAME,
        channel: DesktopUpdateChannel
    ): DesktopUpdateInfo? = withContext(Dispatchers.IO) {
        val url = when (channel) {
            DesktopUpdateChannel.STABLE -> "https://api.github.com/repos/$repo/releases/latest"
            DesktopUpdateChannel.TEST -> "https://api.github.com/repos/$repo/releases?per_page=20"
        }
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "NstuMail-Desktop/$currentVersion")
            .build()

        client.newCall(req).execute().use { resp ->
            if (resp.code == 404) return@withContext null
            if (!resp.isSuccessful) throw IOException("GitHub API вернул HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            if (body.isBlank()) return@withContext null
            when (channel) {
                DesktopUpdateChannel.STABLE -> parseRelease(JSONObject(body), currentVersion, allowPrerelease = false)
                DesktopUpdateChannel.TEST -> {
                    val arr = JSONArray(body)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val parsed = parseRelease(obj, currentVersion, allowPrerelease = true)
                        if (parsed != null) return@withContext parsed
                    }
                    null
                }
            }
        }
    }

    private fun parseRelease(
        obj: JSONObject,
        currentVersion: String,
        allowPrerelease: Boolean
    ): DesktopUpdateInfo? {
        if (obj.optBoolean("draft", false)) return null
        val isPre = obj.optBoolean("prerelease", false)
        if (!allowPrerelease && isPre) return null
        val tagName = obj.optString("tag_name", "").trim()
        val versionName = tagName.removePrefix("v").removePrefix("V")
        if (versionName.isBlank() || compareVersions(versionName, currentVersion) <= 0) return null

        val os = System.getProperty("os.name").orEmpty().lowercase()
        val wantedExt = if ("win" in os) "-windows-x64.zip" else ".AppImage"
        val assets = obj.optJSONArray("assets") ?: return null
        var pickedUrl = ""
        var pickedName = ""
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name", "")
            val dl = a.optString("browser_download_url", "")
            if (name.endsWith(wantedExt, ignoreCase = true) && dl.startsWith("https://")) {
                pickedUrl = dl
                pickedName = name
                break
            }
        }
        val htmlUrl = obj.optString("html_url", "https://github.com/$repo/releases/tag/$tagName")
        return DesktopUpdateInfo(
            versionName = versionName,
            tagName = tagName,
            releaseNotes = obj.optString("body", "").trim(),
            downloadUrl = pickedUrl.ifBlank { htmlUrl },
            assetName = pickedName.ifBlank { tagName },
            htmlUrl = htmlUrl,
            isPrerelease = isPre
        )
    }

    private fun compareVersions(aRaw: String, bRaw: String): Int {
        fun parse(raw: String): Pair<List<Int>, Int?> {
            val clean = raw.trim().removePrefix("v").removePrefix("V")
            val mainPart = clean.substringBefore('-')
            val suffix = clean.substringAfter('-', "")
            val nums = mainPart.split('.').map { it.toIntOrNull() ?: 0 }
            val preNum = Regex("""\d+""").findAll(suffix).lastOrNull()?.value?.toIntOrNull()
            return nums to if (suffix.isEmpty()) null else (preNum ?: 0)
        }
        val (aNums, aPre) = parse(aRaw)
        val (bNums, bPre) = parse(bRaw)
        val len = maxOf(aNums.size, bNums.size)
        for (i in 0 until len) {
            val d = aNums.getOrElse(i) { 0 }.compareTo(bNums.getOrElse(i) { 0 })
            if (d != 0) return d
        }
        return when {
            aPre == null && bPre == null -> 0
            aPre == null -> 1
            bPre == null -> -1
            else -> aPre.compareTo(bPre)
        }
    }
}
