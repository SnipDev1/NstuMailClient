package ru.nstu.mail.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.nstu.mail.data.ContactEntry
import ru.nstu.mail.data.EmailDetail
import ru.nstu.mail.data.EmailImportance
import ru.nstu.mail.data.ParticipantRole
import ru.nstu.mail.data.PersonProfile
import ru.nstu.mail.data.childTagsForPrefix
import ru.nstu.mail.data.extractSubjectTagHierarchies
import ru.nstu.mail.data.formatMailDate
import ru.nstu.mail.data.formatShortPersonName
import ru.nstu.mail.data.formatSubjectWithTagHierarchies
import ru.nstu.mail.data.formatTagHierarchiesRaw
import ru.nstu.mail.data.formatTagPath
import ru.nstu.mail.data.needsNameResolution
import ru.nstu.mail.data.parseFormattedRecipient
import ru.nstu.mail.data.parseTagHierarchiesRaw
import ru.nstu.mail.data.parseTagPath
import ru.nstu.mail.data.resolveDisplayName
import ru.nstu.mail.data.sanitizeTagSegment
import java.util.concurrent.atomic.AtomicLong

private enum class ActiveRecipientField { TO, CC, BCC }

data class ComposeRecipient(
    val id: Long,
    val name: String,
    val email: String
)

fun parseRecipientTokens(raw: String): Pair<List<Pair<String, String>>, String> {
    if (',' !in raw && ';' !in raw) {
        return emptyList<Pair<String, String>>() to raw
    }
    val parts = raw.split(',', ';')
    val completed = parts.dropLast(1).mapNotNull { seg ->
        finalizeRecipientToken(seg)
    }
    return completed to parts.last().trimStart()
}

