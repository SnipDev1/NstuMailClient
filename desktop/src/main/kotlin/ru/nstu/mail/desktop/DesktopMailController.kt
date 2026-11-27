package ru.nstu.mail.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import ru.nstu.mail.data.AttachmentInfo
import ru.nstu.mail.data.CalendarEvent
import ru.nstu.mail.data.ContactEntry
import ru.nstu.mail.data.Credentials
import ru.nstu.mail.data.DEFAULT_SERVER_URL
import ru.nstu.mail.data.EmailDetail
import ru.nstu.mail.data.EmailImportance
import ru.nstu.mail.data.EmailSummary
import ru.nstu.mail.data.EwsClient
import ru.nstu.mail.data.EwsException
import ru.nstu.mail.data.MailFilter
import ru.nstu.mail.data.MailFolder
import ru.nstu.mail.data.OutgoingAttachment
import ru.nstu.mail.data.PersonProfile
import ru.nstu.mail.data.SystemFolderKind
import ru.nstu.mail.data.TagSortRule
import ru.nstu.mail.data.UNSORTED_FOLDER_NAME
import ru.nstu.mail.data.adjustFolderUnreadDelta
import ru.nstu.mail.data.applyFolderUnreadDeltas
import ru.nstu.mail.data.buildAutoSortFromFolderTree
import ru.nstu.mail.data.collectSubtreeFolderIds
import ru.nstu.mail.data.deduplicateSummaries
import ru.nstu.mail.data.extractAllTagHierarchiesForMatching
import ru.nstu.mail.data.formatTagPath
import ru.nstu.mail.data.matchAllAutoSortRules
import ru.nstu.mail.data.needsNameResolution
import ru.nstu.mail.data.normalizeOwnSenderDetail
import ru.nstu.mail.data.normalizeOwnSenderSummary
import ru.nstu.mail.data.ownSenderFolderIds
import ru.nstu.mail.data.parseFormattedRecipient
import ru.nstu.mail.data.parseTagPath
import ru.nstu.mail.data.profileSmtpKeys
import ru.nstu.mail.data.resolveDisplayName
import ru.nstu.mail.data.sanitizeFilename
import ru.nstu.mail.data.shouldNotifyForMessage
import ru.nstu.mail.data.sortTagTemplatesHierarchically
import ru.nstu.mail.data.splitEmails
import java.awt.Desktop
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.Collections
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

sealed interface DesktopRightPane {
    data object Empty : DesktopRightPane
    data object MessageDetail : DesktopRightPane
    data class Compose(
        val replyTo: EmailDetail? = null,
        val replyAll: Boolean = false,
        val isForward: Boolean = false,
        val draft: EmailDetail? = null,
        val prefillTo: String? = null,
        val prefillName: String? = null
    ) : DesktopRightPane
    data object Contacts : DesktopRightPane
    data object Calendar : DesktopRightPane
    data object Settings : DesktopRightPane
}

data class DesktopUiState(
    val credentials: Credentials? = null,
    val isLoggedIn: Boolean = false,
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    val toastMessage: String? = null,
    val folders: List<MailFolder> = emptyList(),
    val currentFolder: MailFolder? = null,
    val filter: MailFilter = MailFilter.ALL,
    val includeSubfoldersInFolder: Boolean = true,
    val searchQuery: String = "",
    val searchAllFolders: Boolean = false,
    val messages: List<EmailSummary> = emptyList(),
    val selectedIds: Set<String> = emptySet(),
    val reachedEnd: Boolean = true,
    val selectedMessage: EmailDetail? = null,
    val rightPane: DesktopRightPane = DesktopRightPane.Empty,
    val contacts: List<ContactEntry> = emptyList(),
    val directorySuggestions: List<ContactEntry> = emptyList(),
    val calendarEvents: List<CalendarEvent> = emptyList(),
    val settings: DesktopSettings = DesktopSettings(),
    val availableUpdate: DesktopUpdateInfo? = null,
    val isCheckingUpdate: Boolean = false
)

private data class SeenSnapshot(
    val initialized: Boolean,
    val seenKeys: Set<String>,
    val baselinedFolders: Set<String>,
    val lastSyncMs: Long
)

