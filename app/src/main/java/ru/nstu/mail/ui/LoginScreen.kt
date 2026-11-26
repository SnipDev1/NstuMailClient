package ru.nstu.mail.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import ru.nstu.mail.data.Credentials
import ru.nstu.mail.data.DEFAULT_SERVER_URL

@Composable
fun LoginScreen(
    initialCredentials: Credentials?,
    isLoading: Boolean,
    errorMessage: String?,
    themeMode: AppThemeMode,
    onSetThemeMode: (AppThemeMode) -> Unit,
    onLogin: (email: String, password: String, serverUrl: String) -> Unit
) {
    var email by rememberSaveable { mutableStateOf(initialCredentials?.email.orEmpty()) }
    var password by remember { mutableStateOf(initialCredentials?.password.orEmpty()) }
    var serverUrl by rememberSaveable {
        mutableStateOf(initialCredentials?.serverUrl ?: DEFAULT_SERVER_URL)
    }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    var showServerSettings by rememberSaveable { mutableStateOf(false) }

    Scaffold { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            IconButton(
                onClick = {
                    val next = when (themeMode) {
                        AppThemeMode.SYSTEM -> AppThemeMode.DARK
                        AppThemeMode.DARK -> AppThemeMode.LIGHT
                        AppThemeMode.LIGHT -> AppThemeMode.SYSTEM
                    }
                    onSetThemeMode(next)
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
            ) {
                Icon(
                    imageVector = when (themeMode) {
                        AppThemeMode.DARK -> Icons.Default.DarkMode
                        AppThemeMode.LIGHT -> Icons.Default.LightMode
                        AppThemeMode.SYSTEM -> Icons.Default.BrightnessAuto
                    },
                    contentDescription = "Тема: ${themeMode.labelRu}"
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            Icon(
                imageVector = Icons.Default.Email,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(64.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = "Почта НГТУ",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Microsoft Exchange (mail2.nstu.ru)",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Почта / Логин") },
                placeholder = { Text("username@stud.nstu.ru") },
                singleLine = true,
                enabled = !isLoading,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Пароль") },
                singleLine = true,
                enabled = !isLoading,
                visualTransformation = if (passwordVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            imageVector = if (passwordVisible) {
                                Icons.Default.VisibilityOff
                            } else {
                                Icons.Default.Visibility
                            },
                            contentDescription = "Показать пароль"
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onLogin(email, password, serverUrl) }
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = { showServerSettings = !showServerSettings }) {
                    Text(if (showServerSettings) "Скрыть настройки" else "Настройки сервера")
                }
            }

            AnimatedVisibility(visible = showServerSettings) {
                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = { Text("Сервер Exchange (HTTPS)") },
                    singleLine = true,
                    enabled = !isLoading,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                )
            }

            if (!errorMessage.isNullOrBlank()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            Button(
                onClick = { onLogin(email, password, serverUrl) },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text("Войти")
                }
            }
            }
        }
    }
}
