package ru.nstu.mail.ui

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import ru.nstu.mail.BuildConfig
import ru.nstu.mail.data.AppUpdateInfo
import ru.nstu.mail.data.AppUpdater
import ru.nstu.mail.data.AttachmentInfo
import ru.nstu.mail.data.CalendarEvent
import ru.nstu.mail.data.ContactEntry
import ru.nstu.mail.data.CredentialStore
import ru.nstu.mail.data.Credentials
import ru.nstu.mail.data.DEFAULT_SERVER_URL
import ru.nstu.mail.data.EmailDetail
import ru.nstu.mail.data.EmailImportance
import ru.nstu.mail.data.EmailSummary
import ru.nstu.mail.data.EwsClient
import ru.nstu.mail.data.EwsException
import ru.nstu.mail.data.MailFilter
import ru.nstu.mail.data.MailFolder
import ru.nstu.mail.data.MailRulesStore
import ru.nstu.mail.data.OutgoingAttachment
import ru.nstu.mail.data.PersonProfile
import ru.nstu.mail.data.SwipeAction
import ru.nstu.mail.data.SystemFolderKind
import ru.nstu.mail.data.TagSortRule
import ru.nstu.mail.data.UpdateChannel
import ru.nstu.mail.data.formatTagPath
import ru.nstu.mail.data.matchAutoSortRuleDetailed
import ru.nstu.mail.data.parseFormattedRecipient
import ru.nstu.mail.data.parseTagPath
import ru.nstu.mail.notify.MailNotificationHelper
import ru.nstu.mail.notify.NotificationMode
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Collections
import java.util.Locale

sealed interface Screen {
    data object Login : Screen
    data object MailList : Screen
    data object MessageDetail : Screen
    data class Compose(
        val replyTo: EmailDetail? = null,
        val replyAll: Boolean = false,
        val isForward: Boolean = false,
        val draft: EmailDetail? = null
    ) : Screen
    data object Contacts : Screen
    data object Calendar : Screen
    data object Settings : Screen
}

data class MailUiState(
    val screen: Screen = Screen.Login,
    val credentials: Credentials? = null,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSending: Boolean = false,
    val isDownloadingAttachment: Boolean = false,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val folders: List<MailFolder> = emptyList(),
    val currentFolder: MailFolder? = null,
    val filter: MailFilter = MailFilter.ALL,
    val searchQuery: String = "",
    val searchAllFolders: Boolean = false,
    val messages: List<EmailSummary> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val reachedEnd: Boolean = true,
    val selectedMessage: EmailDetail? = null,
    val contacts: List<ContactEntry> = emptyList(),
    val directorySuggestions: List<ContactEntry> = emptyList(),
    val calendarEvents: List<CalendarEvent> = emptyList(),
    val availableUpdate: AppUpdateInfo? = null,
    val isCheckingUpdate: Boolean = false,
    val updateDownloadProgress: Float? = null,
    val downloadedUpdatePath: String? = null,
    val updateError: String? = null
)

class MailViewModel(application: Application) : AndroidViewModel(application) {

    private val store = CredentialStore(application)
    private val prefs = application.getSharedPreferences("nstumail_prefs", Context.MODE_PRIVATE)
    private val updater = AppUpdater()
    private val _state = MutableStateFlow(MailUiState())
    val state: StateFlow<MailUiState> = _state.asStateFlow()

    private val _themeMode = MutableStateFlow(loadThemeMode())
    val themeMode: StateFlow<AppThemeMode> = _themeMode.asStateFlow()

    private val _updateChannel = MutableStateFlow(loadUpdateChannel())
    val updateChannel: StateFlow<UpdateChannel> = _updateChannel.asStateFlow()

    private val _notificationsEnabled = MutableStateFlow(MailNotificationHelper.isEnabled(application))
    val notificationsEnabled: StateFlow<Boolean> = _notificationsEnabled.asStateFlow()

    private val _notificationMode = MutableStateFlow(MailNotificationHelper.getMode(application))
    val notificationMode: StateFlow<NotificationMode> = _notificationMode.asStateFlow()

    private val _notificationIntervalMinutes =
        MutableStateFlow(MailNotificationHelper.getIntervalMinutes(application))
    val notificationIntervalMinutes: StateFlow<Int> = _notificationIntervalMinutes.asStateFlow()

    private val _mutedFolderIds = MutableStateFlow(MailRulesStore.getMutedFolderIds(application))
    val mutedFolderIds: StateFlow<Set<String>> = _mutedFolderIds.asStateFlow()

    private val _mutedSenders = MutableStateFlow(MailRulesStore.getMutedSenders(application))
    val mutedSenders: StateFlow<Set<String>> = _mutedSenders.asStateFlow()

    private val _tagTemplates = MutableStateFlow(MailRulesStore.getTagTemplates(application))
    val tagTemplates: StateFlow<List<String>> = _tagTemplates.asStateFlow()

    private val _tagSortRules = MutableStateFlow(MailRulesStore.getTagSortRules(application))
    val tagSortRules: StateFlow<List<TagSortRule>> = _tagSortRules.asStateFlow()

    private val _swipeRightAction = MutableStateFlow(MailRulesStore.getSwipeRightAction(application))
    val swipeRightAction: StateFlow<SwipeAction> = _swipeRightAction.asStateFlow()

    private val _swipeLeftAction = MutableStateFlow(MailRulesStore.getSwipeLeftAction(application))
    val swipeLeftAction: StateFlow<SwipeAction> = _swipeLeftAction.asStateFlow()

    private var pendingNotificationMessageId: String? = null

    private val _avatars = MutableStateFlow<Map<String, ImageBitmap?>>(emptyMap())
    val avatars: StateFlow<Map<String, ImageBitmap?>> = _avatars.asStateFlow()
    private val requestedAvatars = Collections.synchronizedSet(mutableSetOf<String>())
    private val avatarSemaphore = Semaphore(4)

