package com.neojou.mystudy

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Desktop
import java.awt.EventQueue

/**
 * Desktop entry — opens a window hosting [App].
 *
 * Creates a Compose for Desktop [Window] within [application]
 * and hosts the shared [App] composable.
 * On macOS the system application menu uses [AppVersion.APP_NAME], and its About
 * item opens the same dialog as the in-app About item.
 */
fun main() {
    // Read by AWT when it creates the macOS application menu. Set this before any window.
    System.setProperty("apple.awt.application.name", AppVersion.APP_NAME)

    application {
        val about = remember { AboutRequest() }
        DisposableEffect(about) {
            val remove = installMacAboutHandler(about::show)
            onDispose(remove)
        }

        val windowState = rememberWindowState(
            size = DpSize(960.dp, 640.dp),
        )
        Window(
            onCloseRequest = ::exitApplication,
            title = AppVersion.APP_NAME,
            state = windowState,
        ) {
            App(about)
        }
    }
}

/**
 * Routes the macOS application-menu About item to [onAbout].
 *
 * Returns a remover. On other systems About is not a desktop action, so this does nothing.
 */
private fun installMacAboutHandler(onAbout: () -> Unit): () -> Unit {
    if (!Desktop.isDesktopSupported()) {
        return {}
    }
    val desktop = Desktop.getDesktop()
    if (!desktop.isSupported(Desktop.Action.APP_ABOUT)) {
        return {}
    }
    desktop.setAboutHandler { _ ->
        EventQueue.invokeLater { onAbout() }
    }
    return {
        desktop.setAboutHandler(null)
    }
}
