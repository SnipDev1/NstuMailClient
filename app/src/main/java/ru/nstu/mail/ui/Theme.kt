package ru.nstu.mail.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val NstuGreen = Color(0xFF006B47)
private val NstuGreenLight = Color(0xFF5CDAA5)
private val NstuGreenContainer = Color(0xFFC2F2DB)
private val NstuOnGreenContainer = Color(0xFF002114)

private val LightColors = lightColorScheme(
    primary = NstuGreen,
    onPrimary = Color.White,
    primaryContainer = NstuGreenContainer,
    onPrimaryContainer = NstuOnGreenContainer,
    secondary = Color(0xFF4C6358),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEE9DA),
    onSecondaryContainer = Color(0xFF092017),
    background = Color(0xFFF7FAF7),
    onBackground = Color(0xFF181D1A),
    surface = Color(0xFFF7FAF7),
    onSurface = Color(0xFF181D1A),
    surfaceVariant = Color(0xFFDCE5DF),
    onSurfaceVariant = Color(0xFF404944),
    error = Color(0xFFBA1A1A),
    onError = Color.White
)

private val DarkColors = darkColorScheme(
    primary = NstuGreenLight,
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005136),
    onPrimaryContainer = NstuGreenContainer,
    secondary = Color(0xFFB3CCBE),
    onSecondary = Color(0xFF1E352B),
    secondaryContainer = Color(0xFF354B41),
    onSecondaryContainer = Color(0xFFCEE9DA),
    background = Color(0xFF101512),
    onBackground = Color(0xFFE0E4E0),
    surface = Color(0xFF101512),
    onSurface = Color(0xFFE0E4E0),
    surfaceVariant = Color(0xFF404944),
    onSurfaceVariant = Color(0xFFBFC9C2),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun NstuMailTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