class DesktopMailController(configDir: File = DesktopConfigPaths.defaultConfigDir()) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val credStore = DesktopCredentialStore(configDir)
    private val settingsStore = DesktopSettingsStore(configDir)
    private val updater = DesktopUpdater()

    private val _state = MutableStateFlow(DesktopUiState(settings = settingsStore.load()))
    val state: StateFlow<DesktopUiState> = _state.asStateFlow()

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

    private val seenKeysOrder = ArrayDeque<String>()
    private val seenKeysSet = HashSet<String>()
    private val baselinedFolderIds = HashSet<String>()
    private val seenLock = Any()
    @Volatile
    private var baselineInitialized = false
    @Volatile
    private var lastSyncMs: Long = 0L

    private var client: EwsClient? = null
    private var loadMessagesJob: Job? = null
    private var nextPageOffset: Int = 0
    private var pushJob: Job? = null
    private var directoryJob: Job? = null
    private var updateCheckJob: Job? = null

    init {
        val saved = credStore.load()
        if (saved != null) {
            _state.update { it.copy(credentials = saved) }
            login(saved.email, saved.password, saved.serverUrl)
        }
        checkForUpdates(manual = false)
    }

    fun dispose() {
        pushJob?.cancel()
        loadMessagesJob?.cancel()
        directoryJob?.cancel()
        updateCheckJob?.cancel()
        scope.cancel()
    }

    private fun seenKeysFor(msg: EmailSummary): List<String> = buildList {
        if (msg.id.isNotBlank()) add(msg.id)
        val mid = msg.internetMessageId.trim().lowercase()
        if (mid.isNotEmpty()) {
            add("mid:$mid")
        } else {
            val subj = msg.subject.trim().lowercase()
            val recv = msg.receivedAt.trim()
            if (recv.isNotEmpty()) add("sr:$subj|$recv")
        }
    }

    private fun groupKeyFor(msg: EmailSummary): String {
        val mid = msg.internetMessageId.trim().lowercase()
        if (mid.isNotEmpty()) return "mid:$mid"
        val subj = msg.subject.trim().lowercase()
        val recv = msg.receivedAt.trim()
        if (subj.isNotEmpty() || recv.isNotEmpty()) return "sr:$subj|$recv"
        return "id:${msg.id}"
    }

    private fun recordSeenKeys(keys: Iterable<String>) {
        synchronized(seenLock) {
            for (k in keys) {
                if (k.isBlank() || !seenKeysSet.add(k)) continue
                seenKeysOrder.addLast(k)
                while (seenKeysOrder.size > MAX_SEEN_KEYS) {
                    val removed = seenKeysOrder.removeFirst()
                    seenKeysSet.remove(removed)
                }
            }
        }
    }

    private fun recordSeenMessages(messages: List<EmailSummary>, folderIds: Collection<String> = emptyList()) {
        recordSeenKeys(messages.flatMap(::seenKeysFor))
        synchronized(seenLock) {
            baselinedFolderIds.addAll(folderIds.filter { it.isNotBlank() })
            if (lastSyncMs <= 0L) lastSyncMs = System.currentTimeMillis()
            baselineInitialized = true
        }
    }

    private fun updateSettings(transform: (DesktopSettings) -> DesktopSettings) {
        val next = transform(_state.value.settings)
        settingsStore.save(next)
        _state.update { it.copy(settings = next) }
    }

    private fun rememberKnownNames(pairs: Iterable<Pair<String, String>>) {
        val current = _knownNames.value
        val additions = mutableMapOf<String, String>()
        for ((rawEmail, rawName) in pairs) {
            val key = rawEmail.trim().lowercase()
            val clean = rawName.trim()
            if ('@' in key && clean.isNotEmpty() && !needsNameResolution(clean, key) && current[key] != clean) {
                additions[key] = clean
            }
        }
        if (additions.isNotEmpty()) _knownNames.update { it + additions }
    }

    private suspend fun ensureUnsortedFolder(c: EwsClient, folders: List<MailFolder>): List<MailFolder> {
        if (folders.any { !it.isSystem && it.displayName.equals(UNSORTED_FOLDER_NAME, ignoreCase = true) }) {
            return folders
        }
        return runCatching {
            c.createFolder(UNSORTED_FOLDER_NAME, parentFolderId = null)
            c.getFolders()
        }.getOrDefault(folders)
    }

    fun login(email: String, password: String, serverUrl: String = DEFAULT_SERVER_URL) {
        val trimmed = email.trim()
        if (trimmed.isBlank() || password.isEmpty()) {
            _state.update { it.copy(errorMessage = "Введите логин и пароль") }
            return
        }
        val normalizedUrl = runCatching { EwsClient.normalizeHttpsUrl(serverUrl) }.getOrElse { err ->
            _state.update { it.copy(errorMessage = err.message ?: "Некорректный адрес сервера") }
            return
        }
        val creds = Credentials(trimmed, password, normalizedUrl)
        val newClient = EwsClient(creds)

        scope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null, credentials = creds) }
            try {
                val folders = ensureUnsortedFolder(newClient, newClient.getFolders())
                val inbox = folders.firstOrNull { it.systemKind == SystemFolderKind.INBOX } ?: folders.firstOrNull()
                val (items, includesLast) = if (inbox != null) {
                    newClient.listMessages(listOf(inbox.id), offset = 0, pageSize = PAGE_SIZE)
                } else {
                    emptyList<EmailSummary>() to true
                }
                client = newClient
                credStore.save(creds)
                rememberKnownNames(items.map { it.senderEmail to it.senderName })
                if (inbox?.systemKind == SystemFolderKind.INBOX) {
                    updateSettings { it.copy(inboxFolderId = inbox.id) }
                    recordSeenMessages(items, listOf(inbox.id))
                }
                nextPageOffset = PAGE_SIZE
                scope.launch {
                    runCatching { newClient.getRecipientCache() }
                        .onSuccess { cache -> rememberKnownNames(cache.map { it.email to it.name }) }
                }
                requestPersonProfile("", creds.email)
                _state.update {
                    it.copy(
                        isLoggedIn = true,
                        isLoading = false,
                        folders = folders,
                        currentFolder = inbox,
                        filter = MailFilter.ALL,
                        includeSubfoldersInFolder = true,
                        searchQuery = "",
                        searchAllFolders = false,
                        messages = items,
                        reachedEnd = includesLast,
                        rightPane = DesktopRightPane.Empty
                    )
                }
                applyAutoSortToMessages(newClient, items, inbox)
                startPushLoop(newClient)
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        isLoggedIn = false,
                        errorMessage = e.message ?: "Ошибка подключения"
                    )
                }
            }
        }
    }

    private suspend fun resolveMonitoredFolders(activeClient: EwsClient): Triple<String?, List<String>, MutableList<MailFolder>?> {
        val folders = runCatching { activeClient.getFolders(includeSubtreeCounts = false).toMutableList() }.getOrNull()
        val inboxId = folders?.firstOrNull { it.systemKind == SystemFolderKind.INBOX }?.id
            ?: _state.value.settings.inboxFolderId
        val rules = _state.value.settings.tagSortRules
        val activeIds = folders?.map { it.id }?.toSet().orEmpty()
        val ruleFolderIds = if (folders != null) {
            rules.flatMap { rule ->
                if (rule.targetFolderId !in activeIds) emptyList()
                else if (rule.autoCreateSubfolders) collectSubtreeFolderIds(folders, rule.targetFolderId)
                else listOf(rule.targetFolderId)
            }
        } else {
            rules.map { it.targetFolderId }.filter { it.isNotBlank() }
        }
        val unsortedId = folders?.firstOrNull {
            !it.isSystem && it.displayName.equals(UNSORTED_FOLDER_NAME, ignoreCase = true)
        }?.id
        val monitored = (listOfNotNull(inboxId) + ruleFolderIds + listOfNotNull(unsortedId)).distinct()
        return Triple(inboxId, monitored, folders)
    }

    private fun startPushLoop(activeClient: EwsClient) {
        pushJob?.cancel()
        pushJob = scope.launch(Dispatchers.IO) {
            var backoffMs = 5_000L
            while (isActive && client === activeClient) {
                var subscriptionId = ""
                try {
                    checkUnreadAndNotify(activeClient)
                    val (_, monitoredFolderIds, _) = resolveMonitoredFolders(activeClient)
                    subscriptionId = activeClient.createStreamingSubscription(
                        folderIds = monitoredFolderIds,
                        subscribeAllFolders = true
                    )
                    backoffMs = 5_000L
                    while (isActive && client === activeClient && subscriptionId.isNotBlank()) {
                        var invalid = false
                        activeClient.streamInboxEvents(subscriptionId, timeoutMinutes = 25) { chunk ->
                            if (chunk.newMailItemIds.isNotEmpty()) {
                                val sysMap = activeClient.getCachedSystemFolderIds()
                                val monitoredSet = monitoredFolderIds.toSet()
                                val onlyIgnored = chunk.eventFolderIds.isNotEmpty() &&
                                    chunk.eventFolderIds.all { fid ->
                                        val kind = sysMap[fid]
                                        fid !in monitoredSet && kind != null && kind != SystemFolderKind.INBOX
                                    }
                                if (!onlyIgnored) {
                                    checkUnreadAndNotify(activeClient)
                                }
                            }
                            if (chunk.subscriptionInvalid) invalid = true
                        }
                        if (invalid) subscriptionId = ""
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: EwsException) {
                    if (e.isAuthError) return@launch
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                } catch (_: Exception) {
                    delay(backoffMs)
                    backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
                } finally {
                    if (subscriptionId.isNotBlank()) {
                        runCatching { activeClient.unsubscribe(subscriptionId) }
                    }
                }
            }
        }
    }

    private suspend fun checkUnreadAndNotify(activeClient: EwsClient) {
        val (savedInboxId, monitoredIds, initialCache) = resolveMonitoredFolders(activeClient)
        if (!savedInboxId.isNullOrBlank() && _state.value.settings.inboxFolderId != savedInboxId) {
            withContext(Dispatchers.Main) { updateSettings { it.copy(inboxFolderId = savedInboxId) } }
        }
        val unread = runCatching {
            if (monitoredIds.isNotEmpty()) {
                activeClient.listUnreadInFolders(monitoredIds, pageSizePerFolder = 15)
            } else {
                activeClient.listInboxUnread(pageSize = 15)
            }
        }.getOrNull() ?: return

        val grouped = LinkedHashMap<String, Pair<EmailSummary, MutableSet<String>>>()
        for (msg in unread) {
            if (msg.id.isBlank()) continue
            val key = groupKeyFor(msg)
            val fid = msg.parentFolderId.trim()
            val entry = grouped[key]
            if (entry == null) {
                val fids = mutableSetOf<String>()
                if (fid.isNotEmpty()) fids += fid
                grouped[key] = msg to fids
            } else {
                if (fid.isNotEmpty()) entry.second += fid
                if (savedInboxId != null && fid == savedInboxId && entry.first.parentFolderId != savedInboxId) {
                    grouped[key] = msg to entry.second
                }
            }
        }

        val (initFlag, seenCopy, baselinedCopy, syncMs) = synchronized(seenLock) {
            SeenSnapshot(
                initialized = baselineInitialized,
                seenKeys = seenKeysSet.toSet(),
                baselinedFolders = baselinedFolderIds.toSet(),
                lastSyncMs = lastSyncMs
            )
        }

        val incomingKeys = unread.flatMap(::seenKeysFor).toMutableList()
        var cache: MutableList<MailFolder>? = initialCache
        val stSettings = _state.value.settings
        var mutedFolders = stSettings.mutedFolderIds
        val toNotify = mutableListOf<EmailSummary>()
        var anyMoved = false

        if (initFlag) {
            for ((repMsg, sourceFolderIds) in grouped.values) {
                if (seenKeysFor(repMsg).any { it in seenCopy }) continue
                val inInbox = (savedInboxId != null && savedInboxId in sourceFolderIds) || sourceFolderIds.isEmpty()
                val shouldProcess = if (inInbox || sourceFolderIds.any { it in baselinedCopy }) {
                    true
                } else if (syncMs > 0L) {
                    val recMs = runCatching { Instant.parse(repMsg.receivedAt.trim()).toEpochMilli() }.getOrNull()
                    recMs != null && recMs >= (syncMs - 5L * 60_000L)
                } else {
                    false
                }
                if (!shouldProcess) continue

                val effectiveFolderIds: List<String>
                if (inInbox) {
                    val (outcome, nextCache) = DesktopAutoSorter.applyAutoSortForMessage(
                        settingsStore = settingsStore,
                        client = activeClient,
                        msg = repMsg,
                        rules = stSettings.tagSortRules,
                        inboxFolderId = savedInboxId,
                        foldersCache = cache
                    )
                    cache = nextCache
                    if (outcome.movedFromInbox || outcome.foldersChanged) anyMoved = true
                    if (outcome.foldersChanged) mutedFolders = settingsStore.load().mutedFolderIds
                    incomingKeys += outcome.movedItemIds
                    synchronized(seenLock) { baselinedFolderIds.addAll(outcome.targetFolderIds) }
                    effectiveFolderIds = outcome.targetFolderIds.ifEmpty { listOfNotNull(savedInboxId) }
                } else {
                    effectiveFolderIds = sourceFolderIds.toList()
                }

                val shouldNotify = stSettings.notificationsEnabled && if (effectiveFolderIds.isNotEmpty()) {
                    effectiveFolderIds.any { fid ->
                        shouldNotifyForMessage(
                            senderEmail = repMsg.senderEmail,
                            effectiveFolderId = fid,
                            isInbox = (fid == savedInboxId),
                            savedInboxFolderId = savedInboxId,
                            mutedFolderIds = mutedFolders,
                            mutedSenders = stSettings.mutedSenders
                        )
                    }
                } else {
                    shouldNotifyForMessage(
                        senderEmail = repMsg.senderEmail,
                        effectiveFolderId = savedInboxId,
                        isInbox = true,
                        savedInboxFolderId = savedInboxId,
                        mutedFolderIds = mutedFolders,
                        mutedSenders = stSettings.mutedSenders
                    )
                }
                if (shouldNotify) toNotify += repMsg
            }
        }

        recordSeenKeys(incomingKeys)
        synchronized(seenLock) {
            baselinedFolderIds.addAll(monitoredIds)
            lastSyncMs = System.currentTimeMillis()
            baselineInitialized = true
        }

        if (toNotify.isNotEmpty()) {
            val title = if (toNotify.size == 1) {
                toNotify[0].senderName.ifBlank { toNotify[0].senderEmail }
            } else {
                "Новых писем: ${toNotify.size}"
            }
            val body = if (toNotify.size == 1) {
                toNotify[0].subject
            } else {
                "${toNotify[0].senderName}: ${toNotify[0].subject}"
            }
            DesktopNotificationPoster.notify(title, body)
        }

        if (anyMoved || toNotify.isNotEmpty() || _state.value.currentFolder?.systemKind == SystemFolderKind.INBOX) {
            withContext(Dispatchers.Main) {
                refresh(quiet = true)
            }
        }
    }

    // --- Navigation & Filters ---

    fun selectFolder(folder: MailFolder) {
        _state.update {
            it.copy(
                currentFolder = folder,
                includeSubfoldersInFolder = true,
                searchQuery = "",
                searchAllFolders = false,
                messages = emptyList(),
                selectedIds = emptySet(),
                reachedEnd = true,
                rightPane = if (it.rightPane is DesktopRightPane.MessageDetail) DesktopRightPane.Empty else it.rightPane
            )
        }
        loadMessages(offset = 0, reset = true)
    }

    fun setFilter(filter: MailFilter) {
        val cur = _state.value
        val restore = filter == MailFilter.ALL && !cur.includeSubfoldersInFolder
        if (cur.filter == filter && !restore) return
        _state.update {
            it.copy(
                filter = filter,
                includeSubfoldersInFolder = if (filter == MailFilter.ALL) true else it.includeSubfoldersInFolder,
                selectedIds = emptySet()
            )
        }
        loadMessages(offset = 0, reset = true)
    }

    fun setIncludeSubfolders(include: Boolean) {
        if (_state.value.includeSubfoldersInFolder == include) return
        _state.update { it.copy(includeSubfoldersInFolder = include, selectedIds = emptySet()) }
        loadMessages(offset = 0, reset = true)
    }

    fun search(query: String) {
        _state.update { it.copy(searchQuery = query, selectedIds = emptySet()) }
        loadMessages(offset = 0, reset = true)
    }

    fun setSearchAllFolders(enabled: Boolean) {
        _state.update { it.copy(searchAllFolders = enabled, selectedIds = emptySet()) }
        if (_state.value.searchQuery.isNotBlank()) loadMessages(offset = 0, reset = true)
    }

    fun refresh(quiet: Boolean = false) {
        loadMessages(offset = 0, reset = true, asPullRefresh = quiet)
    }

    fun loadMore() {
        val st = _state.value
        if (st.isLoading || st.reachedEnd) return
        loadMessages(offset = nextPageOffset, reset = false)
    }

    private fun targetFolderIdsForQuery(st: DesktopUiState = _state.value): List<String> {
        return when {
            st.searchQuery.isNotBlank() && st.searchAllFolders ->
                st.folders
                    .filter { it.systemKind != SystemFolderKind.DELETED && it.systemKind != SystemFolderKind.JUNK }
                    .map { it.id }
                    .ifEmpty { listOfNotNull(st.currentFolder?.id) }
            st.includeSubfoldersInFolder ->
                collectSubtreeFolderIds(st.folders, st.currentFolder?.id)
                    .ifEmpty { listOfNotNull(st.currentFolder?.id) }
            else -> listOfNotNull(st.currentFolder?.id)
        }
    }

    private fun loadMessages(offset: Int, reset: Boolean, asPullRefresh: Boolean = false) {
        val c = client ?: return
        if (reset) {
            loadMessagesJob?.cancel()
            nextPageOffset = 0
        }
        val snapshot = _state.value
        val query = snapshot.searchQuery
        val filter = snapshot.filter

        loadMessagesJob = scope.launch {
            _state.update {
                it.copy(isLoading = !asPullRefresh, isRefreshing = asPullRefresh, errorMessage = null)
            }
            try {
                var folders = if (reset) ensureUnsortedFolder(c, c.getFolders()) else _state.value.folders
                var updatedCurrent = _state.value.currentFolder?.let { cur ->
                    folders.firstOrNull { it.id == cur.id } ?: cur
                } ?: folders.firstOrNull()
                val inbox = folders.firstOrNull { it.systemKind == SystemFolderKind.INBOX }

                if (reset &&
                    query.isBlank() &&
                    updatedCurrent != null &&
                    !updatedCurrent.isSystem &&
                    updatedCurrent.displayName.equals(UNSORTED_FOLDER_NAME, ignoreCase = true) &&
                    inbox != null
                ) {
                    val (inboxItems, _) = runCatching {
                        c.listMessages(listOf(inbox.id), offset = 0, pageSize = PAGE_SIZE)
                    }.getOrDefault(emptyList<EmailSummary>() to true)
                    val tagged = inboxItems.filter { extractAllTagHierarchiesForMatching(it.subject).isNotEmpty() }
                    if (tagged.isNotEmpty()) {
                        var cache: MutableList<MailFolder>? = folders.toMutableList()
                        var anyChanged = false
                        var errText: String? = null
                        val rules = _state.value.settings.tagSortRules
                        for (item in tagged) {
                            val (out, nextCache) = DesktopAutoSorter.applyAutoSortForMessage(
                                settingsStore, c, item, rules, inbox.id, cache
                            )
                            cache = nextCache
                            if (out.movedFromInbox || out.foldersChanged) anyChanged = true
                            if (errText == null && out.errorMessage != null) errText = out.errorMessage
                        }
                        if (anyChanged) {
                            folders = runCatching { c.getFolders() }.getOrDefault(folders)
                            updatedCurrent = folders.firstOrNull { it.id == updatedCurrent.id } ?: updatedCurrent
                            _state.update { it.copy(settings = settingsStore.load()) }
                        } else if (!errText.isNullOrBlank()) {
                            _state.update { it.copy(toastMessage = "Автосортировка: $errText") }
                        }
                    }
                }

                val effectiveIds = targetFolderIdsForQuery(
                    _state.value.copy(folders = folders, currentFolder = updatedCurrent)
                ).ifEmpty { listOfNotNull(updatedCurrent?.id) }

                val (rawItems, includesLast) = c.listMessages(
                    folderIds = effectiveIds,
                    offset = offset,
                    pageSize = PAGE_SIZE,
                    query = query,
                    filter = filter
                )
                val items = normalizeSummariesForOwnFolders(
                    items = rawItems,
                    folders = folders,
                    current = updatedCurrent,
                    isSearchAllFolders = query.isNotBlank() && _state.value.searchAllFolders
                )
                nextPageOffset = offset + PAGE_SIZE
                rememberKnownNames(items.map { it.senderEmail to it.senderName })
                if (inbox != null && _state.value.settings.inboxFolderId != inbox.id) {
                    updateSettings { it.copy(inboxFolderId = inbox.id) }
                }
                if (updatedCurrent?.systemKind == SystemFolderKind.INBOX &&
                    reset &&
                    query.isBlank() &&
                    filter == MailFilter.ALL
                ) {
                    recordSeenMessages(items, listOf(updatedCurrent.id))
                }
                _state.update { st ->
                    val merged = when {
                        reset -> items
                        effectiveIds.size > 1 -> deduplicateSummaries(st.messages + items)
                        else -> (st.messages + items).distinctBy { it.id }
                    }
                    val nextSelectedIds = if (reset) {
                        val visibleIds = merged.mapTo(HashSet()) { it.id }
                        st.selectedIds.filterTo(HashSet()) { it in visibleIds }
                    } else {
                        st.selectedIds
                    }
                    st.copy(
                        isLoading = false,
                        isRefreshing = false,
                        folders = folders,
                        currentFolder = updatedCurrent,
                        messages = merged,
                        selectedIds = nextSelectedIds,
                        reachedEnd = includesLast
                    )
                }
                if (query.isBlank()) {
                    applyAutoSortToMessages(c, items, updatedCurrent)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, isRefreshing = false, errorMessage = e.message ?: "Не удалось загрузить письма")
                }
            }
        }
    }

    private fun applyAutoSortToMessages(c: EwsClient, items: List<EmailSummary>, currentFolder: MailFolder?) {
        if (currentFolder?.systemKind != SystemFolderKind.INBOX || items.isEmpty()) return
        val pending = items.filter { extractAllTagHierarchiesForMatching(it.subject).isNotEmpty() }
        if (pending.isEmpty()) return
        val rules = _state.value.settings.tagSortRules
        val inboxId = currentFolder.id

        scope.launch {
            var cache: MutableList<MailFolder>? = _state.value.folders.toMutableList()
            val movedIds = mutableSetOf<String>()
            var folderListChanged = false
            var sortError: String? = null
            for (item in pending) {
                val (out, nextCache) = DesktopAutoSorter.applyAutoSortForMessage(
                    settingsStore, c, item, rules, inboxId, cache
                )
                cache = nextCache
                if (out.movedItemIds.isNotEmpty()) {
                    recordSeenKeys(out.movedItemIds)
                }
                if (out.foldersChanged) folderListChanged = true
                if (out.movedFromInbox) movedIds += item.id
                if (sortError == null && out.errorMessage != null) sortError = out.errorMessage
            }
            if (movedIds.isNotEmpty() || folderListChanged) {
                val folders = runCatching { c.getFolders() }.getOrNull()
                _state.update { st ->
                    val nextFolders = folders ?: st.folders
                    val nextCur = st.currentFolder?.let { cur -> nextFolders.firstOrNull { it.id == cur.id } ?: cur }
                    st.copy(
                        folders = nextFolders,
                        currentFolder = nextCur,
                        settings = settingsStore.load(),
                        messages = st.messages.filterNot { it.id in movedIds },
                        selectedIds = st.selectedIds - movedIds
                    )
                }
            } else if (!sortError.isNullOrBlank()) {
                _state.update { it.copy(toastMessage = "Автосортировка: $sortError") }
            }
        }
    }

    // --- Folders Management ---

    fun createFolder(name: String, parentFolderId: String?) {
        val c = client ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.createFolder(trimmed, parentFolderId)
                val folders = c.getFolders()
                _state.update { it.copy(isLoading = false, folders = folders, toastMessage = "Папка «$trimmed» создана") }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, toastMessage = e.message ?: "Не удалось создать папку") }
            }
        }
    }

    fun renameFolder(folder: MailFolder, newName: String) {
        val c = client ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.renameFolder(folder.id, folder.changeKey, trimmed)
                updateSettings { st ->
                    st.copy(
                        tagSortRules = st.tagSortRules.map { r ->
                            if (r.targetFolderId == folder.id) r.copy(targetFolderName = trimmed) else r
                        }
                    )
                }
                val folders = c.getFolders()
                val cur = _state.value.currentFolder?.let { old -> folders.firstOrNull { it.id == old.id } ?: old }
                _state.update { it.copy(isLoading = false, folders = folders, currentFolder = cur, toastMessage = "Папка переименована") }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, toastMessage = e.message ?: "Не удалось переименовать папку") }
            }
        }
    }

    fun deleteFolder(folder: MailFolder) {
        val c = client ?: return
        if (folder.isSystem) return
        val deletedIds = collectSubtreeFolderIds(_state.value.folders, folder.id).toSet()
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.deleteFolder(folder.id, folder.changeKey)
                updateSettings { st ->
                    st.copy(
                        mutedFolderIds = st.mutedFolderIds - deletedIds,
                        tagSortRules = st.tagSortRules.filterNot { it.targetFolderId in deletedIds }
                    )
                }
                val folders = c.getFolders()
                val fallback = folders.firstOrNull { it.systemKind == SystemFolderKind.INBOX } ?: folders.firstOrNull()
                val curId = _state.value.currentFolder?.id
                val switched = curId == folder.id || (curId != null && folders.none { it.id == curId })
                _state.update {
                    it.copy(
                        isLoading = false,
                        folders = folders,
                        currentFolder = if (switched) fallback else it.currentFolder,
                        selectedIds = if (switched) emptySet() else it.selectedIds,
                        toastMessage = "Папка удалена"
                    )
                }
                if (switched) loadMessages(offset = 0, reset = true)
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, toastMessage = e.message ?: "Не удалось удалить папку") }
            }
        }
    }

    fun emptyCurrentFolder() {
        val c = client ?: return
        val folder = _state.value.currentFolder ?: return
        scope.launch {
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
                        selectedMessage = null,
                        rightPane = DesktopRightPane.Empty,
                        toastMessage = "Папка очищена"
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, toastMessage = e.message ?: "Не удалось очистить папку") }
            }
        }
    }

    fun markAllReadInCurrentFolder() {
        val c = client ?: return
        val stSnapshot = _state.value
        val folder = stSnapshot.currentFolder ?: return
        val targetIds = if (stSnapshot.includeSubfoldersInFolder) {
            collectSubtreeFolderIds(stSnapshot.folders, folder.id).ifEmpty { listOf(folder.id) }
        } else {
            listOf(folder.id)
        }
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.markAllRead(targetIds)
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
                _state.update { it.copy(isLoading = false, toastMessage = e.message ?: "Ошибка обновления писем") }
            }
        }
    }

    // --- Multi-selection & Batch Actions ---

    fun toggleSelection(id: String) {
        _state.update { st ->
            val next = if (id in st.selectedIds) st.selectedIds - id else st.selectedIds + id
            st.copy(selectedIds = next)
        }
    }

    fun toggleSelectAllVisible() {
        _state.update { st ->
            if (st.messages.isEmpty()) return@update st
            val allVisibleIds = st.messages.mapTo(HashSet()) { it.id }
            val next = if (st.messages.all { it.id in st.selectedIds }) {
                emptySet()
            } else {
                allVisibleIds
            }
            st.copy(selectedIds = next)
        }
    }

    fun clearSelection() {
        if (_state.value.selectedIds.isEmpty()) return
        _state.update { it.copy(selectedIds = emptySet()) }
    }

    fun batchSetRead(isRead: Boolean) {
        val c = client ?: return
        val ids = _state.value.selectedIds
        val targets = _state.value.messages.filter { it.id in ids }
        if (targets.isEmpty()) return
        scope.launch {
            try {
                c.setRead(targets.map { it.id to it.changeKey }, isRead)
                var deltasByFolder: Map<String, Int> = emptyMap()
                _state.update { st ->
                    val deltas = mutableMapOf<String, Int>()
                    val step = if (isRead) -1 else 1
                    for (msg in st.messages) {
                        if (msg.id in ids && msg.isRead != isRead) {
                            val fid = msg.parentFolderId.ifBlank { st.currentFolder?.id.orEmpty() }
                            if (fid.isNotBlank()) deltas[fid] = (deltas[fid] ?: 0) + step
                        }
                    }
                    deltasByFolder = deltas
                    val nextFolders = applyFolderUnreadDeltas(st.folders, deltas)
                    val nextCur = st.currentFolder?.let { cur -> nextFolders.firstOrNull { it.id == cur.id } ?: cur }
                    st.copy(
                        folders = nextFolders,
                        currentFolder = nextCur,
                        selectedIds = emptySet(),
                        selectedMessage = st.selectedMessage?.let { sel ->
                            if (sel.id in ids) sel.copy(isRead = isRead) else sel
                        },
                        messages = st.messages.map { if (it.id in ids) it.copy(isRead = isRead) else it },
                        toastMessage = if (isRead) "Отмечены прочитанными" else "Отмечены непрочитанными"
                    )
                }
                val affectedIds = deltasByFolder.keys
                if (affectedIds.size > 1) {
                    val updated = runCatching { c.getFolders() }.getOrNull()
                    if (updated != null) {
                        _state.update { st ->
                            st.copy(
                                folders = updated,
                                currentFolder = st.currentFolder?.let { cur -> updated.firstOrNull { it.id == cur.id } ?: cur }
                            )
                        }
                    }
                } else {
                    refreshSubtreeFoldersIfNested(c, affectedIds.singleOrNull() ?: _state.value.currentFolder?.id)
                }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Ошибка") }
            }
        }
    }

    fun batchMove(target: MailFolder) {
        val c = client ?: return
        val ids = _state.value.selectedIds
        val targets = _state.value.messages.filter { it.id in ids }
        if (targets.isEmpty()) return
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                c.moveMessages(targets.map { it.id to it.changeKey }, target.id)
                val folders = runCatching { c.getFolders() }.getOrNull()
                _state.update { st ->
                    val nextFolders = folders ?: st.folders
                    val nextCur = st.currentFolder?.let { cur -> nextFolders.firstOrNull { it.id == cur.id } ?: cur }
                    val closeDetail = st.selectedMessage?.id in ids
                    st.copy(
                        isLoading = false,
                        folders = nextFolders,
                        currentFolder = nextCur,
                        selectedIds = emptySet(),
                        selectedMessage = if (closeDetail) null else st.selectedMessage,
                        rightPane = if (closeDetail && st.rightPane is DesktopRightPane.MessageDetail) DesktopRightPane.Empty else st.rightPane,
                        messages = st.messages.filterNot { it.id in ids },
                        toastMessage = "Перемещено в «${target.displayName}»"
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, toastMessage = e.message ?: "Не удалось переместить письма") }
            }
        }
    }

    fun batchDelete() {
        val c = client ?: return
        val snapshot = _state.value
        val ids = snapshot.selectedIds
        val targets = snapshot.messages.filter { it.id in ids }
        if (targets.isEmpty()) return
        val deletedId = snapshot.folders.firstOrNull { it.systemKind == SystemFolderKind.DELETED }?.id
        val inDeletedFolder = snapshot.currentFolder?.systemKind == SystemFolderKind.DELETED
        val (hardTargets, softTargets) = targets.partition {
            inDeletedFolder || (deletedId != null && it.parentFolderId == deletedId)
        }
        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                if (hardTargets.isNotEmpty()) {
                    c.deleteMessages(hardTargets.map { it.id to it.changeKey }, hardDelete = true)
                }
                if (softTargets.isNotEmpty()) {
                    c.deleteMessages(softTargets.map { it.id to it.changeKey }, hardDelete = false)
                }
                val folders = runCatching { c.getFolders() }.getOrNull()
                val toast = when {
                    softTargets.isEmpty() -> "Удалено навсегда"
                    hardTargets.isEmpty() -> "Перемещено в Удалённые"
                    else -> "Письма удалены"
                }
                _state.update { st ->
                    val nextFolders = folders ?: st.folders
                    val nextCur = st.currentFolder?.let { cur -> nextFolders.firstOrNull { it.id == cur.id } ?: cur }
                    val closeDetail = st.selectedMessage?.id in ids
                    st.copy(
                        isLoading = false,
                        folders = nextFolders,
                        currentFolder = nextCur,
                        selectedIds = emptySet(),
                        selectedMessage = if (closeDetail) null else st.selectedMessage,
                        rightPane = if (closeDetail && st.rightPane is DesktopRightPane.MessageDetail) DesktopRightPane.Empty else st.rightPane,
                        messages = st.messages.filterNot { it.id in ids },
                        toastMessage = toast
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, toastMessage = e.message ?: "Не удалось удалить письма") }
            }
        }
    }

    // --- Messages ---

    private fun refreshSubtreeFoldersIfNested(c: EwsClient, folderId: String?) {
        if (folderId.isNullOrBlank()) return
        val folders = _state.value.folders
        val byId = folders.associateBy { it.id }
        val folder = byId[folderId] ?: return
        val hasParent = folder.parentId.isNotBlank() && folder.parentId in byId
        val hasChild = folders.any { it.parentId == folder.id }
        if (!hasParent && !hasChild) return
        scope.launch {
            val updated = runCatching { c.getFolders() }.getOrNull() ?: return@launch
            _state.update { st ->
                st.copy(
                    folders = updated,
                    currentFolder = st.currentFolder?.let { cur -> updated.firstOrNull { it.id == cur.id } ?: cur }
                )
            }
        }
    }

    fun openMessage(summary: EmailSummary) {
        val c = client ?: return
        scope.launch {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                var detail = c.getMessage(summary.id)
                val ownIds = ownFolderSet()
                val (selfEmail, selfName) = resolvedSelfIdentity()
                val stCur = _state.value
                detail = normalizeOwnSenderDetail(
                    detail = detail,
                    ownFolderIds = ownIds,
                    selfEmail = selfEmail,
                    selfName = selfName,
                    fallbackInOwnFolder = !(stCur.searchQuery.isNotBlank() && stCur.searchAllFolders) &&
                        stCur.currentFolder?.id in ownIds
                )
                val markedRead = !detail.isRead && !detail.isDraft
                if (markedRead) {
                    runCatching { c.setRead(listOf(detail.id to detail.changeKey), true) }
                    detail = detail.copy(isRead = true)
                }
                if (detail.isDraft || _state.value.currentFolder?.systemKind == SystemFolderKind.DRAFTS) {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            directorySuggestions = emptyList(),
                            rightPane = DesktopRightPane.Compose(draft = detail)
                        )
                    }
                    return@launch
                }
                if (detail.isHtml && detail.attachments.any { it.isInline && it.contentId.isNotBlank() }) {
                    detail = resolveInlineCidImages(c, detail)
                }
                rememberKnownNames(
                    listOf(detail.fromEmail to detail.from) + (detail.to + detail.cc + detail.bcc).map { raw ->
                        val (n, e) = parseFormattedRecipient(raw)
                        e to n
                    }
                )
                val targetFolderId = detail.parentFolderId.ifBlank { _state.value.currentFolder?.id }
                _state.update { st ->
                    val nextFolders = if (markedRead) {
                        adjustFolderUnreadDelta(st.folders, targetFolderId, -1)
                    } else {
                        st.folders
                    }
                    val nextCur = st.currentFolder?.let { cur -> nextFolders.firstOrNull { it.id == cur.id } ?: cur }
                    st.copy(
                        isLoading = false,
                        folders = nextFolders,
                        currentFolder = nextCur,
                        messages = st.messages.map { if (it.id == detail.id) it.copy(isRead = true) else it },
                        selectedMessage = detail,
                        rightPane = DesktopRightPane.MessageDetail
                    )
                }
                if (markedRead) refreshSubtreeFoldersIfNested(c, targetFolderId)
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, toastMessage = e.message ?: "Не удалось открыть письмо") }
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
                    it.size in 1..(2 * 1024 * 1024L)
            }
            .take(15)
        for (att in inlineImages) {
            runCatching {
                val dl = c.downloadAttachment(att.id)
                val b64 = Base64.getEncoder().encodeToString(dl.bytes)
                val dataUri = "data:${att.contentType};base64,$b64"
                val bareCid = att.contentId.trim().trim('<', '>')
                html = html.replace("cid:$bareCid", dataUri, ignoreCase = true)
                    .replace("cid:<$bareCid>", dataUri, ignoreCase = true)
            }
        }
        return detail.copy(body = html)
    }

    fun toggleReadDetail(detail: EmailDetail) {
        val c = client ?: return
        val next = !detail.isRead
        val targetFolderId = detail.parentFolderId.ifBlank { _state.value.currentFolder?.id }
        scope.launch {
            try {
                c.setRead(listOf(detail.id to detail.changeKey), next)
                _state.update { st ->
                    val delta = if (next) -1 else 1
                    val nextFolders = adjustFolderUnreadDelta(st.folders, targetFolderId, delta)
                    val nextCur = st.currentFolder?.let { cur -> nextFolders.firstOrNull { it.id == cur.id } ?: cur }
                    st.copy(
                        folders = nextFolders,
                        currentFolder = nextCur,
                        selectedMessage = st.selectedMessage?.copy(isRead = next),
                        messages = st.messages.map { if (it.id == detail.id) it.copy(isRead = next) else it },
                        toastMessage = if (next) "Отмечено прочитанным" else "Отмечено непрочитанным"
                    )
                }
                refreshSubtreeFoldersIfNested(c, targetFolderId)
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Ошибка") }
            }
        }
    }

    fun toggleFlagDetail(detail: EmailDetail) {
        val c = client ?: return
        val next = !detail.isFlagged
        scope.launch {
            try {
                c.setFlagged(listOf(detail.id to detail.changeKey), next)
                _state.update { st ->
                    st.copy(
                        selectedMessage = st.selectedMessage?.copy(isFlagged = next),
                        messages = st.messages.map { if (it.id == detail.id) it.copy(isFlagged = next) else it }
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Ошибка") }
            }
        }
    }

    fun toggleFlagSummary(summary: EmailSummary) {
        val c = client ?: return
        val next = !summary.isFlagged
        scope.launch {
            try {
                c.setFlagged(listOf(summary.id to summary.changeKey), next)
                _state.update { st ->
                    st.copy(
                        messages = st.messages.map { if (it.id == summary.id) it.copy(isFlagged = next) else it },
                        selectedMessage = st.selectedMessage?.let { sel ->
                            if (sel.id == summary.id) sel.copy(isFlagged = next) else sel
                        }
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Ошибка") }
            }
        }
    }

    fun moveDetail(detail: EmailDetail, target: MailFolder) {
        val c = client ?: return
        scope.launch {
            try {
                c.moveMessages(listOf(detail.id to detail.changeKey), target.id)
                val folders = runCatching { c.getFolders() }.getOrNull()
                _state.update { st ->
                    val nextFolders = folders ?: st.folders
                    val nextCur = st.currentFolder?.let { cur -> nextFolders.firstOrNull { it.id == cur.id } ?: cur }
                    st.copy(
                        folders = nextFolders,
                        currentFolder = nextCur,
                        selectedMessage = null,
                        selectedIds = st.selectedIds - detail.id,
                        rightPane = DesktopRightPane.Empty,
                        messages = st.messages.filterNot { it.id == detail.id },
                        toastMessage = "Перемещено в «${target.displayName}»"
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Не удалось переместить письмо") }
            }
        }
    }

    fun toggleSpamDetail(detail: EmailDetail) {
        val st = _state.value
        val junkId = st.folders.firstOrNull { it.systemKind == SystemFolderKind.JUNK }?.id
        val inJunk = st.currentFolder?.systemKind == SystemFolderKind.JUNK ||
            (junkId != null && detail.parentFolderId == junkId)
        val target = st.folders.firstOrNull {
            it.systemKind == if (inJunk) SystemFolderKind.INBOX else SystemFolderKind.JUNK
        } ?: return
        moveDetail(detail, target)
    }

    fun deleteDetail(detail: EmailDetail) {
        val c = client ?: return
        val stSnapshot = _state.value
        val deletedId = stSnapshot.folders.firstOrNull { it.systemKind == SystemFolderKind.DELETED }?.id
        val hardDelete = stSnapshot.currentFolder?.systemKind == SystemFolderKind.DELETED ||
            (deletedId != null && detail.parentFolderId == deletedId)
        scope.launch {
            try {
                c.deleteMessages(listOf(detail.id to detail.changeKey), hardDelete)
                val folders = runCatching { c.getFolders() }.getOrNull()
                _state.update { st ->
                    val nextFolders = folders ?: st.folders
                    val nextCur = st.currentFolder?.let { cur -> nextFolders.firstOrNull { it.id == cur.id } ?: cur }
                    st.copy(
                        folders = nextFolders,
                        currentFolder = nextCur,
                        selectedMessage = null,
                        selectedIds = st.selectedIds - detail.id,
                        rightPane = DesktopRightPane.Empty,
                        messages = st.messages.filterNot { it.id == detail.id },
                        toastMessage = if (hardDelete) "Удалено навсегда" else "Перемещено в Удалённые"
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Не удалось удалить письмо") }
            }
        }
    }

    fun downloadAttachment(att: AttachmentInfo, openAfter: Boolean) {
        val c = client ?: return
        scope.launch {
            try {
                val downloaded = c.downloadAttachment(att.id)
                val safeName = sanitizeFilename(downloaded.name.ifBlank { att.name })
                if (openAfter) {
                    val tmpDir = Files.createTempDirectory("nstumail-att").toFile().apply { deleteOnExit() }
                    val tmpFile = File(tmpDir, safeName).apply { deleteOnExit() }
                    withContext(Dispatchers.IO) { tmpFile.writeBytes(downloaded.bytes) }
                    withContext(Dispatchers.IO) {
                        if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(tmpFile)
                    }
                } else {
                    SwingUtilities.invokeLater {
                        val chooser = JFileChooser().apply { selectedFile = File(safeName) }
                        if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                            runCatching { chooser.selectedFile.writeBytes(downloaded.bytes) }
                                .onSuccess { _state.update { it.copy(toastMessage = "Файл сохранён") } }
                                .onFailure { err -> _state.update { it.copy(toastMessage = err.message) } }
                        }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Ошибка загрузки вложения") }
            }
        }
    }

    // --- Compose & Directory ---

    fun openCompose(
        replyTo: EmailDetail? = null,
        replyAll: Boolean = false,
        isForward: Boolean = false,
        draft: EmailDetail? = null,
        prefillTo: String? = null,
        prefillName: String? = null
    ) {
        _state.update {
            it.copy(
                directorySuggestions = emptyList(),
                rightPane = DesktopRightPane.Compose(
                    replyTo = replyTo,
                    replyAll = replyAll,
                    isForward = isForward,
                    draft = draft,
                    prefillTo = prefillTo,
                    prefillName = prefillName
                )
            )
        }
    }

    fun closeRightPane() {
        _state.update { it.copy(rightPane = DesktopRightPane.Empty, directorySuggestions = emptyList()) }
    }

    fun searchDirectory(query: String) {
        directoryJob?.cancel()
        val token = query.trim()
        if (token.length < 2) {
            _state.update { it.copy(directorySuggestions = emptyList()) }
            return
        }
        val localMatches = _knownNames.value.entries
            .filter { (e, n) -> e.contains(token, true) || n.contains(token, true) }
            .map { (e, n) -> ContactEntry(name = n, email = e, isFromDirectory = true) }
        if (localMatches.isNotEmpty()) {
            _state.update { it.copy(directorySuggestions = localMatches.take(8)) }
        }
        val c = client ?: return
        directoryJob = scope.launch {
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
        attachments: List<File>,
        send: Boolean
    ) {
        val c = client ?: return
        val toList = splitEmails(toRaw)
        val ccList = splitEmails(ccRaw)
        val bccList = splitEmails(bccRaw)
        if (send && (toList.isEmpty() || toList.any { '@' !in it })) {
            _state.update { it.copy(toastMessage = "Укажите корректный адрес получателя") }
            return
        }
        scope.launch {
            _state.update { it.copy(isSending = true) }
            try {
                val outgoing = withContext(Dispatchers.IO) {
                    var totalSize = 0L
                    attachments.map { file ->
                        val len = file.length()
                        if (len > MAX_SINGLE_UPLOAD_BYTES) throw EwsException("Файл ${file.name} превышает 20 МБ")
                        totalSize += len
                        if (totalSize > MAX_TOTAL_UPLOAD_BYTES) throw EwsException("Суммарный размер вложений превышает 25 МБ")
                        val bytes = file.readBytes()
                        if (bytes.size > MAX_SINGLE_UPLOAD_BYTES) throw EwsException("Файл ${file.name} превышает 20 МБ")
                        val mime = Files.probeContentType(file.toPath()) ?: "application/octet-stream"
                        OutgoingAttachment(sanitizeFilename(file.name), mime, bytes)
                    }
                }
                c.saveOrSendDraft(
                    draftId = draftId?.takeIf { it.isNotBlank() },
                    draftChangeKey = draftChangeKey,
                    to = toList,
                    cc = ccList,
                    bcc = bccList,
                    subject = subject.ifBlank { "(Без темы)" },
                    body = body,
                    importance = EmailImportance.NORMAL,
                    newAttachments = outgoing,
                    send = send
                )
                _state.update {
                    it.copy(
                        isSending = false,
                        rightPane = DesktopRightPane.Empty,
                        directorySuggestions = emptyList(),
                        toastMessage = if (send) "Письмо отправлено" else "Черновик сохранён"
                    )
                }
                refresh(quiet = true)
            } catch (e: Exception) {
                _state.update { it.copy(isSending = false, toastMessage = e.message ?: "Ошибка отправки") }
            }
        }
    }

    // --- Contacts & Calendar ---

    fun openContacts() {
        val c = client ?: return
        _state.update { it.copy(rightPane = DesktopRightPane.Contacts, isLoading = true) }
        scope.launch {
            runCatching { c.getContacts() }
                .onSuccess { list -> _state.update { it.copy(isLoading = false, contacts = list) } }
                .onFailure { err -> _state.update { it.copy(isLoading = false, toastMessage = err.message) } }
        }
    }

    fun addContact(name: String, email: String) {
        val c = client ?: return
        scope.launch {
            try {
                c.createContact(name, email)
                val list = c.getContacts()
                _state.update { it.copy(contacts = list, toastMessage = "Контакт добавлен") }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Не удалось добавить контакт") }
            }
        }
    }

    fun openCalendar() {
        val c = client ?: return
        _state.update { it.copy(rightPane = DesktopRightPane.Calendar, isLoading = true) }
        scope.launch {
            val now = Instant.now()
            val start = now.minus(1, ChronoUnit.DAYS).toString()
            val end = now.plus(30, ChronoUnit.DAYS).toString()
            runCatching { c.getCalendarEvents(start, end) }
                .onSuccess { events -> _state.update { it.copy(isLoading = false, calendarEvents = events) } }
                .onFailure { err -> _state.update { it.copy(isLoading = false, toastMessage = err.message) } }
        }
    }

    fun createCalendarEvent(subject: String, startIso: String, endIso: String, location: String, body: String) {
        val c = client ?: return
        scope.launch {
            try {
                c.createCalendarEvent(subject, startIso, endIso, location, body)
                openCalendar()
                _state.update { it.copy(toastMessage = "Событие создано") }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Не удалось создать событие") }
            }
        }
    }

    fun deleteCalendarEvent(event: CalendarEvent) {
        val c = client ?: return
        scope.launch {
            try {
                c.deleteMessages(listOf(event.id to event.changeKey), hardDelete = false)
                _state.update { st ->
                    st.copy(
                        calendarEvents = st.calendarEvents.filterNot { it.id == event.id },
                        toastMessage = "Событие удалено"
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(toastMessage = e.message ?: "Не удалось удалить событие") }
            }
        }
    }

    // --- Settings, Rules & Avatars ---

    fun openSettings() {
        _state.update { it.copy(rightPane = DesktopRightPane.Settings) }
    }

    fun setThemeMode(mode: DesktopThemeMode) = updateSettings { it.copy(themeMode = mode) }
    fun setAlwaysLightEmailBody(enabled: Boolean) = updateSettings { it.copy(alwaysLightEmailBody = enabled) }
    fun setNotificationsEnabled(enabled: Boolean) = updateSettings { it.copy(notificationsEnabled = enabled) }

    fun toggleFolderNotifications(folder: MailFolder) {
        val isInbox = folder.systemKind == SystemFolderKind.INBOX
        val cur = _state.value.settings.mutedFolderIds
        val isMuted = folder.id in cur || (isInbox && "inbox" in cur)
        updateSettings { st ->
            val next = st.mutedFolderIds.toMutableSet()
            if (isMuted) {
                next.remove(folder.id)
                if (isInbox || folder.id == st.inboxFolderId) {
                    next.remove("inbox")
                    st.inboxFolderId?.let { next.remove(it) }
                }
            } else {
                next += folder.id
                if (isInbox) next += "inbox"
            }
            st.copy(mutedFolderIds = next)
        }
        _state.update {
            it.copy(
                toastMessage = if (isMuted) "Уведомления для «${folder.displayName}» включены"
                else "Уведомления для «${folder.displayName}» выключены"
            )
        }
    }

    fun unmuteFolderById(folderId: String) {
        updateSettings { st ->
            val next = st.mutedFolderIds.toMutableSet()
            next.remove(folderId)
            if (folderId == st.inboxFolderId || folderId == "inbox") {
                next.remove("inbox")
                st.inboxFolderId?.let { next.remove(it) }
            }
            st.copy(mutedFolderIds = next)
        }
    }

    fun toggleSenderNotifications(email: String) {
        val clean = email.trim().lowercase()
        if (clean.isEmpty()) return
        val wasMuted = clean in _state.value.settings.mutedSenders
        updateSettings { st ->
            val next = if (wasMuted) st.mutedSenders - clean else st.mutedSenders + clean
            st.copy(mutedSenders = next)
        }
        _state.update {
            it.copy(toastMessage = if (wasMuted) "Уведомления от $clean включены" else "Уведомления от $clean выключены")
        }
    }

    fun addTagTemplate(rawPath: String) {
        val segs = parseTagPath(rawPath)
        if (segs.isEmpty()) return
        val additions = (1..segs.size).map { formatTagPath(segs.take(it)) }
        updateSettings { st ->
            st.copy(tagTemplates = sortTagTemplatesHierarchically(st.tagTemplates + additions))
        }
    }

    fun updateTagTemplate(oldPath: String, newRawPath: String) {
        val normOld = formatTagPath(parseTagPath(oldPath))
        val normNew = formatTagPath(parseTagPath(newRawPath))
        if (normOld.isEmpty() || normNew.isEmpty()) return
        updateSettings { st ->
            val nextTpls = st.tagTemplates.map { tpl ->
                when {
                    tpl.equals(normOld, true) -> normNew
                    tpl.startsWith("$normOld/", true) -> normNew + tpl.substring(normOld.length)
                    else -> tpl
                }
            }
            val nextRules = st.tagSortRules.map { r ->
                when {
                    r.tag.equals(normOld, true) -> r.copy(tag = normNew)
                    r.tag.startsWith("$normOld/", true) -> r.copy(tag = normNew + r.tag.substring(normOld.length))
                    else -> r
                }
            }
            st.copy(tagTemplates = sortTagTemplatesHierarchically(nextTpls), tagSortRules = nextRules)
        }
    }

    fun removeTagTemplate(path: String) {
        val norm = formatTagPath(parseTagPath(path))
        updateSettings { st ->
            st.copy(
                tagTemplates = st.tagTemplates.filterNot { it.equals(norm, true) },
                tagSortRules = st.tagSortRules.filterNot { it.tag.equals(norm, true) }
            )
        }
    }

    fun saveTagSortRule(tag: String, folder: MailFolder, autoCreateSubfolders: Boolean = true) {
        val cleanPath = formatTagPath(parseTagPath(tag))
        if (cleanPath.isEmpty()) return
        val rule = TagSortRule(cleanPath, folder.id, folder.displayName, autoCreateSubfolders)
        updateSettings { st ->
            val filtered = st.tagSortRules.filterNot { it.tag.equals(cleanPath, true) }
            val nextRules = (filtered + rule).sortedBy { it.tag.lowercase() }
            val additions = (1..parseTagPath(cleanPath).size).map { formatTagPath(parseTagPath(cleanPath).take(it)) }
            st.copy(
                tagSortRules = nextRules,
                tagTemplates = sortTagTemplatesHierarchically(st.tagTemplates + additions)
            )
        }
        _state.update { it.copy(toastMessage = "Тег [$cleanPath] → «${folder.displayName}»") }
        val c = client
        val cur = _state.value.currentFolder
        if (c != null && cur?.systemKind == SystemFolderKind.INBOX) {
            applyAutoSortToMessages(c, _state.value.messages, cur)
        }
    }

    fun removeTagSortRule(tag: String) {
        val norm = formatTagPath(parseTagPath(tag))
        updateSettings { st -> st.copy(tagSortRules = st.tagSortRules.filterNot { it.tag.equals(norm, true) }) }
    }

    fun enableAutoSortForFolderTree(folder: MailFolder) {
        val c = client ?: return
        val folders = _state.value.folders
        val (newTemplates, newRules) = buildAutoSortFromFolderTree(folder, folders)
        if (newRules.isEmpty()) {
            showToast("Для системной папки автосортировка не применяется")
            return
        }
        updateSettings { st ->
            val mergedTemplates = sortTagTemplatesHierarchically(st.tagTemplates + newTemplates)
            val updatedRules = st.tagSortRules.toMutableList()
            for (rule in newRules) {
                updatedRules.removeAll { it.tag.equals(rule.tag, ignoreCase = true) }
                updatedRules += rule
            }
            st.copy(
                tagTemplates = mergedTemplates,
                tagSortRules = updatedRules.sortedBy { it.tag.lowercase() }
            )
        }
        scope.launch {
            val moved = runCatching { resortExistingMessages(c, folders, _state.value.settings.tagSortRules) }.getOrDefault(0)
            if (moved > 0) {
                val refreshed = runCatching { c.getFolders() }.getOrNull()
                _state.update { st -> st.copy(folders = refreshed ?: st.folders) }
                loadMessages(offset = 0, reset = true)
            }
            showToast("Автосортировка «${folder.displayName}»: правил ${newRules.size}, отсортировано писем: $moved")
        }
    }

    fun showToast(message: String) {
        _state.update { it.copy(toastMessage = message) }
    }

    fun deployTagHierarchyToFolders(rootTagPath: String) {
        val c = client ?: return
        val normRoot = formatTagPath(parseTagPath(rootTagPath))
        if (normRoot.isEmpty()) return
        val branchPaths = (_state.value.settings.tagTemplates + normRoot)
            .map { formatTagPath(parseTagPath(it)) }
            .filter { it.equals(normRoot, true) || it.startsWith("$normRoot/", true) }
            .distinctBy { it.lowercase() }
            .sortedWith(compareBy<String>({ parseTagPath(it).size }, { it.lowercase() }))
        if (branchPaths.isEmpty()) return

        scope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                var workingFolders = c.getFolders().toMutableList()
                var created = 0
                var bound = 0
                val newRules = _state.value.settings.tagSortRules.toMutableList()

                for (path in branchPaths) {
                    val segs = parseTagPath(path)
                    var parentId: String? = null
                    var resolved: MailFolder? = null
                    for (seg in segs) {
                        var match = workingFolders.firstOrNull { f ->
                            if (parentId == null) !f.isSystem && f.depth == 0 && f.displayName.equals(seg, true)
                            else f.parentId == parentId && f.displayName.equals(seg, true)
                        }
                        if (match == null) {
                            c.createFolder(seg, parentFolderId = parentId)
                            created++
                            workingFolders = c.getFolders().toMutableList()
                            match = workingFolders.firstOrNull { f ->
                                if (parentId == null) !f.isSystem && f.depth == 0 && f.displayName.equals(seg, true)
                                else f.parentId == parentId && f.displayName.equals(seg, true)
                            }
                        }
                        if (match == null) break
                        parentId = match.id
                        resolved = match
                    }
                    if (resolved != null) {
                        newRules.removeAll { it.tag.equals(path, true) }
                        newRules += TagSortRule(path, resolved.id, resolved.displayName, autoCreateSubfolders = true)
                        bound++
                    }
                }
                updateSettings { it.copy(tagSortRules = newRules.sortedBy { r -> r.tag.lowercase() }) }
                val resorted = resortExistingMessages(c, workingFolders, _state.value.settings.tagSortRules)
                if (resorted > 0) workingFolders = c.getFolders().toMutableList()
                _state.update { st ->
                    val cur = st.currentFolder?.let { old -> workingFolders.firstOrNull { it.id == old.id } ?: old }
                    st.copy(
                        isLoading = false,
                        folders = workingFolders,
                        currentFolder = cur,
                        settings = settingsStore.load(),
                        toastMessage = "Структура «$normRoot» перенесена (создано папок: $created, правил: $bound, отсортировано писем: $resorted)"
                    )
                }
                if (resorted > 0) loadMessages(offset = 0, reset = true)
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, toastMessage = e.message ?: "Ошибка переноса структуры") }
            }
        }
    }

    // Re-applies rules to tagged mail already sitting in «Входящие» / «Несортированные».
    private suspend fun resortExistingMessages(c: EwsClient, folders: List<MailFolder>, rules: List<TagSortRule>): Int {
        var cache: MutableList<MailFolder>? = folders.toMutableList()
        var moved = 0
        val sources = folders.filter {
            it.systemKind == SystemFolderKind.INBOX || (!it.isSystem && it.displayName.equals(UNSORTED_FOLDER_NAME, true))
        }
        for (src in sources) {
            // shortcut: only the newest RESORT_PAGE_SIZE letters per folder, add paging if folders get huge
            val items = runCatching { c.listMessages(listOf(src.id), offset = 0, pageSize = RESORT_PAGE_SIZE).first }
                .getOrNull() ?: continue
            for (item in items.filter { matchAllAutoSortRules(it.subject, rules).isNotEmpty() }) {
                val (out, next) = DesktopAutoSorter.applyAutoSortForMessage(settingsStore, c, item, rules, src.id, cache)
                cache = next
                if (out.movedItemIds.isNotEmpty()) recordSeenKeys(out.movedItemIds)
                if (out.movedFromInbox) moved++
            }
        }
        return moved
    }

    fun setUpdateChannel(ch: DesktopUpdateChannel) {
        if (_state.value.settings.updateChannel == ch) return
        updateCheckJob?.cancel()
        updateSettings { it.copy(updateChannel = ch) }
        _state.update { it.copy(availableUpdate = null, isCheckingUpdate = false) }
        checkForUpdates(manual = false)
    }

    fun checkForUpdates(manual: Boolean) {
        if (!manual && updateCheckJob?.isActive == true) return
        if (manual) updateCheckJob?.cancel()
        val ch = _state.value.settings.updateChannel
        updateCheckJob = scope.launch {
            _state.update { it.copy(isCheckingUpdate = true) }
            try {
                val upd = updater.checkLatestRelease(channel = ch)
                if (_state.value.settings.updateChannel != ch) return@launch
                _state.update { st ->
                    st.copy(
                        isCheckingUpdate = false,
                        availableUpdate = upd ?: st.availableUpdate,
                        toastMessage = if (manual && upd == null) {
                            "Установлена актуальная версия (${DesktopBuildInfo.VERSION_NAME}, ветка: ${ch.labelRu})"
                        } else {
                            st.toastMessage
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (_state.value.settings.updateChannel != ch) return@launch
                _state.update {
                    it.copy(
                        isCheckingUpdate = false,
                        toastMessage = if (manual) "Ошибка проверки обновлений: ${e.message}" else it.toastMessage
                    )
                }
            }
        }
    }

    fun dismissUpdate() {
        _state.update { it.copy(availableUpdate = null) }
    }

    fun openExternalUrl(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            }
        }
    }

    private fun resolvedSelfIdentity(): Pair<String, String> {
        val credsEmail = _state.value.credentials?.email.orEmpty().trim()
        val selfProfile = _personProfiles.value[credsEmail.lowercase()]
        val effectiveEmail = selfProfile?.email?.takeIf { '@' in it }
            ?: credsEmail.takeIf { '@' in it }.orEmpty()
        return effectiveEmail to selfProfile?.displayName.orEmpty()
    }

    private fun ownFolderSet(
        folders: List<MailFolder> = _state.value.folders,
        current: MailFolder? = _state.value.currentFolder
    ): Set<String> = ownSenderFolderIds(folders, current)

    private fun normalizeSummariesForOwnFolders(
        items: List<EmailSummary>,
        folders: List<MailFolder>,
        current: MailFolder?,
        isSearchAllFolders: Boolean
    ): List<EmailSummary> {
        val ownIds = ownFolderSet(folders, current)
        val (selfEmail, selfName) = resolvedSelfIdentity()
        val fallbackInOwnFolder = !isSearchAllFolders && current?.id in ownIds
        return items.map {
            normalizeOwnSenderSummary(
                item = it,
                ownFolderIds = ownIds,
                selfEmail = selfEmail,
                selfName = selfName,
                fallbackInOwnFolder = fallbackInOwnFolder
            )
        }
    }

    private fun relatedSmtpKeysFor(key: String): List<String> {
        val keys = linkedSetOf(key)
        for ((mapKey, profile) in _personProfiles.value) {
            val profileKeys = profileSmtpKeys(mapKey, profile)
            if (mapKey == key || key in profileKeys) {
                keys += profileKeys
            }
        }
        return keys.filter { '@' in it }.distinct()
    }

    fun requestAvatar(email: String) {
        val key = email.trim().lowercase()
        if ('@' !in key) return
        val c = client ?: return
        if (!requestedAvatars.add(key)) return
        scope.launch {
            val bmp = avatarSemaphore.withPermit {
                val candidates = (listOf(key) + relatedSmtpKeysFor(key)).distinct()
                var bytes: ByteArray? = null
                for (candidate in candidates) {
                    bytes = runCatching { c.getUserPhoto(candidate) }.getOrNull()
                    if (bytes != null) break
                }
                bytes?.let { raw ->
                    runCatching { Image.makeFromEncoded(raw).toComposeImageBitmap() }.getOrNull()
                }
            }
            if (bmp != null) {
                val targetKeys = (listOf(key) + relatedSmtpKeysFor(key)).distinct()
                _avatars.update { cur -> cur + targetKeys.associateWith { bmp } }
            } else {
                _avatars.update { it + (key to null) }
            }
        }
    }

    fun requestPersonProfile(name: String, email: String) {
        val key = email.trim().lowercase().ifBlank { name.trim().lowercase() }
        if (key.isBlank()) return
        val c = client ?: return
        if (!requestedProfiles.add(key)) return
        scope.launch {
            _loadingProfiles.update { it + key }
            val profile = runCatching { c.resolvePersonProfile(email, name) }.getOrNull()
            if (profile != null && profile.displayName.isNotBlank()) {
                val pairs = buildList {
                    if (email.isNotBlank()) add(email to profile.displayName)
                    if (profile.email.isNotBlank()) add(profile.email to profile.displayName)
                    profile.aliases.forEach { add(it to profile.displayName) }
                }
                rememberKnownNames(pairs)
            }
            _personProfiles.update { it + (key to profile) }
            _loadingProfiles.update { it - key }

            if (profile != null) {
                val keys = profileSmtpKeys(email, profile)
                val existingBmp = keys.firstNotNullOfOrNull { _avatars.value[it] }
                if (existingBmp != null) {
                    _avatars.update { cur -> cur + keys.associateWith { existingBmp } }
                } else {
                    val selfKey = _state.value.credentials?.email?.trim()?.lowercase().orEmpty()
                    if (key == selfKey || keys.any { it in requestedAvatars }) {
                        keys.firstOrNull { it !in requestedAvatars }?.let(::requestAvatar)
                    }
                }
                val selfKey = _state.value.credentials?.email?.trim()?.lowercase().orEmpty()
                if (key == selfKey && '@' in profile.email) {
                    _state.update { st ->
                        val ownIds = ownFolderSet(st.folders, st.currentFolder)
                        val fallbackInOwn = !(st.searchQuery.isNotBlank() && st.searchAllFolders) &&
                            st.currentFolder?.id in ownIds
                        st.copy(
                            messages = st.messages.map {
                                normalizeOwnSenderSummary(
                                    item = it,
                                    ownFolderIds = ownIds,
                                    selfEmail = profile.email,
                                    selfName = profile.displayName,
                                    fallbackInOwnFolder = fallbackInOwn
                                )
                            },
                            selectedMessage = st.selectedMessage?.let { sel ->
                                normalizeOwnSenderDetail(
                                    detail = sel,
                                    ownFolderIds = ownIds,
                                    selfEmail = profile.email,
                                    selfName = profile.displayName,
                                    fallbackInOwnFolder = fallbackInOwn
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    fun logout() {
        pushJob?.cancel()
        loadMessagesJob?.cancel()
        directoryJob?.cancel()
        updateCheckJob?.cancel()
        nextPageOffset = 0
        client = null
        credStore.clear()
        synchronized(seenLock) {
            seenKeysOrder.clear()
            seenKeysSet.clear()
            baselinedFolderIds.clear()
            lastSyncMs = 0L
            baselineInitialized = false
        }
        requestedAvatars.clear()
        requestedProfiles.clear()
        _avatars.value = emptyMap()
        _personProfiles.value = emptyMap()
        _knownNames.value = emptyMap()
        _loadingProfiles.value = emptySet()
        updateSettings { st ->
            st.copy(
                mutedFolderIds = emptySet(),
                mutedSenders = emptySet(),
                tagSortRules = emptyList(),
                inboxFolderId = null
            )
        }
        _state.value = DesktopUiState(settings = settingsStore.load())
    }

    fun clearToast() {
        _state.update { it.copy(toastMessage = null) }
    }

    companion object {
        private const val PAGE_SIZE = 25
        private const val RESORT_PAGE_SIZE = 200
        private const val MAX_SEEN_KEYS = 600
        private const val MAX_SINGLE_UPLOAD_BYTES = 20L * 1024L * 1024L
        private const val MAX_TOTAL_UPLOAD_BYTES = 25L * 1024L * 1024L
    }
}
