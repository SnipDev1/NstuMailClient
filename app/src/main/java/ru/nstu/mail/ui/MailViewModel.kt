package ru.nstu.mail.ui

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.nstu.mail.BuildConfig
import ru.nstu.mail.data.AppUpdateInfo
import ru.nstu.mail.data.AppUpdater
import ru.nstu.mail.data.AttachmentInfo
import ru.nstu.mail.data.CredentialStore
import ru.nstu.mail.data.Credentials
import ru.nstu.mail.data.DEFAULT_SERVER_URL
import ru.nstu.mail.data.EmailDetail
import ru.nstu.mail.data.EmailSummary
import ru.nstu.mail.data.EwsClient
import ru.nstu.mail.data.EwsException
import ru.nstu.mail.data.FolderInfo
import ru.nstu.mail.data.MailFolderType
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

sealed interface Screen {
    data object Login : Screen
    data object MailList : Screen
    data object MessageDetail : Screen
    data class Compose(
        val replyTo: EmailDetail? = null,
        val isForward: Boolean = false
    ) : Screen
    data object OwaWebView : Screen
}

data class MailUiState(
    val screen: Screen = Screen.Login,
    val credentials: Credentials? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSending: Boolean = false,
    val isDownloadingAttachment: Boolean = false,
    val errorMessage: String? = null,
    val suggestOwaFallback: Boolean = false,
    val toastMessage: String? = null,
    val folders: List<FolderInfo> = emptyList(),
    val currentFolder: MailFolderType = MailFolderType.INBOX,
    val messages: List<EmailSummary> = emptyList(),
    val searchQuery: String = "",
    val reachedEnd: Boolean = true,
    val selectedMessage: EmailDetail? = null,
    val availableUpdate: AppUpdateInfo? = null,
    val isCheckingUpdate: Boolean = false,
    val updateDownloadProgress: Float? = null,
    val downloadedUpdatePath: String? = null,
    val updateError: String? = null
)

class MailViewModel(application: Application) : AndroidViewModel(application) {

    private val store = CredentialStore(application)
    private val updater = AppUpdater()
    private val _state = MutableStateFlow(MailUiState())
    val state: StateFlow<MailUiState> = _state.asStateFlow()

    private var client: EwsClient? = null

    init {
        File(application.cacheDir, "updates").deleteRecursively()
        val saved = store.load()
        if (saved != null) {
            _state.update { it.copy(credentials = saved) }
            login(saved.email, saved.password, saved.serverUrl)
        }
        checkForUpdates(manual = false)
    }

    fun login(email: String, password: String, serverUrl: String = DEFAULT_SERVER_URL) {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank() || password.isEmpty()) {
            _state.update { it.copy(errorMessage = "Введите логин и пароль", suggestOwaFallback = false) }
            return
        }

        val normalizedUrl = runCatching { EwsClient.normalizeHttpsUrl(serverUrl) }.getOrElse { err ->
            _state.update {
                it.copy(
                    errorMessage = err.message ?: "Некорректный адрес сервера",
                    suggestOwaFallback = false
                )
            }
            return
        }