    private val _personProfiles = MutableStateFlow<Map<String, PersonProfile?>>(emptyMap())
    val personProfiles: StateFlow<Map<String, PersonProfile?>> = _personProfiles.asStateFlow()
    private val _knownNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val knownNames: StateFlow<Map<String, String>> = _knownNames.asStateFlow()
    private val _loadingProfiles = MutableStateFlow<Set<String>>(emptySet())
    val loadingProfiles: StateFlow<Set<String>> = _loadingProfiles.asStateFlow()
    private val requestedProfiles = Collections.synchronizedSet(mutableSetOf<String>())

    private var client: EwsClient? = null
    private var directoryJob: Job? = null

    init {
        File(application.cacheDir, "updates").deleteRecursively()
        val saved = store.load()
        if (saved != null) {
            _state.update { it.copy(credentials = saved) }
            login(saved.email, saved.password, saved.serverUrl)
        }
        checkForUpdates(manual = false)
    }

    private fun loadThemeMode(): AppThemeMode =
        runCatching {
            val saved = prefs.getString("theme_mode", AppThemeMode.SYSTEM.name)
            AppThemeMode.valueOf(saved ?: AppThemeMode.SYSTEM.name)
        }.getOrDefault(AppThemeMode.SYSTEM)

    fun setThemeMode(mode: AppThemeMode) {
        prefs.edit().putString("theme_mode", mode.name).apply()
        _themeMode.value = mode
    }

    private fun loadUpdateChannel(): UpdateChannel {
        val defaultChannel = if ('-' in BuildConfig.VERSION_NAME) {
            UpdateChannel.TEST
        } else {
            UpdateChannel.STABLE
        }
        return runCatching {
            val saved = prefs.getString("update_channel", defaultChannel.name)
            UpdateChannel.valueOf(saved ?: defaultChannel.name)
        }.getOrDefault(defaultChannel)
    }

    fun openSettings() {
        _state.update { it.copy(screen = Screen.Settings, errorMessage = null) }
    }

    fun setUpdateChannel(channel: UpdateChannel) {
        if (_updateChannel.value == channel) return
        prefs.edit().putString("update_channel", channel.name).apply()
        _updateChannel.value = channel
        _state.update {
            it.copy(
                availableUpdate = null,
                downloadedUpdatePath = null,
                updateDownloadProgress = null,
                updateError = null
            )
        }
        checkForUpdates(manual = false)
    }

    fun setNotificationsEnabled(enabled: Boolean) {
        val app = getApplication<Application>()
        MailNotificationHelper.setEnabled(app, enabled)
        _notificationsEnabled.value = enabled
        if (enabled) {
            val currentInbox = if (_state.value.currentFolder?.systemKind == SystemFolderKind.INBOX) {
                _state.value.messages
            } else {
                emptyList()
            }
            if (currentInbox.isNotEmpty() || !MailNotificationHelper.isBaselineReady(app)) {
                MailNotificationHelper.recordSeenMessages(app, currentInbox)
            }
            MailNotificationHelper.syncBackgroundDelivery(app, replacePeriodic = true)
        } else {
            MailNotificationHelper.syncBackgroundDelivery(app)
            MailNotificationHelper.cancelNotifications(app)
        }
    }

    fun setNotificationMode(mode: NotificationMode) {
        val app = getApplication<Application>()
        MailNotificationHelper.setMode(app, mode)
        _notificationMode.value = mode
        if (_notificationsEnabled.value) {
            MailNotificationHelper.syncBackgroundDelivery(app, replacePeriodic = true)
        }
    }

    fun setNotificationInterval(minutes: Int) {
        val app = getApplication<Application>()
        MailNotificationHelper.setIntervalMinutes(app, minutes)
        _notificationIntervalMinutes.value = MailNotificationHelper.getIntervalMinutes(app)
        if (_notificationsEnabled.value) {
            MailNotificationHelper.syncBackgroundDelivery(app, replacePeriodic = true)
        }
    }

    fun checkNewMailNow() {
        val app = getApplication<Application>()
        if (!_notificationsEnabled.value || !MailNotificationHelper.hasSystemPermission(app)) {
            _state.update { it.copy(toastMessage = "Включите уведомления и выдайте разрешение системы") }
            return
        }
        MailNotificationHelper.runImmediateCheck(app)
        _state.update { it.copy(toastMessage = "Проверка новых писем запущена") }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        val app = getApplication<Application>()
        MailNotificationHelper.markPermissionAsked(app)
        if (granted) {
            setNotificationsEnabled(true)
        } else {
            MailNotificationHelper.setEnabled(app, false)
            _notificationsEnabled.value = false
            MailNotificationHelper.cancelPeriodicCheck(app)
        }
    }

    fun toggleFolderNotifications(folder: MailFolder) {
        val app = getApplication<Application>()
        val isInbox = folder.systemKind == SystemFolderKind.INBOX
        val updated = MailRulesStore.toggleFolderMuted(app, folder.id, isInbox = isInbox)
        _mutedFolderIds.value = updated
        val muted = folder.id in updated || (isInbox && "inbox" in updated)
        _state.update {
            it.copy(
                toastMessage = if (muted) {
                    "Уведомления для «${folder.displayName}» выключены"
                } else {
                    "Уведомления для «${folder.displayName}» включены"
                }
            )
        }
    }

    fun unmuteFolderById(folderId: String) {
        val app = getApplication<Application>()
        val updated = MailRulesStore.setFolderMuted(app, folderId, muted = false)
        _mutedFolderIds.value = updated
    }

    fun toggleSenderNotifications(email: String) {
        val clean = email.trim()
        if (clean.isEmpty()) return
        val app = getApplication<Application>()
        val updated = MailRulesStore.toggleSenderMuted(app, clean)
        _mutedSenders.value = updated
        val muted = clean.lowercase() in updated
        _state.update {
            it.copy(
                toastMessage = if (muted) {
                    "Уведомления от $clean выключены"
                } else {
                    "Уведомления от $clean включены"
                }
            )
        }
    }

