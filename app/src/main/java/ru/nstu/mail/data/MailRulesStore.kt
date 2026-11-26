package ru.nstu.mail.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object MailRulesStore {
    private const val PREFS_NAME = "nstumail_prefs"
    private const val KEY_MUTED_FOLDERS = "rules_muted_folders"
    private const val KEY_MUTED_SENDERS = "rules_muted_senders"
    private const val KEY_CUSTOM_TAGS = "rules_custom_tags"
    private const val KEY_TAG_TEMPLATES = "rules_tag_templates"
    private const val KEY_LEGACY_DEFAULTS_PURGED = "rules_legacy_default_templates_purged"
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
        val loaded = if (raw == null) {
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
            sortTagTemplatesHierarchically(legacy)
        } else {
            runCatching {
                val arr = JSONArray(raw)
                val list = (0 until arr.length()).mapNotNull { i ->
                    arr.optString(i, "").trim().takeIf { it.isNotEmpty() }
                }
                sortTagTemplatesHierarchically(list)
            }.getOrDefault(emptyList())
        }
        if (!p.getBoolean(KEY_LEGACY_DEFAULTS_PURGED, false)) {
            val cleaned = purgeUnreferencedLegacyDefaultTemplates(loaded, getTagSortRules(context))
            val arr = JSONArray().apply { cleaned.forEach { put(it) } }
            p.edit()
                .putString(KEY_TAG_TEMPLATES, arr.toString())
                .putBoolean(KEY_LEGACY_DEFAULTS_PURGED, true)
                .apply()
            return cleaned
        }
        return loaded
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
