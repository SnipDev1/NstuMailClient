package ru.nstu.mail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.nstu.mail.ui.ComposeScreen
import ru.nstu.mail.ui.LoginScreen
import ru.nstu.mail.ui.MailListScreen
import ru.nstu.mail.ui.MailViewModel
import ru.nstu.mail.ui.MessageDetailScreen
import ru.nstu.mail.ui.NstuMailTheme
import ru.nstu.mail.ui.OwaWebViewScreen
import ru.nstu.mail.ui.Screen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NstuMailTheme {
                val vm: MailViewModel = viewModel()
                val state by vm.state.collectAsState()

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
                        onSelectFolder = vm::selectFolder,
                        onRefresh = vm::refresh,
                        onLoadMore = vm::loadMore,
                        onSearch = vm::search,
                        onOpenMessage = vm::openMessage,
                        onCompose = { vm.openCompose() },
                        onOpenOwa = vm::openOwaFromMailList,
                        onLogout = vm::logout,
                        onClearToast = vm::clearToast
                    )

                    Screen.MessageDetail -> {
                        val detail = state.selectedMessage
                        if (detail != null) {
                            MessageDetailScreen(
                                detail = detail,
                                currentFolder = state.currentFolder,
                                isDownloadingAttachment = state.isDownloadingAttachment,
                                toastMessage = state.toastMessage,
                                onBack = vm::backToMailList,
                                onToggleRead = vm::toggleRead,
                                onReply = { vm.openCompose(replyTo = it, isForward = false) },
                                onForward = { vm.openCompose(replyTo = it, isForward = true) },
                                onDelete = vm::deleteMessage,
                                onDownloadAttachment = vm::downloadAndOpenAttachment,
                                onClearToast = vm::clearToast
                            )
                        } else {
                            vm.backToMailList()
                        }
                    }

                    is Screen.Compose -> ComposeScreen(
                        replyTo = screen.replyTo,
                        isForward = screen.isForward,
                        isSending = state.isSending,
                        errorMessage = state.errorMessage,
                        onBack = vm::backToMailList,
                        onSend = vm::sendEmail
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
