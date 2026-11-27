package ru.nstu.mail.data

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.absoluteValue

fun needsNameResolution(name: String, email: String): Boolean {
    val cleanEmail = email.trim()
    if ('@' !in cleanEmail) return false
    val cleanName = name.trim().replace(Regex("[\"'<>]"), "")
    if (cleanName.isEmpty() || '@' in cleanName) return true
    return cleanName.equals(cleanEmail.substringBefore('@'), ignoreCase = true)
}

fun resolveDisplayName(
    name: String,
    email: String,
    profile: PersonProfile?,
    knownNames: Map<String, String>
): String {
    val key = email.trim().lowercase()
    val profileName = profile?.displayName?.trim().orEmpty()
    if (profileName.isNotEmpty() && !needsNameResolution(profileName, email)) return profileName
    val cleanName = name.trim()
    if (cleanName.isNotEmpty() && !needsNameResolution(cleanName, email)) return cleanName
    val cached = knownNames[key]?.trim().orEmpty()
    if (cached.isNotEmpty() && !needsNameResolution(cached, email)) return cached
    return profileName.ifEmpty { cleanName.ifEmpty { email.trim() } }
}

fun extractInitials(name: String, email: String): String {
    val cleanName = name.trim().replace(Regex("[\"'<>]"), "")
    val words = cleanName
        .split(Regex("\\s+"))
        .map { it.trim(',', '.', ';', ':') }
        .filter { it.isNotEmpty() && !it.contains('@') }

    if (words.size >= 2) {
        val a = words[0].firstOrNull()?.uppercaseChar()
        val b = words[1].firstOrNull()?.uppercaseChar()
        if (a != null && b != null) return "$a$b"
    }
    if (words.size == 1) {
        val a = words[0].firstOrNull()?.uppercaseChar()
        if (a != null) return a.toString()
    }
    val local = email.trim().substringBefore('@')
    return local.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
}

fun formatShortPersonName(name: String, email: String): String {
    val cleanName = name.trim().replace(Regex("[\"'<>]"), "")
    val words = cleanName
        .split(Regex("\\s+"))
        .map { it.trim(',', '.', ';', ':') }
        .filter { it.isNotEmpty() && !it.contains('@') }

    return when {
        words.size >= 3 -> {
            val i1 = words[1].firstOrNull()?.uppercaseChar()
            val i2 = words[2].firstOrNull()?.uppercaseChar()
            if (i1 != null && i2 != null) "${words[0]} $i1.$i2." else words[0]
        }
        words.size == 2 -> {
            val i1 = words[1].firstOrNull()?.uppercaseChar()
            if (i1 != null) "${words[0]} $i1." else words[0]
        }
        words.size == 1 -> words[0]
        else -> email.trim().substringBefore('@').ifBlank { email.trim() }
    }
}

fun avatarPaletteIndex(key: String, paletteSize: Int): Int {
    if (paletteSize <= 0) return 0
    val norm = key.trim().lowercase().ifEmpty { "default" }
    return norm.hashCode().absoluteValue % paletteSize
}

fun splitEmails(raw: String): List<String> =
    raw.split(',', ';', ' ')
        .map { it.trim() }
        .filter { it.isNotBlank() }

fun parseRecipientTokens(raw: String): Pair<List<Pair<String, String>>, String> {
    if (',' !in raw && ';' !in raw) {
        return emptyList<Pair<String, String>>() to raw
    }
    val parts = raw.split(',', ';')
    val completed = parts.dropLast(1).mapNotNull { seg ->
        finalizeRecipientToken(seg)
    }
    return completed to parts.last().trimStart()
}

fun finalizeRecipientToken(raw: String): Pair<String, String>? {
    val cleaned = raw.trim().trimEnd(',', ';').trim()
    if (cleaned.isEmpty()) return null
    val (name, email) = parseFormattedRecipient(cleaned)
    return if ('@' in email) {
        name.ifBlank { email } to email
    } else {
        null
    }
}

data class ComposeInitialRecipients(
    val to: List<Pair<String, String>>,
    val cc: List<Pair<String, String>>,
    val bcc: List<Pair<String, String>>
)

