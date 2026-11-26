package ru.nstu.mail.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Drafts
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MoveToInbox
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Outbox
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jsoup.Jsoup
import ru.nstu.mail.data.CalendarEvent
import ru.nstu.mail.data.ContactEntry
import ru.nstu.mail.data.DEFAULT_SERVER_URL
import ru.nstu.mail.data.DEFAULT_TAG_TEMPLATES
import ru.nstu.mail.data.EmailDetail
import ru.nstu.mail.data.EmailHtmlSanitizer
import ru.nstu.mail.data.EmailParticipant
import ru.nstu.mail.data.EmailSummary
import ru.nstu.mail.data.MailFilter
import ru.nstu.mail.data.MailFolder
import ru.nstu.mail.data.PersonProfile
import ru.nstu.mail.data.SystemFolderKind
import ru.nstu.mail.data.TagSortRule
import ru.nstu.mail.data.UNSORTED_FOLDER_NAME
import ru.nstu.mail.data.avatarPaletteIndex
import ru.nstu.mail.data.childTagsForPrefix
import ru.nstu.mail.data.extractInitials
import ru.nstu.mail.data.extractSubjectTagHierarchies
import ru.nstu.mail.data.formatMailDate
import ru.nstu.mail.data.formatShortPersonName
import ru.nstu.mail.data.formatSubjectWithTagHierarchies
import ru.nstu.mail.data.formatTagPath
import ru.nstu.mail.data.needsNameResolution
import ru.nstu.mail.data.parseFormattedRecipient
import ru.nstu.mail.data.parseTagPath
import ru.nstu.mail.data.participants
import ru.nstu.mail.data.resolveDisplayName
import ru.nstu.mail.data.sanitizeTagSegment
import ru.nstu.mail.data.splitEmails
import java.awt.BorderLayout
import java.io.File
import javax.swing.JEditorPane
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent

@Composable
fun DesktopRootScreen(
    controller: DesktopMailController,
    state: DesktopUiState
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val avatars by controller.avatars.collectAsState()
    val profiles by controller.personProfiles.collectAsState()
    val knownNames by controller.knownNames.collectAsState()

    LaunchedEffect(state.toastMessage) {
        val msg = state.toastMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        controller.clearToast()
    }

    state.availableUpdate?.let { upd ->
        AlertDialog(
            onDismissRequest = controller::dismissUpdate,
            title = { Text("Доступно обновление ${upd.tagName}") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = if (upd.isPrerelease) "Тестовая сборка (${upd.assetName})" else "Стабильная сборка",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (upd.releaseNotes.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(upd.releaseNotes, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    controller.openExternalUrl(upd.downloadUrl)
                    controller.dismissUpdate()
                }) { Text("Скачать") }
            },
            dismissButton = {
                TextButton(onClick = controller::dismissUpdate) { Text("Позже") }
            }
        )
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (!state.isLoggedIn) {
                DesktopLoginPane(state = state, onLogin = controller::login)
            } else {
                DesktopWorkspace(
                    controller = controller,
                    state = state,
                    avatars = avatars,
                    profiles = profiles,
                    knownNames = knownNames
                )
            }
        }
    }
}

@Composable
private fun DesktopLoginPane(
    state: DesktopUiState,
    onLogin: (String, String, String) -> Unit
) {
    var email by remember(state.credentials?.email) { mutableStateOf(state.credentials?.email.orEmpty()) }
    var password by remember(state.credentials?.password) { mutableStateOf(state.credentials?.password.orEmpty()) }
    var serverUrl by remember { mutableStateOf(state.credentials?.serverUrl ?: DEFAULT_SERVER_URL) }
    var showPassword by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.width(420.dp).padding(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(24.dp).onPreviewKeyEvent { ev ->
                    if (ev.key == Key.Enter && !state.isLoading) {
                        onLogin(email, password, serverUrl)
                        true
                    } else false
                },
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Почта НГТУ", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Клиент для Exchange (ПК-версия ${DesktopBuildInfo.VERSION_NAME})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Корпоративная почта") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Сервер EWS") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (!state.errorMessage.isNullOrBlank()) {
                    Text(state.errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = { onLogin(email, password, serverUrl) },
                    enabled = !state.isLoading && email.isNotBlank() && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text("Войти")
                }
            }
        }
    }
}

