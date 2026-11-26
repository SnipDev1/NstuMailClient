package ru.nstu.mail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.nstu.mail.ui.CalendarScreen
import ru.nstu.mail.ui.ComposeScreen
import ru.nstu.mail.ui.ContactsScreen
import ru.nstu.mail.ui.LoginScreen
import ru.nstu.mail.ui.MailListScreen
import ru.nstu.mail.ui.MailViewModel
import ru.nstu.mail.ui.MessageDetailScreen
import ru.nstu.mail.ui.NstuMailTheme
import ru.nstu.mail.ui.OwaWebViewScreen
import ru.nstu.mail.ui.Screen
import ru.nstu.mail.ui.UpdateDialog

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NstuMailTheme {
                val vm: MailViewModel = viewModel()
                val state by vm.state.collectAsState()
                val avatars by vm.avatars.collectAsState()
                val personProfiles by vm.personProfiles.collectAsState()
                val loadingProfiles by vm.loadingProfiles.collectAsState()
                val context = LocalContext.current

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
                        suggestOwaFallback = state.suggestOwaFallback,
                        onLogin = vm::login,
                        onOpenOwa = vm::openOwaFallback
                    )

                    Screen.MailList -> MailListScreen(
                        state = state,
                        avatars = avatars,
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
                        onOpenOwa = vm::openOwaFromMailList,
                        onCheckUpdates = { vm.checkForUpdates(manual = true) },
                        onLogout = vm::logout,
                        onClearToast = vm::clearToast
                    )

                    Screen.MessageDetail -> {
                        val detail = state.selectedMessage
                        if (detail != null) {
                            MessageDetailScreen(
                                detail = detail,
                                folders = state.folders,
                                currentFolder = state.currentFolder,
                                avatars = avatars,
                                personProfiles = personProfiles,
                                loadingProfiles = loadingProfiles,
                                isDownloadingAttachment = state.isDownloadingAttachment,
                                toastMessage = state.toastMessage,
                                onBack = vm::backToMailList,
                                onRequestAvatar = vm::requestAvatar,
                                onRequestPersonProfile = vm::requestPersonProfile,
                                onComposeTo = { email -> vm.openCompose(prefillTo = email) },
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
                        isSending = state.isSending,
                        errorMessage = state.errorMessage,
                        onBack = vm::backToMailList,
                        onSearchDirectory = vm::searchDirectory,
                        onClearSuggestions = vm::clearDirectorySuggestions,
                        onRequestAvatar = vm::requestAvatar,
                        onRequestPersonProfile = vm::requestPersonProfile,
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
                        onComposeTo = { email -> vm.openCompose(prefillTo = email) },
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

                    Screen.OwaWebView -> OwaWebViewScreen(
                        credentials = state.credentials,
                        onBack = vm::exitOwaToAppropriateScreen,
                        onLogout = vm::logout
                    )
                }
            }
        }
    }
}
