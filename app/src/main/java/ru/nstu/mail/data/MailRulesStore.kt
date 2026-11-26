package ru.nstu.mail.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TagSortRule(
    val tag: String,
    val targetFolderId: String,
    val targetFolderName: String
)

enum class SwipeAction(val labelRu: String) {
    NONE("Нет действия"),
    TOGGLE_READ("Прочитано / Непрочитано"),
    DELETE("Удалить"),
    TOGGLE_FLAG("Флажок (Избранное)"),
    MOVE("Переместить в папку"),
    SPAM("В спам / Не спам")
}

val DEFAULT_TAGS = listOf("ПЗ", "Лаба", "Лекция", "Учёба", "Важно")

private val LEADING_TAG_REGEX = Regex("^\\s*\\[([^\\[\\]]{1,24})]\\s*")
private val ANY_BRACKET_TAG_REGEX = Regex("\\[([^\\[\\]]{1,24})]")
private val HASH_TAG_REGEX = Regex("(?:^|\\s)#([\\p{L}\\p{N}_-]{1,24})(?=\\s|\$|[.,!?;:])")

fun extractSubjectTags(subject: String): Pair<List<String>, String> {
    var remaining = subject
    val tags = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    while (true) {
        val match = LEADING_TAG_REGEX.find(remaining) ?: break
        val tag = match.groupValues[1].trim()
        if (tag.isNotEmpty() && seen.add(tag.lowercase())) {
            tags += tag
        }
        remaining = remaining.substring(match.range.last + 1)
    }
    return tags to remaining.trim()
}

fun extractAllTagsForMatching(subject: String): List<String> {
    val tags = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    for (m in ANY_BRACKET_TAG_REGEX.findAll(subject)) {
        val t = m.groupValues[1].trim()
        if (t.isNotEmpty() && seen.add(t.lowercase())) tags += t
    }
    for (m in HASH_TAG_REGEX.findAll(subject)) {
        val t = m.groupValues[1].trim()
        if (t.isNotEmpty() && seen.add(t.lowercase())) tags += t
    }
    return tags
}

fun formatSubjectWithTags(cleanSubject: String, tags: List<String>): String {
    val (_, stripped) = extractSubjectTags(cleanSubject)
    val distinctTags = tags
        .map { it.trim().replace("[", "").replace("]", "") }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
    val prefix = distinctTags.joinToString(" ") { "[$it]" }
    return when {
        prefix.isEmpty() -> cleanSubject.trim()
        stripped.isEmpty() -> prefix
        else -> "$prefix $stripped"
    }
}

fun matchAutoSortRule(subject: String, rules: List<TagSortRule>): TagSortRule? {
    if (rules.isEmpty() || subject.isBlank()) return null
    val subjectTags = extractAllTagsForMatching(subject)
    if (subjectTags.isEmpty()) return null
    for (tag in subjectTags) {
        val rule = rules.firstOrNull { it.tag.trim().equals(tag, ignoreCase = true) }
        if (rule != null) return rule
    }
    return null
}

fun shouldNotifyForMessage(
    senderEmail: String,
    effectiveFolderId: String?,
    isInbox: Boolean,
    savedInboxFolderId: String?,
    mutedFolderIds: Set<String>,
    mutedSenders: Set<String>
): Boolean {
    val normSender = senderEmail.trim().lowercase()
    if (normSender.isNotEmpty() && normSender in mutedSenders) return false
    if (!effectiveFolderId.isNullOrBlank() && effectiveFolderId in mutedFolderIds) return false
    if (isInbox && (
            "inbox" in mutedFolderIds ||
                (!savedInboxFolderId.isNullOrBlank() && savedInboxFolderId in mutedFolderIds)
            )
    ) {
        return false
    }
    return true
}

object MailRulesStore {
    private const val PREFS_NAME = "nstumail_prefs"
    private const val KEY_MUTED_FOLDERS = "rules_muted_folders"
    private const val KEY_MUTED_SENDERS = "rules_muted_senders"
    private const val KEY_CUSTOM_TAGS = "rules_custom_tags"
    private const val KEY_SORT_RULES = "rules_tag_sort"
    private const val KEY_INBOX_ID = "rules_inbox_folder_id"
    private const val KEY_SWIPE_RIGHT = "gesture_swipe_right"
    private const val KEY_SWIPE_LEFT = "gesture_swipe_left"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getInboxFolderId(context: Context): String? =
        prefs(context).getString(KEY_INBOX_ID, null)?.takeIf { it.isNotBlank() }

    fun setInboxFolderId(context: Context, folderId: String) {
        if (folderId.isBlank()) return
        val existing = getInboxFolderId(context)
        if (existing != folderId) {
            prefs(context).edit().putString(KEY_INBOX_ID, folderId).apply()
        }
    }

    fun getMutedFolderIds(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_MUTED_FOLDERS, emptySet())?.toSet().orEmpty()

    fun setFolderMuted(
        context: Context,
        folderId: String,
        muted: Boolean,
        isInbox: Boolean = false
    ): Set<String> {
        val current = getMutedFolderIds(context).toMutableSet()
        if (muted) {
            if (folderId.isNotBlank()) current += folderId
            if (isInbox) current += "inbox"
        } else {
            current.remove(folderId)
            if (isInbox || folderId == getInboxFolderId(context) || folderId == "inbox") {
                current.remove("inbox")
                getInboxFolderId(context)?.let { current.remove(it) }
            }
        }
        prefs(context).edit().putStringSet(KEY_MUTED_FOLDERS, current).apply()
        return current
    }

