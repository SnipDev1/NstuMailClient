package ru.nstu.mail.data

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

fun isNstuSender(senderEmail: String): Boolean =
    senderEmail.contains("nstu.ru", ignoreCase = true)

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

/**
 * Builds tag templates and auto-sort rules from an existing folder subtree.
 * Walks non-system ancestors so clicking a subfolder still produces its full tag hierarchy.
 */
fun buildAutoSortFromFolderTree(
    rootFolder: MailFolder,
    allFolders: List<MailFolder>
): Pair<List<String>, List<TagSortRule>> {
    if (rootFolder.isSystem || rootFolder.displayName.equals(UNSORTED_FOLDER_NAME, ignoreCase = true)) {
        return emptyList<String>() to emptyList()
    }
    val byId = allFolders.associateBy { it.id }
    val ancestorSegs = mutableListOf<String>()
    val visitedAncestors = mutableSetOf(rootFolder.id)
    var curParentId = rootFolder.parentId
    while (curParentId.isNotBlank() && visitedAncestors.add(curParentId)) {
        val parent = byId[curParentId] ?: break
        if (parent.isSystem || parent.displayName.equals(UNSORTED_FOLDER_NAME, ignoreCase = true)) break
        val seg = sanitizeTagSegment(parent.displayName)
        if (seg.isNotEmpty()) ancestorSegs += seg
        curParentId = parent.parentId
    }
    ancestorSegs.reverse()

    val childrenByParent = mutableMapOf<String, MutableList<MailFolder>>()
    for (f in allFolders) {
        if (!f.isSystem && f.parentId.isNotBlank()) {
            childrenByParent.getOrPut(f.parentId) { mutableListOf() }.add(f)
        }
    }

    val templates = linkedSetOf<String>()
    val rules = mutableListOf<TagSortRule>()
    val visited = mutableSetOf<String>()
    val queue = ArrayDeque<Pair<MailFolder, List<String>>>()
    val rootSeg = sanitizeTagSegment(rootFolder.displayName)
    if (rootSeg.isEmpty()) return emptyList<String>() to emptyList()
    queue.addLast(rootFolder to (ancestorSegs + rootSeg))

    while (queue.isNotEmpty()) {
        val (folder, pathSegs) = queue.removeFirst()
        if (!visited.add(folder.id)) continue
        if (pathSegs.size > MAX_TAG_DEPTH) continue

        for (len in 1..pathSegs.size) {
            formatTagPath(pathSegs.take(len)).takeIf { it.isNotEmpty() }?.let { templates += it }
        }
        val fullTag = formatTagPath(pathSegs)
        if (fullTag.isNotEmpty()) {
            rules += TagSortRule(
                tag = fullTag,
                targetFolderId = folder.id,
                targetFolderName = folder.displayName,
                autoCreateSubfolders = true
            )
        }
        if (pathSegs.size < MAX_TAG_DEPTH) {
            val children = childrenByParent[folder.id]
                ?.sortedBy { it.displayName.lowercase() }
                .orEmpty()
            for (child in children) {
                val childSeg = sanitizeTagSegment(child.displayName)
                if (childSeg.isNotEmpty()) {
                    queue.addLast(child to (pathSegs + childSeg))
                }
            }
        }
    }
    return sortTagTemplatesHierarchically(templates.toList()) to rules
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

private data class ParsedSemVer(val core: List<Int>, val pre: List<String>)

private fun parseSemVerOrNull(raw: String): ParsedSemVer? {
    val trimmed = raw.trim().removePrefix("v").removePrefix("V").substringBefore('+').trim()
    if (trimmed.isEmpty()) return null
    val coreStr = trimmed.substringBefore('-').trim()
    if (coreStr.isEmpty()) return null
    val core = coreStr.split('.').map { it.toIntOrNull() ?: return null }
    val pre = if ('-' in trimmed) {
        trimmed.substringAfter('-').split('.').filter { it.isNotBlank() }
    } else {
        emptyList()
    }
    return ParsedSemVer(core = core, pre = pre)
}

/** Compares two SemVer tags/versions; returns -1 if [a] is invalid. */
fun compareSemVer(a: String, b: String): Int {
    val va = parseSemVerOrNull(a) ?: return -1
    val vb = parseSemVerOrNull(b) ?: return 0
    val maxCore = maxOf(va.core.size, vb.core.size)
    for (i in 0 until maxCore) {
        val r = va.core.getOrElse(i) { 0 }
        val c = vb.core.getOrElse(i) { 0 }
        if (r != c) return r.compareTo(c)
    }
    if (va.pre.isEmpty() && vb.pre.isEmpty()) return 0
    if (va.pre.isEmpty()) return 1
    if (vb.pre.isEmpty()) return -1

    val maxPre = maxOf(va.pre.size, vb.pre.size)
    for (i in 0 until maxPre) {
        if (i >= va.pre.size) return -1
        if (i >= vb.pre.size) return 1
        val pa = va.pre[i]
        val pb = vb.pre[i]
        val na = pa.toIntOrNull()
        val nb = pb.toIntOrNull()
        val cmp = when {
            na != null && nb != null -> na.compareTo(nb)
            na != null -> -1
            nb != null -> 1
            else -> pa.compareTo(pb)
        }
        if (cmp != 0) return cmp
    }
    return 0
}
