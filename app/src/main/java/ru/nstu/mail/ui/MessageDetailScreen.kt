package ru.nstu.mail.ui

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Report
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.launch
import ru.nstu.mail.data.AttachmentInfo
import ru.nstu.mail.data.EmailDetail
import ru.nstu.mail.data.EmailHtmlSanitizer
import ru.nstu.mail.data.EmailImportance
import ru.nstu.mail.data.EmailParticipant
import ru.nstu.mail.data.MailFolder
import ru.nstu.mail.data.ParticipantRole
import ru.nstu.mail.data.PersonProfile
import ru.nstu.mail.data.SystemFolderKind
import ru.nstu.mail.data.extractSubjectTagHierarchies
import ru.nstu.mail.data.participants
import java.io.ByteArrayInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    detail: EmailDetail,
    folders: List<MailFolder>,
    currentFolder: MailFolder?,
    avatars: Map<String, ImageBitmap?>,
    personProfiles: Map<String, PersonProfile?>,
    knownNames: Map<String, String>,
    mutedSenders: Set<String>,
    loadingProfiles: Set<String>,
    darkTheme: Boolean,
    defaultLightBody: Boolean = false,
    isDownloadingAttachment: Boolean,
    toastMessage: String?,
    onBack: () -> Unit,
    onRequestAvatar: (String) -> Unit,
    onRequestPersonProfile: (name: String, email: String) -> Unit,
    onToggleSenderNotifications: (String) -> Unit,
    onComposeTo: (email: String, name: String) -> Unit,
    onToggleRead: (EmailDetail) -> Unit,
    onToggleFlag: (EmailDetail) -> Unit,
    onReply: (EmailDetail) -> Unit,
    onReplyAll: (EmailDetail) -> Unit,
    onForward: (EmailDetail) -> Unit,
    onEditDraft: (EmailDetail) -> Unit,
    onMoveToFolder: (EmailDetail, MailFolder) -> Unit,
    onToggleSpam: (EmailDetail) -> Unit,
    onDelete: (EmailDetail) -> Unit,
    onDownloadAttachment: (AttachmentInfo, Context) -> Unit,
    onClearToast: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var showMovePicker by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var selectedParticipant by remember { mutableStateOf<EmailParticipant?>(null) }
    var forceLightBody by remember(detail.id, defaultLightBody) { mutableStateOf(defaultLightBody) }

    val participants = remember(detail.id, detail.from, detail.fromEmail, detail.to, detail.cc, detail.bcc) {
        detail.participants()
    }

    LaunchedEffect(toastMessage) {
        val msg = toastMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onClearToast()
    }

    selectedParticipant?.let { person ->
        val key = person.email.trim().lowercase().ifBlank { person.name.trim().lowercase() }
        val profile = personProfiles[key]
        val fullName = resolveDisplayName(person.name, person.email, profile, knownNames)
        val dialogEmail = profile?.email?.takeIf { it.isNotBlank() } ?: person.email
        val senderMuted = dialogEmail.trim().lowercase() in mutedSenders
        PersonInfoDialog(
            participant = person,
            photo = avatars[person.email.trim().lowercase()],
            profile = profile,
            knownNames = knownNames,
            isLoadingProfile = key in loadingProfiles,
            notificationsMuted = senderMuted,
            onToggleNotifications = {
                if (dialogEmail.isNotBlank()) onToggleSenderNotifications(dialogEmail)
            },
            onRequestProfile = { onRequestPersonProfile(person.name, person.email) },
            onRequestAvatar = onRequestAvatar,
            onCompose = {
                selectedParticipant = null
                if (person.email.isNotBlank()) onComposeTo(person.email, fullName)
            },
            onCopyEmail = { email ->
                val cm = context.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("email", email))
                scope.launch { snackbarHostState.showSnackbar("Адрес скопирован: $email") }
            },
            onDismiss = { selectedParticipant = null }
        )
    }

    if (showMovePicker) {
        FolderPickerDialog(
            folders = folders,
            excludeFolderId = currentFolder?.id,
            onDismiss = { showMovePicker = false },
            onSelect = { target ->
                showMovePicker = false
                onMoveToFolder(detail, target)
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить письмо?") },
            text = {
                Text(
                    if (currentFolder?.systemKind == SystemFolderKind.DELETED) {
                        "Письмо будет удалено безвозвратно."
                    } else {
                        "Письмо будет перемещено в папку «Удалённые»."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(detail)
                }) {
                    Text("Удалить")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Отмена")
                }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    if (detail.isDraft) {
                        IconButton(onClick = { onEditDraft(detail) }) {
                            Icon(Icons.Default.Edit, contentDescription = "Редактировать черновик")
                        }
                    }
                    IconButton(onClick = { onToggleFlag(detail) }) {
                        Icon(
                            imageVector = if (detail.isFlagged) Icons.Default.Star else Icons.Default.StarBorder,
                            contentDescription = "Флажок",
                            tint = if (detail.isFlagged) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }
                    IconButton(onClick = { onReply(detail) }) {
                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Ответить")
                    }
                    IconButton(onClick = { onReplyAll(detail) }) {
                        Icon(Icons.AutoMirrored.Filled.ReplyAll, contentDescription = "Ответить всем")
                    }
                    IconButton(onClick = { showMovePicker = true }) {
                        Icon(Icons.AutoMirrored.Filled.DriveFileMove, contentDescription = "Переместить")
                    }
                    Box {
                        IconButton(onClick = { moreMenuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "Ещё")
                        }
                        DropdownMenu(
                            expanded = moreMenuExpanded,
                            onDismissRequest = { moreMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Переслать") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.Forward, null) },
                                onClick = { moreMenuExpanded = false; onForward(detail) }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (detail.isRead) "Отметить непрочитанным"
                                        else "Отметить прочитанным"
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (detail.isRead) Icons.Default.MarkEmailUnread
                                        else Icons.Default.MarkEmailRead,
                                        null
                                    )
                                },
                                onClick = { moreMenuExpanded = false; onToggleRead(detail) }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (currentFolder?.systemKind == SystemFolderKind.JUNK) {
                                            "Не спам (во Входящие)"
                                        } else {
                                            "В спам"
                                        }
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        if (currentFolder?.systemKind == SystemFolderKind.JUNK) {
                                            Icons.Default.Inbox
                                        } else {
                                            Icons.Default.Report
                                        },
                                        null
                                    )
                                },
                                onClick = { moreMenuExpanded = false; onToggleSpam(detail) }
                            )
                            if (detail.fromEmail.isNotBlank()) {
                                val fromMuted = detail.fromEmail.trim().lowercase() in mutedSenders
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (fromMuted) "Уведомления от отправителя: выкл (вкл.)"
                                            else "Уведомления от отправителя: вкл (выкл.)"
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (fromMuted) Icons.Default.NotificationsOff else Icons.Default.Notifications,
                                            null
                                        )
                                    },
                                    onClick = {
                                        moreMenuExpanded = false
                                        onToggleSenderNotifications(detail.fromEmail)
                                    }
                                )
                            }
                            if (darkTheme) {
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (forceLightBody) "Тёмный фон письма"
                                            else "Светлый фон письма"
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (forceLightBody) Icons.Default.DarkMode else Icons.Default.LightMode,
                                            null
                                        )
                                    },
                                    onClick = {
                                        moreMenuExpanded = false
                                        forceLightBody = !forceLightBody
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Удалить") },
                                leadingIcon = { Icon(Icons.Default.Delete, null) },
                                onClick = { moreMenuExpanded = false; confirmDelete = true }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isDownloadingAttachment) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                val (subjectHierarchies, cleanSubject) = remember(detail.subject) {
                    extractSubjectTagHierarchies(detail.subject)
                }
                if (subjectHierarchies.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(bottom = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        subjectHierarchies.forEach { hierarchy ->
                            Surface(
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = hierarchy.joinToString(" › "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
                Text(
                    text = cleanSubject.ifBlank { detail.subject },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = MailViewModel.formatMailDate(detail.receivedAt, detailed = true),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (participants.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(end = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(participants, key = { "${it.role}:${it.email}:${it.name}" }) { person ->
                            val key = person.email.trim().lowercase().ifBlank { person.name.trim().lowercase() }
                            ParticipantAvatarTile(
                                participant = person,
                                photo = avatars[person.email.trim().lowercase()],
                                profile = personProfiles[key],
                                knownNames = knownNames,
                                onRequestAvatar = onRequestAvatar,
                                onRequestProfile = { onRequestPersonProfile(person.name, person.email) },
                                onClick = { selectedParticipant = person }
                            )
                        }
                    }
                }

                val visibleAttachments = detail.attachments.filterNot { it.isInline }
                if (visibleAttachments.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        visibleAttachments.forEach { att ->
                            AssistChip(
                                onClick = { onDownloadAttachment(att, context) },
                                label = {
                                    Text("${att.name} (${formatBytes(att.size)})")
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.AttachFile,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }

            HorizontalDivider()

            val effectiveDarkBody = darkTheme && !forceLightBody
            val bodyBgColor = if (effectiveDarkBody) MaterialTheme.colorScheme.surface else Color.White
            val bodyTextColor = if (effectiveDarkBody) MaterialTheme.colorScheme.onSurface else Color(0xFF1B1B1B)
            val bodyLinkColor = if (effectiveDarkBody) MaterialTheme.colorScheme.primary else Color(0xFF006B47)

            val bgHex = bodyBgColor.toCssHex()
            val textHex = bodyTextColor.toCssHex()
            val linkHex = bodyLinkColor.toCssHex()

            val htmlContent = remember(detail.id, detail.body, detail.isHtml, effectiveDarkBody, bgHex, textHex, linkHex) {
                wrapEmailBody(
                    body = detail.body,
                    isHtml = detail.isHtml,
                    darkMode = effectiveDarkBody,
                    bgHex = bgHex,
                    textHex = textHex,
                    linkHex = linkHex
                )
            }

            SandboxedEmailWebView(
                html = htmlContent,
                backgroundColorInt = bodyBgColor.toArgb(),
                darkMode = effectiveDarkBody,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

private fun Color.toCssHex(): String =
    String.format("#%06X", toArgb() and 0xFFFFFF)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SandboxedEmailWebView(
    html: String,
    backgroundColorInt: Int,
    darkMode: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(backgroundColorInt)
                settings.apply {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                    @Suppress("DEPRECATION")
                    databaseEnabled = false
                    @Suppress("DEPRECATION")
                    setGeolocationEnabled(false)
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    builtInZoomControls = true
                    displayZoomControls = false
                    loadWithOverviewMode = false
                    useWideViewPort = true
                    textZoom = 100
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.SAFE_BROWSING_ENABLE)) {
                        WebSettingsCompat.setSafeBrowsingEnabled(this, true)
                    }
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                        WebSettingsCompat.setAlgorithmicDarkeningAllowed(this, false)
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val scheme = request?.url?.scheme?.lowercase().orEmpty()
                        return if (scheme == "https" || scheme == "data") {
                            null
                        } else {
                            WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                403,
                                "Forbidden",
                                emptyMap(),
                                ByteArrayInputStream(ByteArray(0))
                            )
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val uri = request?.url ?: return true
                        if (uri.scheme == "https" || uri.scheme == "http" || uri.scheme == "mailto") {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            }
                        }
                        return true
                    }
                }
            }
        },
        update = { webView ->
            webView.setBackgroundColor(backgroundColorInt)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, false)
            }
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        }
    )
}

internal fun wrapEmailBody(
    body: String,
    isHtml: Boolean,
    darkMode: Boolean,
    bgHex: String,
    textHex: String,
    linkHex: String
): String = EmailHtmlSanitizer.renderEmailDocument(
    body = body,
    isHtml = isHtml,
    darkMode = darkMode,
    bgHex = bgHex,
    textHex = textHex,
    linkHex = linkHex
)

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 Б"
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> String.format("%.1f МБ", bytes / (1024.0 * 1024.0))
}