    fun addTagTemplate(path: String) {
        _tagTemplates.value = MailRulesStore.addTagTemplate(getApplication(), path)
    }

    fun updateTagTemplate(oldPath: String, newPath: String) {
        _tagTemplates.value = MailRulesStore.updateTagTemplate(getApplication(), oldPath, newPath)
    }

    fun removeTagTemplate(path: String) {
        _tagTemplates.value = MailRulesStore.removeTagTemplate(getApplication(), path)
    }

    fun saveTagSortRule(
        tag: String,
        targetFolder: MailFolder,
        autoCreateSubfolders: Boolean = true
    ) {
        val app = getApplication<Application>()
        val cleanPath = formatTagPath(parseTagPath(tag))
        if (cleanPath.isEmpty()) return
        val rule = TagSortRule(
            tag = cleanPath,
            targetFolderId = targetFolder.id,
            targetFolderName = targetFolder.displayName,
            autoCreateSubfolders = autoCreateSubfolders
        )
        _tagSortRules.value = MailRulesStore.upsertTagSortRule(app, rule)
        _tagTemplates.value = MailRulesStore.addTagTemplate(app, cleanPath)
        _state.update {
            it.copy(toastMessage = "Тег [$cleanPath] → «${targetFolder.displayName}»")
        }
        val c = client
        val cur = _state.value.currentFolder
        if (c != null && cur?.systemKind == SystemFolderKind.INBOX) {
            applyAutoSortToMessages(c, _state.value.messages, cur)
        }
    }

    fun removeTagSortRule(tag: String) {
        _tagSortRules.value = MailRulesStore.removeTagSortRule(getApplication(), tag)
    }

    fun setSwipeRightAction(action: SwipeAction) {
        MailRulesStore.setSwipeRightAction(getApplication(), action)
        _swipeRightAction.value = action
    }

    fun setSwipeLeftAction(action: SwipeAction) {
        MailRulesStore.setSwipeLeftAction(getApplication(), action)
        _swipeLeftAction.value = action
    }

    private fun rememberKnownNames(pairs: Iterable<Pair<String, String>>) {
        val current = _knownNames.value
        val additions = mutableMapOf<String, String>()
        for ((rawEmail, rawName) in pairs) {
            val key = rawEmail.trim().lowercase()
            val cleanName = rawName.trim()
            if ('@' in key && cleanName.isNotEmpty() && !needsNameResolution(cleanName, key)) {
                if (current[key] != cleanName) {
                    additions[key] = cleanName
                }
            }
        }
        if (additions.isNotEmpty()) {
            _knownNames.update { it + additions }
        }
    }

    fun login(email: String, password: String, serverUrl: String = DEFAULT_SERVER_URL) {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isBlank() || password.isEmpty()) {
            _state.update { it.copy(errorMessage = "Введите логин и пароль") }
            return
        }