        val creds = Credentials(trimmedEmail, password, normalizedUrl)
        val newClient = EwsClient(creds)

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    suggestOwaFallback = false,
                    credentials = creds
                )
            }
            try {
                val folders = newClient.getFolders()
                val (items, includesLast) = newClient.listMessages(
                    folder = MailFolderType.INBOX,
                    offset = 0,
                    pageSize = PAGE_SIZE
                )
                client = newClient
                store.save(creds)
                _state.update {
                    it.copy(
                        screen = Screen.MailList,
                        isLoading = false,
                        folders = folders,
                        currentFolder = MailFolderType.INBOX,
                        messages = items,
                        reachedEnd = includesLast,
                        searchQuery = ""
                    )
                }
            } catch (e: EwsException) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        screen = Screen.Login,
                        errorMessage = e.message ?: "Ошибка подключения",
                        suggestOwaFallback = e.isAuthError
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        screen = Screen.Login,
                        errorMessage = e.message ?: "Неизвестная ошибка",
                        suggestOwaFallback = false
                    )
                }
            }
        }
    }

    fun openOwaFallback(email: String, password: String, serverUrl: String) {
        val normalizedUrl = runCatching { EwsClient.normalizeHttpsUrl(serverUrl) }.getOrElse { err ->
            _state.update {
                it.copy(
                    errorMessage = err.message ?: "Некорректный адрес сервера",
                    suggestOwaFallback = false
                )
            }
            return
        }
        val creds = Credentials(email.trim(), password, normalizedUrl)
        _state.update {
            it.copy(
                credentials = creds,
                errorMessage = null,
                suggestOwaFallback = false,
                screen = Screen.OwaWebView
            )
        }
    }

    fun openOwaFromMailList() {
        _state.update { it.copy(screen = Screen.OwaWebView, errorMessage = null) }
    }

    fun exitOwaToAppropriateScreen() {
        val target = if (client != null) Screen.MailList else Screen.Login
        _state.update { it.copy(screen = target) }
    }

    fun selectFolder(folder: MailFolderType) {
        _state.update {
            it.copy(
                currentFolder = folder,
                searchQuery = "",
                messages = emptyList(),
                reachedEnd = true
            )
        }
        loadMessages(offset = 0, reset = true)
    }

    fun refresh() {
        loadMessages(offset = 0, reset = true, asPullRefresh = true)
    }

    fun search(query: String) {
        _state.update { it.copy(searchQuery = query) }
        loadMessages(offset = 0, reset = true)
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoading || current.reachedEnd) return
        loadMessages(offset = current.messages.size, reset = false)
    }

    private fun loadMessages(offset: Int, reset: Boolean, asPullRefresh: Boolean = false) {
        val c = client ?: return
        val folder = _state.value.currentFolder
        val query = _state.value.searchQuery

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isLoading = !asPullRefresh,
                    isRefreshing = asPullRefresh,
                    errorMessage = null
                )
            }
            try {
                val folders = if (reset) c.getFolders() else _state.value.folders
                val (items, includesLast) = c.listMessages(
                    folder = folder,
                    offset = offset,
                    pageSize = PAGE_SIZE,
                    query = query
                )
                _state.update { st ->
                    val merged = if (reset) items else (st.messages + items).distinctBy { it.id }
                    st.copy(
                        isLoading = false,
                        isRefreshing = false,
                        folders = folders,
                        messages = merged,
                        reachedEnd = includesLast
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        isRefreshing = false,
                        errorMessage = e.message ?: "Не удалось загрузить письма"
                    )
                }
            }
        }
    }

    fun openMessage(summary: EmailSummary) {
        val c = client ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                var detail = c.getMessage(summary.id)
                if (!detail.isRead) {
                    runCatching { c.setRead(detail.id, detail.changeKey, true) }
                    detail = detail.copy(isRead = true)
                }
                _state.update { st ->
                    val updatedList = st.messages.map {
                        if (it.id == summary.id) it.copy(isRead = true) else it
                    }
                    st.copy(
                        isLoading = false,
                        messages = updatedList,
                        selectedMessage = detail,
                        screen = Screen.MessageDetail
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = e.message ?: "Не удалось открыть письмо"
                    )
                }
            }
        }
    }

    fun backToMailList() {
        _state.update { it.copy(screen = Screen.MailList, selectedMessage = null, errorMessage = null) }
    }

    fun toggleRead(detail: EmailDetail) {
        val c = client ?: return
        val newState = !detail.isRead
        viewModelScope.launch {
            try {
                c.setRead(detail.id, detail.changeKey, newState)
                _state.update { st ->
                    st.copy(
                        selectedMessage = st.selectedMessage?.copy(isRead = newState),
                        messages = st.messages.map {
                            if (it.id == detail.id) it.copy(isRead = newState) else it
                        },
                        toastMessage = if (newState) "Отмечено как прочитанное" else "Отмечено как непрочитанное"
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Ошибка обновления статуса") }
            }
        }
    }

    fun deleteMessage(detail: EmailDetail) {
        val c = client ?: return
        val hardDelete = _state.value.currentFolder == MailFolderType.DELETED
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.deleteMessage(detail.id, detail.changeKey, hardDelete)
                _state.update { st ->
                    st.copy(
                        isLoading = false,
                        screen = Screen.MailList,
                        selectedMessage = null,
                        messages = st.messages.filterNot { it.id == detail.id },
                        toastMessage = if (hardDelete) "Письмо удалено навсегда" else "Перемещено в Удалённые"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = e.message ?: "Не удалось удалить письмо"
                    )
                }
            }
        }
    }

    fun openCompose(replyTo: EmailDetail? = null, isForward: Boolean = false) {
        _state.update { it.copy(screen = Screen.Compose(replyTo, isForward), errorMessage = null) }
    }

    fun sendEmail(toRaw: String, ccRaw: String, subject: String, body: String) {
        val c = client ?: return
        val toList = splitEmails(toRaw)
        val ccList = splitEmails(ccRaw)
        if (toList.isEmpty() || toList.any { '@' !in it }) {
            _state.update { it.copy(errorMessage = "Укажите корректный адрес получателя") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isSending = true, errorMessage = null) }
            try {
                c.sendMessage(
                    to = toList,
                    cc = ccList,
                    subject = subject.ifBlank { "(Без темы)" },
                    body = body
                )
                _state.update {
                    it.copy(
                        isSending = false,
                        screen = Screen.MailList,
                        toastMessage = "Письмо отправлено"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSending = false,
                        errorMessage = e.message ?: "Не удалось отправить письмо"
                    )
                }
            }
        }
    }

    fun downloadAndOpenAttachment(attachment: AttachmentInfo, context: Context) {
        val c = client ?: return
        viewModelScope.launch {
            _state.update { it.copy(isDownloadingAttachment = true) }
            try {
                val downloaded = c.downloadAttachment(attachment.id)
                val safeName = sanitizeFilename(downloaded.name)
                val uri = saveBytesToDownloads(
                    context = context,
                    fileName = safeName,
                    mimeType = downloaded.contentType,
                    bytes = downloaded.bytes
                )
                _state.update {
                    it.copy(
                        isDownloadingAttachment = false,
                        toastMessage = "Файл сохранён в Загрузки: $safeName"
                    )
                }
                if (uri != null) {
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, downloaded.contentType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(viewIntent, safeName))
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isDownloadingAttachment = false,
                        toastMessage = e.message ?: "Ошибка скачивания вложения"
                    )
                }
            }
        }
    }

    fun logout() {
        client = null
        store.clear()
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
        }
        _state.value = MailUiState(screen = Screen.Login)
    }

    fun clearToast() {
        _state.update { it.copy(toastMessage = null) }
    }

    fun clearError() {
        _state.update { it.copy(errorMessage = null) }
    }

    fun checkForUpdates(manual: Boolean) {
        if (_state.value.isCheckingUpdate) return
        viewModelScope.launch {
            _state.update { it.copy(isCheckingUpdate = true) }
            try {
                val update = updater.checkLatestRelease()
                _state.update { st ->
                    st.copy(
                        isCheckingUpdate = false,
                        availableUpdate = update ?: st.availableUpdate,
                        downloadedUpdatePath = if (update != null) null else st.downloadedUpdatePath,
                        updateDownloadProgress = if (update != null) null else st.updateDownloadProgress,
                        updateError = null,
                        toastMessage = if (manual && update == null) {
                            "Установлена последняя версия (${BuildConfig.VERSION_NAME})"
                        } else {
                            st.toastMessage
                        }
                    )
                }
            } catch (e: Exception) {
                _state.update { st ->
                    st.copy(
                        isCheckingUpdate = false,
                        toastMessage = if (manual) {
                            "Не удалось проверить обновления: ${e.localizedMessage ?: "ошибка сети"}"
                        } else {
                            st.toastMessage
                        }
                    )
                }
            }
        }
    }

    fun dismissUpdate() {
        _state.update {
            it.copy(
                availableUpdate = null,
                updateDownloadProgress = null,
                updateError = null
            )
        }
    }

    fun downloadAndInstallUpdate(context: Context) {
        val update = _state.value.availableUpdate ?: return
        val existingPath = _state.value.downloadedUpdatePath
        if (existingPath != null) {
            val file = File(existingPath)
            if (file.exists()) {
                launchApkInstaller(context, file)
                return
            }
        }

        if (_state.value.updateDownloadProgress != null) return
        val destFile = File(File(context.cacheDir, "updates"), "NstuMail-${update.tagName}.apk")

        viewModelScope.launch {
            _state.update { it.copy(updateDownloadProgress = 0f, updateError = null) }
            try {
                updater.downloadApk(update.apkUrl, destFile) { progress ->
                    _state.update { it.copy(updateDownloadProgress = progress) }
                }
                _state.update {
                    it.copy(
                        updateDownloadProgress = 1f,
                        downloadedUpdatePath = destFile.absolutePath
                    )
                }
                launchApkInstaller(context, destFile)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        updateDownloadProgress = null,
                        downloadedUpdatePath = null,
                        updateError = e.localizedMessage ?: "Ошибка загрузки обновления"
                    )
                }
            }
        }
    }

    private fun launchApkInstaller(context: Context, apkFile: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(permIntent) }
            _state.update {
                it.copy(
                    toastMessage = "Разрешите установку из этого источника и нажмите «Установить» ещё раз"
                )
            }
            return
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching {
            context.startActivity(installIntent)
        }.onFailure { err ->
            _state.update {
                it.copy(updateError = err.localizedMessage ?: "Не удалось открыть установщик APK")
            }
        }
    }

    private suspend fun saveBytesToDownloads(
        context: Context,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Uri? = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@withContext null
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.cacheDir
            dir.mkdirs()
            val outFile = File(dir, fileName)
            outFile.writeBytes(bytes)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", outFile)
        }
    }

    companion object {
        private const val PAGE_SIZE = 25
        private val RU_LOCALE = Locale("ru")
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", RU_LOCALE)
        private val DAY_MONTH_FMT = DateTimeFormatter.ofPattern("d MMM", RU_LOCALE)
        private val FULL_DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy", RU_LOCALE)
        private val DETAIL_FMT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", RU_LOCALE)

        fun splitEmails(raw: String): List<String> =
            raw.split(',', ';', ' ')
                .map { it.trim() }
                .filter { it.isNotBlank() }

        fun sanitizeFilename(name: String): String {
            val cleaned = name
                .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
                .replace(Regex("\\.{2,}"), ".")
                .trim(' ', '.')
                .take(120)
            return cleaned.ifBlank { "attachment" }
        }

        fun formatMailDate(iso: String, detailed: Boolean = false): String {
            if (iso.isBlank()) return ""
            return runCatching {
                val zdt = Instant.parse(iso).atZone(ZoneId.systemDefault())
                if (detailed) return zdt.format(DETAIL_FMT)
                val today = LocalDate.now()
                val msgDate = zdt.toLocalDate()
                when {
                    msgDate == today -> zdt.format(TIME_FMT)
                    msgDate.year == today.year -> zdt.format(DAY_MONTH_FMT)
                    else -> zdt.format(FULL_DATE_FMT)
                }
            }.getOrDefault(iso.take(16))
        }
    }
}
