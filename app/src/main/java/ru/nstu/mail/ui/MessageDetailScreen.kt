package ru.nstu.mail.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.text.Html
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.ReplyAll
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ru.nstu.mail.data.AttachmentInfo
import ru.nstu.mail.data.EmailDetail
import ru.nstu.mail.data.EmailImportance
import ru.nstu.mail.data.MailFolder
import ru.nstu.mail.data.SystemFolderKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    detail: EmailDetail,
    folders: List<MailFolder>,
    currentFolder: MailFolder?,
    avatars: Map<String, ImageBitmap?>,
    isDownloadingAttachment: Boolean,
    toastMessage: String?,
    onBack: () -> Unit,
    onRequestAvatar: (String) -> Unit,
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
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    var showMovePicker by remember { mutableStateOf(false) }
    var moreMenuExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(toastMessage) {
        val msg = toastMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onClearToast()
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
                                onClick = { moreMenuExpanded = false; onToggleSpam(detail) }
                            )
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
                if (detail.importance == EmailImportance.HIGH) {
                    Text(
                        text = "Высокая важность",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(2.dp))
                }
                Text(
                    text = detail.subject,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.Top) {
                    UserAvatar(
                        name = detail.from,
                        email = detail.fromEmail,
                        photo = avatars[detail.fromEmail.trim().lowercase()],
                        size = 42.dp,
                        onRequestLoad = onRequestAvatar
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (detail.fromEmail.isNotBlank() &&
                                !detail.from.equals(detail.fromEmail, ignoreCase = true)
                            ) {
                                "${detail.from} <${detail.fromEmail}>"
                            } else {
                                detail.from
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (detail.to.isNotEmpty()) {
                            Text(
                                text = "Кому: ${detail.to.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (detail.cc.isNotEmpty()) {
                            Text(
                                text = "Копия: ${detail.cc.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (detail.bcc.isNotEmpty()) {
                            Text(
                                text = "Скрытая копия: ${detail.bcc.joinToString(", ")}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = MailViewModel.formatMailDate(detail.receivedAt, detailed = true),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
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

            val htmlContent = remember(detail.id, detail.body, detail.isHtml) {
                wrapEmailBody(detail.body, detail.isHtml)
            }

            SandboxedEmailWebView(
                html = htmlContent,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SandboxedEmailWebView(
    html: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                setBackgroundColor(AndroidColor.WHITE)
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
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }
                webViewClient = object : WebViewClient() {
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
            webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
    )
}

private fun wrapEmailBody(body: String, isHtml: Boolean): String {
    val content = if (isHtml) {
        body
    } else {
        "<pre style=\"white-space:pre-wrap;font-family:sans-serif;font-size:15px;\">" +
            Html.escapeHtml(body) +
            "</pre>"
    }
    return """<!DOCTYPE html>
<html>
<head>
<meta charset="utf-8"/>
<meta name="viewport" content="width=device-width, initial-scale=1.0"/>
<style>
body { margin: 16px; color: #1b1b1b; background: #ffffff; font-family: sans-serif; line-height: 1.45; word-wrap: break-word; }
img { max-width: 100%; height: auto; }
table { max-width: 100%; }
</style>
</head>
<body>$content</body>
</html>"""
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "0 Б"
    bytes < 1024 -> "$bytes Б"
    bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
    else -> String.format("%.1f МБ", bytes / (1024.0 * 1024.0))
}
