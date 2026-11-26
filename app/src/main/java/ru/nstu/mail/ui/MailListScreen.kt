package ru.nstu.mail.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.nstu.mail.BuildConfig
import ru.nstu.mail.data.EmailImportance
import ru.nstu.mail.data.EmailSummary
import ru.nstu.mail.data.MailFilter
import ru.nstu.mail.data.MailFolder
import ru.nstu.mail.data.SystemFolderKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailListScreen(
    state: MailUiState,
    avatars: Map<String, ImageBitmap?>,
    onRequestAvatar: (String) -> Unit,
    onSelectFolder: (MailFolder) -> Unit,
    onSelectFilter: (MailFilter) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onSearch: (String) -> Unit,
    onToggleSearchAllFolders: (Boolean) -> Unit,
    onOpenMessage: (EmailSummary) -> Unit,
    onToggleSelect: (String) -> Unit,
    onClearSelection: () -> Unit,
    onToggleFlag: (EmailSummary) -> Unit,
    onBatchSetRead: (Boolean) -> Unit,
    onBatchMove: (MailFolder) -> Unit,
    onBatchDelete: () -> Unit,
    onCreateFolder: (name: String, parentFolderId: String?) -> Unit,
    onRenameFolder: (folder: MailFolder, newName: String) -> Unit,
    onDeleteFolder: (folder: MailFolder) -> Unit,
    onEmptyCurrentFolder: () -> Unit,
    onMarkAllRead: () -> Unit,
    onCompose: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenCalendar: () -> Unit,
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
    var folderMenuExpanded by remember { mutableStateOf(false) }
    var showMovePicker by remember { mutableStateOf(false) }
    var createFolderParent by remember { mutableStateOf<Pair<Boolean, MailFolder?>>(false to null) }
    var renameTarget by remember { mutableStateOf<MailFolder?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<MailFolder?>(null) }
    var confirmEmptyFolder by remember { mutableStateOf(false) }

    val selectionMode = state.selectedIds.isNotEmpty()
    BackHandler(enabled = selectionMode, onBack = onClearSelection)

    LaunchedEffect(state.toastMessage) {
        val msg = state.toastMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onClearToast()
    }

    if (showMovePicker) {
        FolderPickerDialog(
            folders = state.folders,
            excludeFolderId = state.currentFolder?.id,
            onDismiss = { showMovePicker = false },
            onSelect = { target ->
                showMovePicker = false
                onBatchMove(target)
            }
        )
    }

    if (createFolderParent.first) {
        FolderNameDialog(
            title = if (createFolderParent.second != null) {
                "Подпапка в «${createFolderParent.second!!.displayName}»"
            } else {
                "Новая папка"
            },
            initialName = "",
            confirmLabel = "Создать",
            onDismiss = { createFolderParent = false to null },
            onConfirm = { name ->
                val parent = createFolderParent.second
                createFolderParent = false to null
                onCreateFolder(name, parent?.id)
            }
        )
    }

    renameTarget?.let { target ->
        FolderNameDialog(
            title = "Переименовать папку",
            initialName = target.displayName,
            confirmLabel = "Сохранить",
            onDismiss = { renameTarget = null },
            onConfirm = { newName ->
                renameTarget = null
                onRenameFolder(target, newName)
            }
        )
    }

    deleteFolderTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteFolderTarget = null },
            title = { Text("Удалить папку «${target.displayName}»?") },
            text = { Text("Папка и её содержимое будут перемещены в Удалённые.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteFolderTarget = null
                    onDeleteFolder(target)
                }) { Text("Удалить") }
            },
            dismissButton = {
                TextButton(onClick = { deleteFolderTarget = null }) { Text("Отмена") }
            }
        )
    }

    if (confirmEmptyFolder && state.currentFolder != null) {
        AlertDialog(
            onDismissRequest = { confirmEmptyFolder = false },
            title = { Text("Очистить «${state.currentFolder.displayName}»?") },
            text = { Text("Все письма в этой папке будут удалены безвозвратно.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmptyFolder = false
                    onEmptyCurrentFolder()
                }) { Text("Очистить") }
            },
            dismissButton = {
                TextButton(onClick = { confirmEmptyFolder = false }) { Text("Отмена") }
            }
        )
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val userEmail = state.credentials?.email.orEmpty()
                        UserAvatar(
                            name = userEmail.substringBefore('@'),
                            email = userEmail,
                            photo = avatars[userEmail.trim().lowercase()],
                            size = 46.dp,
                            onRequestLoad = onRequestAvatar
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Почта НГТУ",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = userEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))

                    val systemFolders = state.folders.filter { it.isSystem }
                    val customFolders = state.folders.filterNot { it.isSystem }

                    systemFolders.forEach { folder ->
                        DrawerFolderItem(
                            folder = folder,
                            selected = state.currentFolder?.id == folder.id,
                            onSelect = {
                                scope.launch { drawerState.close() }
                                onSelectFolder(folder)
                            },
                            onCreateSubfolder = {
                                scope.launch { drawerState.close() }
                                createFolderParent = true to folder
                            },
                            onRename = null,
                            onDelete = null
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Пользовательские папки",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = {
                                scope.launch { drawerState.close() }
                                createFolderParent = true to null
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.CreateNewFolder,
                                contentDescription = "Новая папка",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    if (customFolders.isEmpty()) {
                        Text(
                            text = "Нет своих папок",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp)
                        )
                    } else {
                        customFolders.forEach { folder ->
                            DrawerFolderItem(
                                folder = folder,
                                selected = state.currentFolder?.id == folder.id,
                                onSelect = {
                                    scope.launch { drawerState.close() }
                                    onSelectFolder(folder)
                                },
                                onCreateSubfolder = {
                                    scope.launch { drawerState.close() }
                                    createFolderParent = true to folder
                                },
                                onRename = {
                                    scope.launch { drawerState.close() }
                                    renameTarget = folder
                                },
                                onDelete = {
                                    scope.launch { drawerState.close() }
                                    deleteFolderTarget = folder
                                }
                            )
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    NavigationDrawerItem(
                        label = { Text("Контакты и адресная книга") },
                        selected = false,
                        icon = { Icon(Icons.Default.People, contentDescription = null) },
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenContacts()
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    NavigationDrawerItem(
                        label = { Text("Календарь") },
                        selected = false,
                        icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null) },
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenCalendar()
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

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
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (selectionMode) {
                    TopAppBar(
                        title = { Text("Выбрано: ${state.selectedIds.size}") },
                        navigationIcon = {
                            IconButton(onClick = onClearSelection) {
                                Icon(Icons.Default.Close, contentDescription = "Снять выделение")
                            }
                        },
                        actions = {
                            IconButton(onClick = { onBatchSetRead(true) }) {
                                Icon(Icons.Default.MarkEmailRead, contentDescription = "Прочитано")
                            }
                            IconButton(onClick = { showMovePicker = true }) {
                                Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Переместить")
                            }
                            IconButton(onClick = onBatchDelete) {
                                Icon(Icons.Default.Delete, contentDescription = "Удалить")
                            }
                        }
                    )
                } else {
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
                                Text(
                                    text = state.currentFolder?.displayName ?: "Почта",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
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
                                    if (state.searchQuery.isNotEmpty() || searchDraft.isNotEmpty()) {
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
                                Box {
                                    IconButton(onClick = { folderMenuExpanded = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Действия папки")
                                    }
                                    DropdownMenu(
                                        expanded = folderMenuExpanded,
                                        onDismissRequest = { folderMenuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Отметить все прочитанными") },
                                            onClick = {
                                                folderMenuExpanded = false
                                                onMarkAllRead()
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Создать подпапку") },
                                            onClick = {
                                                folderMenuExpanded = false
                                                createFolderParent = true to state.currentFolder
                                            }
                                        )
                                        val canEmpty = state.currentFolder?.systemKind == SystemFolderKind.DELETED ||
                                            state.currentFolder?.systemKind == SystemFolderKind.JUNK
                                        if (canEmpty) {
                                            DropdownMenuItem(
                                                text = { Text("Очистить папку") },
                                                onClick = {
                                                    folderMenuExpanded = false
                                                    confirmEmptyFolder = true
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    )
                }
            },
            floatingActionButton = {
                if (!selectionMode) {
                    FloatingActionButton(onClick = onCompose) {
                        Icon(Icons.Default.Edit, contentDescription = "Написать письмо")
                    }
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

                // Filter chips + optional "Во всех папках" toggle when searching
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (searchExpanded || state.searchQuery.isNotBlank()) {
                        FilterChip(
                            selected = state.searchAllFolders,
                            onClick = { onToggleSearchAllFolders(!state.searchAllFolders) },
                            label = { Text("Во всех папках") }
                        )
                    }
                    MailFilter.entries.forEach { f ->
                        FilterChip(
                            selected = state.filter == f,
                            onClick = { onSelectFilter(f) },
                            label = { Text(f.labelRu) }
                        )
                    }
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
                                text = if (state.searchQuery.isBlank() && state.filter == MailFilter.ALL) {
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
                                    photo = avatars[item.senderEmail.trim().lowercase()],
                                    selected = item.id in state.selectedIds,
                                    onRequestAvatar = onRequestAvatar,
                                    onClick = { onOpenMessage(item) },
                                    onLongClick = { onToggleSelect(item.id) },
                                    onAvatarClick = { onToggleSelect(item.id) },
                                    onToggleFlag = { onToggleFlag(item) }
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
private fun DrawerFolderItem(
    folder: MailFolder,
    selected: Boolean,
    onSelect: () -> Unit,
    onCreateSubfolder: () -> Unit,
    onRename: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    var menuOpen by remember { mutableStateOf(false) }
    val indentDp = (12 + folder.depth * 14).dp

    NavigationDrawerItem(
        label = {
            Text(
                text = folder.displayName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        badge = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (folder.unreadCount > 0) {
                    Text(folder.unreadCount.toString())
                }
                if (onRename != null || onDelete != null) {
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "Опции папки",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Создать подпапку") },
                                onClick = { menuOpen = false; onCreateSubfolder() }
                            )
                            if (onRename != null) {
                                DropdownMenuItem(
                                    text = { Text("Переименовать") },
                                    onClick = { menuOpen = false; onRename() }
                                )
                            }
                            if (onDelete != null) {
                                DropdownMenuItem(
                                    text = { Text("Удалить") },
                                    onClick = { menuOpen = false; onDelete() }
                                )
                            }
                        }
                    }
                }
            }
        },
        selected = selected,
        icon = { Icon(folderIcon(folder), contentDescription = null) },
        onClick = onSelect,
        modifier = Modifier.padding(start = indentDp, end = 12.dp)
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmailRow(
    item: EmailSummary,
    photo: ImageBitmap?,
    selected: Boolean,
    onRequestAvatar: (String) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onToggleFlag: () -> Unit
) {
    val weight = if (item.isRead) FontWeight.Normal else FontWeight.Bold
    val bg = if (selected) {
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        UserAvatar(
            name = item.senderName,
            email = item.senderEmail,
            photo = photo,
            size = 40.dp,
            selected = selected,
            onRequestLoad = onRequestAvatar,
            modifier = Modifier
                .padding(top = 2.dp, end = 12.dp)
                .clickable(onClick = onAvatarClick)
        )

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!item.isRead) {
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
                if (item.importance == EmailImportance.HIGH) {
                    Icon(
                        imageVector = Icons.Default.PriorityHigh,
                        contentDescription = "Высокая важность",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(2.dp))
                }
                Text(
                    text = item.senderName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = weight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(6.dp))
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
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
                IconButton(
                    onClick = onToggleFlag,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (item.isFlagged) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Флажок",
                        tint = if (item.isFlagged) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun FolderPickerDialog(
    folders: List<MailFolder>,
    excludeFolderId: String?,
    onDismiss: () -> Unit,
    onSelect: (MailFolder) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переместить в папку") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                folders.filter { it.id != excludeFolderId }.forEach { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(folder) }
                            .padding(
                                start = (8 + folder.depth * 16).dp,
                                top = 10.dp,
                                bottom = 10.dp,
                                end = 8.dp
                            ),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            folderIcon(folder),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(folder.displayName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

@Composable
private fun FolderNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Название папки") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank()
            ) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        }
    )
}

private fun folderIcon(folder: MailFolder) = when (folder.systemKind) {
    SystemFolderKind.INBOX -> Icons.Default.Inbox
    SystemFolderKind.SENT -> Icons.AutoMirrored.Filled.Send
    SystemFolderKind.DRAFTS -> Icons.Default.Drafts
    SystemFolderKind.OUTBOX -> Icons.Default.Outbox
    SystemFolderKind.DELETED -> Icons.Default.Delete
    SystemFolderKind.JUNK -> Icons.Default.Report
    null -> Icons.Default.Folder
}
