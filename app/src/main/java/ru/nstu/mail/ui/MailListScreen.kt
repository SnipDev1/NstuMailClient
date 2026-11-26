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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.nstu.mail.data.EmailImportance
import ru.nstu.mail.data.EmailSummary
import ru.nstu.mail.data.MailFilter
import ru.nstu.mail.data.MailFolder
import ru.nstu.mail.data.PersonProfile
import ru.nstu.mail.data.SwipeAction
import ru.nstu.mail.data.SystemFolderKind
import ru.nstu.mail.data.TagSortRule
import ru.nstu.mail.data.extractSubjectTagHierarchies
import ru.nstu.mail.data.formatTagPath
import ru.nstu.mail.data.parseTagPath

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MailListScreen(
    state: MailUiState,
    avatars: Map<String, ImageBitmap?>,
    personProfiles: Map<String, PersonProfile?>,
    knownNames: Map<String, String>,
    mutedFolderIds: Set<String>,
    tagSortRules: List<TagSortRule>,
    tagTemplates: List<String>,
    swipeRightAction: SwipeAction,
    swipeLeftAction: SwipeAction,
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
    onToggleReadSummary: (EmailSummary) -> Unit,
    onDeleteSummary: (EmailSummary) -> Unit,
    onMoveSummary: (EmailSummary, MailFolder) -> Unit,
    onToggleSpamSummary: (EmailSummary) -> Unit,
    onToggleFolderNotifications: (MailFolder) -> Unit,
    onSaveTagSortRule: (tag: String, folder: MailFolder, autoCreateSubfolders: Boolean) -> Unit,
    onRemoveTagSortRule: (tag: String) -> Unit,
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
    onOpenSettings: () -> Unit,
    onLogout: () -> Unit,
    onClearToast: () -> Unit
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var otherExpanded by rememberSaveable { mutableStateOf(false) }
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    var searchDraft by rememberSaveable { mutableStateOf(state.searchQuery) }
    var folderMenuExpanded by remember { mutableStateOf(false) }
    var showMovePicker by remember { mutableStateOf(false) }
    var createFolderParent by remember { mutableStateOf<Pair<Boolean, MailFolder?>>(false to null) }
    var renameTarget by remember { mutableStateOf<MailFolder?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<MailFolder?>(null) }
    var confirmEmptyFolder by remember { mutableStateOf(false) }
    var moveSingleTarget by remember { mutableStateOf<EmailSummary?>(null) }
    var quickRuleFolder by remember { mutableStateOf<MailFolder?>(null) }
    var showRulesDialog by remember { mutableStateOf(false) }

    fun isFolderMuted(f: MailFolder?): Boolean {
        if (f == null) return false
        return f.id in mutedFolderIds || (f.systemKind == SystemFolderKind.INBOX && "inbox" in mutedFolderIds)
    }

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

    moveSingleTarget?.let { msg ->
        FolderPickerDialog(
            folders = state.folders,
            excludeFolderId = state.currentFolder?.id,
            onDismiss = { moveSingleTarget = null },
            onSelect = { target ->
                moveSingleTarget = null
                onMoveSummary(msg, target)
            }
        )
    }

    quickRuleFolder?.let { targetFolder ->
        AddTagRuleDialog(
            folders = state.folders,
            tagTemplates = tagTemplates,
            rules = tagSortRules,
            fixedFolder = targetFolder,
            onDismiss = { quickRuleFolder = null },
            onConfirm = { tag, chosen, autoSub ->
                quickRuleFolder = null
                onSaveTagSortRule(tag, chosen, autoSub)
            },
            onRemoveRule = { tag ->
                quickRuleFolder = null
                onRemoveTagSortRule(tag)
            }
        )
    }

    if (showRulesDialog) {
        TagSortRulesDialog(
            folders = state.folders,
            rules = tagSortRules,
            tagTemplates = tagTemplates,
            mutedFolderIds = mutedFolderIds,
            onSaveRule = onSaveTagSortRule,
            onRemoveRule = onRemoveTagSortRule,
            onDismiss = { showRulesDialog = false }
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
                        val userKey = userEmail.trim().lowercase()
                        val userDisplayName = resolveDisplayName(
                            name = "",
                            email = userEmail,
                            profile = personProfiles[userKey],
                            knownNames = knownNames
                        )
                        UserAvatar(
                            name = userDisplayName,
                            email = userEmail,
                            photo = avatars[userKey],
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
                                text = userDisplayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    HorizontalDivider()
                    Spacer(Modifier.height(6.dp))

                    val deletedFolder = state.folders.firstOrNull { it.systemKind == SystemFolderKind.DELETED }
                    val systemFolders = state.folders.filter {
                        it.isSystem && it.systemKind != SystemFolderKind.DELETED
                    }
                    val customFolders = state.folders.filterNot { it.isSystem }

                    systemFolders.forEach { folder ->
                        DrawerFolderItem(
                            folder = folder,
                            selected = state.currentFolder?.id == folder.id,
                            notificationsMuted = isFolderMuted(folder),
                            onSelect = {
                                scope.launch { drawerState.close() }
                                onSelectFolder(folder)
                            },
                            onToggleNotifications = {
                                scope.launch { drawerState.close() }
                                onToggleFolderNotifications(folder)
                            },
                            onSetupAutoSort = {
                                scope.launch { drawerState.close() }
                                quickRuleFolder = folder
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
                                notificationsMuted = isFolderMuted(folder),
                                onSelect = {
                                    scope.launch { drawerState.close() }
                                    onSelectFolder(folder)
                                },
                                onToggleNotifications = {
                                    scope.launch { drawerState.close() }
                                    onToggleFolderNotifications(folder)
                                },
                                onSetupAutoSort = {
                                    scope.launch { drawerState.close() }
                                    quickRuleFolder = folder
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
                        label = { Text("Прочее") },
                        selected = false,
                        icon = {
                            Icon(
                                if (otherExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = null
                            )
                        },
                        onClick = { otherExpanded = !otherExpanded },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )

                    if (otherExpanded) {
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            deletedFolder?.let { delFolder ->
                                DrawerFolderItem(
                                    folder = delFolder,
                                    selected = state.currentFolder?.id == delFolder.id,
                                    notificationsMuted = isFolderMuted(delFolder),
                                    onSelect = {
                                        scope.launch { drawerState.close() }
                                        onSelectFolder(delFolder)
                                    },
                                    onToggleNotifications = {
                                        scope.launch { drawerState.close() }
                                        onToggleFolderNotifications(delFolder)
                                    },
                                    onSetupAutoSort = {
                                        scope.launch { drawerState.close() }
                                        quickRuleFolder = delFolder
                                    },
                                    onCreateSubfolder = {
                                        scope.launch { drawerState.close() }
                                        createFolderParent = true to delFolder
                                    },
                                    onRename = null,
                                    onDelete = null
                                )
                            }

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
                        }
                    }

                    NavigationDrawerItem(
                        label = { Text("Настройки") },
                        selected = false,
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        onClick = {
                            scope.launch { drawerState.close() }
                            onOpenSettings()
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
                                        state.currentFolder?.let { curFolder ->
                                            val muted = isFolderMuted(curFolder)
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        if (muted) "Уведомления папки: выкл (вкл.)"
                                                        else "Уведомления папки: вкл (выкл.)"
                                                    )
                                                },
                                                onClick = {
                                                    folderMenuExpanded = false
                                                    onToggleFolderNotifications(curFolder)
                                                }
                                            )
                                        }
                                        DropdownMenuItem(
                                            text = { Text("Автосортировка по тегам") },
                                            onClick = {
                                                folderMenuExpanded = false
                                                showRulesDialog = true
                                            }
                                        )
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
                                val senderKey = item.senderEmail.trim().lowercase()
                                val resolvedSenderName = resolveDisplayName(
                                    name = item.senderName,
                                    email = item.senderEmail,
                                    profile = personProfiles[senderKey],
                                    knownNames = knownNames
                                )
                                SwipeableEmailRow(
                                    item = item,
                                    displaySenderName = resolvedSenderName,
                                    photo = avatars[senderKey],
                                    selected = item.id in state.selectedIds,
                                    selectionMode = selectionMode,
                                    swipeRightAction = swipeRightAction,
                                    swipeLeftAction = swipeLeftAction,
                                    onRequestAvatar = onRequestAvatar,
                                    onClick = { onOpenMessage(item) },
                                    onLongClick = { onToggleSelect(item.id) },
                                    onAvatarClick = { onToggleSelect(item.id) },
                                    onToggleFlag = { onToggleFlag(item) },
                                    onExecuteSwipeAction = { action, msg ->
                                        when (action) {
                                            SwipeAction.NONE -> Unit
                                            SwipeAction.TOGGLE_READ -> onToggleReadSummary(msg)
                                            SwipeAction.DELETE -> onDeleteSummary(msg)
                                            SwipeAction.TOGGLE_FLAG -> onToggleFlag(msg)
                                            SwipeAction.MOVE -> moveSingleTarget = msg
                                            SwipeAction.SPAM -> onToggleSpamSummary(msg)
                                        }
                                    }
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
    notificationsMuted: Boolean,
    onSelect: () -> Unit,
    onToggleNotifications: () -> Unit,
    onSetupAutoSort: () -> Unit,
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
                if (notificationsMuted) {
                    Icon(
                        imageVector = Icons.Default.NotificationsOff,
                        contentDescription = "Уведомления выключены",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(14.dp)
                    )
                }
                if (folder.totalCount > 0 || folder.unreadCount > 0) {
                    val primaryColor = MaterialTheme.colorScheme.primary
                    val normalColor = MaterialTheme.colorScheme.onSurfaceVariant
                    val dimColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                    Text(
                        text = buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    color = if (folder.unreadCount > 0) primaryColor else normalColor,
                                    fontWeight = if (folder.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal
                                )
                            ) {
                                append(folder.unreadCount.toString())
                            }
                            withStyle(SpanStyle(color = dimColor)) {
                                append("/${folder.totalCount}")
                            }
                        },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 2.dp)
                    )
                }
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
                            text = {
                                Text(
                                    if (notificationsMuted) "Уведомления: выкл (вкл.)"
                                    else "Уведомления: вкл (выкл.)"
                                )
                            },
                            onClick = { menuOpen = false; onToggleNotifications() }
                        )
                        DropdownMenuItem(
                            text = { Text("Автосортировка по тегу…") },
                            onClick = { menuOpen = false; onSetupAutoSort() }
                        )
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
        },
        selected = selected,
        icon = { Icon(folderIcon(folder), contentDescription = null) },
        onClick = onSelect,
        modifier = Modifier.padding(start = indentDp, end = 12.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableEmailRow(
    item: EmailSummary,
    displaySenderName: String,
    photo: ImageBitmap?,
    selected: Boolean,
    selectionMode: Boolean,
    swipeRightAction: SwipeAction,
    swipeLeftAction: SwipeAction,
    onRequestAvatar: (String) -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onAvatarClick: () -> Unit,
    onToggleFlag: () -> Unit,
    onExecuteSwipeAction: (SwipeAction, EmailSummary) -> Unit
) {
    val currentItem by rememberUpdatedState(item)
    val currentRight by rememberUpdatedState(swipeRightAction)
    val currentLeft by rememberUpdatedState(swipeLeftAction)

    if (selectionMode || (swipeRightAction == SwipeAction.NONE && swipeLeftAction == SwipeAction.NONE)) {
        EmailRow(
            item = item,
            displaySenderName = displaySenderName,
            photo = photo,
            selected = selected,
            onRequestAvatar = onRequestAvatar,
            onClick = onClick,
            onLongClick = onLongClick,
            onAvatarClick = onAvatarClick,
            onToggleFlag = onToggleFlag
        )
        return
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> {
                    if (currentRight != SwipeAction.NONE) {
                        onExecuteSwipeAction(currentRight, currentItem)
                    }
                    false
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    if (currentLeft != SwipeAction.NONE) {
                        onExecuteSwipeAction(currentLeft, currentItem)
                    }
                    false
                }
                SwipeToDismissBoxValue.Settled -> false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = swipeRightAction != SwipeAction.NONE,
        enableDismissFromEndToStart = swipeLeftAction != SwipeAction.NONE,
        backgroundContent = {
            val dir = dismissState.dismissDirection
            val action = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> swipeRightAction
                SwipeToDismissBoxValue.EndToStart -> swipeLeftAction
                SwipeToDismissBoxValue.Settled -> SwipeAction.NONE
            }
            val bgColor = when (action) {
                SwipeAction.DELETE, SwipeAction.SPAM -> MaterialTheme.colorScheme.errorContainer
                SwipeAction.TOGGLE_READ, SwipeAction.TOGGLE_FLAG, SwipeAction.MOVE ->
                    MaterialTheme.colorScheme.primaryContainer
                SwipeAction.NONE -> MaterialTheme.colorScheme.surface
            }
            val fgColor = when (action) {
                SwipeAction.DELETE, SwipeAction.SPAM -> MaterialTheme.colorScheme.onErrorContainer
                SwipeAction.TOGGLE_READ, SwipeAction.TOGGLE_FLAG, SwipeAction.MOVE ->
                    MaterialTheme.colorScheme.onPrimaryContainer
                SwipeAction.NONE -> MaterialTheme.colorScheme.onSurface
            }
            val icon = when (action) {
                SwipeAction.DELETE -> Icons.Default.Delete
                SwipeAction.SPAM -> Icons.Default.Report
                SwipeAction.TOGGLE_READ ->
                    if (item.isRead) Icons.Default.MarkEmailUnread else Icons.Default.MarkEmailRead
                SwipeAction.TOGGLE_FLAG ->
                    if (item.isFlagged) Icons.Default.StarBorder else Icons.Default.Star
                SwipeAction.MOVE -> Icons.AutoMirrored.Filled.DriveFileMove
                SwipeAction.NONE -> null
            }
            val align = if (dir == SwipeToDismissBoxValue.StartToEnd) {
                Alignment.CenterStart
            } else {
                Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bgColor)
                    .padding(horizontal = 20.dp),
                contentAlignment = align
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = action.labelRu,
                        tint = fgColor
                    )
                }
            }
        }
    ) {
        EmailRow(
            item = item,
            displaySenderName = displaySenderName,
            photo = photo,
            selected = selected,
            onRequestAvatar = onRequestAvatar,
            onClick = onClick,
            onLongClick = onLongClick,
            onAvatarClick = onAvatarClick,
            onToggleFlag = onToggleFlag
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EmailRow(
    item: EmailSummary,
    displaySenderName: String,
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
            name = displaySenderName,
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
                    text = displaySenderName,
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
                    val (tagHierarchies, cleanSubject) = remember(item.subject) {
                        extractSubjectTagHierarchies(item.subject)
                    }
                    if (tagHierarchies.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(bottom = 2.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            tagHierarchies.forEach { hierarchy ->
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Text(
                                        text = hierarchy.joinToString(" › "),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                    }
                    Text(
                        text = cleanSubject.ifBlank { item.subject },
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

@Composable
private fun TagSortRulesDialog(
    folders: List<MailFolder>,
    rules: List<TagSortRule>,
    tagTemplates: List<String>,
    mutedFolderIds: Set<String>,
    onSaveRule: (tag: String, folder: MailFolder, autoCreateSubfolders: Boolean) -> Unit,
    onRemoveRule: (tag: String) -> Unit,
    onDismiss: () -> Unit
) {
    var editTagTarget by remember { mutableStateOf<String?>(null) }

    editTagTarget?.let { initialTag ->
        AddTagRuleDialog(
            folders = folders,
            tagTemplates = tagTemplates,
            rules = rules,
            fixedFolder = null,
            initialTag = initialTag.ifBlank { null },
            onDismiss = { editTagTarget = null },
            onConfirm = { tag, folder, autoSub ->
                editTagTarget = null
                onSaveRule(tag, folder, autoSub)
            },
            onRemoveRule = { tag ->
                editTagTarget = null
                onRemoveRule(tag)
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Автосортировка по тегам") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "Нажмите на любой шаблон тега, чтобы назначить папку. Неизвестные теги из НГТУ уходят в «Несортированные».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                tagTemplates.forEach { tpl ->
                    val segs = parseTagPath(tpl)
                    val depth = (segs.size - 1).coerceAtLeast(0)
                    val directRule = rules.firstOrNull { it.tag.equals(tpl, ignoreCase = true) }
                    val muted = directRule?.targetFolderId in mutedFolderIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editTagTarget = tpl }
                            .padding(start = (depth * 12).dp, top = 6.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (depth > 0) "↳ ${segs.last()}" else segs.lastOrNull().orEmpty(),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (directRule != null) FontWeight.SemiBold else FontWeight.Normal
                            )
                            Text(
                                text = if (directRule != null) {
                                    "→ ${directRule.targetFolderName}" +
                                        (if (directRule.autoCreateSubfolders) " · подпапки" else "") +
                                        (if (muted) " · ув. выкл." else "")
                                } else {
                                    "Без правила (нажмите для выбора папки)"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (directRule != null) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (directRule != null) {
                            IconButton(
                                onClick = { onRemoveRule(directRule.tag) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Удалить правило",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                }
                val extraRules = rules.filter { rule ->
                    tagTemplates.none { it.equals(rule.tag, ignoreCase = true) }
                }
                extraRules.forEach { rule ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { editTagTarget = rule.tag }
                            .padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${rule.pathSegments().joinToString(" › ")} → ${rule.targetFolderName}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        IconButton(
                            onClick = { onRemoveRule(rule.tag) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Удалить правило", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { editTagTarget = "" }) { Text("+ Свой тег") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
    )
}

@Composable
internal fun AddTagRuleDialog(
    folders: List<MailFolder>,
    tagTemplates: List<String>,
    rules: List<TagSortRule> = emptyList(),
    fixedFolder: MailFolder?,
    initialTag: String? = null,
    onDismiss: () -> Unit,
    onConfirm: (tag: String, targetFolder: MailFolder, autoCreateSubfolders: Boolean) -> Unit,
    onRemoveRule: ((tag: String) -> Unit)? = null
) {
    val allTags = remember(tagTemplates) {
        (ru.nstu.mail.data.DEFAULT_TAG_TEMPLATES + tagTemplates)
            .map { formatTagPath(parseTagPath(it)) }
            .filter { it.isNotEmpty() }
            .distinctBy { it.lowercase() }
    }
    val startingTag = initialTag?.takeIf { it.isNotBlank() } ?: allTags.firstOrNull().orEmpty()
    val startingRule = remember(startingTag, rules) {
        rules.firstOrNull { it.tag.equals(startingTag, ignoreCase = true) }
    }
    var tagText by remember(startingTag) { mutableStateOf(startingTag) }
    var autoCreateSubfolders by remember(startingRule) {
        mutableStateOf(startingRule?.autoCreateSubfolders ?: true)
    }
    var selectedFolder by remember(startingRule, fixedFolder, folders) {
        mutableStateOf(
            fixedFolder
                ?: startingRule?.let { r -> folders.firstOrNull { it.id == r.targetFolderId } }
                ?: folders.firstOrNull { !it.isSystem }
                ?: folders.firstOrNull { it.systemKind != SystemFolderKind.INBOX }
                ?: folders.firstOrNull()
        )
    }
    val currentRuleForTag = rules.firstOrNull {
        it.tag.equals(formatTagPath(parseTagPath(tagText)), ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (fixedFolder != null) "Автосортировка в «${fixedFolder.displayName}»"
                else "Правило автосортировки"
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                OutlinedTextField(
                    value = tagText,
                    onValueChange = { tagText = it.take(120) },
                    label = { Text("Тег или путь (напр. 1 или 1/2)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Из шаблонов тегов:",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                allTags.forEach { tpl ->
                    val segs = parseTagPath(tpl)
                    val depth = (segs.size - 1).coerceAtLeast(0)
                    val picked = tagText.equals(tpl, ignoreCase = true)
                    val boundRule = rules.firstOrNull { it.tag.equals(tpl, ignoreCase = true) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                tagText = tpl
                                if (fixedFolder == null && boundRule != null) {
                                    folders.firstOrNull { it.id == boundRule.targetFolderId }?.let {
                                        selectedFolder = it
                                    }
                                    autoCreateSubfolders = boundRule.autoCreateSubfolders
                                }
                            }
                            .padding(start = (4 + depth * 12).dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (picked) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(
                            text = if (depth > 0) "↳ ${segs.last()}" else segs.lastOrNull().orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (boundRule != null) {
                            Text(
                                text = "→ ${boundRule.targetFolderName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { autoCreateSubfolders = !autoCreateSubfolders }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text("Ветвление подпапок", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Для [\"1\", \"2\"] создаст подпапку «2» внутри целевой",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoCreateSubfolders,
                        onCheckedChange = { autoCreateSubfolders = it }
                    )
                }
                if (fixedFolder == null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Целевая папка:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(4.dp))
                    folders.forEach { f ->
                        val picked = selectedFolder?.id == f.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedFolder = f }
                                .padding(
                                    start = (4 + f.depth * 14).dp,
                                    top = 6.dp,
                                    bottom = 6.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (picked) Icons.Default.CheckCircle else folderIcon(f),
                                contentDescription = null,
                                tint = if (picked) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = f.displayName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (onRemoveRule != null && currentRuleForTag != null) {
                    TextButton(onClick = { onRemoveRule(currentRuleForTag.tag) }) {
                        Text("Сбросить", color = MaterialTheme.colorScheme.error)
                    }
                }
                TextButton(
                    onClick = {
                        val target = fixedFolder ?: selectedFolder ?: return@TextButton
                        onConfirm(tagText.trim(), target, autoCreateSubfolders)
                    },
                    enabled = parseTagPath(tagText).isNotEmpty() &&
                        (fixedFolder != null || selectedFolder != null)
                ) {
                    Text("Сохранить")
                }
            }
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