        val normalizedUrl = runCatching { EwsClient.normalizeHttpsUrl(serverUrl) }.getOrElse { err ->
            _state.update {
                it.copy(errorMessage = err.message ?: "Некорректный адрес сервера")
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
                    credentials = creds
                )
            }
            try {
                val folders = newClient.getFolders()
                val inbox = folders.firstOrNull { it.systemKind == SystemFolderKind.INBOX }
                    ?: folders.firstOrNull()
                val (items, includesLast) = if (inbox != null) {
                    newClient.listMessages(
                        folderIds = listOf(inbox.id),
                        offset = 0,
                        pageSize = PAGE_SIZE
                    )
                } else {
                    emptyList<EmailSummary>() to true
                }
                client = newClient
                store.save(creds)
                rememberKnownNames(items.map { it.senderEmail to it.senderName })
                val app = getApplication<Application>()
                if (inbox?.systemKind == SystemFolderKind.INBOX) {
                    MailRulesStore.setInboxFolderId(app, inbox.id)
                    MailNotificationHelper.recordSeenMessages(app, items)
                }
                MailNotificationHelper.syncBackgroundDelivery(app, replacePeriodic = false)
                viewModelScope.launch {
                    runCatching { newClient.getRecipientCache() }
                        .onSuccess { cache ->
                            rememberKnownNames(cache.map { it.email to it.name })
                        }
                }
                requestPersonProfile("", creds.email)
                _state.update {
                    it.copy(
                        screen = Screen.MailList,
                        isLoading = false,
                        folders = folders,
                        currentFolder = inbox,
                        filter = MailFilter.ALL,
                        searchQuery = "",
                        searchAllFolders = false,
                        selectedIds = emptySet(),
                        messages = items,
                        reachedEnd = includesLast
                    )
                }
                applyAutoSortToMessages(newClient, items, inbox)
                pendingNotificationMessageId?.let { pendingId ->
                    pendingNotificationMessageId = null
                    openMessageById(pendingId)
                }
            } catch (e: EwsException) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        screen = Screen.Login,
                        errorMessage = e.message ?: "Ошибка подключения"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        screen = Screen.Login,
                        errorMessage = e.message ?: "Неизвестная ошибка"
                    )
                }
            }
        }
    }

    // --- Folders & Filters ---

    fun selectFolder(folder: MailFolder) {
        _state.update {
            it.copy(
                currentFolder = folder,
                searchQuery = "",
                searchAllFolders = false,
                selectedIds = emptySet(),
                messages = emptyList(),
                reachedEnd = true
            )
        }
        loadMessages(offset = 0, reset = true)
    }

    fun setFilter(filter: MailFilter) {
        if (_state.value.filter == filter) return
        _state.update { it.copy(filter = filter, selectedIds = emptySet()) }
        loadMessages(offset = 0, reset = true)
    }

    fun search(query: String) {
        _state.update { it.copy(searchQuery = query, selectedIds = emptySet()) }
        loadMessages(offset = 0, reset = true)
    }

    fun setSearchAllFolders(enabled: Boolean) {
        _state.update { it.copy(searchAllFolders = enabled, selectedIds = emptySet()) }
        if (_state.value.searchQuery.isNotBlank()) {
            loadMessages(offset = 0, reset = true)
        }
    }

    fun refresh() {
        loadMessages(offset = 0, reset = true, asPullRefresh = true)
    }

    fun loadMore() {
        val current = _state.value
        if (current.isLoading || current.reachedEnd) return
        loadMessages(offset = current.messages.size, reset = false)
    }

    private fun targetFolderIdsForQuery(): List<String> {
        val st = _state.value
        return if (st.searchQuery.isNotBlank() && st.searchAllFolders) {
            st.folders
                .filter { it.systemKind != SystemFolderKind.DELETED && it.systemKind != SystemFolderKind.JUNK }
                .map { it.id }
                .ifEmpty { listOfNotNull(st.currentFolder?.id) }
        } else {
            listOfNotNull(st.currentFolder?.id)
        }
    }

    private fun loadMessages(offset: Int, reset: Boolean, asPullRefresh: Boolean = false) {
        val c = client ?: return
        val folderIds = targetFolderIdsForQuery()
        val query = _state.value.searchQuery
        val filter = _state.value.filter

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
                val updatedCurrent = _state.value.currentFolder?.let { cur ->
                    folders.firstOrNull { it.id == cur.id } ?: cur
                } ?: folders.firstOrNull()

                val effectiveIds = if (folderIds.isEmpty() && updatedCurrent != null) {
                    listOf(updatedCurrent.id)
                } else {
                    folderIds
                }

                val (items, includesLast) = c.listMessages(
                    folderIds = effectiveIds,
                    offset = offset,
                    pageSize = PAGE_SIZE,
                    query = query,
                    filter = filter
                )
                rememberKnownNames(items.map { it.senderEmail to it.senderName })
                val inbox = folders.firstOrNull { it.systemKind == SystemFolderKind.INBOX }
                if (inbox != null) {
                    MailRulesStore.setInboxFolderId(getApplication(), inbox.id)
                }
                if (updatedCurrent?.systemKind == SystemFolderKind.INBOX &&
                    reset &&
                    query.isBlank() &&
                    filter == MailFilter.ALL
                ) {
                    MailNotificationHelper.recordSeenMessages(getApplication(), items)
                }
                _state.update { st ->
                    val merged = if (reset) items else (st.messages + items).distinctBy { it.id }
                    st.copy(
                        isLoading = false,
                        isRefreshing = false,
                        folders = folders,
                        currentFolder = updatedCurrent,
                        messages = merged,
                        reachedEnd = includesLast
                    )
                }
                if (query.isBlank()) {
                    applyAutoSortToMessages(c, items, updatedCurrent)
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

    fun createFolder(name: String, parentFolderId: String?) {
        val c = client ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.createFolder(trimmed, parentFolderId)
                val folders = c.getFolders()
                _state.update {
                    it.copy(
                        isLoading = false,
                        folders = folders,
                        toastMessage = "Папка «$trimmed» создана"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = e.message ?: "Не удалось создать папку"
                    )
                }
            }
        }
    }

    fun renameFolder(folder: MailFolder, newName: String) {
        val c = client ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.renameFolder(folder.id, folder.changeKey, trimmed)
                val folders = c.getFolders()
                val updatedCurrent = _state.value.currentFolder?.let { cur ->
                    folders.firstOrNull { it.id == cur.id } ?: cur
                }
                _state.update {
                    it.copy(
                        isLoading = false,
                        folders = folders,
                        currentFolder = updatedCurrent,
                        toastMessage = "Папка переименована"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = e.message ?: "Не удалось переименовать папку"
                    )
                }
            }
        }
    }

    fun deleteFolder(folder: MailFolder) {
        val c = client ?: return
        if (folder.isSystem) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.deleteFolder(folder.id, folder.changeKey)
                val folders = c.getFolders()
                val fallback = folders.firstOrNull { it.systemKind == SystemFolderKind.INBOX }
                    ?: folders.firstOrNull()
                val switched = _state.value.currentFolder?.id == folder.id
                _state.update {
                    it.copy(
                        isLoading = false,
                        folders = folders,
                        currentFolder = if (switched) fallback else it.currentFolder,
                        toastMessage = "Папка удалена"
                    )
                }
                if (switched) loadMessages(offset = 0, reset = true)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = e.message ?: "Не удалось удалить папку"
                    )
                }
            }
        }
    }

    fun emptyCurrentFolder() {
        val c = client ?: return
        val folder = _state.value.currentFolder ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.emptyFolder(folder.id)
                val folders = c.getFolders()
                val updated = folders.firstOrNull { it.id == folder.id } ?: folder
                _state.update {
                    it.copy(
                        isLoading = false,
                        folders = folders,
                        currentFolder = updated,
                        messages = emptyList(),
                        selectedIds = emptySet(),
                        reachedEnd = true,
                        toastMessage = "Папка очищена"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = e.message ?: "Не удалось очистить папку"
                    )
                }
            }
        }
    }

    fun markAllReadInCurrentFolder() {
        val c = client ?: return
        val folder = _state.value.currentFolder ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.markAllRead(folder.id)
                val folders = c.getFolders()
                val updated = folders.firstOrNull { it.id == folder.id } ?: folder
                _state.update { st ->
                    st.copy(
                        isLoading = false,
                        folders = folders,
                        currentFolder = updated,
                        messages = st.messages.map { it.copy(isRead = true) },
                        toastMessage = "Все письма отмечены прочитанными"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = e.message ?: "Ошибка обновления писем"
                    )
                }
            }
        }
    }

    // --- Selection & Message Actions ---

    fun toggleSelection(id: String) {
        _state.update { st ->
            val next = if (id in st.selectedIds) st.selectedIds - id else st.selectedIds + id
            st.copy(selectedIds = next)
        }
    }

    fun clearSelection() {
        _state.update { it.copy(selectedIds = emptySet()) }
    }

    fun batchSetRead(isRead: Boolean) {
        val c = client ?: return
        val ids = _state.value.selectedIds
        val pairs = _state.value.messages.filter { it.id in ids }.map { it.id to it.changeKey }
        if (pairs.isEmpty()) return
        viewModelScope.launch {
            try {
                c.setRead(pairs, isRead)
                _state.update { st ->
                    st.copy(
                        selectedIds = emptySet(),
                        messages = st.messages.map {
                            if (it.id in ids) it.copy(isRead = isRead) else it
                        },
                        toastMessage = if (isRead) "Отмечены прочитанными" else "Отмечены непрочитанными"
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Ошибка") }
            }
        }
    }

    fun batchMove(targetFolder: MailFolder) {
        val c = client ?: return
        val ids = _state.value.selectedIds
        val pairs = _state.value.messages.filter { it.id in ids }.map { it.id to it.changeKey }
        if (pairs.isEmpty()) return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.moveMessages(pairs, targetFolder.id)
                val folders = c.getFolders()
                _state.update { st ->
                    st.copy(
                        isLoading = false,
                        folders = folders,
                        selectedIds = emptySet(),
                        messages = st.messages.filterNot { it.id in ids },
                        toastMessage = "Перемещено в «${targetFolder.displayName}»"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = e.message ?: "Не удалось переместить письма"
                    )
                }
            }
        }
    }

    fun batchDelete() {
        val c = client ?: return
        val ids = _state.value.selectedIds
        val pairs = _state.value.messages.filter { it.id in ids }.map { it.id to it.changeKey }
        if (pairs.isEmpty()) return
        val hardDelete = _state.value.currentFolder?.systemKind == SystemFolderKind.DELETED
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.deleteMessages(pairs, hardDelete)
                val folders = c.getFolders()
                _state.update { st ->
                    st.copy(
                        isLoading = false,
                        folders = folders,
                        selectedIds = emptySet(),
                        messages = st.messages.filterNot { it.id in ids },
                        toastMessage = if (hardDelete) "Удалено навсегда" else "Перемещено в Удалённые"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = e.message ?: "Не удалось удалить письма"
                    )
                }
            }
        }
    }

    fun toggleFlag(summary: EmailSummary) {
        val c = client ?: return
        val next = !summary.isFlagged
        viewModelScope.launch {
            try {
                c.setFlagged(listOf(summary.id to summary.changeKey), next)
                _state.update { st ->
                    st.copy(
                        messages = st.messages.map {
                            if (it.id == summary.id) it.copy(isFlagged = next) else it
                        }
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Ошибка обновления флажка") }
            }
        }
    }

    fun toggleReadSummary(summary: EmailSummary) {
        val c = client ?: return
        val next = !summary.isRead
        viewModelScope.launch {
            try {
                c.setRead(listOf(summary.id to summary.changeKey), next)
                _state.update { st ->
                    st.copy(
                        messages = st.messages.map {
                            if (it.id == summary.id) it.copy(isRead = next) else it
                        },
                        toastMessage = if (next) "Отмечено прочитанным" else "Отмечено непрочитанным"
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Ошибка") }
            }
        }
    }

    fun moveSummary(summary: EmailSummary, targetFolder: MailFolder) {
        val c = client ?: return
        viewModelScope.launch {
            try {
                c.moveMessages(listOf(summary.id to summary.changeKey), targetFolder.id)
                val folders = runCatching { c.getFolders() }.getOrNull()
                _state.update { st ->
                    st.copy(
                        folders = folders ?: st.folders,
                        messages = st.messages.filterNot { it.id == summary.id },
                        toastMessage = "Перемещено в «${targetFolder.displayName}»"
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Не удалось переместить письмо") }
            }
        }
    }

    fun toggleSpamSummary(summary: EmailSummary) {
        val inJunk = _state.value.currentFolder?.systemKind == SystemFolderKind.JUNK
        val targetKind = if (inJunk) SystemFolderKind.INBOX else SystemFolderKind.JUNK
        val target = _state.value.folders.firstOrNull { it.systemKind == targetKind }
        if (target == null) {
            _state.update { it.copy(toastMessage = "Целевая папка не найдена") }
            return
        }
        moveSummary(summary, target)
    }

    fun deleteSummary(summary: EmailSummary) {
        val c = client ?: return
        val hardDelete = _state.value.currentFolder?.systemKind == SystemFolderKind.DELETED
        viewModelScope.launch {
            try {
                c.deleteMessages(listOf(summary.id to summary.changeKey), hardDelete)
                val folders = runCatching { c.getFolders() }.getOrNull()
                _state.update { st ->
                    st.copy(
                        folders = folders ?: st.folders,
                        messages = st.messages.filterNot { it.id == summary.id },
                        toastMessage = if (hardDelete) "Удалено навсегда" else "Перемещено в Удалённые"
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Не удалось удалить письмо") }
            }
        }
    }

    private fun applyAutoSortToMessages(
        c: EwsClient,
        items: List<EmailSummary>,
        currentFolder: MailFolder?
    ) {
        if (currentFolder?.systemKind != SystemFolderKind.INBOX) return
        val rules = _tagSortRules.value
        if (rules.isEmpty() || items.isEmpty()) return
        val inboxId = currentFolder.id

        val pending = items.mapNotNull { item ->
            val matched = matchAutoSortRuleDetailed(item.subject, rules) ?: return@mapNotNull null
            if (matched.rule.targetFolderId.isBlank() || matched.rule.targetFolderId == inboxId) {
                return@mapNotNull null
            }
            item to matched
        }
        if (pending.isEmpty()) return

        val app = getApplication<Application>()
        viewModelScope.launch {
            val foldersCache = _state.value.folders.toMutableList()
            val movedIds = mutableSetOf<String>()
            var folderListChanged = false

            for ((item, matched) in pending) {
                val (movedFolderId, refreshedFolders) = MailNotificationHelper.resolveAndMoveForTagRule(
                    context = app,
                    client = c,
                    msg = item,
                    match = matched,
                    foldersCache = foldersCache
                )
                if (refreshedFolders != null) {
                    folderListChanged = true
                }
                if (!movedFolderId.isNullOrBlank()) {
                    movedIds += item.id
                }
            }
            if (movedIds.isNotEmpty() || folderListChanged) {
                val folders = runCatching { c.getFolders() }.getOrNull()
                _mutedFolderIds.value = MailRulesStore.getMutedFolderIds(app)
                _state.update { st ->
                    st.copy(
                        folders = folders ?: st.folders,
                        messages = st.messages.filterNot { it.id in movedIds },
                        selectedIds = st.selectedIds - movedIds
                    )
                }
            }
        }
    }

    fun toggleFlagDetail(detail: EmailDetail) {
        val c = client ?: return
        val next = !detail.isFlagged
        viewModelScope.launch {
            try {
                c.setFlagged(listOf(detail.id to detail.changeKey), next)
                _state.update { st ->
                    st.copy(
                        selectedMessage = st.selectedMessage?.copy(isFlagged = next),
                        messages = st.messages.map {
                            if (it.id == detail.id) it.copy(isFlagged = next) else it
                        },
                        toastMessage = if (next) "Флажок установлен" else "Флажок снят"
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Ошибка обновления флажка") }
            }
        }
    }

    fun openMessage(summary: EmailSummary) {
        if (_state.value.selectedIds.isNotEmpty()) {
            toggleSelection(summary.id)
            return
        }
        val c = client ?: return
        openMessageById(summary.id, c)
    }

    fun openMessageById(itemId: String, activeClient: EwsClient? = client) {
        if (itemId.isBlank()) return
        val c = activeClient
        if (c == null) {
            pendingNotificationMessageId = itemId
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                var detail = c.getMessage(itemId)
                if (!detail.isRead && !detail.isDraft) {
                    runCatching { c.setRead(listOf(detail.id to detail.changeKey), true) }
                    detail = detail.copy(isRead = true)
                }
                if (detail.isDraft || _state.value.currentFolder?.systemKind == SystemFolderKind.DRAFTS) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            directorySuggestions = emptyList(),
                            screen = Screen.Compose(draft = detail)
                        )
                    }
                    return@launch
                }

                if (detail.isHtml && detail.attachments.any { it.isInline && it.contentId.isNotBlank() }) {
                    detail = resolveInlineCidImages(c, detail)
                }

                val detailNames = buildList {
                    add(detail.fromEmail to detail.from)
                    (detail.to + detail.cc + detail.bcc).forEach { raw ->
                        val (n, e) = parseFormattedRecipient(raw)
                        if (e.isNotBlank()) add(e to n)
                    }
                }
                rememberKnownNames(detailNames)

                _state.update { st ->
                    val updatedList = st.messages.map {
                        if (it.id == detail.id) it.copy(isRead = true) else it
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

    private suspend fun resolveInlineCidImages(c: EwsClient, detail: EmailDetail): EmailDetail {
        var html = detail.body
        val inlineImages = detail.attachments
            .filter {
                it.isInline &&
                    it.contentId.isNotBlank() &&
                    it.contentType.startsWith("image/", ignoreCase = true) &&
                    it.size in 1..MAX_INLINE_IMAGE_BYTES
            }
            .take(15)

        for (att in inlineImages) {
            runCatching {
                val downloaded = c.downloadAttachment(att.id)
                val b64 = Base64.getEncoder().encodeToString(downloaded.bytes)
                val dataUri = "data:${downloaded.contentType};base64,$b64"
                val cidClean = att.contentId.trim().removePrefix("<").removeSuffix(">")
                html = html.replace("cid:$cidClean", dataUri, ignoreCase = true)
                val cidEncoded = Uri.encode(cidClean)
                if (cidEncoded != cidClean) {
                    html = html.replace("cid:$cidEncoded", dataUri, ignoreCase = true)
                }
            }
        }
        return detail.copy(body = html)
    }

    fun backToMailList() {
        _state.update {
            it.copy(
                screen = Screen.MailList,
                selectedMessage = null,
                directorySuggestions = emptyList(),
                errorMessage = null
            )
        }
    }

    fun toggleRead(detail: EmailDetail) {
        val c = client ?: return
        val newState = !detail.isRead
        viewModelScope.launch {
            try {
                c.setRead(listOf(detail.id to detail.changeKey), newState)
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

    fun moveMessage(detail: EmailDetail, targetFolder: MailFolder) {
        val c = client ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.moveMessages(listOf(detail.id to detail.changeKey), targetFolder.id)
                val folders = c.getFolders()
                _state.update { st ->
                    st.copy(
                        isLoading = false,
                        folders = folders,
                        screen = Screen.MailList,
                        selectedMessage = null,
                        messages = st.messages.filterNot { it.id == detail.id },
                        toastMessage = "Перемещено в «${targetFolder.displayName}»"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = e.message ?: "Не удалось переместить письмо"
                    )
                }
            }
        }
    }

    fun toggleSpam(detail: EmailDetail) {
        val inJunk = _state.value.currentFolder?.systemKind == SystemFolderKind.JUNK
        val targetKind = if (inJunk) SystemFolderKind.INBOX else SystemFolderKind.JUNK
        val target = _state.value.folders.firstOrNull { it.systemKind == targetKind }
        if (target == null) {
            _state.update { it.copy(toastMessage = "Целевая папка не найдена") }
            return
        }
        moveMessage(detail, target)
    }

    fun deleteMessage(detail: EmailDetail) {
        val c = client ?: return
        val hardDelete = _state.value.currentFolder?.systemKind == SystemFolderKind.DELETED
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.deleteMessages(listOf(detail.id to detail.changeKey), hardDelete)
                val folders = c.getFolders()
                _state.update { st ->
                    st.copy(
                        isLoading = false,
                        folders = folders,
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

    // --- Compose & Directory Autocomplete ---

    fun openCompose(
        replyTo: EmailDetail? = null,
        replyAll: Boolean = false,
        isForward: Boolean = false,
        draft: EmailDetail? = null,
        prefillTo: String? = null,
        prefillName: String? = null
    ) {
        val formattedPrefill = prefillTo?.let { email ->
            val resolved = resolveDisplayName(prefillName.orEmpty(), email, null, _knownNames.value)
            if (resolved.isNotBlank() && !needsNameResolution(resolved, email)) {
                "$resolved <$email>"
            } else {
                email
            }
        }
        val effectiveReplyTo = if (prefillTo != null && replyTo == null && draft == null) {
            EmailDetail(
                id = "",
                changeKey = "",
                subject = "",
                from = prefillTo,
                fromEmail = prefillTo,
                to = emptyList(),
                cc = emptyList(),
                receivedAt = "",
                body = "",
                isHtml = false,
                isRead = true,
                attachments = emptyList()
            )
        } else {
            replyTo
        }
        val effectiveDraft = if (formattedPrefill != null && draft == null && replyTo == null) {
            effectiveReplyTo?.copy(isDraft = true, to = listOf(formattedPrefill))
        } else {
            draft
        }
        _state.update {
            it.copy(
                directorySuggestions = emptyList(),
                screen = Screen.Compose(
                    replyTo = if (effectiveDraft != null) null else effectiveReplyTo,
                    replyAll = replyAll,
                    isForward = isForward,
                    draft = effectiveDraft
                ),
                errorMessage = null
            )
        }
    }

    fun searchDirectory(query: String) {
        directoryJob?.cancel()
        val token = query.substringAfterLast(',').substringAfterLast(';').trim()
        if (token.length < 2) {
            _state.update { it.copy(directorySuggestions = emptyList()) }
            return
        }
        val localMatches = _knownNames.value.entries
            .filter { (email, name) ->
                email.contains(token, ignoreCase = true) || name.contains(token, ignoreCase = true)
            }
            .map { (email, name) -> ContactEntry(name = name, email = email, isFromDirectory = true) }
        if (localMatches.isNotEmpty()) {
            _state.update { it.copy(directorySuggestions = localMatches.take(8)) }
        }
        val c = client ?: return
        directoryJob = viewModelScope.launch {
            delay(300)
            runCatching { c.resolveNames(token) }
                .onSuccess { remote ->
                    rememberKnownNames(remote.map { it.email to it.name })
                    val merged = (remote + localMatches)
                        .sortedBy { needsNameResolution(it.name, it.email) }
                        .distinctBy { it.email.lowercase() }
                        .take(8)
                    _state.update { it.copy(directorySuggestions = merged) }
                }
        }
    }

    fun clearDirectorySuggestions() {
        _state.update { it.copy(directorySuggestions = emptyList()) }
    }

    fun saveOrSendEmail(
        draftId: String?,
        draftChangeKey: String?,
        toRaw: String,
        ccRaw: String,
        bccRaw: String,
        subject: String,
        body: String,
        importance: EmailImportance,
        attachmentUris: List<Uri>,
        context: Context,
        send: Boolean
    ) {
        val c = client ?: return
        val toList = splitEmails(toRaw)
        val ccList = splitEmails(ccRaw)
        val bccList = splitEmails(bccRaw)

        if (send && (toList.isEmpty() || toList.any { '@' !in it })) {
            _state.update { it.copy(errorMessage = "Укажите корректный адрес получателя") }
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isSending = true, errorMessage = null) }
            try {
                val outgoing = readOutgoingAttachments(context, attachmentUris)
                c.saveOrSendDraft(
                    draftId = draftId?.takeIf { it.isNotBlank() },
                    draftChangeKey = draftChangeKey,
                    to = toList,
                    cc = ccList,
                    bcc = bccList,
                    subject = subject.ifBlank { "(Без темы)" },
                    body = body,
                    importance = importance,
                    newAttachments = outgoing,
                    send = send
                )
                _state.update {
                    it.copy(
                        isSending = false,
                        directorySuggestions = emptyList(),
                        screen = Screen.MailList,
                        toastMessage = if (send) "Письмо отправлено" else "Черновик сохранён"
                    )
                }
                loadMessages(offset = 0, reset = true)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSending = false,
                        errorMessage = e.message ?: if (send) {
                            "Не удалось отправить письмо"
                        } else {
                            "Не удалось сохранить черновик"
                        }
                    )
                }
            }
        }
    }

    private suspend fun readOutgoingAttachments(
        context: Context,
        uris: List<Uri>
    ): List<OutgoingAttachment> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext emptyList()
        val resolver = context.contentResolver
        var totalSize = 0L
        val result = mutableListOf<OutgoingAttachment>()
        for (uri in uris) {
            var name = "attachment"
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) {
                    val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = cur.getString(idx) ?: name
                }
            }
            val safeName = sanitizeFilename(name)
            val mime = resolver.getType(uri) ?: "application/octet-stream"
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw EwsException("Не удалось прочитать файл: $safeName")
            if (bytes.size > MAX_SINGLE_UPLOAD_BYTES) {
                throw EwsException("Файл $safeName превышает 20 МБ")
            }
            totalSize += bytes.size
            if (totalSize > MAX_TOTAL_UPLOAD_BYTES) {
                throw EwsException("Суммарный размер вложений превышает 25 МБ")
            }
            result += OutgoingAttachment(name = safeName, contentType = mime, bytes = bytes)
        }
        result
    }

    // --- Contacts & Calendar ---

    fun openContacts() {
        _state.update { it.copy(screen = Screen.Contacts, errorMessage = null, directorySuggestions = emptyList()) }
        val c = client ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val list = c.getContacts()
                rememberKnownNames(list.map { it.email to it.name })
                _state.update { it.copy(isLoading = false, contacts = list) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = e.message ?: "Не удалось загрузить контакты"
                    )
                }
            }
        }
    }

    fun addContact(name: String, email: String) {
        val c = client ?: return
        if (name.isBlank() || '@' !in email) {
            _state.update { it.copy(toastMessage = "Укажите имя и корректный email") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.createContact(name, email)
                val list = c.getContacts()
                rememberKnownNames(list.map { it.email to it.name })
                _state.update {
                    it.copy(
                        isLoading = false,
                        contacts = list,
                        toastMessage = "Контакт добавлен"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = e.message ?: "Не удалось добавить контакт"
                    )
                }
            }
        }
    }

    fun openCalendar() {
        _state.update { it.copy(screen = Screen.Calendar, errorMessage = null) }
        val c = client ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val now = Instant.now()
                val start = DateTimeFormatter.ISO_INSTANT.format(now.minusSeconds(86400L))
                val end = DateTimeFormatter.ISO_INSTANT.format(now.plusSeconds(30L * 86400L))
                val events = c.getCalendarEvents(start, end)
                _state.update { it.copy(isLoading = false, calendarEvents = events) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = e.message ?: "Не удалось загрузить календарь"
                    )
                }
            }
        }
    }

    fun createCalendarEvent(
        subject: String,
        startIso: String,
        endIso: String,
        location: String,
        body: String
    ) {
        val c = client ?: return
        if (subject.isBlank()) {
            _state.update { it.copy(toastMessage = "Укажите название события") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.createCalendarEvent(subject, startIso, endIso, location, body)
                _state.update { it.copy(toastMessage = "Событие добавлено") }
                openCalendar()
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = e.message ?: "Не удалось создать событие"
                    )
                }
            }
        }
    }

    fun deleteCalendarEvent(event: CalendarEvent) {
        val c = client ?: return
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.deleteMessages(listOf(event.id to event.changeKey), hardDelete = false)
                _state.update { st ->
                    st.copy(
                        isLoading = false,
                        calendarEvents = st.calendarEvents.filterNot { it.id == event.id },
                        toastMessage = "Событие удалено"
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        toastMessage = e.message ?: "Не удалось удалить событие"
                    )
                }
            }
        }
    }

    // --- Attachments & Updates & Auth ---

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

    fun requestAvatar(email: String) {
        val key = email.trim().lowercase()
        if ('@' !in key) return
        val c = client ?: return
        if (!requestedAvatars.add(key)) return

        viewModelScope.launch {
            val bitmap = avatarSemaphore.withPermit {
                val bytes = runCatching { c.getUserPhoto(key) }.getOrNull()
                if (bytes != null) {
                    withContext(Dispatchers.Default) {
                        runCatching {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                        }.getOrNull()
                    }
                } else {
                    null
                }
            }
            _avatars.update { it + (key to bitmap) }
        }
    }

    fun requestPersonProfile(name: String, email: String) {
        val key = email.trim().lowercase().ifBlank { name.trim().lowercase() }
        if (key.isEmpty()) return
        val c = client ?: return
        if (!requestedProfiles.add(key)) return

        viewModelScope.launch {
            _loadingProfiles.update { it + key }
            val profile = runCatching { c.resolvePersonProfile(email, name) }.getOrNull()
            if (profile != null && profile.displayName.isNotBlank()) {
                val pairs = buildList {
                    if (email.isNotBlank()) add(email to profile.displayName)
                    if (profile.email.isNotBlank()) add(profile.email to profile.displayName)
                    profile.aliases.forEach { alias -> add(alias to profile.displayName) }
                }
                rememberKnownNames(pairs)
            }
            _personProfiles.update { it + (key to profile) }
            _loadingProfiles.update { it - key }
        }
    }

    fun logout() {
        client = null
        pendingNotificationMessageId = null
        store.clear()
        MailNotificationHelper.clearOnLogout(getApplication())
        _mutedFolderIds.value = emptySet()
        _mutedSenders.value = emptySet()
        _tagSortRules.value = emptyList()
        requestedAvatars.clear()
        requestedProfiles.clear()
        _avatars.value = emptyMap()
        _personProfiles.value = emptyMap()
        _knownNames.value = emptyMap()
        _loadingProfiles.value = emptySet()
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
        val channel = _updateChannel.value
        viewModelScope.launch {
            _state.update { it.copy(isCheckingUpdate = true) }
            try {
                val update = updater.checkLatestRelease(channel = channel)
                _state.update { st ->
                    st.copy(
                        isCheckingUpdate = false,
                        availableUpdate = update ?: st.availableUpdate,
                        downloadedUpdatePath = if (update != null) null else st.downloadedUpdatePath,
                        updateDownloadProgress = if (update != null) null else st.updateDownloadProgress,
                        updateError = null,
                        toastMessage = if (manual && update == null) {
                            "Установлена последняя версия (${BuildConfig.VERSION_NAME}, ветка: ${channel.labelRu})"
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
        private const val MAX_INLINE_IMAGE_BYTES = 2 * 1024 * 1024L
        private const val MAX_SINGLE_UPLOAD_BYTES = 20 * 1024 * 1024L
        private const val MAX_TOTAL_UPLOAD_BYTES = 25 * 1024 * 1024L
        private val RU_LOCALE = Locale("ru")
        private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", RU_LOCALE)
        private val DAY_MONTH_FMT = DateTimeFormatter.ofPattern("d MMM", RU_LOCALE)
        private val FULL_DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy", RU_LOCALE)
        private val DETAIL_FMT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", RU_LOCALE)

        fun splitEmails(raw: String): List<String> =
            raw.split(',', ';', ' ')
                .map { it.trim() }
                .filter { it.isNotBlank() }

        fun extractRawEmail(formatted: String): String {
            val start = formatted.indexOf('<')
            val end = formatted.indexOf('>')
            return if (start in 0 until end) {
                formatted.substring(start + 1, end).trim()
            } else {
                formatted.trim()
            }
        }

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
