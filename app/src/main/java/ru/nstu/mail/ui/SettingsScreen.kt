package ru.nstu.mail.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.nstu.mail.BuildConfig
import ru.nstu.mail.data.MailFolder
import ru.nstu.mail.data.SwipeAction
import ru.nstu.mail.data.SystemFolderKind
import ru.nstu.mail.data.TagSortRule
import ru.nstu.mail.data.UpdateChannel
import ru.nstu.mail.data.parseTagPath
import ru.nstu.mail.notify.MailNotificationHelper
import ru.nstu.mail.notify.NOTIFICATION_INTERVALS_MINUTES
import ru.nstu.mail.notify.NotificationMode
import ru.nstu.mail.notify.PUSH_SERVICE_CHANNEL_ID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: AppThemeMode,
    alwaysLightEmailBody: Boolean,
    notificationsEnabled: Boolean,
    notificationMode: NotificationMode,
    notificationIntervalMinutes: Int,
    folders: List<MailFolder>,
    mutedFolderIds: Set<String>,
    mutedSenders: Set<String>,
    tagTemplates: List<String>,
    tagSortRules: List<TagSortRule>,
    swipeRightAction: SwipeAction,
    swipeLeftAction: SwipeAction,
    updateChannel: UpdateChannel,
    isCheckingUpdate: Boolean,
    toastMessage: String?,
    onBack: () -> Unit,
    onSetThemeMode: (AppThemeMode) -> Unit,
    onSetAlwaysLightEmailBody: (Boolean) -> Unit,
    onSetNotificationsEnabled: (Boolean) -> Unit,
    onSetNotificationMode: (NotificationMode) -> Unit,
    onSetNotificationInterval: (Int) -> Unit,
    onCheckNewMailNow: () -> Unit,
    onNotificationPermissionResult: (Boolean) -> Unit,
    onUnmuteFolder: (String) -> Unit,
    onUnmuteSender: (String) -> Unit,
    onAddTagTemplate: (String) -> Unit,
    onUpdateTagTemplate: (oldPath: String, newPath: String) -> Unit,
    onRemoveTagTemplate: (String) -> Unit,
    onSaveTagSortRule: (tag: String, folder: MailFolder, autoCreateSubfolders: Boolean) -> Unit,
    onRemoveTagSortRule: (String) -> Unit,
    onSetSwipeRightAction: (SwipeAction) -> Unit,
    onSetSwipeLeftAction: (SwipeAction) -> Unit,
    onSetUpdateChannel: (UpdateChannel) -> Unit,
    onCheckUpdates: () -> Unit,
    onClearToast: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val snackbarHostState = remember { SnackbarHostState() }
    var hasSystemPerm by remember { mutableStateOf(MailNotificationHelper.hasSystemPermission(context)) }
    var ignoringBatteryOpt by remember {
        mutableStateOf(MailNotificationHelper.isIgnoringBatteryOptimizations(context))
    }
    var ruleTargetTag by remember { mutableStateOf<String?>(null) }
    var editingTemplateTarget by remember { mutableStateOf<Pair<String?, String>?>(null) }
    var pickingSwipeRight by remember { mutableStateOf<Boolean?>(null) }

    ruleTargetTag?.let { targetTag ->
        if (folders.isNotEmpty()) {
            AddTagRuleDialog(
                folders = folders,
                tagTemplates = tagTemplates,
                rules = tagSortRules,
                fixedFolder = null,
                initialTag = targetTag,
                onDismiss = { ruleTargetTag = null },
                onConfirm = { tag, folder, autoSub ->
                    ruleTargetTag = null
                    onSaveTagSortRule(tag, folder, autoSub)
                },
                onRemoveRule = { tag ->
                    ruleTargetTag = null
                    onRemoveTagSortRule(tag)
                }
            )
        }
    }

    editingTemplateTarget?.let { (oldPath, initialText) ->
        var text by remember(oldPath, initialText) { mutableStateOf(initialText) }
        AlertDialog(
            onDismissRequest = { editingTemplateTarget = null },
            title = {
                Text(if (oldPath != null) "Редактировать шаблон" else "Новый шаблон тега")
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.take(80) },
                        label = { Text("Путь тега через / (напр. 1/2)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val segs = parseTagPath(text)
                    if (segs.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        val preview = segs.joinToString(", ", "[", "]") { "\"$it\"" }
                        Text(
                            text = "В теме письма: $preview",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (oldPath != null) {
                            onUpdateTagTemplate(oldPath, text)
                        } else {
                            onAddTagTemplate(text)
                        }
                        editingTemplateTarget = null
                    },
                    enabled = parseTagPath(text).isNotEmpty()
                ) { Text("Сохранить") }
            },
            dismissButton = {
                TextButton(onClick = { editingTemplateTarget = null }) { Text("Отмена") }
            }
        )
    }

    pickingSwipeRight?.let { isRight ->
        val current = if (isRight) swipeRightAction else swipeLeftAction
        AlertDialog(
            onDismissRequest = { pickingSwipeRight = null },
            title = { Text(if (isRight) "Свайп вправо" else "Свайп влево") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    SwipeAction.entries.forEach { act ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pickingSwipeRight = null
                                    if (isRight) onSetSwipeRightAction(act)
                                    else onSetSwipeLeftAction(act)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = act == current,
                                onClick = {
                                    pickingSwipeRight = null
                                    if (isRight) onSetSwipeRightAction(act)
                                    else onSetSwipeLeftAction(act)
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(act.labelRu, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { pickingSwipeRight = null }) { Text("Закрыть") }
            }
        )
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasSystemPerm = MailNotificationHelper.hasSystemPermission(context)
                ignoringBatteryOpt = MailNotificationHelper.isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasSystemPerm = granted
        onNotificationPermissionResult(granted)
    }

    LaunchedEffect(toastMessage) {
        val msg = toastMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onClearToast()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                text = "Оформление",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = themeMode == mode,
                        onClick = { onSetThemeMode(mode) },
                        label = { Text(mode.labelRu) }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Светлый фон писем",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Показывать текст писем на светлом фоне даже в тёмной теме",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = alwaysLightEmailBody,
                    onCheckedChange = onSetAlwaysLightEmailBody
                )
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                text = "Уведомления",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Уведомления о новых письмах",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = when {
                            !notificationsEnabled -> "Выключены"
                            !hasSystemPerm -> "Нет разрешения системы на показ уведомлений"
                            else -> "Фоновая проверка входящих активна"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (notificationsEnabled && !hasSystemPerm) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
                Switch(
                    checked = notificationsEnabled && hasSystemPerm,
                    onCheckedChange = { wantEnable ->
                        if (wantEnable) {
                            val grantedNow = MailNotificationHelper.hasSystemPermission(context)
                            hasSystemPerm = grantedNow
                            if (!grantedNow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                onSetNotificationsEnabled(true)
                            }
                        } else {
                            onSetNotificationsEnabled(false)
                        }
                    }
                )
            }

            if (notificationsEnabled && hasSystemPerm) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Режим доставки",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                NotificationMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSetNotificationMode(mode) }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = notificationMode == mode,
                            onClick = { onSetNotificationMode(mode) }
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(mode.labelRu, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                mode.descriptionRu,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (notificationMode == NotificationMode.PUSH) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Системное уведомление «ожидание новых писем» в шторке можно скрыть в настройках канала Android.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    MailNotificationHelper.channelSettingsIntent(context, PUSH_SERVICE_CHANNEL_ID)
                                )
                            }
                        }) {
                            Text("Скрыть уведомление в шторке")
                        }
                        OutlinedButton(onClick = onCheckNewMailNow) {
                            Text("Проверить сейчас")
                        }
                    }
                } else {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Интервал проверки",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        NOTIFICATION_INTERVALS_MINUTES.forEach { minutes ->
                            val label = if (minutes >= 60) "${minutes / 60} час" else "$minutes мин"
                            FilterChip(
                                selected = notificationIntervalMinutes == minutes,
                                onClick = { onSetNotificationInterval(minutes) },
                                label = { Text(label) }
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = onCheckNewMailNow) {
                        Text("Проверить сейчас")
                    }
                }

                if (!ignoringBatteryOpt) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Оптимизация батареи включена — система может останавливать уведомления при выгрузке из памяти.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(MailNotificationHelper.batteryOptimizationSettingsIntent())
                        }
                    }) {
                        Text("Отключить оптимизацию батареи")
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Text(
                text = "Папки и отправители настраиваются через кнопку ⋮ рядом с папкой или внутри письма.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val mutedFoldersDisplay = remember(folders, mutedFolderIds) {
                folders.filter { f ->
                    f.id in mutedFolderIds || (f.systemKind == SystemFolderKind.INBOX && "inbox" in mutedFolderIds)
                }
            }
            if (mutedFoldersDisplay.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Без уведомлений (папки):",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    mutedFoldersDisplay.forEach { folder ->
                        InputChip(
                            selected = true,
                            onClick = { onUnmuteFolder(folder.id) },
                            label = { Text(folder.displayName) },
                            trailingIcon = {
                                Icon(Icons.Default.Close, contentDescription = "Включить", modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }

            if (mutedSenders.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Без уведомлений (отправители):",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    mutedSenders.sorted().forEach { email ->
                        InputChip(
                            selected = true,
                            onClick = { onUnmuteSender(email) },
                            label = { Text(email) },
                            trailingIcon = {
                                Icon(Icons.Default.Close, contentDescription = "Включить", modifier = Modifier.size(16.dp))
                            }
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                text = "Шаблоны тегов и автосортировка",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "«/» — вложенность ([ \"1\", \"2\" ]), «;» в письме — несколько веток ([ \"1\"; \"Вопрос\" ]). " +
                    "Нажмите на иконку папки у шаблона, чтобы настроить автосортировку; письма из НГТУ с неизвестным тегом уйдут в «Несортированные».",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(6.dp))
            tagTemplates.forEach { tpl ->
                val segs = parseTagPath(tpl)
                val depth = (segs.size - 1).coerceAtLeast(0)
                val preview = segs.joinToString(", ", "[", "]") { "\"$it\"" }
                val directRule = tagSortRules.firstOrNull { it.tag.equals(tpl, ignoreCase = true) }
                val inheritedRule = if (directRule == null) {
                    tagSortRules
                        .filter { rule ->
                            rule.autoCreateSubfolders &&
                                segs.size > rule.pathSegments().size &&
                                tpl.startsWith("${rule.tag}/", ignoreCase = true)
                        }
                        .maxByOrNull { it.pathSegments().size }
                } else {
                    null
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = (depth * 14).dp, top = 2.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (depth > 0) "↳ ${segs.last()}" else segs.lastOrNull().orEmpty(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (depth == 0) FontWeight.Medium else FontWeight.Normal
                        )
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        when {
                            directRule != null -> {
                                val targetMuted = directRule.targetFolderId in mutedFolderIds
                                Text(
                                    text = buildString {
                                        append("→ ${directRule.targetFolderName} · ")
                                        append(if (directRule.autoCreateSubfolders) "с подпапками · " else "только папка · ")
                                        append(if (targetMuted) "ув. выкл." else "ув. вкл.")
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            inheritedRule != null -> {
                                Text(
                                    text = "↳ через «${inheritedRule.tag}» → ${inheritedRule.targetFolderName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (folders.isNotEmpty()) {
                            IconButton(
                                onClick = { ruleTargetTag = tpl },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.DriveFileMove,
                                    contentDescription = "Настроить автосортировку",
                                    tint = if (directRule != null) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                        if (segs.size < 4) {
                            IconButton(
                                onClick = { editingTemplateTarget = null to "$tpl/" },
                                modifier = Modifier.size(30.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "Подтег", modifier = Modifier.size(16.dp))
                            }
                        }
                        IconButton(
                            onClick = { editingTemplateTarget = tpl to tpl },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Изменить", modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = { onRemoveTagTemplate(tpl) },
                            modifier = Modifier.size(30.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Удалить", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            val extraRules = tagSortRules.filter { rule ->
                tagTemplates.none { it.equals(rule.tag, ignoreCase = true) }
            }
            extraRules.forEach { rule ->
                val segPreview = rule.pathSegments().joinToString(", ", "[", "]") { "\"$it\"" }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$segPreview → ${rule.targetFolderName}",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    IconButton(
                        onClick = { onRemoveTagSortRule(rule.tag) },
                        modifier = Modifier.size(30.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Удалить правило", modifier = Modifier.size(16.dp))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { editingTemplateTarget = null to "" }) {
                Text("+ Добавить шаблон")
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                text = "Жесты (свайп по письму)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pickingSwipeRight = true }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Свайп вправо", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        swipeRightAction.labelRu,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { pickingSwipeRight = false }
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Свайп влево", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        swipeLeftAction.labelRu,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            Text(
                text = "Обновления",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Ветка сборок",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))

            UpdateChannel.entries.forEach { ch ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSetUpdateChannel(ch) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = updateChannel == ch,
                        onClick = { onSetUpdateChannel(ch) }
                    )
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(ch.labelRu, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            ch.descriptionRu,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = "Текущая версия: v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = onCheckUpdates,
                enabled = !isCheckingUpdate
            ) {
                if (isCheckingUpdate) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text("Проверить обновления")
            }
        }
    }
}
