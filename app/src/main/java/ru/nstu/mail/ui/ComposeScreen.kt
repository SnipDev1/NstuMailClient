package ru.nstu.mail.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.nstu.mail.data.ContactEntry
import ru.nstu.mail.data.EmailDetail
import ru.nstu.mail.data.EmailImportance

private enum class ActiveRecipientField { TO, CC, BCC }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    currentUserEmail: String,
    replyTo: EmailDetail?,
    replyAll: Boolean,
    isForward: Boolean,
    draft: EmailDetail?,
    directorySuggestions: List<ContactEntry>,
    avatars: Map<String, ImageBitmap?>,
    isSending: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onSearchDirectory: (String) -> Unit,
    onClearSuggestions: () -> Unit,
    onRequestAvatar: (String) -> Unit,
    onSubmit: (
        draftId: String?,
        draftChangeKey: String?,
        to: String,
        cc: String,
        bcc: String,
        subject: String,
        body: String,
        importance: EmailImportance,
        attachments: List<Uri>,
        context: Context,
        send: Boolean
    ) -> Unit
) {
    BackHandler(enabled = !isSending, onBack = onBack)

    val context = LocalContext.current

    var to by rememberSaveable {
        mutableStateOf(
            when {
                draft != null -> draft.to.joinToString(", ") { MailViewModel.extractRawEmail(it) }
                replyTo != null && !isForward && replyAll -> {
                    val others = (listOf(replyTo.fromEmail) + replyTo.to.map { MailViewModel.extractRawEmail(it) })
                        .filter { it.isNotBlank() && !it.equals(currentUserEmail, ignoreCase = true) }
                        .distinctBy { it.lowercase() }
                    others.joinToString(", ")
                }
                replyTo != null && !isForward -> replyTo.fromEmail
                else -> ""
            }
        )
    }
    var cc by rememberSaveable {
        mutableStateOf(
            when {
                draft != null -> draft.cc.joinToString(", ") { MailViewModel.extractRawEmail(it) }
                replyTo != null && replyAll && !isForward ->
                    replyTo.cc
                        .map { MailViewModel.extractRawEmail(it) }
                        .filter { it.isNotBlank() && !it.equals(currentUserEmail, ignoreCase = true) }
                        .joinToString(", ")
                else -> ""
            }
        )
    }
    var bcc by rememberSaveable {
        mutableStateOf(
            draft?.bcc?.joinToString(", ") { MailViewModel.extractRawEmail(it) }.orEmpty()
        )
    }
    var showBcc by rememberSaveable { mutableStateOf(bcc.isNotBlank()) }
    var subject by rememberSaveable {
        mutableStateOf(
            when {
                draft != null -> if (draft.subject == "(Без темы)") "" else draft.subject
                replyTo == null -> ""
                isForward -> if (replyTo.subject.startsWith("Fwd:", ignoreCase = true)) {
                    replyTo.subject
                } else {
                    "Fwd: ${replyTo.subject}"
                }
                else -> if (replyTo.subject.startsWith("Re:", ignoreCase = true)) {
                    replyTo.subject
                } else {
                    "Re: ${replyTo.subject}"
                }
            }
        )
    }
    var body by rememberSaveable {
        mutableStateOf(
            when {
                draft != null -> if (draft.isHtml) {
                    Html.fromHtml(draft.body, Html.FROM_HTML_MODE_COMPACT).toString().trim()
                } else {
                    draft.body
                }
                replyTo != null -> {
                    val plain = if (replyTo.isHtml) {
                        Html.fromHtml(replyTo.body, Html.FROM_HTML_MODE_COMPACT).toString().trim()
                    } else {
                        replyTo.body.trim()
                    }
                    val date = MailViewModel.formatMailDate(replyTo.receivedAt, detailed = true)
                    "\n\n--- Исходное сообщение ---\nОт: ${replyTo.from}\nДата: $date\n\n$plain"
                }
                else -> ""
            }
        )
    }
    var importance by rememberSaveable {
        mutableStateOf(draft?.importance ?: EmailImportance.NORMAL)
    }
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var activeField by remember { mutableStateOf(ActiveRecipientField.TO) }

    val attachLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedUris = (selectedUris + uris).distinct()
        }
    }

    fun appendRecipient(current: String, email: String): String {
        val prefix = if (',' in current) current.substringBeforeLast(',') + ", " else ""
        return "$prefix$email, "
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            draft != null && draft.id.isNotBlank() -> "Черновик"
                            replyTo == null -> "Новое письмо"
                            isForward -> "Переслать"
                            replyAll -> "Ответить всем"
                            else -> "Ответить"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !isSending) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Отмена")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { attachLauncher.launch(arrayOf("*/*")) },
                        enabled = !isSending
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = "Прикрепить файл")
                    }
                    IconButton(
                        onClick = {
                            onSubmit(
                                draft?.id,
                                draft?.changeKey,
                                to, cc, bcc, subject, body, importance, selectedUris, context, false
                            )
                        },
                        enabled = !isSending
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Сохранить черновик")
                    }
                    IconButton(
                        onClick = {
                            onSubmit(
                                draft?.id,
                                draft?.changeKey,
                                to, cc, bcc, subject, body, importance, selectedUris, context, true
                            )
                        },
                        enabled = !isSending
                    ) {
                        if (isSending) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Отправить")
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
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (!errorMessage.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            OutlinedTextField(
                value = to,
                onValueChange = {
                    to = it
                    activeField = ActiveRecipientField.TO
                    onSearchDirectory(it)
                },
                label = { Text("Кому (через запятую)") },
                singleLine = true,
                enabled = !isSending,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = cc,
                    onValueChange = {
                        cc = it
                        activeField = ActiveRecipientField.CC
                        onSearchDirectory(it)
                    },
                    label = { Text("Копия (необязательно)") },
                    singleLine = true,
                    enabled = !isSending,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.weight(1f)
                )
                if (!showBcc) {
                    TextButton(onClick = { showBcc = true }) {
                        Text("СК")
                    }
                }
            }

            if (showBcc) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = bcc,
                    onValueChange = {
                        bcc = it
                        activeField = ActiveRecipientField.BCC
                        onSearchDirectory(it)
                    },
                    label = { Text("Скрытая копия") },
                    singleLine = true,
                    enabled = !isSending,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (directorySuggestions.isNotEmpty()) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) {
                    Column {
                        directorySuggestions.forEachIndexed { idx, entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        when (activeField) {
                                            ActiveRecipientField.TO -> to = appendRecipient(to, entry.email)
                                            ActiveRecipientField.CC -> cc = appendRecipient(cc, entry.email)
                                            ActiveRecipientField.BCC -> bcc = appendRecipient(bcc, entry.email)
                                        }
                                        onClearSuggestions()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UserAvatar(
                                    name = entry.name,
                                    email = entry.email,
                                    photo = avatars[entry.email.trim().lowercase()],
                                    size = 32.dp,
                                    onRequestLoad = onRequestAvatar
                                )
                                Spacer(Modifier.size(10.dp))
                                Column {
                                    Text(entry.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        entry.email,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            if (idx < directorySuggestions.lastIndex) HorizontalDivider()
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("Тема") },
                singleLine = true,
                enabled = !isSending,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Важность:", style = MaterialTheme.typography.labelMedium)
                EmailImportance.entries.forEach { imp ->
                    FilterChip(
                        selected = importance == imp,
                        onClick = { importance = imp },
                        label = { Text(imp.labelRu) }
                    )
                }
            }

            if (selectedUris.isNotEmpty() || (draft?.attachments?.isNotEmpty() == true)) {
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    draft?.attachments?.filterNot { it.isInline }?.forEach { existing ->
                        InputChip(
                            selected = false,
                            onClick = {},
                            label = { Text(existing.name) }
                        )
                    }
                    selectedUris.forEach { uri ->
                        val label = remember(uri) { resolveUriDisplayName(context, uri) }
                        InputChip(
                            selected = true,
                            onClick = { selectedUris = selectedUris - uri },
                            label = { Text(label) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Убрать",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Текст письма") },
                enabled = !isSending,
                minLines = 10,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private fun resolveUriDisplayName(context: Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cur ->
            if (cur.moveToFirst()) {
                val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cur.getString(idx) ?: "file"
            }
        }
    return uri.lastPathSegment ?: "file"
}
