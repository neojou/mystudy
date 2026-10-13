package com.neojou.mystudy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicLong

/**
 * One Compose window shown in the macOS Windows menu.
 *
 * [title] and [focused] are observable so every window's menu updates together.
 * [kind] is the Ask or Chat source. It is appended only when another open window
 * has the same title.
 */
internal class OpenWindow(
    val id: Long,
    title: String,
    val kind: String?,
    val focus: () -> Unit,
) {
    var title by mutableStateOf(title)
    var focused by mutableStateOf(false)
}

/**
 * Open Compose windows in the order they were registered.
 *
 * Dialogs are not registered. The app menu reads [shared].
 */
internal class OpenWindowList {
    private val entries = mutableStateListOf<OpenWindow>()
    private val nextId = AtomicLong(1)

    val windows: List<OpenWindow> get() = entries

    fun register(title: String, kind: String?, focus: () -> Unit): OpenWindow {
        val window = OpenWindow(
            id = nextId.getAndIncrement(),
            title = title,
            kind = kind?.takeIf { it.isNotBlank() },
            focus = focus,
        )
        entries.add(window)
        return window
    }

    fun unregister(window: OpenWindow) {
        entries.remove(window)
    }

    companion object {
        val shared = OpenWindowList()
    }
}

/**
 * Menu text for [windows], in the same order.
 *
 * A title that appears once is used as-is. A repeated title keeps [OpenWindow.kind]
 * in parentheses. Repeated titles without a kind are numbered from 1.
 */
internal fun menuLabels(windows: List<OpenWindow>): List<String> {
    val counts = windows.groupingBy { it.title }.eachCount()
    val numbers = HashMap<String, Int>()
    return windows.map { window ->
        if ((counts[window.title] ?: 0) <= 1) {
            window.title
        } else {
            val kind = window.kind?.takeIf { it.isNotBlank() }
            if (kind != null) {
                "${window.title} ($kind)"
            } else {
                val number = (numbers[window.title] ?: 0) + 1
                numbers[window.title] = number
                "${window.title} ($number)"
            }
        }
    }
}
