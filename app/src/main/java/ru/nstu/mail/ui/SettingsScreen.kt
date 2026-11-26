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
import androidx.compose.material.icons.filled.Close
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
import ru.nstu.mail.notify.MailNotificationHelper
import ru.nstu.mail.notify.NOTIFICATION_INTERVALS_MINUTES
import ru.nstu.mail.notify.NotificationMode
import ru.nstu.mail.notify.PUSH_SERVICE_CHANNEL_ID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    themeMode: AppThemeMode,
    notificationsEnabled: Boolean,
    notificationMode: NotificationMode,
    notificationIntervalMinutes: Int,
    folders: List<MailFolder>,
    mutedFolderIds: Set<String>,
    mutedSenders: Set<String>,
    tagSortRules: List<TagSortRule>,
    availableTags: List<String>,
    swipeRightAction: SwipeAction,
    swipeLeftAction: SwipeAction,
    updateChannel: UpdateChannel,
    isCheckingUpdate: Boolean,
    toastMessage: String?,
    onBack: () -> Unit,
    onSetThemeMode: (AppThemeMode) -> Unit,
    onSetNotificationsEnabled: (Boolean) -> Unit,
    onSetNotificationMode: (NotificationMode) -> Unit,
    onSetNotificationInterval: (Int) -> Unit,
    onCheckNewMailNow: () -> Unit,
    onNotificationPermissionResult: (Boolean) -> Unit,
    onUnmuteFolder: (String) -> Unit,
    onUnmuteSender: (String) -> Unit,
    onSaveTagSortRule: (tag: String, folder: MailFolder) -> Unit,
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
    var showAddRuleDialog by remember { mutableStateOf(false) }
    var pickingSwipeRight by remember { mutableStateOf<Boolean?>(null) }

    if (showAddRuleDialog && folders.isNotEmpty()) {
        AddTagRuleDialog(
            folders = folders,
            availableTags = availableTags,
            fixedFolder = null,
            onDismiss = { showAddRuleDialog = false },
            onConfirm = { tag, folder ->
                showAddRuleDialog = false
                onSaveTagSortRule(tag, folder)
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
                text = "Теги и автосортировка",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            if (tagSortRules.isEmpty()) {
                Text(
                    text = "Правил автосортировки пока нет. Письма с тегом в теме (напр. [ПЗ]) могут автоматически уходить в нужную папку.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                tagSortRules.forEach { rule ->
                    val targetMuted = rule.targetFolderId in mutedFolderIds
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "[${rule.tag}] → ${rule.targetFolderName}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = if (targetMuted) "Уведомления в папке выкл." else "Уведомления в папке вкл.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { onRemoveTagSortRule(rule.tag) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = "Удалить", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
            if (folders.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { showAddRuleDialog = true }) {
                    Text("+ Добавить правило")
                }
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
