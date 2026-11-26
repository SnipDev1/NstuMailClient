package ru.nstu.mail.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class TagSortRule(
    val tag: String,
    val targetFolderId: String,
    val targetFolderName: String,
    val autoCreateSubfolders: Boolean = true
) {
    fun pathSegments(): List<String> = parseTagPath(tag)
}

data class TagMatchResult(
    val rule: TagSortRule,
    val remainingSubtags: List<String>
)

enum class SwipeAction(val labelRu: String) {
    NONE("Нет действия"),
    TOGGLE_READ("Прочитано / Непрочитано"),
    DELETE("Удалить"),
    TOGGLE_FLAG("Флажок (Избранное)"),
    MOVE("Переместить в папку"),
    SPAM("В спам / Не спам")
}

val DEFAULT_TAG_TEMPLATES = listOf("Учёба", "Учёба/ПЗ", "Учёба/Лаба", "Учёба/Лекция", "Важно")
val DEFAULT_TAGS = DEFAULT_TAG_TEMPLATES
const val UNSORTED_FOLDER_NAME = "Несортированные"

private const val MAX_TAG_DEPTH = 4
private const val MAX_TAG_LEN = 36
private const val MAX_TAG_HIERARCHIES = 6

private val TRAILING_QUOTED_TAGS_REGEX = Regex(
    """\s*\[\s*"[^"\[\];,]{1,36}"(?:\s*[,;]\s*"[^"\[\];,]{1,36}")*\s*\]\s*$"""
)
private val QUOTED_ITEM_REGEX = Regex(""""([^"\[\];,]{1,36})"""")
private val LEGACY_LEADING_TAG_REGEX = Regex("""^\s*\[([^\[\]]{1,36})]\s*""")
private val HASH_TAG_REGEX = Regex("""(?:^|\s)#([\p{L}\p{N}_/-]{1,40})(?=\s|$|[.,!?;:])""")

fun sanitizeTagSegment(raw: String): String =
    raw.trim()
        .replace("\"", "")
        .replace("[", "")
        .replace("]", "")
        .replace(";", "")
        .replace(",", "")
        .replace("/", "")
        .replace("\\", "")
        .replace(":", "")
        .replace("*", "")
        .replace("?", "")
        .replace("<", "")
        .replace(">", "")
        .replace("|", "")
        .trim()
        .take(MAX_TAG_LEN)

fun parseTagPath(raw: String): List<String> =
    raw.split('/', '>')
        .map { sanitizeTagSegment(it) }
        .filter { it.isNotEmpty() }
        .take(MAX_TAG_DEPTH)

fun formatTagPath(segments: List<String>): String =
    segments.map { sanitizeTagSegment(it) }
        .filter { it.isNotEmpty() }
        .take(MAX_TAG_DEPTH)
        .joinToString("/")

fun parseTagHierarchiesRaw(raw: String): List<List<String>> =
    raw.split(';')
        .map { parseTagPath(it) }
        .filter { it.isNotEmpty() }
        .distinctBy { formatTagPath(it).lowercase() }
        .take(MAX_TAG_HIERARCHIES)

fun formatTagHierarchiesRaw(hierarchies: List<List<String>>): String =
    hierarchies.mapNotNull { segs ->
        formatTagPath(segs).takeIf { it.isNotEmpty() }
    }
        .distinctBy { it.lowercase() }
        .take(MAX_TAG_HIERARCHIES)
        .joinToString(";")

fun extractSubjectTagHierarchies(subject: String): Pair<List<List<String>>, String> {
    val trailingMatch = TRAILING_QUOTED_TAGS_REGEX.find(subject)
    if (trailingMatch != null) {
        val rawValue = trailingMatch.value.trim()
        val inner = rawValue.removePrefix("[").removeSuffix("]")
        val hierarchies = inner.split(';')
            .map { group ->
                QUOTED_ITEM_REGEX.findAll(group)
                    .map { sanitizeTagSegment(it.groupValues[1]) }
                    .filter { it.isNotEmpty() }
                    .take(MAX_TAG_DEPTH)
                    .toList()
            }
            .filter { it.isNotEmpty() }
            .distinctBy { formatTagPath(it).lowercase() }
            .take(MAX_TAG_HIERARCHIES)
        val clean = subject.substring(0, trailingMatch.range.first).trim()
        if (hierarchies.isNotEmpty()) {
            return hierarchies to clean
        }
    }

    // Legacy fallback: leading [Tag] brackets
    var remaining = subject
    val legacyTags = mutableListOf<String>()
    while (true) {
        val m = LEGACY_LEADING_TAG_REGEX.find(remaining) ?: break
        val seg = sanitizeTagSegment(m.groupValues[1])
        if (seg.isNotEmpty()) legacyTags += seg
        remaining = remaining.substring(m.range.last + 1)
    }
    val trimmedLegacy = legacyTags.take(MAX_TAG_DEPTH)
    return if (trimmedLegacy.isNotEmpty()) {
        listOf(trimmedLegacy) to remaining.trim()
    } else {
        emptyList<List<String>>() to remaining.trim()
    }
}

fun extractSubjectTags(subject: String): Pair<List<String>, String> {
    val (hierarchies, clean) = extractSubjectTagHierarchies(subject)
    return hierarchies.firstOrNull().orEmpty() to clean
}

fun extractAllTagHierarchiesForMatching(subject: String): List<List<String>> {
    val (primary, _) = extractSubjectTagHierarchies(subject)
    if (primary.isNotEmpty()) return primary
    return HASH_TAG_REGEX.findAll(subject)
        .map { parseTagPath(it.groupValues[1]) }
        .filter { it.isNotEmpty() }
        .distinctBy { formatTagPath(it).lowercase() }
        .take(MAX_TAG_HIERARCHIES)
        .toList()
}

fun extractAllTagsForMatching(subject: String): List<String> =
    extractAllTagHierarchiesForMatching(subject).firstOrNull().orEmpty()

fun formatSubjectWithTagHierarchies(
    cleanSubject: String,
    hierarchies: List<List<String>>
): String {
    val (_, stripped) = extractSubjectTagHierarchies(cleanSubject)
    val cleanHierarchies = hierarchies
        .map { path ->
            path.map { sanitizeTagSegment(it) }
                .filter { it.isNotEmpty() }
                .take(MAX_TAG_DEPTH)
        }
        .filter { it.isNotEmpty() }
        .distinctBy { formatTagPath(it).lowercase() }
        .take(MAX_TAG_HIERARCHIES)
    if (cleanHierarchies.isEmpty()) return stripped.ifBlank { cleanSubject.trim() }
    val suffix = cleanHierarchies.joinToString("; ", prefix = "[", postfix = "]") { path ->
        path.joinToString(", ") { "\"$it\"" }
    }
    return if (stripped.isEmpty()) suffix else "$stripped $suffix"
}

fun formatSubjectWithTags(cleanSubject: String, tags: List<String>): String =
    formatSubjectWithTagHierarchies(cleanSubject, if (tags.isEmpty()) emptyList() else listOf(tags))

fun matchAllAutoSortRules(subject: String, rules: List<TagSortRule>): List<TagMatchResult> {
    if (rules.isEmpty() || subject.isBlank()) return emptyList()
    val hierarchies = extractAllTagHierarchiesForMatching(subject)
    if (hierarchies.isEmpty()) return emptyList()

    val results = mutableListOf<TagMatchResult>()
    val seenTargets = mutableSetOf<Pair<String, List<String>>>()
    for (emailPath in hierarchies) {
        var bestMatch: TagMatchResult? = null
        var bestLen = -1
        for (rule in rules) {
            val rulePath = rule.pathSegments()
            if (rulePath.isEmpty() || emailPath.size < rulePath.size) continue
            val prefixMatches = rulePath.indices.all { idx ->
                emailPath[idx].equals(rulePath[idx], ignoreCase = true)
            }
            if (prefixMatches && rulePath.size > bestLen) {
                bestLen = rulePath.size
                bestMatch = TagMatchResult(
                    rule = rule,
                    remainingSubtags = emailPath.drop(rulePath.size)
                )
            }
        }
        if (bestMatch != null) {
            val dedupeKey = bestMatch.rule.targetFolderId to if (bestMatch.rule.autoCreateSubfolders) {
                bestMatch.remainingSubtags.map { it.lowercase() }
            } else {
                emptyList()
            }
            if (seenTargets.add(dedupeKey)) {
                results += bestMatch
            }
        }
    }
    return results
}

fun matchAutoSortRuleDetailed(subject: String, rules: List<TagSortRule>): TagMatchResult? =
    matchAllAutoSortRules(subject, rules).firstOrNull()

fun matchAutoSortRule(subject: String, rules: List<TagSortRule>): TagSortRule? =
    matchAllAutoSortRules(subject, rules).firstOrNull()?.rule

fun isNstuSender(senderEmail: String): Boolean {
    val domain = senderEmail.trim().substringAfter('@', "").lowercase()
    return domain == "nstu.ru" || domain.endsWith(".nstu.ru")
}

fun shouldRouteToUnsorted(
    subject: String,
    matchedRulesCount: Int,
    senderEmail: String
): Boolean =
    matchedRulesCount == 0 &&
        extractAllTagHierarchiesForMatching(subject).isNotEmpty() &&
        isNstuSender(senderEmail)

fun childTagsForPrefix(templates: List<String>, prefix: List<String>): List<String> {
    val result = mutableListOf<String>()
    val seen = mutableSetOf<String>()
    for (tpl in templates) {
        val segs = parseTagPath(tpl)
        if (segs.size <= prefix.size) continue
        val matches = prefix.indices.all { i -> segs[i].equals(prefix[i], ignoreCase = true) }
        if (matches) {
            val child = segs[prefix.size]
            if (seen.add(child.lowercase())) {
                result += child
            }
        }
    }
    return result
}

fun sortTagTemplatesHierarchically(templates: List<String>): List<String> =
    templates.map { formatTagPath(parseTagPath(it)) }
        .filter { it.isNotEmpty() }
        .distinctBy { it.lowercase() }
        .sortedWith(compareBy<String>({ it.lowercase() }, { parseTagPath(it).size }))

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
    private const val KEY_TAG_TEMPLATES = "rules_tag_templates"
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

    fun getTagTemplates(context: Context): List<String> {
        val p = prefs(context)
        val raw = p.getString(KEY_TAG_TEMPLATES, null)
        if (raw == null) {
            // Migrate legacy custom tags cleanly alongside defaults once
            val legacyRaw = p.getString(KEY_CUSTOM_TAGS, null)
            val legacy = if (legacyRaw != null) {
                runCatching {
                    val arr = JSONArray(legacyRaw)
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optString(i, "").trim().takeIf { it.isNotEmpty() }
                    }
                }.getOrDefault(emptyList())
            } else {
                emptyList()
            }
            return sortTagTemplatesHierarchically(DEFAULT_TAG_TEMPLATES + legacy)
        }
        return runCatching {
            val arr = JSONArray(raw)
            val list = (0 until arr.length()).mapNotNull { i ->
                arr.optString(i, "").trim().takeIf { it.isNotEmpty() }
            }
            sortTagTemplatesHierarchically(list)
        }.getOrDefault(DEFAULT_TAG_TEMPLATES)
    }

    private fun saveTagTemplates(context: Context, templates: List<String>): List<String> {
        val sorted = sortTagTemplatesHierarchically(templates)
        val arr = JSONArray().apply { sorted.forEach { put(it) } }
        prefs(context).edit().putString(KEY_TAG_TEMPLATES, arr.toString()).apply()
        return sorted
    }

    fun addTagTemplate(context: Context, rawPath: String): List<String> {
        val segs = parseTagPath(rawPath)
        if (segs.isEmpty()) return getTagTemplates(context)
        val additions = mutableListOf<String>()
        for (len in 1..segs.size) {
            additions += formatTagPath(segs.take(len))
        }
        return saveTagTemplates(context, getTagTemplates(context) + additions)
    }

    fun updateTagTemplate(context: Context, oldPath: String, newRawPath: String): List<String> {
        val normOld = formatTagPath(parseTagPath(oldPath))
        val normNew = formatTagPath(parseTagPath(newRawPath))
        if (normOld.isEmpty() || normNew.isEmpty()) return getTagTemplates(context)
        val updated = getTagTemplates(context).map { tpl ->
            when {
                tpl.equals(normOld, ignoreCase = true) -> normNew
                tpl.startsWith("$normOld/", ignoreCase = true) ->
                    normNew + tpl.substring(normOld.length)
                else -> tpl
            }
        }
        val updatedRules = getTagSortRules(context).map { rule ->
            when {
                rule.tag.equals(normOld, ignoreCase = true) -> rule.copy(tag = normNew)
                rule.tag.startsWith("$normOld/", ignoreCase = true) ->
                    rule.copy(tag = normNew + rule.tag.substring(normOld.length))
                else -> rule
            }
        }
        saveTagSortRules(context, updatedRules)
        return saveTagTemplates(context, updated)
    }

    fun removeTagTemplate(context: Context, rawPath: String): List<String> {
        val norm = formatTagPath(parseTagPath(rawPath))
        val next = getTagTemplates(context).filterNot { it.equals(norm, ignoreCase = true) }
        val nextRules = getTagSortRules(context).filterNot { it.tag.equals(norm, ignoreCase = true) }
        saveTagSortRules(context, nextRules)
        return saveTagTemplates(context, next)
    }

    fun getTagSortRules(context: Context): List<TagSortRule> {
        val raw = prefs(context).getString(KEY_SORT_RULES, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val tag = formatTagPath(parseTagPath(obj.optString("tag", "")))
                val fid = obj.optString("folderId", "").trim()
                val fname = obj.optString("folderName", "").trim()
                val autoSub = obj.optBoolean("autoSubfolders", true)
                if (tag.isEmpty() || fid.isEmpty()) null
                else TagSortRule(
                    tag = tag,
                    targetFolderId = fid,
                    targetFolderName = fname,
                    autoCreateSubfolders = autoSub
                )
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
                    put("autoSubfolders", r.autoCreateSubfolders)
                }
            )
        }
        prefs(context).edit().putString(KEY_SORT_RULES, arr.toString()).apply()
    }

    fun upsertTagSortRule(context: Context, rule: TagSortRule): List<TagSortRule> {
        val cleanPath = formatTagPath(rule.pathSegments())
        if (cleanPath.isEmpty() || rule.targetFolderId.isBlank()) return getTagSortRules(context)
        val filtered = getTagSortRules(context).filterNot { it.tag.equals(cleanPath, ignoreCase = true) }
        val next = (filtered + rule.copy(tag = cleanPath)).sortedBy { it.tag.lowercase() }
        saveTagSortRules(context, next)
        return next
    }

    fun removeTagSortRule(context: Context, tag: String): List<TagSortRule> {
        val cleanPath = formatTagPath(parseTagPath(tag))
        val next = getTagSortRules(context).filterNot { it.tag.equals(cleanPath, ignoreCase = true) }
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