@Composable
private fun ParticipantAvatarTile(
    participant: EmailParticipant,
    photo: ImageBitmap?,
    profile: PersonProfile?,
    knownNames: Map<String, String>,
    onRequestAvatar: (String) -> Unit,
    onRequestProfile: () -> Unit,
    onClick: () -> Unit
) {
    val emailKey = participant.email.trim().lowercase()
    val cachedName = knownNames[emailKey]
    LaunchedEffect(participant.email, cachedName) {
        if (needsNameResolution(participant.name, participant.email) &&
            cachedName.isNullOrBlank() &&
            profile == null
        ) {
            onRequestProfile()
        }
    }

    val resolvedName = resolveDisplayName(participant.name, participant.email, profile, knownNames)
    val badgeBg = when (participant.role) {
        ParticipantRole.FROM -> MaterialTheme.colorScheme.primaryContainer
        ParticipantRole.TO -> MaterialTheme.colorScheme.tertiaryContainer
        ParticipantRole.CC -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ParticipantRole.BCC -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
    }
    val badgeText = when (participant.role) {
        ParticipantRole.FROM -> MaterialTheme.colorScheme.onPrimaryContainer
        ParticipantRole.TO -> MaterialTheme.colorScheme.onTertiaryContainer
        ParticipantRole.CC -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f)
        ParticipantRole.BCC -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.70f)
    }
    val nameColor = when (participant.role) {
        ParticipantRole.FROM -> MaterialTheme.colorScheme.onSurface
        ParticipantRole.TO -> MaterialTheme.colorScheme.tertiary
        ParticipantRole.CC -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        ParticipantRole.BCC -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    }
    val nameWeight = when (participant.role) {
        ParticipantRole.FROM -> FontWeight.SemiBold
        ParticipantRole.TO -> FontWeight.Medium
        else -> FontWeight.Normal
    }

    Column(
        modifier = Modifier
            .width(78.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            color = badgeBg,
            shape = RoundedCornerShape(8.dp)
        ) {
            Text(
                text = participant.role.labelRu,
                style = MaterialTheme.typography.labelSmall,
                color = badgeText,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        UserAvatar(
            name = resolvedName,
            email = participant.email,
            photo = photo,
            size = 44.dp,
            onRequestLoad = onRequestAvatar,
            modifier = if (participant.role == ParticipantRole.TO) {
                Modifier.border(1.5.dp, MaterialTheme.colorScheme.tertiary, CircleShape)
            } else {
                Modifier
            }
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = formatShortPersonName(resolvedName, participant.email),
            style = MaterialTheme.typography.labelSmall,
            color = nameColor,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = nameWeight
        )
    }
}

@Composable
private fun PersonInfoDialog(
    participant: EmailParticipant,
    photo: ImageBitmap?,
    profile: PersonProfile?,
    knownNames: Map<String, String>,
    isLoadingProfile: Boolean,
    notificationsMuted: Boolean,
    onToggleNotifications: () -> Unit,
    onRequestProfile: () -> Unit,
    onRequestAvatar: (String) -> Unit,
    onCompose: () -> Unit,
    onCopyEmail: (String) -> Unit,
    onDismiss: () -> Unit
) {
    LaunchedEffect(participant.email, participant.name) {
        onRequestProfile()
    }

    val fullName = resolveDisplayName(participant.name, participant.email, profile, knownNames)
    val primaryEmail = profile?.email?.takeIf { it.isNotBlank() } ?: participant.email

    AlertDialog(
        onDismissRequest = onDismiss,
        title = null,
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                UserAvatar(
                    name = fullName,
                    email = primaryEmail,
                    photo = photo,
                    size = 72.dp,
                    onRequestLoad = onRequestAvatar
                )
                Spacer(Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = participant.role.labelRu,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = fullName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )
                if (primaryEmail.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = primaryEmail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(8.dp))
                    FilterChip(
                        selected = !notificationsMuted,
                        onClick = onToggleNotifications,
                        label = {
                            Text(
                                if (notificationsMuted) "Уведомления выключены"
                                else "Уведомления включены"
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (notificationsMuted) {
                                    Icons.Default.NotificationsOff
                                } else {
                                    Icons.Default.Notifications
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }

                if (isLoadingProfile) {
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                if (profile != null) {
                    val rows = buildList {
                        if (profile.jobTitle.isNotBlank()) add("Должность" to profile.jobTitle)
                        if (profile.department.isNotBlank()) add("Подразделение" to profile.department)
                        if (profile.company.isNotBlank()) add("Организация" to profile.company)
                        if (profile.officeLocation.isNotBlank()) add("Кабинет" to profile.officeLocation)
                        profile.phones.forEach { (label, num) -> add(label to num) }
                        if (profile.aliases.isNotEmpty()) {
                            add("Доп. адреса" to profile.aliases.joinToString("\n"))
                        }
                    }
                    if (rows.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            rows.forEach { (label, value) ->
                                Column {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = value,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (primaryEmail.isNotBlank()) {
                TextButton(onClick = onCompose) {
                    Text("Написать")
                }
            }
        },
        dismissButton = {
            Row {
                if (primaryEmail.isNotBlank()) {
                    TextButton(onClick = { onCopyEmail(primaryEmail) }) {
                        Text("Копировать")
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Закрыть")
                }
            }
        }
    )
}