@Composable
private fun DesktopWorkspace(
    controller: DesktopMailController,
    state: DesktopUiState,
    avatars: Map<String, ImageBitmap?>,
    profiles: Map<String, PersonProfile?>,
    knownNames: Map<String, String>
) {
    var otherExpanded by remember { mutableStateOf(false) }
    var createFolderParent by remember { mutableStateOf<Pair<Boolean, MailFolder?>>(false to null) }
    var renameTarget by remember { mutableStateOf<MailFolder?>(null) }
    var deleteFolderTarget by remember { mutableStateOf<MailFolder?>(null) }
    var quickRuleFolder by remember { mutableStateOf<MailFolder?>(null) }
    var showRulesDialog by remember { mutableStateOf(false) }
    var confirmEmptyFolder by remember { mutableStateOf(false) }
    var moveMessageTarget by remember { mutableStateOf<EmailDetail?>(null) }
    var topFolderMenuOpen by remember { mutableStateOf(false) }
    var searchDraft by remember(state.searchQuery) { mutableStateOf(state.searchQuery) }

    fun isMuted(f: MailFolder?): Boolean {
        if (f == null) return false
        val muted = state.settings.mutedFolderIds
        return f.id in muted || (f.systemKind == SystemFolderKind.INBOX && "inbox" in muted)
    }

    if (createFolderParent.first) {
        FolderNameDialog(
            title = createFolderParent.second?.let { "Подпапка в «${it.displayName}»" } ?: "Новая папка",
            initialName = "",
            confirmLabel = "Создать",
            onDismiss = { createFolderParent = false to null },
            onConfirm = { name ->
                val p = createFolderParent.second
                createFolderParent = false to null
                controller.createFolder(name, p?.id)
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
                controller.renameFolder(target, newName)
            }
        )
    }

    deleteFolderTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteFolderTarget = null },
            title = { Text("Удалить папку «${target.displayName}»?") },
            text = { Text("Папка и её подпапки будут удалены.") },
            confirmButton = {
                TextButton(onClick = {
                    deleteFolderTarget = null
                    controller.deleteFolder(target)
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteFolderTarget = null }) { Text("Отмена") } }
        )
    }

    quickRuleFolder?.let { target ->
        AddTagRuleDialog(
            folders = state.folders,
            tagTemplates = state.settings.tagTemplates,
            rules = state.settings.tagSortRules,
            fixedFolder = target,
            initialTag = null,
            onDismiss = { quickRuleFolder = null },
            onConfirm = { tag, chosen, autoSub ->
                quickRuleFolder = null
                controller.saveTagSortRule(tag, chosen, autoSub)
            },
            onRemoveRule = { tag ->
                quickRuleFolder = null
                controller.removeTagSortRule(tag)
            }
        )
    }

    if (showRulesDialog) {
        TagRulesTreeDialog(
            folders = state.folders,
            rules = state.settings.tagSortRules,
            tagTemplates = state.settings.tagTemplates,
            mutedFolderIds = state.settings.mutedFolderIds,
            onSaveRule = controller::saveTagSortRule,
            onRemoveRule = controller::removeTagSortRule,
            onDismiss = { showRulesDialog = false }
        )
    }

    if (confirmEmptyFolder && state.currentFolder != null) {
        AlertDialog(
            onDismissRequest = { confirmEmptyFolder = false },
            title = { Text("Очистить «${state.currentFolder.displayName}»?") },
            text = { Text("Все письма и подпапки внутри будут удалены навсегда.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmptyFolder = false
                    controller.emptyCurrentFolder()
                }) { Text("Очистить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmEmptyFolder = false }) { Text("Отмена") } }
        )
    }

    moveMessageTarget?.let { msg ->
        AlertDialog(
            onDismissRequest = { moveMessageTarget = null },
            title = { Text("Переместить в папку") },
            text = {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    state.folders.filter { it.id != state.currentFolder?.id }.forEach { f ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                moveMessageTarget = null
                                controller.moveDetail(msg, f)
                            }.padding(start = (6 + f.depth * 14).dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(folderIcon(f), null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(f.displayName)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { moveMessageTarget = null }) { Text("Отмена") } }
        )
    }

    Row(modifier = Modifier.fillMaxSize()) {
        // --- 1. Left Sidebar ---
        Column(
            modifier = Modifier
                .width(260.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
                .verticalScroll(rememberScrollState())
                .padding(8.dp)
        ) {
            val userEmail = state.credentials?.email.orEmpty()
            val userKey = userEmail.trim().lowercase()
            val userName = resolveDisplayName("", userEmail, profiles[userKey], knownNames)
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DesktopAvatar(userName, userEmail, avatars[userKey], 40.dp, controller::requestAvatar)
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Почта НГТУ", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(userName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(6.dp))
            Button(
                onClick = { controller.openCompose() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
            ) {
                Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Написать письмо")
            }
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            Spacer(Modifier.height(6.dp))

            val unsortedFolder = state.folders.firstOrNull { !it.isSystem && it.displayName.equals(UNSORTED_FOLDER_NAME, true) }
            val deletedFolder = state.folders.firstOrNull { it.systemKind == SystemFolderKind.DELETED }
            val systemFolders = state.folders.filter { it.isSystem && it.systemKind != SystemFolderKind.DELETED }
            val customFolders = state.folders.filterNot { it.isSystem || it.id == unsortedFolder?.id }

            var unsortedDone = false
            systemFolders.forEach { folder ->
                if (unsortedFolder != null && !unsortedDone && folder.systemKind != SystemFolderKind.INBOX) {
                    SidebarFolderRow(unsortedFolder.copy(depth = 0), state.currentFolder?.id == unsortedFolder.id, isMuted(unsortedFolder),
                        onSelect = { controller.selectFolder(unsortedFolder) },
                        onToggleMute = { controller.toggleFolderNotifications(unsortedFolder) },
                        onAutoSort = { quickRuleFolder = unsortedFolder },
                        onCreateSub = { createFolderParent = true to unsortedFolder }
                    )
                    unsortedDone = true
                }
                SidebarFolderRow(folder, state.currentFolder?.id == folder.id, isMuted(folder),
                    onSelect = { controller.selectFolder(folder) },
                    onToggleMute = { controller.toggleFolderNotifications(folder) },
                    onAutoSort = { quickRuleFolder = folder },
                    onCreateSub = { createFolderParent = true to folder }
                )
                if (unsortedFolder != null && !unsortedDone && folder.systemKind == SystemFolderKind.INBOX) {
                    SidebarFolderRow(unsortedFolder.copy(depth = 0), state.currentFolder?.id == unsortedFolder.id, isMuted(unsortedFolder),
                        onSelect = { controller.selectFolder(unsortedFolder) },
                        onToggleMute = { controller.toggleFolderNotifications(unsortedFolder) },
                        onAutoSort = { quickRuleFolder = unsortedFolder },
                        onCreateSub = { createFolderParent = true to unsortedFolder }
                    )
                    unsortedDone = true
                }
            }
            if (unsortedFolder != null && !unsortedDone) {
                SidebarFolderRow(unsortedFolder.copy(depth = 0), state.currentFolder?.id == unsortedFolder.id, isMuted(unsortedFolder),
                    onSelect = { controller.selectFolder(unsortedFolder) },
                    onToggleMute = { controller.toggleFolderNotifications(unsortedFolder) },
                    onAutoSort = { quickRuleFolder = unsortedFolder },
                    onCreateSub = { createFolderParent = true to unsortedFolder }
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Пользовательские папки", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                IconButton(onClick = { createFolderParent = true to null }, modifier = Modifier.size(26.dp)) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "Новая папка", modifier = Modifier.size(16.dp))
                }
            }
            if (customFolders.isEmpty()) {
                Text("Нет своих папок", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(10.dp))
            } else {
                customFolders.forEach { folder ->
                    SidebarFolderRow(folder, state.currentFolder?.id == folder.id, isMuted(folder),
                        onSelect = { controller.selectFolder(folder) },
                        onToggleMute = { controller.toggleFolderNotifications(folder) },
                        onAutoSort = { quickRuleFolder = folder },
                        onCreateSub = { createFolderParent = true to folder },
                        onRename = { renameTarget = folder },
                        onDelete = { deleteFolderTarget = folder }
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            SidebarNavRow(
                icon = if (otherExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                label = "Прочее",
                selected = false,
                onClick = { otherExpanded = !otherExpanded }
            )
            if (otherExpanded) {
                Column(modifier = Modifier.padding(start = 12.dp)) {
                    deletedFolder?.let { del ->
                        SidebarFolderRow(del, state.currentFolder?.id == del.id, isMuted(del),
                            onSelect = { controller.selectFolder(del) },
                            onToggleMute = { controller.toggleFolderNotifications(del) },
                            onAutoSort = { quickRuleFolder = del },
                            onCreateSub = { createFolderParent = true to del }
                        )
                    }
                    SidebarNavRow(Icons.Default.People, "Контакты и адресная книга", state.rightPane is DesktopRightPane.Contacts, controller::openContacts)
                    SidebarNavRow(Icons.Default.CalendarMonth, "Календарь", state.rightPane is DesktopRightPane.Calendar, controller::openCalendar)
                }
            }
            SidebarNavRow(Icons.Default.Settings, "Настройки", state.rightPane is DesktopRightPane.Settings, controller::openSettings)
            SidebarNavRow(Icons.AutoMirrored.Filled.Logout, "Выйти", false, controller::logout)
        }

        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))

        // --- 2. Middle Mail List ---
        Column(modifier = Modifier.width(360.dp).fillMaxHeight()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(state.currentFolder?.displayName ?: "Почта", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Row {
                    IconButton(onClick = { controller.refresh() }) { Icon(Icons.Default.Refresh, "Обновить") }
                    Box {
                        IconButton(onClick = { topFolderMenuOpen = true }) { Icon(Icons.Default.MoreVert, "Меню папки") }
                        DropdownMenu(expanded = topFolderMenuOpen, onDismissRequest = { topFolderMenuOpen = false }) {
                            state.currentFolder?.let { cur ->
                                DropdownMenuItem(
                                    text = { Text(if (isMuted(cur)) "Уведомления: выкл (вкл.)" else "Уведомления: вкл (выкл.)") },
                                    onClick = { topFolderMenuOpen = false; controller.toggleFolderNotifications(cur) }
                                )
                            }
                            DropdownMenuItem(text = { Text("Автосортировка по тегам") }, onClick = { topFolderMenuOpen = false; showRulesDialog = true })
                            DropdownMenuItem(text = { Text("Отметить все прочитанными") }, onClick = { topFolderMenuOpen = false; controller.markAllReadInCurrentFolder() })
                            DropdownMenuItem(text = { Text("Создать подпапку") }, onClick = { topFolderMenuOpen = false; createFolderParent = true to state.currentFolder })
                            val canEmpty = state.currentFolder?.systemKind == SystemFolderKind.DELETED || state.currentFolder?.systemKind == SystemFolderKind.JUNK
                            if (canEmpty) {
                                DropdownMenuItem(text = { Text("Очистить папку") }, onClick = { topFolderMenuOpen = false; confirmEmptyFolder = true })
                            }
                        }
                    }
                }
            }

            OutlinedTextField(
                value = searchDraft,
                onValueChange = { searchDraft = it },
                placeholder = { Text("Поиск…") },
                singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null, modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (searchDraft.isNotEmpty() || state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchDraft = ""; controller.search("") }) {
                            Icon(Icons.Default.Close, "Очистить", modifier = Modifier.size(16.dp))
                        }
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .onPreviewKeyEvent { ev ->
                        if (ev.key == Key.Enter) {
                            controller.search(searchDraft)
                            true
                        } else false
                    }
            )

            val hasChildren = remember(state.folders, state.currentFolder?.id) {
                val curId = state.currentFolder?.id
                curId != null && state.folders.any { it.parentId == curId }
            }
            val folderTagLabel = remember(state.currentFolder?.id, state.currentFolder?.displayName, state.settings.tagSortRules) {
                val curId = state.currentFolder?.id
                state.settings.tagSortRules.firstOrNull { it.targetFolderId == curId }?.pathSegments()?.lastOrNull()
                    ?: state.currentFolder?.displayName.orEmpty()
            }

            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (state.searchQuery.isNotBlank()) {
                    FilterChip(selected = state.searchAllFolders, onClick = { controller.setSearchAllFolders(!state.searchAllFolders) }, label = { Text("Во всех папках") })
                }
                MailFilter.entries.forEach { f ->
                    val isAll = f == MailFilter.ALL
                    FilterChip(
                        selected = if (isAll && hasChildren) state.filter == f && state.includeSubfoldersInFolder else state.filter == f,
                        onClick = {
                            if (isAll && !state.includeSubfoldersInFolder && state.filter == MailFilter.ALL) {
                                controller.setIncludeSubfolders(true)
                            } else {
                                controller.setFilter(f)
                            }
                        },
                        label = { Text(f.labelRu) }
                    )
                    if (isAll && hasChildren) {
                        FilterChip(
                            selected = !state.includeSubfoldersInFolder,
                            onClick = { controller.setIncludeSubfolders(!state.includeSubfoldersInFolder) },
                            label = { Text("Только с «$folderTagLabel» тегом") }
                        )
                    }
                }
            }

            if (state.isLoading || state.isRefreshing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            if (state.messages.isEmpty() && !state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (state.searchQuery.isBlank() && state.filter == MailFilter.ALL) "В папке нет писем" else "Ничего не найдено",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(state.messages, key = { it.id }) { item ->
                        val skey = item.senderEmail.trim().lowercase()
                        val displayName = resolveDisplayName(item.senderName, item.senderEmail, profiles[skey], knownNames)
                        val isOpen = state.rightPane is DesktopRightPane.MessageDetail && state.selectedMessage?.id == item.id
                        DesktopEmailListItem(
                            item = item,
                            displaySender = displayName,
                            photo = avatars[skey],
                            selected = isOpen,
                            onRequestAvatar = controller::requestAvatar,
                            onClick = { controller.openMessage(item) },
                            onToggleFlag = { controller.toggleFlagSummary(item) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    if (!state.reachedEnd) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                OutlinedButton(onClick = controller::loadMore, enabled = !state.isLoading) { Text("Загрузить ещё") }
                            }
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))

        // --- 3. Right Pane ---
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            when (val pane = state.rightPane) {
                DesktopRightPane.Empty -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Выберите письмо для чтения", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                DesktopRightPane.MessageDetail -> {
                    val detail = state.selectedMessage
                    if (detail != null) {
                        val isDark = when (state.settings.themeMode) {
                            DesktopThemeMode.SYSTEM -> isSystemInDarkTheme()
                            DesktopThemeMode.LIGHT -> false
                            DesktopThemeMode.DARK -> true
                        }
                        DesktopMessageDetailPane(
                            detail = detail,
                            currentFolder = state.currentFolder,
                            avatars = avatars,
                            profiles = profiles,
                            knownNames = knownNames,
                            mutedSenders = state.settings.mutedSenders,
                            darkTheme = isDark,
                            defaultLightBody = state.settings.alwaysLightEmailBody,
                            onRequestAvatar = controller::requestAvatar,
                            onRequestProfile = controller::requestPersonProfile,
                            onToggleSenderMuted = controller::toggleSenderNotifications,
                            onComposeTo = { e, n -> controller.openCompose(prefillTo = e, prefillName = n) },
                            onToggleRead = { controller.toggleReadDetail(detail) },
                            onToggleFlag = { controller.toggleFlagDetail(detail) },
                            onReply = { controller.openCompose(replyTo = detail) },
                            onReplyAll = { controller.openCompose(replyTo = detail, replyAll = true) },
                            onForward = { controller.openCompose(replyTo = detail, isForward = true) },
                            onMove = { moveMessageTarget = detail },
                            onToggleSpam = { controller.toggleSpamDetail(detail) },
                            onDelete = { controller.deleteDetail(detail) },
                            onDownloadAttachment = controller::downloadAttachment,
                            onOpenUrl = controller::openExternalUrl
                        )
                    }
                }
                is DesktopRightPane.Compose -> {
                    DesktopComposePane(
                        currentUserEmail = state.credentials?.email.orEmpty(),
                        replyTo = pane.replyTo,
                        replyAll = pane.replyAll,
                        isForward = pane.isForward,
                        draft = pane.draft,
                        suggestions = state.directorySuggestions,
                        profiles = profiles,
                        knownNames = knownNames,
                        tagTemplates = state.settings.tagTemplates,
                        isSending = state.isSending,
                        onCancel = controller::closeRightPane,
                        onSearchDirectory = controller::searchDirectory,
                        onClearSuggestions = controller::clearDirectorySuggestions,
                        onAddTagTemplate = controller::addTagTemplate,
                        onSubmit = controller::saveOrSendEmail
                    )
                }
                DesktopRightPane.Contacts -> DesktopContactsPane(
                    contacts = state.contacts,
                    onClose = controller::closeRightPane,
                    onAddContact = controller::addContact,
                    onComposeTo = { e, n -> controller.openCompose(prefillTo = e, prefillName = n) }
                )
                DesktopRightPane.Calendar -> DesktopCalendarPane(
                    events = state.calendarEvents,
                    onClose = controller::closeRightPane,
                    onRefresh = controller::openCalendar,
                    onCreateEvent = controller::createCalendarEvent
                )
                DesktopRightPane.Settings -> DesktopSettingsPane(
                    state = state,
                    onClose = controller::closeRightPane,
                    onSetThemeMode = controller::setThemeMode,
                    onSetAlwaysLightBody = controller::setAlwaysLightEmailBody,
                    onSetNotificationsEnabled = controller::setNotificationsEnabled,
                    onUnmuteFolder = controller::unmuteFolderById,
                    onUnmuteSender = controller::toggleSenderNotifications,
                    onAddTagTemplate = controller::addTagTemplate,
                    onUpdateTagTemplate = controller::updateTagTemplate,
                    onRemoveTagTemplate = controller::removeTagTemplate,
                    onSaveTagSortRule = controller::saveTagSortRule,
                    onRemoveTagSortRule = controller::removeTagSortRule,
                    onDeployTagHierarchy = controller::deployTagHierarchyToFolders,
                    onSetUpdateChannel = controller::setUpdateChannel,
                    onCheckUpdates = { controller.checkForUpdates(manual = true) }
                )
            }
        }
    }
}

@Composable
private fun SidebarNavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SidebarFolderRow(
    folder: MailFolder,
    selected: Boolean,
    muted: Boolean,
    onSelect: () -> Unit,
    onToggleMute: () -> Unit,
    onAutoSort: () -> Unit,
    onCreateSub: () -> Unit,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = (folder.depth * 14).dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onSelect)
            .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(folderIcon(folder), null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(folder.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (muted) {
            Icon(Icons.Default.NotificationsOff, null, modifier = Modifier.size(14.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        if (folder.totalCount > 0 || folder.unreadCount > 0) {
            val primary = MaterialTheme.colorScheme.primary
            val normal = MaterialTheme.colorScheme.onSurfaceVariant
            val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
            Text(
                text = buildAnnotatedString {
                    withStyle(SpanStyle(color = if (folder.unreadCount > 0) primary else normal, fontWeight = if (folder.unreadCount > 0) FontWeight.SemiBold else FontWeight.Normal)) {
                        append(folder.unreadCount.toString())
                    }
                    withStyle(SpanStyle(color = dim)) { append("/${folder.totalCount}") }
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Default.MoreVert, null, modifier = Modifier.size(15.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(if (muted) "Уведомления: выкл (вкл.)" else "Уведомления: вкл (выкл.)") }, onClick = { menuOpen = false; onToggleMute() })
                DropdownMenuItem(text = { Text("Автосортировка по тегу…") }, onClick = { menuOpen = false; onAutoSort() })
                DropdownMenuItem(text = { Text("Создать подпапку") }, onClick = { menuOpen = false; onCreateSub() })
                if (onRename != null) DropdownMenuItem(text = { Text("Переименовать") }, onClick = { menuOpen = false; onRename() })
                if (onDelete != null) DropdownMenuItem(text = { Text("Удалить") }, onClick = { menuOpen = false; onDelete() })
            }
        }
    }
}

@Composable
private fun DesktopEmailListItem(
    item: EmailSummary,
    displaySender: String,
    photo: ImageBitmap?,
    selected: Boolean,
    onRequestAvatar: (String) -> Unit,
    onClick: () -> Unit,
    onToggleFlag: () -> Unit
) {
    val weight = if (item.isRead) FontWeight.Normal else FontWeight.Bold
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier.fillMaxWidth().background(bg).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        DesktopAvatar(displaySender, item.senderEmail, photo, 36.dp, onRequestAvatar)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (!item.isRead) {
                    Box(modifier = Modifier.padding(end = 6.dp).size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                }
                Text(displaySender, style = MaterialTheme.typography.titleSmall, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (item.hasAttachments) {
                    Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(14.dp).padding(end = 2.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(formatMailDate(item.receivedAt), style = MaterialTheme.typography.labelSmall, color = if (item.isRead) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary)
            }
            val (hierarchies, cleanSubject) = remember(item.subject) { extractSubjectTagHierarchies(item.subject) }
            if (hierarchies.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 2.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    hierarchies.forEach { h ->
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
                            Text(h.joinToString(" › "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(cleanSubject.ifBlank { item.subject }, style = MaterialTheme.typography.bodyMedium, fontWeight = weight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (item.preview.isNotBlank()) {
                        Text(item.preview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onToggleFlag, modifier = Modifier.size(28.dp)) {
                    Icon(if (item.isFlagged) Icons.Default.Star else Icons.Default.StarBorder, null, tint = if (item.isFlagged) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun DesktopMessageDetailPane(
    detail: EmailDetail,
    currentFolder: MailFolder?,
    avatars: Map<String, ImageBitmap?>,
    profiles: Map<String, PersonProfile?>,
    knownNames: Map<String, String>,
    mutedSenders: Set<String>,
    darkTheme: Boolean,
    defaultLightBody: Boolean,
    onRequestAvatar: (String) -> Unit,
    onRequestProfile: (String, String) -> Unit,
    onToggleSenderMuted: (String) -> Unit,
    onComposeTo: (String, String) -> Unit,
    onToggleRead: () -> Unit,
    onToggleFlag: () -> Unit,
    onReply: () -> Unit,
    onReplyAll: () -> Unit,
    onForward: () -> Unit,
    onMove: () -> Unit,
    onToggleSpam: () -> Unit,
    onDelete: () -> Unit,
    onDownloadAttachment: (ru.nstu.mail.data.AttachmentInfo, Boolean) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    var forceLight by remember(detail.id, defaultLightBody) { mutableStateOf(defaultLightBody) }
    var confirmDelete by remember { mutableStateOf(false) }
    var selectedPerson by remember { mutableStateOf<EmailParticipant?>(null) }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить письмо?") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Отмена") } }
        )
    }

    selectedPerson?.let { person ->
        val pkey = person.email.trim().lowercase()
        val prof = profiles[pkey]
        val fullName = resolveDisplayName(person.name, person.email, prof, knownNames)
        val fromMuted = person.email.trim().lowercase() in mutedSenders
        AlertDialog(
            onDismissRequest = { selectedPerson = null },
            title = { Text(fullName) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (person.email.isNotBlank()) Text(person.email, style = MaterialTheme.typography.bodySmall)
                    prof?.jobTitle?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    prof?.department?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (person.email.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = { onToggleSenderMuted(person.email) }) {
                            Icon(if (fromMuted) Icons.Default.Notifications else Icons.Default.NotificationsOff, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (fromMuted) "Включить уведомления от отправителя" else "Отключить уведомления от отправителя")
                        }
                    }
                }
            },
            confirmButton = {
                if (person.email.isNotBlank()) {
                    TextButton(onClick = { selectedPerson = null; onComposeTo(person.email, fullName) }) { Text("Написать") }
                }
            },
            dismissButton = { TextButton(onClick = { selectedPerson = null }) { Text("Закрыть") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                IconButton(onClick = onReply) { Icon(Icons.AutoMirrored.Filled.Reply, "Ответить") }
                IconButton(onClick = onReplyAll) { Icon(Icons.AutoMirrored.Filled.ReplyAll, "Ответить всем") }
                IconButton(onClick = onForward) { Icon(Icons.AutoMirrored.Filled.Forward, "Переслать") }
                IconButton(onClick = onToggleRead) { Icon(if (detail.isRead) Icons.Default.MarkEmailUnread else Icons.Default.MarkEmailRead, "Статус прочтения") }
                IconButton(onClick = onToggleFlag) { Icon(if (detail.isFlagged) Icons.Default.Star else Icons.Default.StarBorder, "Флажок") }
                IconButton(onClick = onMove) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Переместить") }
                IconButton(onClick = onToggleSpam) { Icon(if (currentFolder?.systemKind == SystemFolderKind.JUNK) Icons.Default.Inbox else Icons.Default.Report, "Спам") }
                if (darkTheme) {
                    IconButton(onClick = { forceLight = !forceLight }) { Icon(if (forceLight) Icons.Default.DarkMode else Icons.Default.LightMode, "Фон письма") }
                }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Удалить") }
            }
            Text(formatMailDate(detail.receivedAt, detailed = true), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider()
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            val (hierarchies, cleanSubject) = remember(detail.subject) { extractSubjectTagHierarchies(detail.subject) }
            if (hierarchies.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    hierarchies.forEach { h ->
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
                            Text(h.joinToString(" › "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                }
            }
            Text(cleanSubject.ifBlank { detail.subject }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.participants().forEach { p ->
                    val k = p.email.trim().lowercase()
                    val prof = profiles[k]
                    LaunchedEffect(p.email, knownNames[k]) {
                        if (needsNameResolution(p.name, p.email) && knownNames[k].isNullOrBlank() && prof == null) {
                            onRequestProfile(p.name, p.email)
                        }
                    }
                    val full = resolveDisplayName(p.name, p.email, prof, knownNames)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.clickable { selectedPerson = p }
                    ) {
                        Row(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            DesktopAvatar(full, p.email, avatars[k], 24.dp, onRequestAvatar)
                            Spacer(Modifier.width(6.dp))
                            Text("${p.role.labelRu}: ${formatShortPersonName(full, p.email)}", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            val visibleAtts = detail.attachments.filterNot { it.isInline }
            if (visibleAtts.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    visibleAtts.forEach { att ->
                        AssistChip(
                            onClick = { onDownloadAttachment(att, true) },
                            label = { Text(att.name) },
                            leadingIcon = { Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(16.dp)) },
                            trailingIcon = {
                                IconButton(onClick = { onDownloadAttachment(att, false) }, modifier = Modifier.size(20.dp)) {
                                    Icon(Icons.Default.Download, "Сохранить", modifier = Modifier.size(15.dp))
                                }
                            }
                        )
                    }
                }
            }
        }
        HorizontalDivider()
        val effectiveDark = darkTheme && !forceLight
        val bgHex = if (effectiveDark) "#101512" else "#FFFFFF"
        val fgHex = if (effectiveDark) "#E0E4E0" else "#181D1A"
        val linkHex = if (effectiveDark) "#5CDAA5" else "#006B47"
        val bgColor = if (effectiveDark) Color(0xFF101512) else Color.White
        val fgColor = if (effectiveDark) Color(0xFFE0E4E0) else Color(0xFF181D1A)
        val htmlDoc = remember(detail.id, detail.body, detail.isHtml, effectiveDark) {
            EmailHtmlSanitizer.renderEmailDocument(
                body = detail.body,
                isHtml = detail.isHtml,
                darkMode = effectiveDark,
                bgHex = bgHex,
                textHex = fgHex,
                linkHex = linkHex
            )
        }
        SwingPanel(
            background = bgColor,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = {
                val editor = JEditorPane().apply {
                    contentType = "text/html"
                    isEditable = false
                    putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                    addHyperlinkListener { ev ->
                        if (ev.eventType == HyperlinkEvent.EventType.ACTIVATED) {
                            ev.url?.toString()?.let(onOpenUrl)
                        }
                    }
                }
                val scroll = JScrollPane(editor).apply { border = null }
                JPanel(BorderLayout()).apply {
                    add(scroll, BorderLayout.CENTER)
                    putClientProperty("editor", editor)
                }
            },
            update = { panel ->
                val editor = panel.getClientProperty("editor") as? JEditorPane ?: return@SwingPanel
                editor.background = java.awt.Color(bgColor.toArgb())
                editor.foreground = java.awt.Color(fgColor.toArgb())
                if (editor.getClientProperty("lastDoc") != htmlDoc) {
                    editor.putClientProperty("lastDoc", htmlDoc)
                    editor.text = htmlDoc
                    SwingUtilities.invokeLater { editor.caretPosition = 0 }
                }
            }
        )
    }
}

@Composable
private fun DesktopComposePane(
    currentUserEmail: String,
    replyTo: EmailDetail?,
    replyAll: Boolean,
    isForward: Boolean,
    draft: EmailDetail?,
    suggestions: List<ContactEntry>,
    profiles: Map<String, PersonProfile?>,
    knownNames: Map<String, String>,
    tagTemplates: List<String>,
    isSending: Boolean,
    onCancel: () -> Unit,
    onSearchDirectory: (String) -> Unit,
    onClearSuggestions: () -> Unit,
    onAddTagTemplate: (String) -> Unit,
    onSubmit: (String?, String?, String, String, String, String, String, List<File>, Boolean) -> Unit
) {
    var toText by remember(draft?.id, replyTo?.id, replyAll, isForward) {
        mutableStateOf(
            when {
                draft != null -> draft.to.map { parseFormattedRecipient(it).second.ifBlank { it } }.joinToString(", ")
                replyTo != null && !isForward && replyAll ->
                    (listOf(replyTo.fromEmail) + replyTo.to.map { parseFormattedRecipient(it).second })
                        .filter { it.isNotBlank() && !it.equals(currentUserEmail, true) }
                        .distinctBy { it.lowercase() }
                        .joinToString(", ")
                replyTo != null && !isForward -> replyTo.fromEmail
                else -> ""
            }
        )
    }
    var ccText by remember(draft?.id, replyTo?.id, replyAll) {
        mutableStateOf(
            when {
                draft != null -> draft.cc.map { parseFormattedRecipient(it).second.ifBlank { it } }.joinToString(", ")
                replyTo != null && replyAll && !isForward ->
                    replyTo.cc.map { parseFormattedRecipient(it).second }
                        .filter { it.isNotBlank() && !it.equals(currentUserEmail, true) }
                        .joinToString(", ")
                else -> ""
            }
        )
    }
    var bccText by remember(draft?.id) {
        mutableStateOf(draft?.bcc.orEmpty().map { parseFormattedRecipient(it).second.ifBlank { it } }.joinToString(", "))
    }
    var showCc by remember { mutableStateOf(ccText.isNotBlank()) }
    var showBcc by remember { mutableStateOf(bccText.isNotBlank()) }
    val initialSubj = remember(draft?.id, replyTo?.id, isForward) {
        when {
            draft != null -> extractSubjectTagHierarchies(draft.subject)
            replyTo == null -> emptyList<List<String>>() to ""
            isForward -> emptyList<List<String>>() to (if (replyTo.subject.startsWith("Fwd:", true)) replyTo.subject else "Fwd: ${replyTo.subject}")
            else -> emptyList<List<String>>() to (if (replyTo.subject.startsWith("Re:", true)) replyTo.subject else "Re: ${replyTo.subject}")
        }
    }
    var savedHierarchies by remember(draft?.id, replyTo?.id) { mutableStateOf(initialSubj.first) }
    var activePath by remember { mutableStateOf<List<String>>(emptyList()) }
    var subject by remember(draft?.id, replyTo?.id) { mutableStateOf(initialSubj.second) }
    var body by remember(draft?.id, replyTo?.id) {
        mutableStateOf(
            when {
                draft != null -> if (draft.isHtml) Jsoup.parse(draft.body).text() else draft.body
                replyTo != null -> {
                    val plain = if (replyTo.isHtml) Jsoup.parse(replyTo.body).text() else replyTo.body
                    "\n\n--- Исходное сообщение ---\nОт: ${replyTo.from}\nДата: ${formatMailDate(replyTo.receivedAt, true)}\n\n$plain"
                }
                else -> ""
            }
        )
    }
    var attachments by remember { mutableStateOf<List<File>>(emptyList()) }
    var showSendConfirm by remember { mutableStateOf(false) }
    var showAddTagDialog by remember { mutableStateOf(false) }
    val allHierarchies = (savedHierarchies + listOfNotNull(activePath.takeIf { it.isNotEmpty() })).distinctBy { formatTagPath(it).lowercase() }

    fun appendRecipient(email: String) {
        val current = splitEmails(toText).filterNot { it.equals(email, true) }
        val base = if (',' in toText) toText.substringBeforeLast(',') + ", " else ""
        toText = (base + (current.dropLast(if (',' in toText) 0 else 1) + email).distinct().joinToString(", "))
            .ifBlank { email }
        onClearSuggestions()
    }

    if (showSendConfirm) {
        val subjFinal = formatSubjectWithTagHierarchies(subject, allHierarchies).ifBlank { "(Без темы)" }
        val formattedTo = splitEmails(toText).joinToString(", ") { e ->
            val name = resolveDisplayName("", e, profiles[e.lowercase()], knownNames)
            if (name.isNotBlank() && !name.equals(e, true)) "$name <$e>" else e
        }
        AlertDialog(
            onDismissRequest = { showSendConfirm = false },
            title = { Text("Отправить письмо?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Кому: $formattedTo", fontWeight = FontWeight.Medium)
                    if (showCc && ccText.isNotBlank()) Text("Копия: $ccText", style = MaterialTheme.typography.bodySmall)
                    if (showBcc && bccText.isNotBlank()) Text("Скрытая копия: $bccText", style = MaterialTheme.typography.bodySmall)
                    Text("Тема: $subjFinal", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSendConfirm = false
                    onSubmit(draft?.id, draft?.changeKey, toText, ccText, bccText, subjFinal, body, attachments, true)
                }) { Text("Отправить") }
            },
            dismissButton = { TextButton(onClick = { showSendConfirm = false }) { Text("Отмена") } }
        )
    }

    if (showAddTagDialog) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddTagDialog = false },
            title = { Text(if (activePath.isEmpty()) "Добавить тег" else "Подтег для ${activePath.joinToString(" › ")}") },
            text = { OutlinedTextField(value = input, onValueChange = { input = it }, label = { Text("Тег или путь (через /)") }, singleLine = true) },
            confirmButton = {
                TextButton(
                    onClick = {
                        val added = parseTagPath(input)
                        if (added.isNotEmpty()) {
                            val combined = if ('/' in input && activePath.isEmpty()) added else (activePath + added).take(4)
                            onAddTagTemplate(formatTagPath(combined))
                            activePath = combined
                        }
                        showAddTagDialog = false
                    },
                    enabled = parseTagPath(input).isNotEmpty()
                ) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { showAddTagDialog = false }) { Text("Отмена") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (replyTo != null) "Ответ / Пересылка" else "Новое письмо", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val chooser = JFileChooser().apply { isMultiSelectionEnabled = true }
                    if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                        attachments = (attachments + chooser.selectedFiles.toList()).distinctBy { it.absolutePath }
                    }
                }) {
                    Icon(Icons.Default.AttachFile, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Файл")
                }
                OutlinedButton(onClick = {
                    onSubmit(draft?.id, draft?.changeKey, toText, ccText, bccText, formatSubjectWithTagHierarchies(subject, allHierarchies), body, attachments, false)
                }, enabled = !isSending) {
                    Icon(Icons.Default.Save, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Черновик")
                }
                Button(onClick = {
                    val toEmails = splitEmails(toText)
                    if (toEmails.isEmpty() || toEmails.any { '@' !in it }) {
                        onSubmit(draft?.id, draft?.changeKey, toText, ccText, bccText, subject, body, attachments, true)
                    } else {
                        showSendConfirm = true
                    }
                }, enabled = !isSending) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Отправить")
                }
                TextButton(onClick = onCancel) { Text("Закрыть") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = toText,
                onValueChange = {
                    toText = it
                    val token = it.substringAfterLast(',').substringAfterLast(';').trim()
                    onSearchDirectory(token)
                },
                label = { Text("Кому") },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            if (!showCc) TextButton(onClick = { showCc = true }) { Text("+ Копия") }
            if (!showBcc) TextButton(onClick = { showBcc = true }) { Text("+ СК") }
        }
        if (suggestions.isNotEmpty()) {
            Card {
                Column(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                    suggestions.forEach { s ->
                        Row(modifier = Modifier.fillMaxWidth().clickable { appendRecipient(s.email) }.padding(8.dp)) {
                            Text("${s.name} <${s.email}>", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (showCc) {
            OutlinedTextField(
                value = ccText,
                onValueChange = { ccText = it },
                label = { Text("Копия") },
                singleLine = true,
                trailingIcon = { IconButton(onClick = { ccText = ""; showCc = false }) { Icon(Icons.Default.Close, "Убрать") } },
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (showBcc) {
            OutlinedTextField(
                value = bccText,
                onValueChange = { bccText = it },
                label = { Text("Скрытая копия") },
                singleLine = true,
                trailingIcon = { IconButton(onClick = { bccText = ""; showBcc = false }) { Icon(Icons.Default.Close, "Убрать") } },
                modifier = Modifier.fillMaxWidth()
            )
        }
        OutlinedTextField(value = subject, onValueChange = { subject = it }, label = { Text("Тема") }, singleLine = true, modifier = Modifier.fillMaxWidth())

        if (savedHierarchies.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Ветки:", style = MaterialTheme.typography.labelMedium)
                savedHierarchies.forEachIndexed { idx, h ->
                    InputChip(selected = true, onClick = { savedHierarchies = savedHierarchies.filterIndexed { i, _ -> i != idx } }, label = { Text(h.joinToString(" › ")) }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) })
                }
            }
        }
        if (activePath.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Путь:", style = MaterialTheme.typography.labelMedium)
                activePath.forEachIndexed { idx, seg ->
                    InputChip(selected = true, onClick = { activePath = activePath.take(idx) }, label = { Text(seg) })
                }
                TextButton(onClick = { savedHierarchies = (savedHierarchies + listOf(activePath)); activePath = emptyList() }) { Text("+ Ещё ветка") }
                TextButton(onClick = { activePath = emptyList() }) { Text("Сброс") }
            }
        }
        if (allHierarchies.isNotEmpty()) {
            Text("В конце темы: ${formatSubjectWithTagHierarchies("", allHierarchies)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (activePath.size < 4) {
            val nextTags = childTagsForPrefix(tagTemplates, activePath)
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (activePath.isEmpty()) "Теги:" else "Подтеги:", style = MaterialTheme.typography.labelMedium)
                nextTags.forEach { child ->
                    FilterChip(selected = false, onClick = { activePath = activePath + sanitizeTagSegment(child) }, label = { Text(child) })
                }
                AssistChip(onClick = { showAddTagDialog = true }, label = { Text(if (activePath.isEmpty()) "+ Тег" else "+ Подтег") })
            }
        }
        if (attachments.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                attachments.forEach { f ->
                    InputChip(selected = false, onClick = { attachments = attachments - f }, label = { Text(f.name) }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) })
                }
            }
        }
        OutlinedTextField(
            value = body,
            onValueChange = { body = it },
            label = { Text("Текст письма") },
            minLines = 12,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun DesktopContactsPane(
    contacts: List<ContactEntry>,
    onClose: () -> Unit,
    onAddContact: (String, String) -> Unit,
    onComposeTo: (String, String) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var email by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Новый контакт") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Имя") }, singleLine = true)
                    OutlinedTextField(value = email, onValueChange = { email = it }, label = { Text("Email") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = { showAdd = false; onAddContact(name, email) }, enabled = email.contains('@')) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Отмена") } }
        )
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Контакты", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row {
                OutlinedButton(onClick = { showAdd = true }) { Text("+ Контакт") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(contacts) { c ->
                Card(modifier = Modifier.fillMaxWidth().clickable { onComposeTo(c.email, c.name) }) {
                    Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text(c.name, fontWeight = FontWeight.Medium)
                            Text(c.email, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopCalendarPane(
    events: List<CalendarEvent>,
    onClose: () -> Unit,
    onRefresh: () -> Unit,
    onCreateEvent: (String, String, String, String, String) -> Unit
) {
    var showCreate by remember { mutableStateOf(false) }
    if (showCreate) {
        var subject by remember { mutableStateOf("") }
        var location by remember { mutableStateOf("") }
        var start by remember { mutableStateOf(java.time.LocalDate.now().plusDays(1).toString() + "T09:00:00Z") }
        var end by remember { mutableStateOf(java.time.LocalDate.now().plusDays(1).toString() + "T10:00:00Z") }
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("Новое событие") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = subject, onValueChange = { subject = it }, label = { Text("Название") }, singleLine = true)
                    OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("Место") }, singleLine = true)
                    OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text("Начало ISO") }, singleLine = true)
                    OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text("Конец ISO") }, singleLine = true)
                }
            },
            confirmButton = { TextButton(onClick = { showCreate = false; onCreateEvent(subject, start, end, location, "") }, enabled = subject.isNotBlank()) { Text("Создать") } },
            dismissButton = { TextButton(onClick = { showCreate = false }) { Text("Отмена") } }
        )
    }
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Календарь (30 дней)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Row {
                OutlinedButton(onClick = { showCreate = true }) { Text("+ Событие") }
                Spacer(Modifier.width(6.dp))
                IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Обновить") }
                TextButton(onClick = onClose) { Text("Закрыть") }
            }
        }
        Spacer(Modifier.height(10.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(events, key = { it.id }) { ev ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(ev.subject, fontWeight = FontWeight.Medium)
                        Text("${formatMailDate(ev.startIso, true)} — ${formatMailDate(ev.endIso, true)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (ev.location.isNotBlank()) Text("Место: ${ev.location}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopSettingsPane(
    state: DesktopUiState,
    onClose: () -> Unit,
    onSetThemeMode: (DesktopThemeMode) -> Unit,
    onSetAlwaysLightBody: (Boolean) -> Unit,
    onSetNotificationsEnabled: (Boolean) -> Unit,
    onUnmuteFolder: (String) -> Unit,
    onUnmuteSender: (String) -> Unit,
    onAddTagTemplate: (String) -> Unit,
    onUpdateTagTemplate: (String, String) -> Unit,
    onRemoveTagTemplate: (String) -> Unit,
    onSaveTagSortRule: (String, MailFolder, Boolean) -> Unit,
    onRemoveTagSortRule: (String) -> Unit,
    onDeployTagHierarchy: (String) -> Unit,
    onSetUpdateChannel: (DesktopUpdateChannel) -> Unit,
    onCheckUpdates: () -> Unit
) {
    val s = state.settings
    var ruleTargetTag by remember { mutableStateOf<String?>(null) }
    var deployTarget by remember { mutableStateOf<String?>(null) }
    var editingTpl by remember { mutableStateOf<Pair<String?, String>?>(null) }

    ruleTargetTag?.let { t ->
        if (state.folders.isNotEmpty()) {
            AddTagRuleDialog(
                folders = state.folders,
                tagTemplates = s.tagTemplates,
                rules = s.tagSortRules,
                fixedFolder = null,
                initialTag = t,
                onDismiss = { ruleTargetTag = null },
                onConfirm = { tag, f, autoSub -> ruleTargetTag = null; onSaveTagSortRule(tag, f, autoSub) },
                onRemoveRule = { tag -> ruleTargetTag = null; onRemoveTagSortRule(tag) }
            )
        }
    }

    deployTarget?.let { root ->
        val branches = remember(root, s.tagTemplates) {
            s.tagTemplates.filter { it.equals(root, true) || it.startsWith("$root/", true) }
                .ifEmpty { listOf(root) }
                .sortedWith(compareBy<String>({ parseTagPath(it).size }, { it.lowercase() }))
        }
        AlertDialog(
            onDismissRequest = { deployTarget = null },
            title = { Text("Перенести иерархию в папки?") },
            text = {
                Column {
                    Text("Будут созданы недостающие папки и привязаны правила автосортировки:", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    val base = parseTagPath(root).size
                    branches.forEach { p ->
                        val segs = parseTagPath(p)
                        val rel = (segs.size - base).coerceAtLeast(0)
                        Text(if (rel > 0) "↳ ${segs.last()}" else p, modifier = Modifier.padding(start = (rel * 12).dp, top = 2.dp, bottom = 2.dp))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { deployTarget = null; onDeployTagHierarchy(root) }) { Text("Перенести") } },
            dismissButton = { TextButton(onClick = { deployTarget = null }) { Text("Отмена") } }
        )
    }

    editingTpl?.let { (old, init) ->
        var text by remember(old, init) { mutableStateOf(init) }
        AlertDialog(
            onDismissRequest = { editingTpl = null },
            title = { Text(if (old != null) "Редактировать шаблон" else "Новый шаблон тега") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it.take(100) }, label = { Text("Путь тега через /") }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = {
                    if (old != null) onUpdateTagTemplate(old, text) else onAddTagTemplate(text)
                    editingTpl = null
                }, enabled = parseTagPath(text).isNotEmpty()) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editingTpl = null }) { Text("Отмена") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = onClose) { Text("Закрыть") }
        }
        Text("Оформление", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DesktopThemeMode.entries.forEach { mode ->
                FilterChip(selected = s.themeMode == mode, onClick = { onSetThemeMode(mode) }, label = { Text(mode.labelRu) })
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Светлый фон писем")
                Text("Показывать текст писем на светлом фоне даже в тёмной теме", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = s.alwaysLightEmailBody, onCheckedChange = onSetAlwaysLightBody)
        }
        HorizontalDivider()
        Text("Уведомления", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Системные уведомления о новых письмах")
            Switch(checked = s.notificationsEnabled, onCheckedChange = onSetNotificationsEnabled)
        }
        val mutedFoldersDisplay = state.folders.filter { it.id in s.mutedFolderIds || (it.systemKind == SystemFolderKind.INBOX && "inbox" in s.mutedFolderIds) }
        if (mutedFoldersDisplay.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                mutedFoldersDisplay.forEach { f ->
                    InputChip(selected = true, onClick = { onUnmuteFolder(f.id) }, label = { Text("Папка: ${f.displayName}") }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) })
                }
            }
        }
        if (s.mutedSenders.isNotEmpty()) {
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                s.mutedSenders.sorted().forEach { email ->
                    InputChip(selected = true, onClick = { onUnmuteSender(email) }, label = { Text(email) }, trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(14.dp)) })
                }
            }
        }
        HorizontalDivider()
        Text("Шаблоны тегов и автосортировка", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        s.tagTemplates.forEach { tpl ->
            val segs = parseTagPath(tpl)
            val depth = (segs.size - 1).coerceAtLeast(0)
            val directRule = s.tagSortRules.firstOrNull { it.tag.equals(tpl, true) }
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = (depth * 14).dp, top = 2.dp, bottom = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(if (depth > 0) "↳ ${segs.last()}" else segs.lastOrNull().orEmpty(), fontWeight = if (depth == 0) FontWeight.Medium else FontWeight.Normal)
                    Text(
                        text = if (directRule != null) "→ ${directRule.targetFolderName}" else segs.joinToString(", ", "[", "]") { "\"$it\"" },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (directRule != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row {
                    if (state.folders.isNotEmpty()) {
                        IconButton(onClick = { deployTarget = tpl }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.CreateNewFolder, "Перенести структуру", Modifier.size(16.dp)) }
                        IconButton(onClick = { ruleTargetTag = tpl }, modifier = Modifier.size(28.dp)) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, "Автосортировка", Modifier.size(16.dp)) }
                    }
                    if (segs.size < 4) IconButton(onClick = { editingTpl = null to "$tpl/" }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Add, "Подтег", Modifier.size(16.dp)) }
                    IconButton(onClick = { editingTpl = tpl to tpl }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Edit, "Изменить", Modifier.size(16.dp)) }
                    IconButton(onClick = { onRemoveTagTemplate(tpl) }, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "Удалить", Modifier.size(16.dp)) }
                }
            }
        }
        OutlinedButton(onClick = { editingTpl = null to "" }) { Text("+ Добавить шаблон") }
        HorizontalDivider()
        Text("Обновления", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        DesktopUpdateChannel.entries.forEach { ch ->
            Row(modifier = Modifier.fillMaxWidth().clickable { onSetUpdateChannel(ch) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.updateChannel == ch, onClick = { onSetUpdateChannel(ch) })
                Column {
                    Text(ch.labelRu)
                    Text(ch.descriptionRu, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("Версия: v${DesktopBuildInfo.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onCheckUpdates, enabled = !state.isCheckingUpdate) { Text("Проверить обновления") }
    }
}

@Composable
private fun TagRulesTreeDialog(
    folders: List<MailFolder>,
    rules: List<TagSortRule>,
    tagTemplates: List<String>,
    mutedFolderIds: Set<String>,
    onSaveRule: (String, MailFolder, Boolean) -> Unit,
    onRemoveRule: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var editTag by remember { mutableStateOf<String?>(null) }
    editTag?.let { t ->
        AddTagRuleDialog(
            folders = folders,
            tagTemplates = tagTemplates,
            rules = rules,
            fixedFolder = null,
            initialTag = t.ifBlank { null },
            onDismiss = { editTag = null },
            onConfirm = { tag, f, autoSub -> editTag = null; onSaveRule(tag, f, autoSub) },
            onRemoveRule = { tag -> editTag = null; onRemoveRule(tag) }
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Автосортировка по тегам") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                tagTemplates.forEach { tpl ->
                    val segs = parseTagPath(tpl)
                    val depth = (segs.size - 1).coerceAtLeast(0)
                    val rule = rules.firstOrNull { it.tag.equals(tpl, true) }
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { editTag = tpl }.padding(start = (depth * 12).dp, top = 6.dp, bottom = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(if (depth > 0) "↳ ${segs.last()}" else segs.lastOrNull().orEmpty())
                            Text(
                                text = if (rule != null) "→ ${rule.targetFolderName}" + (if (rule.targetFolderId in mutedFolderIds) " · ув. выкл." else "") else "Нажмите для настройки",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (rule != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (rule != null) {
                            IconButton(onClick = { onRemoveRule(rule.tag) }, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.Close, null, Modifier.size(15.dp)) }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                }
            }
        },
        confirmButton = { TextButton(onClick = { editTag = "" }) { Text("+ Свой тег") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

@Composable
private fun AddTagRuleDialog(
    folders: List<MailFolder>,
    tagTemplates: List<String>,
    rules: List<TagSortRule>,
    fixedFolder: MailFolder?,
    initialTag: String?,
    onDismiss: () -> Unit,
    onConfirm: (String, MailFolder, Boolean) -> Unit,
    onRemoveRule: ((String) -> Unit)?
) {
    val allTags = remember(tagTemplates) {
        (DEFAULT_TAG_TEMPLATES + tagTemplates).map { formatTagPath(parseTagPath(it)) }.filter { it.isNotEmpty() }.distinctBy { it.lowercase() }
    }
    val startTag = initialTag?.takeIf { it.isNotBlank() } ?: allTags.firstOrNull().orEmpty()
    val startRule = remember(startTag, rules) { rules.firstOrNull { it.tag.equals(startTag, true) } }
    var tagText by remember(startTag) { mutableStateOf(startTag) }
    var autoSub by remember(startRule) { mutableStateOf(startRule?.autoCreateSubfolders ?: true) }
    var selectedFolder by remember(startRule, fixedFolder, folders) {
        mutableStateOf(
            fixedFolder
                ?: startRule?.let { r -> folders.firstOrNull { it.id == r.targetFolderId } }
                ?: folders.firstOrNull { !it.isSystem }
                ?: folders.firstOrNull()
        )
    }
    val curRule = rules.firstOrNull { it.tag.equals(formatTagPath(parseTagPath(tagText)), true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(fixedFolder?.let { "Автосортировка в «${it.displayName}»" } ?: "Правило автосортировки") },
        text = {
            Column(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = tagText, onValueChange = { tagText = it.take(120) }, label = { Text("Тег или путь (напр. 1/2)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                allTags.forEach { tpl ->
                    val segs = parseTagPath(tpl)
                    val depth = (segs.size - 1).coerceAtLeast(0)
                    val picked = tagText.equals(tpl, true)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { tagText = tpl }.padding(start = (4 + depth * 12).dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (picked) {
                            Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(if (depth > 0) "↳ ${segs.last()}" else segs.lastOrNull().orEmpty(), style = MaterialTheme.typography.bodySmall, fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth().clickable { autoSub = !autoSub }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Ветвление подпапок", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = autoSub, onCheckedChange = { autoSub = it })
                }
                if (fixedFolder == null) {
                    Spacer(Modifier.height(8.dp))
                    Text("Целевая папка:", style = MaterialTheme.typography.labelMedium)
                    folders.forEach { f ->
                        val picked = selectedFolder?.id == f.id
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { selectedFolder = f }.padding(start = (4 + f.depth * 14).dp, top = 5.dp, bottom = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(if (picked) Icons.Default.CheckCircle else folderIcon(f), null, tint = if (picked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(f.displayName, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row {
                if (onRemoveRule != null && curRule != null) {
                    TextButton(onClick = { onRemoveRule(curRule.tag) }) { Text("Сбросить", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(
                    onClick = { (fixedFolder ?: selectedFolder)?.let { onConfirm(tagText.trim(), it, autoSub) } },
                    enabled = parseTagPath(tagText).isNotEmpty() && (fixedFolder != null || selectedFolder != null)
                ) { Text("Сохранить") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun FolderNameDialog(title: String, initialName: String, confirmLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Название") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } }
    )
}

private val AVATAR_COLORS = listOf(
    Color(0xFF006B47), Color(0xFF1E6091), Color(0xFF6A4C93), Color(0xFFB55400),
    Color(0xFF2D6A4F), Color(0xFF8C2F39), Color(0xFF3A5A40), Color(0xFF4361EE)
)

@Composable
private fun DesktopAvatar(
    name: String,
    email: String,
    photo: ImageBitmap?,
    size: Dp,
    onRequestAvatar: (String) -> Unit
) {
    val normEmail = email.trim().lowercase()
    LaunchedEffect(normEmail) { if ('@' in normEmail) onRequestAvatar(normEmail) }
    val bgColor = remember(normEmail, name) {
        val k = normEmail.ifBlank { name.lowercase() }
        AVATAR_COLORS[avatarPaletteIndex(k, AVATAR_COLORS.size)]
    }
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        if (photo != null) {
            Image(bitmap = photo, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(extractInitials(name, email), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (size.value * 0.38f).sp)
        }
    }
}

private fun folderIcon(folder: MailFolder) = when (folder.systemKind) {
    SystemFolderKind.INBOX -> Icons.Default.Inbox
    SystemFolderKind.SENT -> Icons.AutoMirrored.Filled.Send
    SystemFolderKind.DRAFTS -> Icons.Default.Drafts
    SystemFolderKind.OUTBOX -> Icons.Default.Outbox
    SystemFolderKind.DELETED -> Icons.Default.Delete
    SystemFolderKind.JUNK -> Icons.Default.Report
    null -> if (folder.displayName.equals(UNSORTED_FOLDER_NAME, true)) Icons.Default.MoveToInbox else Icons.Default.Folder
}
