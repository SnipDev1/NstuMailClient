package ru.nstu.mail.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.text.Html
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Forward
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MarkEmailRead
import androidx.compose.material.icons.filled.MarkEmailUnread
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import ru.nstu.mail.data.AttachmentInfo
import ru.nstu.mail.data.EmailDetail
import ru.nstu.mail.data.MailFolderType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    detail: EmailDetail,
    currentFolder: MailFolderType,
    isDownloadingAttachment: Boolean,
    toastMessage: String?,
    onBack: () -> Unit,
    onToggleRead: (EmailDetail) -> Unit,
    onReply: (EmailDetail) -> Unit,
    onForward: (EmailDetail) -> Unit,
    onDelete: (EmailDetail) -> Unit,
    onDownloadAttachment: (AttachmentInfo, Context) -> Unit,
    onClearToast: () -> Unit
) {
    BackHandler(onBack = onBack)

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(toastMessage) {
        val msg = toastMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        onClearToast()
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Удалить письмо?") },
            text = {
                Text(
                    if (currentFolder == MailFolderType.DELETED) {
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
                    IconButton(onClick = { onToggleRead(detail) }) {
                        Icon(
                            imageVector = if (detail.isRead) {
                                Icons.Default.MarkEmailUnread
                            } else {
                                Icons.Default.MarkEmailRead
                            },
                            contentDescription = "Переключить статус прочтения"
                        )
                    }
                    IconButton(onClick = { onReply(detail) }) {
                        Icon(Icons.AutoMirrored.Filled.Reply, contentDescription = "Ответить")
                    }
                    IconButton(onClick = { onForward(detail) }) {
                        Icon(Icons.AutoMirrored.Filled.Forward, contentDescription = "Переслать")
                    }
                    IconButton(onClick = { confirmDelete = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить")
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
                Text(
                    text = detail.subject,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = if (detail.fromEmail.isNotBlank() &&
                        !detail.from.equals(detail.fromEmail, ignoreCase = true)
                    ) {
                        "От: ${detail.from} <${detail.fromEmail}>"
                    } else {
                        "От: ${detail.from}"
                    },
                    style = MaterialTheme.typography.bodyMedium
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
                Text(
                    text = MailViewModel.formatMailDate(detail.receivedAt, detailed = true),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

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