fun buildComposeInitialRecipients(
    selfEmails: Set<String>,
    replyTo: EmailDetail?,
    replyAll: Boolean,
    isForward: Boolean,
    draft: EmailDetail?,
    prefillTo: String? = null,
    prefillName: String? = null
): ComposeInitialRecipients {
    val normSelf = selfEmails.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
    if (draft != null) {
        return ComposeInitialRecipients(
            to = draft.to.map(::parseFormattedRecipient).filter { it.second.isNotBlank() },
            cc = draft.cc.map(::parseFormattedRecipient).filter { it.second.isNotBlank() },
            bcc = draft.bcc.map(::parseFormattedRecipient).filter { it.second.isNotBlank() }
        )
    }
    if (!prefillTo.isNullOrBlank()) {
        val clean = prefillTo.trim()
        return ComposeInitialRecipients(
            to = listOf(prefillName.orEmpty().trim().ifBlank { clean } to clean),
            cc = emptyList(),
            bcc = emptyList()
        )
    }
    if (replyTo == null || isForward) {
        return ComposeInitialRecipients(emptyList(), emptyList(), emptyList())
    }
    if (replyAll) {
        val toCandidates = buildList {
            if (replyTo.fromEmail.isNotBlank()) add(replyTo.from to replyTo.fromEmail)
            replyTo.to.forEach { add(parseFormattedRecipient(it)) }
        }
        val filteredTo = toCandidates
            .filter { it.second.isNotBlank() && it.second.trim().lowercase() !in normSelf }
            .distinctBy { it.second.trim().lowercase() }
        val to = if (filteredTo.isEmpty() && replyTo.fromEmail.isNotBlank()) {
            listOf(replyTo.from to replyTo.fromEmail)
        } else {
            filteredTo
        }
        val toKeys = to.map { it.second.trim().lowercase() }.toSet()
        val cc = replyTo.cc
            .map(::parseFormattedRecipient)
            .filter {
                val k = it.second.trim().lowercase()
                k.isNotBlank() && k !in normSelf && k !in toKeys
            }
            .distinctBy { it.second.trim().lowercase() }
        return ComposeInitialRecipients(to = to, cc = cc, bcc = emptyList())
    }
    val fromKey = replyTo.fromEmail.trim().lowercase()
    val to = if (replyTo.fromEmail.isNotBlank() && fromKey !in normSelf) {
        listOf(replyTo.from to replyTo.fromEmail)
    } else {
        val alt = replyTo.to
            .map(::parseFormattedRecipient)
            .filter { it.second.isNotBlank() && it.second.trim().lowercase() !in normSelf }
            .distinctBy { it.second.trim().lowercase() }
        if (alt.isNotEmpty()) {
            alt
        } else if (replyTo.fromEmail.isNotBlank()) {
            listOf(replyTo.from to replyTo.fromEmail)
        } else {
            emptyList()
        }
    }
    return ComposeInitialRecipients(to = to, cc = emptyList(), bcc = emptyList())
}

fun sanitizeFilename(name: String): String {
    val cleaned = name
        .replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1F]"), "_")
        .replace(Regex("\\.{2,}"), ".")
        .trim(' ', '.')
        .take(120)
    return cleaned.ifBlank { "attachment" }
}

private val RU_LOCALE = Locale("ru")
private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", RU_LOCALE)
private val DAY_MONTH_FMT = DateTimeFormatter.ofPattern("d MMM", RU_LOCALE)
private val FULL_DATE_FMT = DateTimeFormatter.ofPattern("dd.MM.yyyy", RU_LOCALE)
private val DETAIL_FMT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", RU_LOCALE)

fun formatMailDate(iso: String, detailed: Boolean = false): String {
    if (iso.isBlank()) return ""
    return runCatching {
        val zdt = Instant.parse(iso).atZone(ZoneId.systemDefault())
        if (detailed) return zdt.format(DETAIL_FMT)
        val today = LocalDate.now()
        val msgDate = zdt.toLocalDate()
        when {
            msgDate == today -> zdt.format(TIME_FMT)
            msgDate.year == today.year -> zdt.format(DAY_MONTH_FMT)
            else -> zdt.format(FULL_DATE_FMT)
        }
    }.getOrDefault(iso.take(16))
}
