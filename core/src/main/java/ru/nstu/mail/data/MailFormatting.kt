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
