package ru.nstu.mail.desktop

import ru.nstu.mail.data.EmailSummary
import ru.nstu.mail.data.EwsClient
import ru.nstu.mail.data.EwsException
import ru.nstu.mail.data.MailFolder
import ru.nstu.mail.data.TagSortRule
import ru.nstu.mail.data.UNSORTED_FOLDER_NAME
import ru.nstu.mail.data.extractAllTagHierarchiesForMatching
import ru.nstu.mail.data.isNstuSender
import ru.nstu.mail.data.matchAllAutoSortRules
import ru.nstu.mail.data.shouldRouteToUnsorted
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import javax.swing.SwingUtilities

data class DesktopAutoSortOutcome(
    val movedFromInbox: Boolean,
    val targetFolderIds: List<String>,
    val foldersChanged: Boolean,
    val errorMessage: String? = null
)

object DesktopAutoSorter {

    suspend fun applyAutoSortForMessage(
        settingsStore: DesktopSettingsStore,
        client: EwsClient,
        msg: EmailSummary,
        rules: List<TagSortRule>,
        inboxFolderId: String?,
        foldersCache: MutableList<MailFolder>? = null
    ): Pair<DesktopAutoSortOutcome, MutableList<MailFolder>?> {
        var cache = foldersCache
        var foldersChanged = false
        var lastError: String? = null

        suspend fun ensureFoldersCache(): MutableList<MailFolder>? {
            if (cache == null) {
                cache = runCatching { client.getFolders().toMutableList() }.getOrNull()
            }
            return cache
        }

        suspend fun moveResilient(targetId: String): Result<Unit> {
            val first = runCatching { client.moveMessages(listOf(msg.id to msg.changeKey), targetId) }
            if (first.isSuccess) return first
            val err = first.exceptionOrNull()
            if (err is EwsException && err.isAuthError) return first
            return if (msg.changeKey.isNotBlank()) {
                runCatching { client.moveMessages(listOf(msg.id to ""), targetId) }
            } else {
                first
            }
        }

        suspend fun copyResilient(targetId: String): Result<Unit> {
            val first = runCatching { client.copyMessages(listOf(msg.id to msg.changeKey), targetId) }
            if (first.isSuccess) return first
            val err = first.exceptionOrNull()
            if (err is EwsException && err.isAuthError) return first
            return if (msg.changeKey.isNotBlank()) {
                runCatching { client.copyMessages(listOf(msg.id to ""), targetId) }
            } else {
                first
            }
        }

        suspend fun resolveSubfolderTarget(
            baseFolderId: String,
            remainingSubtags: List<String>,
            autoCreateSubfolders: Boolean
        ): String {
            var targetId = baseFolderId
            if (!autoCreateSubfolders || remainingSubtags.isEmpty()) return targetId
            val workingFolders = ensureFoldersCache() ?: return targetId
            for (subSeg in remainingSubtags.take(3)) {
                val safeName = subSeg.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(36)
                if (safeName.isEmpty()) break
                var child = workingFolders.firstOrNull {
                    it.parentId == targetId && it.displayName.equals(safeName, ignoreCase = true)
                }
                if (child == null) {
                    val created = runCatching {
                        client.createFolder(safeName, parentFolderId = targetId)
                        client.getFolders()
                    }.getOrNull()
                    if (created != null) {
                        workingFolders.clear()
                        workingFolders.addAll(created)
                        foldersChanged = true
                        child = workingFolders.firstOrNull {
                            it.parentId == targetId && it.displayName.equals(safeName, ignoreCase = true)
                        }
                        if (child != null) {
                            val currentSettings = settingsStore.load()
                            if (targetId in currentSettings.mutedFolderIds) {
                                settingsStore.save(
                                    currentSettings.copy(mutedFolderIds = currentSettings.mutedFolderIds + child.id)
                                )
                            }
                        }
                    }
                }
                if (child != null) targetId = child.id else break
            }
            return targetId
        }

        val matches = matchAllAutoSortRules(msg.subject, rules)
        if (matches.isNotEmpty()) {
            val workingFolders = ensureFoldersCache()
            val resolvedIds = mutableListOf<String>()
            for (match in matches) {
                val baseId = match.rule.targetFolderId
                if (baseId.isBlank()) continue
                if (workingFolders != null && workingFolders.none { it.id == baseId }) continue
                val resolved = resolveSubfolderTarget(
                    baseFolderId = baseId,
                    remainingSubtags = match.remainingSubtags,
                    autoCreateSubfolders = match.rule.autoCreateSubfolders
                )
                if (resolved.isNotBlank() && resolved !in resolvedIds) {
                    resolvedIds += resolved
                }
            }
            if (resolvedIds.isNotEmpty()) {
                val externalTargets = resolvedIds.filter { it != inboxFolderId }
                if (externalTargets.isEmpty()) {
                    return DesktopAutoSortOutcome(false, resolvedIds, foldersChanged) to cache
                }
                val keepInInbox = inboxFolderId != null && inboxFolderId in resolvedIds
                if (keepInInbox) {
                    for (fid in externalTargets) {
                        copyResilient(fid).onFailure { err -> lastError = lastError ?: err.message }
                    }
                    return DesktopAutoSortOutcome(false, resolvedIds, foldersChanged, lastError) to cache
                } else {
                    for (extraId in externalTargets.drop(1)) {
                        copyResilient(extraId).onFailure { err -> lastError = lastError ?: err.message }
                    }
                    val moveRes = moveResilient(externalTargets.first())
                    if (moveRes.isSuccess) {
                        return DesktopAutoSortOutcome(true, externalTargets, foldersChanged, lastError) to cache
                    } else {
                        lastError = moveRes.exceptionOrNull()?.message ?: lastError
                    }
                }
            }
        }

        if (extractAllTagHierarchiesForMatching(msg.subject).isNotEmpty()) {
            var effectiveSender = msg.senderEmail
            if (!isNstuSender(effectiveSender)) {
                val fromDetail = runCatching { client.getMessage(msg.id).fromEmail }.getOrNull().orEmpty()
                if (fromDetail.isNotBlank()) effectiveSender = fromDetail
            }
            if (!isNstuSender(effectiveSender)) {
                val profile = runCatching {
                    client.resolvePersonProfile(effectiveSender, msg.senderName)
                }.getOrNull()
                if (profile != null) {
                    val nstuAddr = (listOf(profile.email) + profile.aliases).firstOrNull { isNstuSender(it) }
                    if (nstuAddr != null) effectiveSender = nstuAddr
                }
            }
            if (shouldRouteToUnsorted(msg.subject, matchedRulesCount = 0, senderEmail = effectiveSender)) {
                val workingFolders = ensureFoldersCache()
                var unsorted = workingFolders?.firstOrNull {
                    !it.isSystem && it.displayName.equals(UNSORTED_FOLDER_NAME, ignoreCase = true)
                }
                if (unsorted == null && workingFolders != null) {
                    val refreshed = runCatching { client.getFolders() }.getOrNull()
                    if (refreshed != null) {
                        workingFolders.clear()
                        workingFolders.addAll(refreshed)
                        unsorted = workingFolders.firstOrNull {
                            !it.isSystem && it.displayName.equals(UNSORTED_FOLDER_NAME, ignoreCase = true)
                        }
                    }
                    if (unsorted == null) {
                        val created = runCatching {
                            client.createFolder(UNSORTED_FOLDER_NAME, parentFolderId = null)
                            client.getFolders()
                        }.getOrElse { err ->
                            lastError = lastError ?: err.message
                            null
                        }
                        if (created != null) {
                            workingFolders.clear()
                            workingFolders.addAll(created)
                            foldersChanged = true
                            unsorted = workingFolders.firstOrNull {
                                !it.isSystem && it.displayName.equals(UNSORTED_FOLDER_NAME, ignoreCase = true)
                            }
                        }
                    }
                }
                if (unsorted != null && unsorted.id != inboxFolderId) {
                    val moveRes = moveResilient(unsorted.id)
                    if (moveRes.isFailure) lastError = moveRes.exceptionOrNull()?.message ?: lastError
                    return DesktopAutoSortOutcome(
                        movedFromInbox = moveRes.isSuccess,
                        targetFolderIds = if (moveRes.isSuccess) listOf(unsorted.id) else emptyList(),
                        foldersChanged = foldersChanged,
                        errorMessage = lastError
                    ) to cache
                }
            }
        }

        return DesktopAutoSortOutcome(false, emptyList(), foldersChanged, lastError) to cache
    }
}

object DesktopNotificationPoster {
    @Volatile
    private var trayIcon: TrayIcon? = null

    fun notify(title: String, message: String) {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        if ("linux" in os) {
            val sent = runCatching {
                ProcessBuilder("notify-send", "-a", "Почта НГТУ", title, message)
                    .redirectErrorStream(true)
                    .start()
                    .waitFor() == 0
            }.getOrDefault(false)
            if (sent) return
        }
        if (!SystemTray.isSupported()) return
        SwingUtilities.invokeLater {
            runCatching {
                val icon = ensureTrayIcon() ?: return@runCatching
                icon.displayMessage(title, message, TrayIcon.MessageType.INFO)
            }
        }
    }

    private fun ensureTrayIcon(): TrayIcon? {
        trayIcon?.let { return it }
        val url = DesktopNotificationPoster::class.java.getResource("/nstumail.png") ?: return null
        val img = Toolkit.getDefaultToolkit().getImage(url)
        val icon = TrayIcon(img, "Почта НГТУ").apply { isImageAutoSize = true }
        SystemTray.getSystemTray().add(icon)
        trayIcon = icon
        return icon
    }
}