    fun toggleFolderMuted(
        context: Context,
        folderId: String,
        isInbox: Boolean = false
    ): Set<String> {
        val current = getMutedFolderIds(context)
        val currentlyMuted = folderId in current || (isInbox && "inbox" in current)
        return setFolderMuted(context, folderId, muted = !currentlyMuted, isInbox = isInbox)
    }

    fun getMutedSenders(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_MUTED_SENDERS, emptySet())
            ?.map { it.trim().lowercase() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            .orEmpty()

    fun setSenderMuted(context: Context, email: String, muted: Boolean): Set<String> {
        val norm = email.trim().lowercase()
        if (norm.isEmpty()) return getMutedSenders(context)
        val current = getMutedSenders(context).toMutableSet()
        if (muted) current += norm else current -= norm
        prefs(context).edit().putStringSet(KEY_MUTED_SENDERS, current).apply()
        return current
    }

    fun toggleSenderMuted(context: Context, email: String): Set<String> {
        val norm = email.trim().lowercase()
        val current = getMutedSenders(context)
        return setSenderMuted(context, norm, muted = norm !in current)
    }

    fun getCustomTags(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_CUSTOM_TAGS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                arr.optString(i, "").trim().takeIf { it.isNotEmpty() }
            }.distinctBy { it.lowercase() }
        }.getOrDefault(emptyList())
    }

    fun addCustomTag(context: Context, tag: String): List<String> {
        val clean = tag.trim().replace("[", "").replace("]", "").take(24)
        if (clean.isEmpty()) return getCustomTags(context)
        val next = (getCustomTags(context) + clean).distinctBy { it.lowercase() }
        val arr = JSONArray().apply { next.forEach { put(it) } }
        prefs(context).edit().putString(KEY_CUSTOM_TAGS, arr.toString()).apply()
        return next
    }

    fun getTagSortRules(context: Context): List<TagSortRule> {
        val raw = prefs(context).getString(KEY_SORT_RULES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val tag = obj.optString("tag", "").trim()
                val fid = obj.optString("folderId", "").trim()
                val fname = obj.optString("folderName", "").trim()
                if (tag.isEmpty() || fid.isEmpty()) null
                else TagSortRule(tag = tag, targetFolderId = fid, targetFolderName = fname)
            }
        }.getOrDefault(emptyList())
    }

    private fun saveTagSortRules(context: Context, rules: List<TagSortRule>) {
        val arr = JSONArray()
        for (r in rules) {
            arr.put(
                JSONObject().apply {
                    put("tag", r.tag)
                    put("folderId", r.targetFolderId)
                    put("folderName", r.targetFolderName)
                }
            )
        }
        prefs(context).edit().putString(KEY_SORT_RULES, arr.toString()).apply()
    }

    fun upsertTagSortRule(context: Context, rule: TagSortRule): List<TagSortRule> {
        val cleanTag = rule.tag.trim().replace("[", "").replace("]", "").take(24)
        if (cleanTag.isEmpty() || rule.targetFolderId.isBlank()) return getTagSortRules(context)
        val filtered = getTagSortRules(context).filterNot { it.tag.equals(cleanTag, ignoreCase = true) }
        val next = filtered + rule.copy(tag = cleanTag)
        saveTagSortRules(context, next)
        return next
    }

    fun removeTagSortRule(context: Context, tag: String): List<TagSortRule> {
        val next = getTagSortRules(context).filterNot { it.tag.equals(tag.trim(), ignoreCase = true) }
        saveTagSortRules(context, next)
        return next
    }

    fun getSwipeRightAction(context: Context): SwipeAction =
        runCatching {
            val saved = prefs(context).getString(KEY_SWIPE_RIGHT, SwipeAction.TOGGLE_READ.name)
            SwipeAction.valueOf(saved ?: SwipeAction.TOGGLE_READ.name)
        }.getOrDefault(SwipeAction.TOGGLE_READ)

    fun setSwipeRightAction(context: Context, action: SwipeAction) {
        prefs(context).edit().putString(KEY_SWIPE_RIGHT, action.name).apply()
    }

    fun getSwipeLeftAction(context: Context): SwipeAction =
        runCatching {
            val saved = prefs(context).getString(KEY_SWIPE_LEFT, SwipeAction.DELETE.name)
            SwipeAction.valueOf(saved ?: SwipeAction.DELETE.name)
        }.getOrDefault(SwipeAction.DELETE)

    fun setSwipeLeftAction(context: Context, action: SwipeAction) {
        prefs(context).edit().putString(KEY_SWIPE_LEFT, action.name).apply()
    }

    fun clearAccountRulesOnLogout(context: Context) {
        prefs(context).edit()
            .remove(KEY_MUTED_FOLDERS)
            .remove(KEY_MUTED_SENDERS)
            .remove(KEY_SORT_RULES)
            .remove(KEY_INBOX_ID)
            .apply()
    }
}
