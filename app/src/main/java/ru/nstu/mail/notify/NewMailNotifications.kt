package ru.nstu.mail.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.nstu.mail.MainActivity
import ru.nstu.mail.R
import ru.nstu.mail.data.CredentialStore
import ru.nstu.mail.data.EmailSummary
import ru.nstu.mail.data.EwsClient
import ru.nstu.mail.data.EwsException
import ru.nstu.mail.data.MailRulesStore
import ru.nstu.mail.data.matchAutoSortRule
import ru.nstu.mail.data.shouldNotifyForMessage
import java.util.concurrent.TimeUnit

const val EXTRA_MESSAGE_ID = "extra_message_id"
const val ACTION_RESTART_PUSH = "ru.nstu.mail.ACTION_RESTART_PUSH"
const val NOTIFICATION_CHANNEL_ID = "new_mail"
const val PUSH_SERVICE_CHANNEL_ID = "push_service"
val NOTIFICATION_INTERVALS_MINUTES = listOf(15, 30, 60)

enum class NotificationMode(val labelRu: String, val descriptionRu: String) {
    PUSH("Мгновенно (Push)", "Фоновая служба — уведомление за 1–2 сек"),
    PERIODIC("По расписанию", "Экономный режим (15–60 мин, без строки в шторке)")
}

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
    private const val KEY_MODE = "notif_mode"
    private const val KEY_INTERVAL = "notif_interval_min"
    private const val KEY_SEEN_IDS = "notif_seen_ids"
    private const val KEY_BASELINE_READY = "notif_baseline_ready"
    private const val KEY_PERM_ASKED = "notif_perm_asked"

    private const val PERIODIC_WORK_NAME = "nstumail_new_mail_poll"
    private const val IMMEDIATE_WORK_NAME = "nstumail_new_mail_now"
    private const val RECOVERY_WORK_NAME = "nstumail_new_mail_recover"
    private const val NOTIFICATION_ID = 1001
    internal const val PUSH_SERVICE_NOTIFICATION_ID = 1002
    private const val REQUEST_CODE_OPEN = 1001
    private const val REQUEST_CODE_PUSH_SETTINGS = 1002
    private const val REQUEST_CODE_RESTART_ALARM = 1003

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getMode(context: Context): NotificationMode =
        runCatching {
            val saved = prefs(context).getString(KEY_MODE, NotificationMode.PUSH.name)
            NotificationMode.valueOf(saved ?: NotificationMode.PUSH.name)
        }.getOrDefault(NotificationMode.PUSH)

    fun setMode(context: Context, mode: NotificationMode) {
        prefs(context).edit().putString(KEY_MODE, mode.name).apply()
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

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun batteryOptimizationSettingsIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

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

    fun ensurePushServiceChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            if (nm.getNotificationChannel(PUSH_SERVICE_CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    PUSH_SERVICE_CHANNEL_ID,
                    "Фоновая служба почты",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                    description = "Постоянное соединение для мгновенных уведомлений. " +
                        "Этот канал можно скрыть без отключения уведомлений о письмах."
                }
                nm.createNotificationChannel(channel)
            }
        }
    }

    fun channelSettingsIntent(context: Context, channelId: String): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, channelId)
            }
        } else {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        }
    }

    fun buildPushServiceNotification(context: Context): Notification {
        ensurePushServiceChannel(context)
        val settingsIntent = channelSettingsIntent(context, PUSH_SERVICE_CHANNEL_ID).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val settingsPi = PendingIntent.getActivity(
            context,
            REQUEST_CODE_PUSH_SETTINGS,
            settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(context, PUSH_SERVICE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_mail)
            .setContentTitle("Почта НГТУ — ожидание новых писем")
            .setContentText("Можно скрыть в настройках уведомлений")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("Поддерживается соединение для мгновенных уведомлений. Нажмите, чтобы скрыть эту строку в настройках Android.")
            )
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(settingsPi)
            .build()
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

    fun syncBackgroundDelivery(context: Context, replacePeriodic: Boolean = false) {
        val app = context.applicationContext
        val hasCreds = runCatching { CredentialStore(app).load() != null }.getOrDefault(false)
        if (!isEnabled(app) || !hasSystemPermission(app) || !hasCreds) {
            stopPushService(app)
            cancelPeriodicCheck(app)
            return
        }
        when (getMode(app)) {
            NotificationMode.PUSH -> {
                runCatching {
                    ContextCompat.startForegroundService(app, Intent(app, MailPushService::class.java))
                }
                // Keep WorkManager watchdog active so swiping from Recents/OEM kills recover automatically.
                schedulePeriodicCheck(app, replaceExisting = replacePeriodic)
            }
            NotificationMode.PERIODIC -> {
                stopPushService(app)
                schedulePeriodicCheck(app, replaceExisting = replacePeriodic)
            }
        }
    }

    fun stopPushService(context: Context) {
        runCatching {
            context.applicationContext.stopService(Intent(context.applicationContext, MailPushService::class.java))
        }
    }

    fun schedulePeriodicCheck(context: Context, replaceExisting: Boolean = false) {
        if (!isEnabled(context) || !hasSystemPermission(context)) {
            cancelPeriodicCheck(context)
            return
        }
        val interval = if (getMode(context) == NotificationMode.PUSH) {
            15L
        } else {
            getIntervalMinutes(context).toLong()
        }
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

    fun scheduleDelayedRecovery(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<NewMailWorker>()
            .setInitialDelay(5, TimeUnit.SECONDS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            RECOVERY_WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    fun schedulePushRestartAlarm(context: Context, delayMs: Long = 2_500L) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val restartIntent = Intent(app, MailBootReceiver::class.java).apply {
            action = ACTION_RESTART_PUSH
        }
        val pi = PendingIntent.getBroadcast(
            app,
            REQUEST_CODE_RESTART_ALARM,
            restartIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + delayMs,
            pi
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
        wm.cancelUniqueWork(RECOVERY_WORK_NAME)
    }

    fun cancelNotifications(context: Context) {
        NotificationManagerCompat.from(context.applicationContext).cancel(NOTIFICATION_ID)
    }

    fun clearOnLogout(context: Context) {
        stopPushService(context)
        cancelPeriodicCheck(context)
        cancelNotifications(context)
        MailRulesStore.clearAccountRulesOnLogout(context)
        prefs(context).edit()
            .remove(KEY_SEEN_IDS)
            .remove(KEY_BASELINE_READY)
            .apply()
    }

    suspend fun fetchUnreadAndNotify(context: Context, client: EwsClient) {
        val unread = client.listInboxUnread(pageSize = 15)
        val existing = getSeenIds(context)
        val baselineReady = isBaselineReady(context)
        val toProcess = selectNewMessagesToNotify(unread, existing.toSet(), baselineReady)
        val merged = mergeSeenMessageIds(existing, unread.map { it.id })
        saveSeenIds(context, merged)
        if (toProcess.isEmpty()) return

        val rules = MailRulesStore.getTagSortRules(context)
        val mutedFolders = MailRulesStore.getMutedFolderIds(context)
        val mutedSenders = MailRulesStore.getMutedSenders(context)
        val savedInboxId = MailRulesStore.getInboxFolderId(context)

        val toNotify = mutableListOf<EmailSummary>()
        for (msg in toProcess) {
            val matchedRule = matchAutoSortRule(msg.subject, rules)
            var effectiveFolderId = savedInboxId
            var inInbox = true
            if (matchedRule != null &&
                matchedRule.targetFolderId.isNotBlank() &&
                matchedRule.targetFolderId != savedInboxId
            ) {
                val moved = runCatching {
                    client.moveMessages(listOf(msg.id to msg.changeKey), matchedRule.targetFolderId)
                }.isSuccess
                if (moved) {
                    effectiveFolderId = matchedRule.targetFolderId
                    inInbox = false
                }
            }
            if (shouldNotifyForMessage(
                    senderEmail = msg.senderEmail,
                    effectiveFolderId = effectiveFolderId,
                    isInbox = inInbox,
                    savedInboxFolderId = savedInboxId,
                    mutedFolderIds = mutedFolders,
                    mutedSenders = mutedSenders
                )
            ) {
                toNotify += msg
            }
        }
        if (toNotify.isNotEmpty()) {
            showNewMailNotification(context, toNotify)
        }
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

        val mode = MailNotificationHelper.getMode(applicationContext)
        if (mode == NotificationMode.PUSH) {
            if (MailPushService.isRunning) {
                return Result.success()
            }
            runCatching {
                ContextCompat.startForegroundService(
                    applicationContext,
                    Intent(applicationContext, MailPushService::class.java)
                )
            }
        }

        return try {
            MailNotificationHelper.fetchUnreadAndNotify(applicationContext, EwsClient(creds))
            Result.success()
        } catch (e: EwsException) {
            if (e.isAuthError) Result.success()
            else if (runAttemptCount < 2) Result.retry() else Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < 2) Result.retry() else Result.success()
        }
    }
}

class MailPushService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var streamJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = MailNotificationHelper.buildPushServiceNotification(this)
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            MailNotificationHelper.PUSH_SERVICE_NOTIFICATION_ID,
            notification,
            serviceType
        )

        val creds = runCatching { CredentialStore(applicationContext).load() }.getOrNull()
        if (!MailNotificationHelper.isEnabled(applicationContext) ||
            MailNotificationHelper.getMode(applicationContext) != NotificationMode.PUSH ||
            !MailNotificationHelper.hasSystemPermission(applicationContext) ||
            creds == null
        ) {
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }

        isRunning = true
        if (streamJob?.isActive != true) {
            streamJob = serviceScope.launch {
                runStreamingLoop()
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (MailNotificationHelper.isEnabled(applicationContext) &&
            MailNotificationHelper.getMode(applicationContext) == NotificationMode.PUSH &&
            MailNotificationHelper.hasSystemPermission(applicationContext)
        ) {
            MailNotificationHelper.schedulePushRestartAlarm(applicationContext)
            MailNotificationHelper.schedulePeriodicCheck(applicationContext, replaceExisting = false)
            MailNotificationHelper.scheduleDelayedRecovery(applicationContext)
        }
        super.onTaskRemoved(rootIntent)
    }

    private suspend fun runStreamingLoop() {
        var backoffMs = 5_000L
        while (serviceScope.isActive) {
            if (!MailNotificationHelper.isEnabled(applicationContext) ||
                MailNotificationHelper.getMode(applicationContext) != NotificationMode.PUSH ||
                !MailNotificationHelper.hasSystemPermission(applicationContext)
            ) {
                isRunning = false
                stopSelf()
                return
            }
            val creds = runCatching { CredentialStore(applicationContext).load() }.getOrNull()
            if (creds == null) {
                isRunning = false
                stopSelf()
                return
            }

            val client = EwsClient(creds)
            var subscriptionId = ""
            try {
                MailNotificationHelper.fetchUnreadAndNotify(applicationContext, client)
                subscriptionId = client.createStreamingSubscription()
                backoffMs = 5_000L

                while (serviceScope.isActive && subscriptionId.isNotBlank()) {
                    var invalid = false
                    client.streamInboxEvents(subscriptionId, timeoutMinutes = 25) { chunk ->
                        if (chunk.newMailItemIds.isNotEmpty()) {
                            MailNotificationHelper.fetchUnreadAndNotify(applicationContext, client)
                        }
                        if (chunk.subscriptionInvalid) {
                            invalid = true
                        }
                    }
                    if (invalid) {
                        subscriptionId = ""
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: EwsException) {
                if (e.isAuthError) {
                    isRunning = false
                    stopSelf()
                    return
                }
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            } catch (_: Exception) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(60_000L)
            } finally {
                if (subscriptionId.isNotBlank()) {
                    runCatching { client.unsubscribe(subscriptionId) }
                }
            }
        }
    }

    override fun onDestroy() {
        isRunning = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        @Volatile
        var isRunning: Boolean = false
    }
}

class MailBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == ACTION_RESTART_PUSH
        ) {
            MailNotificationHelper.syncBackgroundDelivery(context)
        }
    }
}
