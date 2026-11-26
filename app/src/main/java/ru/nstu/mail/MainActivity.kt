package ru.nstu.mail

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import ru.nstu.mail.notify.EXTRA_MESSAGE_ID
import ru.nstu.mail.notify.MailNotificationHelper
import ru.nstu.mail.ui.AppThemeMode
import ru.nstu.mail.ui.CalendarScreen
import ru.nstu.mail.ui.ComposeScreen
import ru.nstu.mail.ui.ContactsScreen
import ru.nstu.mail.ui.LoginScreen
import ru.nstu.mail.ui.MailListScreen
import ru.nstu.mail.ui.MailViewModel
import ru.nstu.mail.ui.MessageDetailScreen
import ru.nstu.mail.ui.NstuMailTheme
import ru.nstu.mail.ui.Screen
import ru.nstu.mail.ui.SettingsScreen
import ru.nstu.mail.ui.UpdateDialog

class MainActivity : ComponentActivity() {
    private val pendingMessageId = MutableStateFlow<String?>(null)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_MESSAGE_ID)?.takeIf { it.isNotBlank() }?.let {
            pendingMessageId.value = it
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(EXTRA_MESSAGE_ID)?.takeIf { it.isNotBlank() }?.let {
            pendingMessageId.value = it
        }
        enableEdgeToEdge()
        setContent {
            val vm: MailViewModel = viewModel()
            val themeMode by vm.themeMode.collectAsState()
            val alwaysLightEmailBody by vm.alwaysLightEmailBody.collectAsState()
            val updateChannel by vm.updateChannel.collectAsState()
            val notificationsEnabled by vm.notificationsEnabled.collectAsState()
            val notificationMode by vm.notificationMode.collectAsState()
            val notificationIntervalMinutes by vm.notificationIntervalMinutes.collectAsState()
            val mutedFolderIds by vm.mutedFolderIds.collectAsState()
            val mutedSenders by vm.mutedSenders.collectAsState()
            val tagTemplates by vm.tagTemplates.collectAsState()
            val tagSortRules by vm.tagSortRules.collectAsState()
            val swipeRightAction by vm.swipeRightAction.collectAsState()
            val swipeLeftAction by vm.swipeLeftAction.collectAsState()
            val pendingId by pendingMessageId.collectAsState()

            val darkTheme = when (themeMode) {
                AppThemeMode.SYSTEM -> isSystemInDarkTheme()
                AppThemeMode.LIGHT -> false
                AppThemeMode.DARK -> true
            }
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT,
                        android.graphics.Color.TRANSPARENT
                    ) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.argb(0xe6, 0xFF, 0xFF, 0xFF),
                        android.graphics.Color.argb(0x80, 0x1b, 0x1b, 0x1b)
                    ) { darkTheme }
                )
                onDispose {}
            }

            NstuMailTheme(darkTheme = darkTheme) {
                val state by vm.state.collectAsState()
                val avatars by vm.avatars.collectAsState()
                val personProfiles by vm.personProfiles.collectAsState()
                val knownNames by vm.knownNames.collectAsState()
                val loadingProfiles by vm.loadingProfiles.collectAsState()
                val context = LocalContext.current

                val notifPermLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { granted ->
                    vm.onNotificationPermissionResult(granted)
                }

                LaunchedEffect(pendingId) {
                    val id = pendingId ?: return@LaunchedEffect
                    vm.openMessageById(id)
                    pendingMessageId.value = null
                }

                state.availableUpdate?.let { update ->
                    UpdateDialog(
                        update = update,
                        downloadProgress = state.updateDownloadProgress,
                        isDownloaded = state.downloadedUpdatePath != null,
                        errorMessage = state.updateError,
                        onInstallOrDownload = { vm.downloadAndInstallUpdate(context) },
                        onDismiss = vm::dismissUpdate
                    )
                }

                when (val screen = state.screen) {
                    Screen.Login -> LoginScreen(
                        initialCredentials = state.credentials,
                        isLoading = state.isLoading,
                        errorMessage = state.errorMessage,
                        themeMode = themeMode,
                        onSetThemeMode = vm::setThemeMode,
                        onLogin = vm::login
                    )

                    Screen.MailList -> {
                        LaunchedEffect(Unit) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                notificationsEnabled &&
                                !MailNotificationHelper.hasSystemPermission(context) &&
                                !MailNotificationHelper.wasPermissionAsked(context)
                            ) {
                                MailNotificationHelper.markPermissionAsked(context)
                                notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                        MailListScreen(
                            state = state,
                            avatars = avatars,
                            personProfiles = personProfiles,
                            knownNames = knownNames,
                            mutedFolderIds = mutedFolderIds,
                            tagSortRules = tagSortRules,
                            tagTemplates = tagTemplates,
                            swipeRightAction = swipeRightAction,
                            swipeLeftAction = swipeLeftAction,
                            onRequestAvatar = vm::requestAvatar,
                            onSelectFolder = vm::selectFolder,
                            onSelectFilter = vm::setFilter,
                            onRefresh = vm::refresh,
                            onLoadMore = vm::loadMore,
                            onSearch = vm::search,
                            onToggleSearchAllFolders = vm::setSearchAllFolders,
                            onOpenMessage = vm::openMessage,
                            onToggleSelect = vm::toggleSelection,
                            onClearSelection = vm::clearSelection,
                            onToggleFlag = vm::toggleFlag,
                            onToggleReadSummary = vm::toggleReadSummary,
                            onDeleteSummary = vm::deleteSummary,
                            onMoveSummary = vm::moveSummary,
                            onToggleSpamSummary = vm::toggleSpamSummary,
                            onToggleFolderNotifications = vm::toggleFolderNotifications,
                            onSaveTagSortRule = vm::saveTagSortRule,
                            onRemoveTagSortRule = vm::removeTagSortRule,
                            onBatchSetRead = vm::batchSetRead,
                            onBatchMove = vm::batchMove,
                            onBatchDelete = vm::batchDelete,
                            onCreateFolder = vm::createFolder,
                            onRenameFolder = vm::renameFolder,
                            onDeleteFolder = vm::deleteFolder,
                            onEmptyCurrentFolder = vm::emptyCurrentFolder,
                            onMarkAllRead = vm::markAllReadInCurrentFolder,
                            onCompose = { vm.openCompose() },
                            onOpenContacts = vm::openContacts,
                            onOpenCalendar = vm::openCalendar,
                            onOpenSettings = vm::openSettings,
                            onLogout = vm::logout,
                            onClearToast = vm::clearToast
                        )
                    }

                    Screen.MessageDetail -> {
                        val detail = state.selectedMessage
                        if (detail != null) {
                            MessageDetailScreen(
                                detail = detail,
                                folders = state.folders,
                                currentFolder = state.currentFolder,
                                avatars = avatars,
                                personProfiles = personProfiles,
                                knownNames = knownNames,
                                mutedSenders = mutedSenders,
                                loadingProfiles = loadingProfiles,
                                darkTheme = darkTheme,
                                defaultLightBody = alwaysLightEmailBody,
                                isDownloadingAttachment = state.isDownloadingAttachment,
                                toastMessage = state.toastMessage,
                                onBack = vm::backToMailList,
                                onRequestAvatar = vm::requestAvatar,
                                onRequestPersonProfile = vm::requestPersonProfile,
                                onToggleSenderNotifications = vm::toggleSenderNotifications,
                                onComposeTo = { email, name ->
                                    vm.openCompose(prefillTo = email, prefillName = name)
                                },
                                onToggleRead = vm::toggleRead,
                                onToggleFlag = vm::toggleFlagDetail,
                                onReply = { vm.openCompose(replyTo = it) },
                                onReplyAll = { vm.openCompose(replyTo = it, replyAll = true) },
                                onForward = { vm.openCompose(replyTo = it, isForward = true) },
                                onEditDraft = { vm.openCompose(draft = it) },
                                onMoveToFolder = vm::moveMessage,
                                onToggleSpam = vm::toggleSpam,
                                onDelete = vm::deleteMessage,
                                onDownloadAttachment = vm::downloadAndOpenAttachment,
                                onClearToast = vm::clearToast
                            )
                        } else {
                            vm.backToMailList()
                        }
                    }

                    is Screen.Compose -> ComposeScreen(
                        currentUserEmail = state.credentials?.email.orEmpty(),
                        replyTo = screen.replyTo,
                        replyAll = screen.replyAll,
                        isForward = screen.isForward,
                        draft = screen.draft,
                        directorySuggestions = state.directorySuggestions,
                        avatars = avatars,
                        personProfiles = personProfiles,
                        knownNames = knownNames,
                        tagTemplates = tagTemplates,
                        isSending = state.isSending,
                        errorMessage = state.errorMessage,
                        onBack = vm::backToMailList,
                        onSearchDirectory = vm::searchDirectory,
                        onClearSuggestions = vm::clearDirectorySuggestions,
                        onRequestAvatar = vm::requestAvatar,
                        onRequestPersonProfile = vm::requestPersonProfile,
                        onAddTagTemplate = vm::addTagTemplate,
                        onSubmit = vm::saveOrSendEmail
                    )

                    Screen.Contacts -> ContactsScreen(
                        contacts = state.contacts,
                        directorySuggestions = state.directorySuggestions,
                        avatars = avatars,
                        isLoading = state.isLoading,
                        toastMessage = state.toastMessage,
                        onBack = vm::backToMailList,
                        onSearchDirectory = vm::searchDirectory,
                        onRequestAvatar = vm::requestAvatar,
                        onAddContact = vm::addContact,
                        onComposeTo = { email, name ->
                            vm.openCompose(prefillTo = email, prefillName = name)
                        },
                        onClearToast = vm::clearToast
                    )

                    Screen.Calendar -> CalendarScreen(
                        events = state.calendarEvents,
                        isLoading = state.isLoading,
                        toastMessage = state.toastMessage,
                        onBack = vm::backToMailList,
                        onRefresh = vm::openCalendar,
                        onCreateEvent = vm::createCalendarEvent,
                        onDeleteEvent = vm::deleteCalendarEvent,
                        onClearToast = vm::clearToast
                    )

                    Screen.Settings -> SettingsScreen(
                        themeMode = themeMode,
                        alwaysLightEmailBody = alwaysLightEmailBody,
                        notificationsEnabled = notificationsEnabled,
                        notificationMode = notificationMode,
                        notificationIntervalMinutes = notificationIntervalMinutes,
                        folders = state.folders,
                        mutedFolderIds = mutedFolderIds,
                        mutedSenders = mutedSenders,
                        tagTemplates = tagTemplates,
                        tagSortRules = tagSortRules,
                        swipeRightAction = swipeRightAction,
                        swipeLeftAction = swipeLeftAction,
                        updateChannel = updateChannel,
                        isCheckingUpdate = state.isCheckingUpdate,
                        toastMessage = state.toastMessage,
                        onBack = vm::backToMailList,
                        onSetThemeMode = vm::setThemeMode,
                        onSetAlwaysLightEmailBody = vm::setAlwaysLightEmailBody,
                        onSetNotificationsEnabled = vm::setNotificationsEnabled,
                        onSetNotificationMode = vm::setNotificationMode,
                        onSetNotificationInterval = vm::setNotificationInterval,
                        onCheckNewMailNow = vm::checkNewMailNow,
                        onNotificationPermissionResult = vm::onNotificationPermissionResult,
                        onUnmuteFolder = vm::unmuteFolderById,
                        onUnmuteSender = vm::toggleSenderNotifications,
                        onAddTagTemplate = vm::addTagTemplate,
                        onUpdateTagTemplate = vm::updateTagTemplate,
                        onRemoveTagTemplate = vm::removeTagTemplate,
                        onSaveTagSortRule = vm::saveTagSortRule,
                        onRemoveTagSortRule = vm::removeTagSortRule,
                        onSetSwipeRightAction = vm::setSwipeRightAction,
                        onSetSwipeLeftAction = vm::setSwipeLeftAction,
                        onSetUpdateChannel = vm::setUpdateChannel,
                        onCheckUpdates = { vm.checkForUpdates(manual = true) },
                        onClearToast = vm::clearToast
                    )
                }
            }
        }
    }
}
