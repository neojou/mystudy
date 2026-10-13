package com.neojou.mystudy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import java.awt.EventQueue
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent

/**
 * A Compose [Window] that registers itself in the Windows menu.
 *
 * macOS shows the menu bar of the focused window, so every window installs the
 * same Windows menu. The menu body reads [OpenWindowList.shared] itself: [MenuBar]
 * keeps the first content lambda, and that lambda sees later title and focus changes
 * only through the list's observable state.
 */
@Composable
fun AppWindow(
    onCloseRequest: () -> Unit,
    title: String,
    state: WindowState = rememberWindowState(),
    kind: String? = null,
    content: @Composable FrameWindowScope.() -> Unit,
) {
    Window(
        onCloseRequest = onCloseRequest,
        title = title,
        state = state,
    ) {
        WindowsMenu(title = title, kind = kind, state = state)
        content()
    }
}

@Composable
private fun FrameWindowScope.WindowsMenu(
    title: String,
    kind: String?,
    state: WindowState,
) {
    val list = OpenWindowList.shared
    val registered = remember { RegisteredWindow() }

    DisposableEffect(window) {
        val created = list.register(title = title, kind = kind) {
            // Run after the menu closes, so macOS does not hand focus back to the window that owned the menu.
            EventQueue.invokeLater {
                if (!window.isDisplayable) {
                    return@invokeLater
                }
                if (state.isMinimized) {
                    state.isMinimized = false
                }
                window.isMinimized = false
                window.toFront()
                window.requestFocus()
            }
        }
        created.focused = window.isFocused
        val listener = object : WindowAdapter() {
            override fun windowGainedFocus(event: WindowEvent) {
                created.focused = true
            }

            override fun windowLostFocus(event: WindowEvent) {
                created.focused = false
            }
        }
        window.addWindowFocusListener(listener)
        registered.entry = created
        onDispose {
            window.removeWindowFocusListener(listener)
            list.unregister(created)
            registered.entry = null
        }
    }

    SideEffect {
        registered.entry?.title = title
    }

    MenuBar {
        Menu("Windows") {
            val open = list.windows
            val labels = menuLabels(open)
            open.forEachIndexed { index, item ->
                key(item.id) {
                    CheckboxItem(
                        text = labels[index],
                        checked = item.focused,
                        onCheckedChange = { _ -> item.focus() },
                    )
                }
            }
        }
    }
}

private class RegisteredWindow {
    var entry: OpenWindow? = null
}
