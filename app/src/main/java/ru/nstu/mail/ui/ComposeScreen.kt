package ru.nstu.mail.ui

import android.text.Html
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ru.nstu.mail.data.EmailDetail

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    replyTo: EmailDetail?,
    isForward: Boolean,
    isSending: Boolean,
    errorMessage: String?,
    onBack: () -> Unit,
    onSend: (to: String, cc: String, subject: String, body: String) -> Unit
) {
    BackHandler(enabled = !isSending, onBack = onBack)

    var to by rememberSaveable {
        mutableStateOf(
            if (replyTo != null && !isForward) replyTo.fromEmail else ""
        )
    }
    var cc by rememberSaveable { mutableStateOf("") }
    var subject by rememberSaveable {
        mutableStateOf(
            when {
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
            if (replyTo != null) {
                val plain = if (replyTo.isHtml) {
                    Html.fromHtml(replyTo.body, Html.FROM_HTML_MODE_COMPACT).toString().trim()
                } else {
                    replyTo.body.trim()
                }
                val date = MailViewModel.formatMailDate(replyTo.receivedAt, detailed = true)
                "\n\n--- Исходное сообщение ---\nОт: ${replyTo.from}\nДата: $date\n\n$plain"
            } else {
                ""
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            replyTo == null -> "Новое письмо"
                            isForward -> "Переслать"
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
                        onClick = { onSend(to, cc, subject, body) },
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
                onValueChange = { to = it },
                label = { Text("Кому (через запятую)") },
                singleLine = true,
                enabled = !isSending,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = cc,
                onValueChange = { cc = it },
                label = { Text("Копия (необязательно)") },
                singleLine = true,
                enabled = !isSending,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("Тема") },
                singleLine = true,
                enabled = !isSending,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))

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
