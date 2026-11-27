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
import ru.nstu.mail.data.avatarPaletteIndex
import ru.nstu.mail.data.extractInitials

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

fun avatarColorFor(key: String): Color =
    AVATAR_PALETTE[avatarPaletteIndex(key, AVATAR_PALETTE.size)]

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
