package ru.nstu.mail.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.nstu.mail.BuildConfig
import ru.nstu.mail.data.EmailSummary
import ru.nstu.mail.data.MailFolderType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailListScreen(
    state: MailUiState,
    onSelectFolder: (MailFolderType) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSearch: (String) -> Unit,
    onOpenMessage: (EmailSummary) -> Unit,
    onCompose: () -> Unit,
    onOpenOwa: () -> Unit,
    onCheckUpdates: () -> Unit,
    onLogout: () -> Unit,
    onClearToast: () -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchDraft by rememberSaveable { mutableStateOf(state.searchQuery) }

    LaunchedEffect(state.toastMessage) {
        val msg = state.toastMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onClearToast()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Почта НГТУ",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = state.credentials?.email.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                MailFolderType.entries.forEach { folder ->
                    val info = state.folders.firstOrNull { it.type == folder }
                    val unread = info?.unreadCount ?: 0
                    NavigationDrawerItem(
                        label = { Text(folder.labelRu) },
                        badge = {
                            if (unread > 0) Text(unread.toString())
                        },
                        selected = state.currentFolder == folder,
                        icon = { Icon(folderIcon(folder), contentDescription = null) },
                        onClick = {
                            scope.launch { drawerState.close() }
                            onSelectFolder(folder)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }

                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))

                NavigationDrawerItem(
                    label = { Text("Открыть OWA (WebView)") },
                    selected = false,
                    icon = { Icon(Icons.Default.Language, contentDescription = null) },
                    onClick = {
                        scope.launch { drawerState.close() }
                        onOpenOwa()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                NavigationDrawerItem(
                    label = { Text("Проверить обновления (v${BuildConfig.VERSION_NAME})") },
                    selected = false,
                    icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
                    onClick = {
                        scope.launch { drawerState.close() }
                        onCheckUpdates()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )

                NavigationDrawerItem(
                    label = { Text("Выйти") },
                    selected = false,
                    icon = { Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null) },
                    onClick = {
                        scope.launch { drawerState.close() }
                        onLogout()
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        if (searchExpanded) {
                            OutlinedTextField(
                                value = searchDraft,
                                onValueChange = { searchDraft = it },
                                placeholder = { Text("Поиск писем...") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(
                                    onSearch = { onSearch(searchDraft) }
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(state.currentFolder.labelRu)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Меню")
                        }
                    },
                    actions = {
                        if (searchExpanded) {
                            IconButton(onClick = {
                                searchExpanded = false
                                if (state.searchQuery.isNotEmpty()) {
                                    searchDraft = ""
                                    onSearch("")
                                }
                            }) {
                                Icon(Icons.Default.Close, contentDescription = "Закрыть поиск")
                            }
                        } else {
                            IconButton(onClick = { searchExpanded = true }) {
                                Icon(Icons.Default.Search, contentDescription = "Поиск")
                            }
                            IconButton(onClick = onRefresh) {
                                Icon(Icons.Default.Refresh, contentDescription = "Обновить")
                            }
                        }
                    }
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = onCompose) {
                    Icon(Icons.Default.Edit, contentDescription = "Написать письмо")
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (state.isLoading && !state.isRefreshing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (!state.errorMessage.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.errorContainer)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onRefresh) { Text("Повтор") }
                        TextButton(onClick = onOpenOwa) { Text("OWA") }
                    }
                }

                PullToRefreshBox(
                    isRefreshing = state.isRefreshing,
                    onRefresh = onRefresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    if (state.messages.isEmpty() && !state.isLoading) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (state.searchQuery.isBlank()) {
                                    "В папке нет писем"
                                } else {
                                    "Ничего не найдено"
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(state.messages, key = { it.id }) { item ->
                                EmailRow(
                                    item = item,
                                    onClick = { onOpenMessage(item) }
                                )
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                            if (!state.reachedEnd) {
                                item {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        OutlinedButton(
                                            onClick = onLoadMore,
                                            enabled = !state.isLoading
                                        ) {
                                            Text("Загрузить ещё")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmailRow(
    item: EmailSummary,
    onClick: () -> Unit
) {
    val weight = if (item.isRead) FontWeight.Normal else FontWeight.Bold

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 6.dp, end = 10.dp)
                .size(8.dp)
        ) {
            if (!item.isRead) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.senderName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = weight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                if (item.hasAttachments) {
                    Icon(
                        imageVector = Icons.Default.AttachFile,
                        contentDescription = "Вложение",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Text(
                    text = MailViewModel.formatMailDate(item.receivedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.isRead) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
            }

            Spacer(Modifier.height(2.dp))
            Text(
                text = item.subject,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = weight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (item.preview.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = item.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun folderIcon(folder: MailFolderType) = when (folder) {
    MailFolderType.INBOX -> Icons.Default.Inbox
    MailFolderType.SENT -> Icons.AutoMirrored.Filled.Send
    MailFolderType.DRAFTS -> Icons.Default.Drafts
    MailFolderType.DELETED -> Icons.Default.Delete
    MailFolderType.JUNK -> Icons.Default.Report
}
