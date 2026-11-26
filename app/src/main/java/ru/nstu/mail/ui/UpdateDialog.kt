package ru.nstu.mail.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.nstu.mail.BuildConfig
import ru.nstu.mail.data.AppUpdateInfo

@Composable
fun UpdateDialog(
    update: AppUpdateInfo,
    downloadProgress: Float?,
    isDownloaded: Boolean,
    errorMessage: String?,
    onInstallOrDownload: () -> Unit,
    onDismiss: () -> Unit
) {
    val isDownloading = downloadProgress != null && !isDownloaded

    AlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = {
            Text("Доступно обновление ${update.tagName}")
        },
        text = {
            Column {
                Text(
                    text = "Текущая версия: v${BuildConfig.VERSION_NAME} → новая: v${update.versionName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (update.apkSizeBytes > 0L) {
                    val mb = update.apkSizeBytes / (1024.0 * 1024.0)
                    Text(
                        text = String.format("Размер APK: %.1f МБ", mb),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (update.releaseNotes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = "Что нового:",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = update.releaseNotes,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                if (isDownloading) {
                    Spacer(Modifier.height(12.dp))
                    val pct = ((downloadProgress ?: 0f) * 100).toInt()
                    LinearProgressIndicator(
                        progress = { downloadProgress ?: 0f },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Скачивание: $pct%",
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                if (!errorMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onInstallOrDownload,
                enabled = !isDownloading
            ) {
                Text(if (isDownloaded) "Установить" else "Скачать и обновить")
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = onDismiss) {
                    Text("Позже")
                }
            }
        }
    )
}
