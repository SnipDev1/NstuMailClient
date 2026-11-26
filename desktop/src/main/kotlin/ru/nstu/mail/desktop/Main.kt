package ru.nstu.mail.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension
import java.awt.Image
import java.awt.Toolkit

fun main() = application {
    val controller = remember { DesktopMailController() }
    DisposableEffect(Unit) {
        onDispose { controller.dispose() }
    }

    val state by controller.state.collectAsState()
    val icon: Image? = remember {
        DesktopMailController::class.java.getResource("/nstumail.png")?.let { url ->
            Toolkit.getDefaultToolkit().getImage(url)
        }
    }

    Window(
        onCloseRequest = {
            controller.dispose()
            exitApplication()
        },
        title = "Почта НГТУ",
        state = rememberWindowState(size = DpSize(1240.dp, 800.dp))
    ) {
        LaunchedEffectOnce(window, icon)
        DesktopNstuTheme(themeMode = state.settings.themeMode) {
            DesktopRootScreen(controller = controller, state = state)
        }
    }
}

@androidx.compose.runtime.Composable
private fun LaunchedEffectOnce(window: java.awt.Window, icon: Image?) {
    androidx.compose.runtime.LaunchedEffect(window) {
        window.minimumSize = Dimension(920, 620)
        if (icon != null) {
            window.setIconImage(icon)
        }
    }
}