fun finalizeRecipientToken(raw: String): Pair<String, String>? {
    val cleaned = raw.trim().trimEnd(',', ';').trim()
    if (cleaned.isEmpty()) return null
    val (name, email) = parseFormattedRecipient(cleaned)
    return if ('@' in email) {
        name.ifBlank { email } to email
    } else {
        null
    }
}

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
    personProfiles: Map<String, PersonProfile?>,
    knownNames: Map<String, String>,
    tagTemplates: List<String>,
    isSending: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onSearchDirectory: (String) -> Unit,
    onClearSuggestions: () -> Unit,
    onRequestAvatar: (String) -> Unit,
    onRequestPersonProfile: (name: String, email: String) -> Unit,
    onAddTagTemplate: (String) -> Unit,
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
    val idSeq = remember { AtomicLong(1L) }

    fun makeRecipient(name: String, email: String) =
        ComposeRecipient(id = idSeq.getAndIncrement(), name = name, email = email)

    var toRecipients by remember {
        mutableStateOf<List<ComposeRecipient>>(
            when {
                draft != null -> draft.to.mapNotNull { raw ->
                    val (n, e) = parseFormattedRecipient(raw)
                    if (e.isNotBlank()) makeRecipient(n, e) else null
                }
                replyTo != null && !isForward && replyAll -> {
                    val rawPairs = buildList {
                        if (replyTo.fromEmail.isNotBlank()) {
                            add(replyTo.from to replyTo.fromEmail)
                        }
                        replyTo.to.forEach { add(parseFormattedRecipient(it)) }
                    }
                    rawPairs
                        .filter { it.second.isNotBlank() && !it.second.equals(currentUserEmail, true) }
                        .distinctBy { it.second.lowercase() }
                        .map { (n, e) -> makeRecipient(n, e) }
                }
                replyTo != null && !isForward && replyTo.fromEmail.isNotBlank() ->
                    listOf(makeRecipient(replyTo.from, replyTo.fromEmail))
                else -> emptyList()
            }
        )
    }

    var ccRecipients by remember {
        mutableStateOf<List<ComposeRecipient>>(
            when {
                draft != null -> draft.cc.mapNotNull { raw ->
                    val (n, e) = parseFormattedRecipient(raw)
                    if (e.isNotBlank()) makeRecipient(n, e) else null
                }
                replyTo != null && replyAll && !isForward ->
                    replyTo.cc
                        .map { parseFormattedRecipient(it) }
                        .filter { it.second.isNotBlank() && !it.second.equals(currentUserEmail, true) }
                        .distinctBy { it.second.lowercase() }
                        .map { (n, e) -> makeRecipient(n, e) }
                else -> emptyList()
            }
        )
    }

    var bccRecipients by remember {
        mutableStateOf<List<ComposeRecipient>>(
            draft?.bcc.orEmpty().mapNotNull { raw ->
                val (n, e) = parseFormattedRecipient(raw)
                if (e.isNotBlank()) makeRecipient(n, e) else null
            }
        )
    }

    var showCc by rememberSaveable { mutableStateOf(ccRecipients.isNotEmpty()) }
    var showBcc by rememberSaveable { mutableStateOf(bccRecipients.isNotEmpty()) }

    var toInput by rememberSaveable { mutableStateOf("") }
    var ccInput by rememberSaveable { mutableStateOf("") }
    var bccInput by rememberSaveable { mutableStateOf("") }

    var activeField by remember { mutableStateOf(ActiveRecipientField.TO) }
    var editingField by remember { mutableStateOf<ActiveRecipientField?>(null) }
    var editingId by remember { mutableStateOf<Long?>(null) }
    var editingText by remember { mutableStateOf("") }

    val initialParsedSubject = remember(draft?.id, draft?.subject, replyTo?.id, isForward) {
        when {
            draft != null -> if (draft.subject == "(Без темы)") {
                emptyList<List<String>>() to ""
            } else {
                extractSubjectTagHierarchies(draft.subject)
            }
            replyTo == null -> emptyList<List<String>>() to ""
            isForward -> {
                val base = if (replyTo.subject.startsWith("Fwd:", ignoreCase = true)) {
                    replyTo.subject
                } else {
                    "Fwd: ${replyTo.subject}"
                }
                emptyList<List<String>>() to base
            }
            else -> {
                val base = if (replyTo.subject.startsWith("Re:", ignoreCase = true)) {
                    replyTo.subject
                } else {
                    "Re: ${replyTo.subject}"
                }
                emptyList<List<String>>() to base
            }
        }
    }

    var savedHierarchiesRaw by rememberSaveable {
        mutableStateOf(formatTagHierarchiesRaw(initialParsedSubject.first))
    }
    var activePathRaw by rememberSaveable { mutableStateOf("") }
    val savedHierarchies = remember(savedHierarchiesRaw) { parseTagHierarchiesRaw(savedHierarchiesRaw) }
    val activePath = remember(activePathRaw) { parseTagPath(activePathRaw) }
    val allHierarchies = remember(savedHierarchies, activePath) {
        (savedHierarchies + listOfNotNull(activePath.takeIf { it.isNotEmpty() }))
            .distinctBy { formatTagPath(it).lowercase() }
    }
    var subject by rememberSaveable { mutableStateOf(initialParsedSubject.second) }
    var showAddTagDialog by remember { mutableStateOf(false) }
    var showSendConfirm by remember { mutableStateOf(false) }

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
                    val date = formatMailDate(replyTo.receivedAt, detailed = true)
                    "\n\n--- Исходное сообщение ---\nОт: ${replyTo.from}\nДата: $date\n\n$plain"
                }
                else -> ""
            }
        )
    }
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val attachLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedUris = (selectedUris + uris).distinct()
        }
    }

    fun listFor(field: ActiveRecipientField): List<ComposeRecipient> = when (field) {
        ActiveRecipientField.TO -> toRecipients
        ActiveRecipientField.CC -> ccRecipients
        ActiveRecipientField.BCC -> bccRecipients
    }

    fun updateList(field: ActiveRecipientField, next: List<ComposeRecipient>) {
        when (field) {
            ActiveRecipientField.TO -> toRecipients = next
            ActiveRecipientField.CC -> ccRecipients = next
            ActiveRecipientField.BCC -> bccRecipients = next
        }
    }

    fun inputFor(field: ActiveRecipientField): String = when (field) {
        ActiveRecipientField.TO -> toInput
        ActiveRecipientField.CC -> ccInput
        ActiveRecipientField.BCC -> bccInput
    }

    fun setInput(field: ActiveRecipientField, value: String) {
        when (field) {
            ActiveRecipientField.TO -> toInput = value
            ActiveRecipientField.CC -> ccInput = value
            ActiveRecipientField.BCC -> bccInput = value
        }
    }

    fun addUniqueRecipients(field: ActiveRecipientField, pairs: List<Pair<String, String>>) {
        if (pairs.isEmpty()) return
        val current = listFor(field).toMutableList()
        for ((name, email) in pairs) {
            if (current.none { it.email.equals(email, ignoreCase = true) }) {
                current += makeRecipient(name, email)
            }
        }
        updateList(field, current)
    }

    fun commitTrailingInput(field: ActiveRecipientField) {
        val raw = inputFor(field)
        val parsed = finalizeRecipientToken(raw)
        if (parsed != null) {
            addUniqueRecipients(field, listOf(parsed))
            setInput(field, "")
            onClearSuggestions()
        }
    }

    fun commitInlineEdit() {
        val f = editingField ?: return
        val id = editingId ?: return
        val current = listFor(f)
        val existing = current.firstOrNull { it.id == id }
        val trimmed = editingText.trim().trimEnd(',', ';').trim()
        if (trimmed.isEmpty()) {
            updateList(f, current.filterNot { it.id == id })
        } else {
            val parsed = finalizeRecipientToken(trimmed)
            if (parsed != null && existing != null) {
                val (newName, newEmail) = parsed
                val finalName = if (newEmail.equals(existing.email, ignoreCase = true) &&
                    existing.name.isNotBlank() && !existing.name.equals(existing.email, true)
                ) {
                    existing.name
                } else {
                    newName
                }
                updateList(
                    f,
                    current.map {
                        if (it.id == id) it.copy(name = finalName, email = newEmail) else it
                    }
                )
            }
        }
        editingField = null
        editingId = null
        editingText = ""
        onClearSuggestions()
    }

    fun collectAddressesForSubmit(field: ActiveRecipientField): String {
        val emails = listFor(field).map { it.email }.toMutableList()
        val trailing = inputFor(field).trim()
        if (trailing.isNotEmpty()) {
            val parsed = finalizeRecipientToken(trailing)
            if (parsed != null) {
                if (emails.none { it.equals(parsed.second, ignoreCase = true) }) {
                    emails += parsed.second
                }
            } else {
                emails += trailing
            }
        }
        return emails.joinToString(", ")
    }

    fun formatRecipientsPreview(field: ActiveRecipientField): String {
        val formatted = listFor(field).map { rec ->
            val key = rec.email.trim().lowercase()
            val resolved = resolveDisplayName(rec.name, rec.email, personProfiles[key], knownNames)
            if (resolved.isNotBlank() && !resolved.equals(rec.email, ignoreCase = true)) {
                "$resolved <${rec.email}>"
            } else {
                rec.email
            }
        }.toMutableList()
        val trailing = inputFor(field).trim()
        if (trailing.isNotEmpty()) {
            formatted += trailing
        }
        return formatted.joinToString(", ")
    }

    fun submitEmail(send: Boolean) {
        commitInlineEdit()
        val finalSubject = formatSubjectWithTagHierarchies(subject, allHierarchies)
        onSubmit(
            draft?.id,
            draft?.changeKey,
            collectAddressesForSubmit(ActiveRecipientField.TO),
            collectAddressesForSubmit(ActiveRecipientField.CC),
            collectAddressesForSubmit(ActiveRecipientField.BCC),
            finalSubject,
            body,
            EmailImportance.NORMAL,
            selectedUris,
            context,
            send
        )
    }

    if (showSendConfirm) {
        val toPreview = formatRecipientsPreview(ActiveRecipientField.TO)
        val ccPreview = formatRecipientsPreview(ActiveRecipientField.CC)
        val bccPreview = formatRecipientsPreview(ActiveRecipientField.BCC)
        val subjectPreview = formatSubjectWithTagHierarchies(subject, allHierarchies).ifBlank { "(Без темы)" }
        AlertDialog(
            onDismissRequest = { showSendConfirm = false },
            title = { Text("Отправить письмо?") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = "Кому: $toPreview",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    if (ccPreview.isNotBlank()) {
                        Text(
                            text = "Копия: $ccPreview",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (bccPreview.isNotBlank()) {
                        Text(
                            text = "Скрытая копия: $bccPreview",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "Тема: $subjectPreview",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSendConfirm = false
                    submitEmail(send = true)
                }) { Text("Отправить") }
            },
            dismissButton = {
                TextButton(onClick = { showSendConfirm = false }) { Text("Отмена") }
            }
        )
    }

    if (showAddTagDialog) {
        var newTagInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddTagDialog = false },
            title = {
                Text(
                    if (activePath.isEmpty()) "Добавить тег"
                    else "Подтег для ${activePath.joinToString(" › ")}"
                )
            },
            text = {
                OutlinedTextField(
                    value = newTagInput,
                    onValueChange = { newTagInput = it },
                    label = {
                        Text(
                            if (activePath.isEmpty()) "Имя или путь (напр. 1 или 1/2)"
                            else "Имя подтега (напр. 2)"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val addedSegs = parseTagPath(newTagInput)
                        if (addedSegs.isNotEmpty()) {
                            val combined = if ('/' in newTagInput && activePath.isEmpty()) {
                                addedSegs
                            } else {
                                (activePath + addedSegs).take(4)
                            }
                            val path = formatTagPath(combined)
                            onAddTagTemplate(path)
                            activePathRaw = path
                        }
                        showAddTagDialog = false
                    },
                    enabled = parseTagPath(newTagInput).isNotEmpty()
                ) { Text("Добавить") }
            },
            dismissButton = {
                TextButton(onClick = { showAddTagDialog = false }) { Text("Отмена") }
            }
        )
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
                        onClick = { submitEmail(send = false) },
                        enabled = !isSending
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Сохранить черновик")
                    }
                    IconButton(
                        onClick = {
                            commitInlineEdit()
                            commitTrailingInput(ActiveRecipientField.TO)
                            commitTrailingInput(ActiveRecipientField.CC)
                            commitTrailingInput(ActiveRecipientField.BCC)
                            if (collectAddressesForSubmit(ActiveRecipientField.TO).isBlank()) {
                                submitEmail(send = true)
                            } else {
                                showSendConfirm = true
                            }
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
                .padding(horizontal = 16.dp, vertical = 10.dp)
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

            @Composable
            fun renderSection(
                field: ActiveRecipientField,
                role: ParticipantRole,
                trailingHeaderActions: @Composable () -> Unit = {}
            ) {
                RecipientStripSection(
                    role = role,
                    recipients = listFor(field),
                    inputText = inputFor(field),
                    editingId = if (editingField == field) editingId else null,
                    editingText = editingText,
                    avatars = avatars,
                    personProfiles = personProfiles,
                    knownNames = knownNames,
                    enabled = !isSending,
                    suggestions = if (activeField == field) directorySuggestions else emptyList(),
                    headerExtra = trailingHeaderActions,
                    onRequestAvatar = onRequestAvatar,
                    onRequestPersonProfile = onRequestPersonProfile,
                    onInputChange = { newVal ->
                        activeField = field
                        val (completed, remainder) = parseRecipientTokens(newVal)
                        if (completed.isNotEmpty()) {
                            addUniqueRecipients(field, completed)
                        }
                        setInput(field, remainder)
                        onSearchDirectory(remainder)
                    },
                    onInputFocusChanged = { focused ->
                        if (focused) {
                            activeField = field
                        } else {
                            commitTrailingInput(field)
                        }
                    },
                    onInputDone = { commitTrailingInput(field) },
                    onStartEdit = { rec ->
                        commitInlineEdit()
                        activeField = field
                        editingField = field
                        editingId = rec.id
                        editingText = rec.email
                    },
                    onEditTextChange = { newText ->
                        activeField = field
                        if (',' in newText || ';' in newText) {
                            val clean = newText.replace(",", "").replace(";", "").trim()
                            editingText = clean
                            commitInlineEdit()
                        } else {
                            editingText = newText
                            onSearchDirectory(newText)
                        }
                    },
                    onCommitEdit = { commitInlineEdit() },
                    onRemove = { rec ->
                        updateList(field, listFor(field).filterNot { it.id == rec.id })
                        if (editingField == field && editingId == rec.id) {
                            editingField = null
                            editingId = null
                            editingText = ""
                        }
                    },
                    onPickSuggestion = { entry ->
                        val editTargetId = if (editingField == field) editingId else null
                        if (editTargetId != null) {
                            updateList(
                                field,
                                listFor(field).map {
                                    if (it.id == editTargetId) {
                                        it.copy(name = entry.name, email = entry.email)
                                    } else {
                                        it
                                    }
                                }
                            )
                            editingField = null
                            editingId = null
                            editingText = ""
                        } else {
                            addUniqueRecipients(field, listOf(entry.name to entry.email))
                            setInput(field, "")
                        }
                        onClearSuggestions()
                    }
                )
            }

            renderSection(ActiveRecipientField.TO, ParticipantRole.TO) {
                Row {
                    if (!showCc) {
                        TextButton(onClick = { showCc = true }) { Text("+ Копия") }
                    }
                    if (!showBcc) {
                        TextButton(onClick = { showBcc = true }) { Text("+ СК") }
                    }
                }
            }

            if (showCc) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                renderSection(ActiveRecipientField.CC, ParticipantRole.CC) {
                    IconButton(
                        onClick = {
                            if (editingField == ActiveRecipientField.CC) {
                                editingField = null
                                editingId = null
                                editingText = ""
                            }
                            if (activeField == ActiveRecipientField.CC) {
                                activeField = ActiveRecipientField.TO
                                onClearSuggestions()
                            }
                            ccRecipients = emptyList()
                            ccInput = ""
                            showCc = false
                        },
                        enabled = !isSending,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Убрать копию",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (showBcc) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                renderSection(ActiveRecipientField.BCC, ParticipantRole.BCC) {
                    IconButton(
                        onClick = {
                            if (editingField == ActiveRecipientField.BCC) {
                                editingField = null
                                editingId = null
                                editingText = ""
                            }
                            if (activeField == ActiveRecipientField.BCC) {
                                activeField = ActiveRecipientField.TO
                                onClearSuggestions()
                            }
                            bccRecipients = emptyList()
                            bccInput = ""
                            showBcc = false
                        },
                        enabled = !isSending,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Убрать скрытую копию",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("Тема") },
                singleLine = true,
                enabled = !isSending,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            if (savedHierarchies.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Ветки:", style = MaterialTheme.typography.labelMedium)
                    savedHierarchies.forEachIndexed { idx, hierarchy ->
                        InputChip(
                            selected = true,
                            onClick = {
                                savedHierarchiesRaw = formatTagHierarchiesRaw(
                                    savedHierarchies.filterIndexed { i, _ -> i != idx }
                                )
                            },
                            label = { Text(hierarchy.joinToString(" › ")) },
                            trailingIcon = {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Удалить ветку",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }
            }

            if (activePath.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Путь:", style = MaterialTheme.typography.labelMedium)
                    activePath.forEachIndexed { idx, seg ->
                        InputChip(
                            selected = true,
                            onClick = {
                                activePathRaw = if (idx == activePath.lastIndex && idx == 0) {
                                    ""
                                } else {
                                    formatTagPath(activePath.take(if (idx == activePath.lastIndex) idx else idx + 1))
                                }
                            },
                            label = { Text(seg) },
                            trailingIcon = if (idx == activePath.lastIndex) {
                                {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Назад",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            } else {
                                null
                            }
                        )
                    }
                    TextButton(onClick = {
                        savedHierarchiesRaw = formatTagHierarchiesRaw(savedHierarchies + listOf(activePath))
                        activePathRaw = ""
                    }) {
                        Text("+ Ещё ветка")
                    }
                    TextButton(onClick = { activePathRaw = "" }) {
                        Text("Сброс")
                    }
                }
            }

            if (allHierarchies.isNotEmpty()) {
                val suffixPreview = formatSubjectWithTagHierarchies("", allHierarchies)
                Text(
                    text = "В конце темы: $suffixPreview",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
            }

            val nextLevelTags = remember(tagTemplates, activePath) {
                childTagsForPrefix(tagTemplates, activePath)
            }
            if (activePath.size < 4) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (activePath.isEmpty()) "Теги:" else "Подтеги:",
                        style = MaterialTheme.typography.labelMedium
                    )
                    nextLevelTags.forEach { childSeg ->
                        val nextSeg = sanitizeTagSegment(childSeg)
                        FilterChip(
                            selected = false,
                            onClick = {
                                if (nextSeg.isNotEmpty()) {
                                    activePathRaw = formatTagPath(activePath + nextSeg)
                                }
                            },
                            label = { Text(childSeg) }
                        )
                    }
                    AssistChip(
                        onClick = { showAddTagDialog = true },
                        label = {
                            Text(if (activePath.isEmpty()) "+ Тег" else "+ Подтег")
                        }
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

@Composable
private fun RecipientStripSection(
    role: ParticipantRole,
    recipients: List<ComposeRecipient>,
    inputText: String,
    editingId: Long?,
    editingText: String,
    avatars: Map<String, ImageBitmap?>,
    personProfiles: Map<String, PersonProfile?>,
    knownNames: Map<String, String>,
    enabled: Boolean,
    suggestions: List<ContactEntry>,
    headerExtra: @Composable () -> Unit,
    onRequestAvatar: (String) -> Unit,
    onRequestPersonProfile: (name: String, email: String) -> Unit,
    onInputChange: (String) -> Unit,
    onInputFocusChanged: (Boolean) -> Unit,
    onInputDone: () -> Unit,
    onStartEdit: (ComposeRecipient) -> Unit,
    onEditTextChange: (String) -> Unit,
    onCommitEdit: () -> Unit,
    onRemove: (ComposeRecipient) -> Unit,
    onPickSuggestion: (ContactEntry) -> Unit
) {
    val badgeBg = when (role) {
        ParticipantRole.TO -> MaterialTheme.colorScheme.tertiaryContainer
        ParticipantRole.CC -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ParticipantRole.BCC -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
        ParticipantRole.FROM -> MaterialTheme.colorScheme.primaryContainer
    }
    val badgeText = when (role) {
        ParticipantRole.TO -> MaterialTheme.colorScheme.onTertiaryContainer
        ParticipantRole.CC -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.50f)
        ParticipantRole.BCC -> MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.70f)
        ParticipantRole.FROM -> MaterialTheme.colorScheme.onPrimaryContainer
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                color = badgeBg,
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = role.labelRu,
                    style = MaterialTheme.typography.labelMedium,
                    color = badgeText,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
            headerExtra()
        }

        Spacer(Modifier.height(6.dp))

        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            items(recipients, key = { it.id }) { rec ->
                if (editingId == rec.id) {
                    val focusRequester = remember { FocusRequester() }
                    var hadFocus by remember { mutableStateOf(false) }
                    LaunchedEffect(rec.id) {
                        runCatching { focusRequester.requestFocus() }
                    }
                    OutlinedTextField(
                        value = editingText,
                        onValueChange = onEditTextChange,
                        singleLine = true,
                        enabled = enabled,
                        placeholder = { Text("Email...") },
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { onCommitEdit() }),
                        modifier = Modifier
                            .width(200.dp)
                            .focusRequester(focusRequester)
                            .onFocusChanged { fs ->
                                if (fs.isFocused) {
                                    hadFocus = true
                                } else if (hadFocus) {
                                    hadFocus = false
                                    onCommitEdit()
                                }
                            }
                    )
                } else {
                    ComposeRecipientTile(
                        recipient = rec,
                        role = role,
                        photo = avatars[rec.email.trim().lowercase()],
                        profile = personProfiles[rec.email.trim().lowercase()],
                        knownNames = knownNames,
                        enabled = enabled,
                        onRequestAvatar = onRequestAvatar,
                        onRequestProfile = { onRequestPersonProfile(rec.name, rec.email) },
                        onClick = { if (enabled) onStartEdit(rec) },
                        onRemove = { if (enabled) onRemove(rec) }
                    )
                }
            }

            item(key = "input-${role.name}") {
                var hadFocus by remember { mutableStateOf(false) }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    placeholder = { Text("Добавить email...") },
                    singleLine = true,
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { onInputDone() }),
                    modifier = Modifier
                        .width(195.dp)
                        .onFocusChanged { fs ->
                            if (fs.isFocused) {
                                hadFocus = true
                                onInputFocusChanged(true)
                            } else if (hadFocus) {
                                hadFocus = false
                                onInputFocusChanged(false)
                            }
                        }
                )
            }
        }

        if (suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
            ) {
                Column {
                    suggestions.forEachIndexed { idx, entry ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPickSuggestion(entry) }
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
                        if (idx < suggestions.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun ComposeRecipientTile(
    recipient: ComposeRecipient,
    role: ParticipantRole,
    photo: ImageBitmap?,
    profile: PersonProfile?,
    knownNames: Map<String, String>,
    enabled: Boolean,
    onRequestAvatar: (String) -> Unit,
    onRequestProfile: () -> Unit,
    onClick: () -> Unit,
    onRemove: () -> Unit
) {
    val emailKey = recipient.email.trim().lowercase()
    val cachedName = knownNames[emailKey]
    LaunchedEffect(recipient.email, cachedName) {
        if (needsNameResolution(recipient.name, recipient.email) &&
            cachedName.isNullOrBlank() &&
            profile == null
        ) {
            onRequestProfile()
        }
    }

    val resolvedName = resolveDisplayName(recipient.name, recipient.email, profile, knownNames)
    val nameColor = when (role) {
        ParticipantRole.TO -> MaterialTheme.colorScheme.tertiary
        ParticipantRole.CC -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        ParticipantRole.BCC -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
        ParticipantRole.FROM -> MaterialTheme.colorScheme.onSurface
    }

    Column(
        modifier = Modifier
            .width(78.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(48.dp)) {
            UserAvatar(
                name = resolvedName,
                email = recipient.email,
                photo = photo,
                size = 42.dp,
                onRequestLoad = onRequestAvatar,
                modifier = Modifier
                    .align(Alignment.Center)
                    .then(
                        if (role == ParticipantRole.TO) {
                            Modifier.border(1.5.dp, MaterialTheme.colorScheme.tertiary, CircleShape)
                        } else {
                            Modifier
                        }
                    )
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(enabled = enabled, onClick = onRemove),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Удалить",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = formatShortPersonName(resolvedName, recipient.email),
            style = MaterialTheme.typography.labelSmall,
            color = nameColor,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            fontWeight = if (role == ParticipantRole.TO) FontWeight.Medium else FontWeight.Normal
        )
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
