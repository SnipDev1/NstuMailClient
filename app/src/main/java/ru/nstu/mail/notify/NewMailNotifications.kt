package ru.nstu.mail.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ru.nstu.mail.MainActivity
import ru.nstu.mail.R
import ru.nstu.mail.data.CredentialStore
import ru.nstu.mail.data.EmailSummary
import ru.nstu.mail.data.EwsClient
import ru.nstu.mail.data.EwsException
import java.util.concurrent.TimeUnit

const val EXTRA_MESSAGE_ID = "extra_message_id"
const val NOTIFICATION_CHANNEL_ID = "new_mail"
val NOTIFICATION_INTERVALS_MINUTES = listOf(15, 30, 60)

fun selectNewMessagesToNotify(
    unread: List<EmailSummary>,
    seenIds: Set<String>,
    initialized: Boolean
): List<EmailSummary> {
    if (!initialized) return emptyList()
    return unread.filter { it.id.isNotBlank() && it.id !in seenIds }
}

fun mergeSeenMessageIds(
    existing: List<String>,
    incoming: Iterable<String>,
    maxSize: Int = 200
): List<String> {
    val result = existing.filter { it.isNotBlank() }.toMutableList()
    val present = result.toHashSet()
    for (id in incoming) {
        if (id.isNotBlank() && present.add(id)) {
            result += id
        }
    }
    return if (result.size > maxSize) {
        result.takeLast(maxSize)
    } else {
        result
    }
}

object MailNotificationHelper {
    private const val PREFS_NAME = "nstumail_prefs"
    private const val KEY_ENABLED = "notif_enabled"
    private const val KEY_INTERVAL = "notif_interval_min"
    private const val KEY_SEEN_IDS = "notif_seen_ids"
    private const val KEY_BASELINE_READY = "notif_baseline_ready"
    private const val KEY_PERM_ASKED = "notif_perm_asked"

    private const val PERIODIC_WORK_NAME = "nstumail_new_mail_poll"
    private const val IMMEDIATE_WORK_NAME = "nstumail_new_mail_now"
    private const val NOTIFICATION_ID = 1001
    private const val REQUEST_CODE_OPEN = 1001

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getIntervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_INTERVAL, 15).coerceAtLeast(15)

    fun setIntervalMinutes(context: Context, minutes: Int) {
        prefs(context).edit().putInt(KEY_INTERVAL, minutes.coerceAtLeast(15)).apply()
    }

    fun wasPermissionAsked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PERM_ASKED, false)

    fun markPermissionAsked(context: Context) {
        prefs(context).edit().putBoolean(KEY_PERM_ASKED, true).apply()
    }

    fun hasSystemPermission(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            true
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Новые письма",
                    NotificationManager.IMPORTANCE_DEFAULT
                ).apply {
                    description = "Уведомления о входящих письмах Почты НГТУ"
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun getSeenIds(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_SEEN_IDS, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split('\n').filter { it.isNotBlank() }
    }

    fun isBaselineReady(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BASELINE_READY, false)

    fun saveSeenIds(context: Context, ids: List<String>) {
        prefs(context).edit()
            .putString(KEY_SEEN_IDS, ids.joinToString("\n"))
            .putBoolean(KEY_BASELINE_READY, true)
            .apply()
    }

    fun recordSeenMessages(context: Context, messages: List<EmailSummary>) {
        val merged = mergeSeenMessageIds(getSeenIds(context), messages.map { it.id })
        saveSeenIds(context, merged)
        cancelNotifications(context)
    }

    fun schedulePeriodicCheck(context: Context, replaceExisting: Boolean = false) {
        if (!isEnabled(context) || !hasSystemPermission(context)) {
            cancelPeriodicCheck(context)
            return
        }
        val interval = getIntervalMinutes(context).toLong()
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<NewMailWorker>(interval, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            if (replaceExisting) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun runImmediateCheck(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<NewMailWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun cancelPeriodicCheck(context: Context) {
        val wm = WorkManager.getInstance(context.applicationContext)
        wm.cancelUniqueWork(PERIODIC_WORK_NAME)
        wm.cancelUniqueWork(IMMEDIATE_WORK_NAME)
    }

    fun cancelNotifications(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    fun clearOnLogout(context: Context) {
        cancelPeriodicCheck(context)
        cancelNotifications(context)
        prefs(context).edit()
            .remove(KEY_SEEN_IDS)
            .remove(KEY_BASELINE_READY)
            .apply()
    }

    @SuppressLint("MissingPermission")
    fun showNewMailNotification(context: Context, newMessages: List<EmailSummary>) {
        if (newMessages.isEmpty() || !hasSystemPermission(context)) return
        ensureChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (newMessages.size == 1) {
                putExtra(EXTRA_MESSAGE_ID, newMessages.first().id)
            }
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            REQUEST_CODE_OPEN,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)

        if (newMessages.size == 1) {
            val msg = newMessages.first()
            val sender = msg.senderName.ifBlank { msg.senderEmail.ifBlank { "Новое письмо" } }
            val bigText = listOf(msg.subject, msg.preview)
                .filter { it.isNotBlank() }
                .joinToString("\n")
            builder
                .setContentTitle(sender)
                .setContentText(msg.subject)
                .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
        } else {
            val first = newMessages.first()
            val firstSender = first.senderName.ifBlank { first.senderEmail }
            val inboxStyle = NotificationCompat.InboxStyle()
            newMessages.take(5).forEach { m ->
                val s = m.senderName.ifBlank { m.senderEmail }
                inboxStyle.addLine("$s: ${m.subject}")
            }
            if (newMessages.size > 5) {
                inboxStyle.setSummaryText("И ещё ${newMessages.size - 5}")
            }
            builder
                .setContentTitle("Новых писем: ${newMessages.size}")
                .setContentText("$firstSender: ${first.subject}")
                .setStyle(inboxStyle)
        }

        NotificationManagerCompat.from(context.applicationContext)
            .notify(NOTIFICATION_ID, builder.build())
    }
}

class NewMailWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!MailNotificationHelper.isEnabled(applicationContext) ||
            !MailNotificationHelper.hasSystemPermission(applicationContext)
        ) {
            return Result.success()
        }

        val creds = runCatching { CredentialStore(applicationContext).load() }.getOrNull()
            ?: return Result.success()

        val unread = try {
            EwsClient(creds).listInboxUnread(pageSize = 15)
        } catch (e: EwsException) {
            if (e.isAuthError) return Result.success()
            return if (runAttemptCount < 2) Result.retry() else Result.success()
        } catch (_: Exception) {
            return if (runAttemptCount < 2) Result.retry() else Result.success()
        }

        val existing = MailNotificationHelper.getSeenIds(applicationContext)
        val baselineReady = MailNotificationHelper.isBaselineReady(applicationContext)
        val toNotify = selectNewMessagesToNotify(unread, existing.toSet(), baselineReady)
        val merged = mergeSeenMessageIds(existing, unread.map { it.id })
        MailNotificationHelper.saveSeenIds(applicationContext, merged)

        if (toNotify.isNotEmpty()) {
            MailNotificationHelper.showNewMailNotification(applicationContext, toNotify)
        }
        return Result.success()
    }
}
