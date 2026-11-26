package ru.nstu.mail.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.nstu.mail.data.PersonProfile
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

private val AVATAR_PALETTE = listOf(
    Color(0xFF006B47),
    Color(0xFF1E6091),
    Color(0xFF6A4C93),
    Color(0xFFB55400),
    Color(0xFF2D6A4F),
    Color(0xFF8C2F39),
    Color(0xFF3A5A40),
    Color(0xFF4361EE)
)

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

fun avatarColorFor(key: String): Color {
    val norm = key.trim().lowercase().ifEmpty { "default" }
    val idx = norm.hashCode().absoluteValue % AVATAR_PALETTE.size
    return AVATAR_PALETTE[idx]
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
        else -> email.trim().substringBefore('@').ifBlank { email.trim().ifBlank { "?" } }
    }
}

@Composable
fun UserAvatar(
    name: String,
    email: String,
    photo: ImageBitmap?,
    size: Dp = 40.dp,
    selected: Boolean = false,
    onRequestLoad: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(email) {
        if ('@' in email) {
            onRequestLoad?.invoke(email)
        }
    }

    val shape = CircleShape
    if (selected) {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Выбрано",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(size * 0.55f)
            )
        }
    } else if (photo != null) {
        Image(
            bitmap = photo,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(shape)
        )
    } else {
        val initials = extractInitials(name, email)
        val bg = avatarColorFor(email.ifBlank { name })
        val fontSize = (size.value * 0.38f).sp
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(bg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = initials,
                color = Color.White,
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
        }
    }
}
