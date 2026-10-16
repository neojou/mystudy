package com.neojou.mystudy.study

import java.nio.file.Files
import java.nio.file.Path
import kotlin.collections.ArrayDeque

enum class NavOrigin { Link, Tree, Back, Forward, System }

enum class NavDirection { Back, Forward }

enum class NavKey { LeftBracket, RightBracket, Left, Right, Other }

enum class NavCommand { Back, Forward }

data class ScrollAnchor(val firstVisibleIndex: Int, val offset: Int)

data class HistoryEntry(
    val path: Path,
    val mode: NoteViewMode,
    val scroll: ScrollAnchor?,
)

internal fun sameNotePath(left: Path?, right: Path?): Boolean {
    if (left == null || right == null) return false
    return normalizeNotePath(left) == normalizeNotePath(right)
}

internal fun normalizeNotePath(path: Path): Path = path.toAbsolutePath().normalize()

internal fun isExistingNote(path: Path): Boolean = try {
    Files.isRegularFile(path)
} catch (_: Exception) {
    false
}

internal fun isMacOs(osName: String): Boolean = osName.contains("mac", ignoreCase = true)

/**
 * In-memory back/forward stacks for the preview pane.
 * Each stack keeps at most [limit] entries and drops the oldest.
 * Not written to Preferences or the vault.
 */
class NavigationHistory(private val limit: Int = 50) {
    private val lock = Any()
    private val backStack = ArrayDeque<HistoryEntry>()
    private val forwardStack = ArrayDeque<HistoryEntry>()
    private var currentEntry: HistoryEntry? = null

    val canGoBack: Boolean get() = synchronized(lock) { backStack.isNotEmpty() }
    val canGoForward: Boolean get() = synchronized(lock) { forwardStack.isNotEmpty() }

    fun current(): HistoryEntry? = synchronized(lock) { currentEntry }

    fun peek(direction: NavDirection): HistoryEntry? = synchronized(lock) { peekLocked(direction) }

    fun visit(entry: HistoryEntry) {
        synchronized(lock) {
            val normalized = entry.copy(path = normalizeNotePath(entry.path))
            val cur = currentEntry
            if (cur != null && sameNotePath(cur.path, normalized.path)) {
                currentEntry = normalized
                return
            }
            if (cur != null) {
                backStack.addLast(cur)
                trim(backStack)
            }
            forwardStack.clear()
            currentEntry = normalized
        }
    }

    fun updateCurrent(mode: NoteViewMode, scroll: ScrollAnchor?) {
        synchronized(lock) {
            val cur = currentEntry ?: return
            currentEntry = cur.copy(mode = mode, scroll = scroll)
        }
    }

    fun commit(direction: NavDirection): HistoryEntry? {
        return synchronized(lock) {
            val stack = stack(direction)
            if (stack.isEmpty()) return null
            val target = stack.removeLast()
            val cur = currentEntry
            if (cur != null) {
                val dest = stack(opposite(direction))
                dest.addLast(cur)
                trim(dest)
            }
            currentEntry = target
            target
        }
    }

    /** Drops the next entry in [direction] and merges adjacent duplicates. */
    fun dropPeek(direction: NavDirection): HistoryEntry? = synchronized(lock) {
        val stack = stack(direction)
        if (stack.isNotEmpty()) stack.removeLast()
        mergeAdjacent(stack)
        peekLocked(direction)
    }

    fun clear() = synchronized(lock) {
        backStack.clear()
        forwardStack.clear()
        currentEntry = null
    }

    private fun peekLocked(direction: NavDirection): HistoryEntry? {
        val stack = stack(direction)
        return if (stack.isEmpty()) null else stack.last()
    }

    private fun stack(direction: NavDirection): ArrayDeque<HistoryEntry> = when (direction) {
        NavDirection.Back -> backStack
        NavDirection.Forward -> forwardStack
    }

    private fun opposite(direction: NavDirection): NavDirection = when (direction) {
        NavDirection.Back -> NavDirection.Forward
        NavDirection.Forward -> NavDirection.Back
    }

    private fun trim(stack: ArrayDeque<HistoryEntry>) {
        while (stack.size > limit) stack.removeFirst()
    }

    private fun mergeAdjacent(stack: ArrayDeque<HistoryEntry>) {
        while (stack.size >= 2) {
            val top = stack.last()
            val under = stack.elementAt(stack.size - 2)
            if (!sameNotePath(top.path, under.path)) break
            val kept = stack.removeLast()
            stack.removeLast()
            stack.addLast(kept)
        }
        val open = currentEntry?.path ?: return
        while (stack.isNotEmpty() && sameNotePath(stack.last().path, open)) {
            stack.removeLast()
        }
    }
}

internal fun navShortcut(
    key: NavKey,
    ctrl: Boolean,
    meta: Boolean,
    alt: Boolean,
    shift: Boolean,
    editing: Boolean,
    mac: Boolean,
): NavCommand? {
    if (shift) return null
    if (mac) {
        if (ctrl) return null
        if (meta && !alt && key == NavKey.LeftBracket) return NavCommand.Back
        if (meta && !alt && key == NavKey.RightBracket) return NavCommand.Forward
        if (meta && alt && key == NavKey.Left) return NavCommand.Back
        if (meta && alt && key == NavKey.Right) return NavCommand.Forward
        return null
    }
    if (meta) return null
    if (alt && ctrl && key == NavKey.Left) return NavCommand.Back
    if (alt && ctrl && key == NavKey.Right) return NavCommand.Forward
    if (alt && !ctrl && !editing && key == NavKey.Left) return NavCommand.Back
    if (alt && !ctrl && !editing && key == NavKey.Right) return NavCommand.Forward
    return null
}

/** Side buttons. When both are down, back wins. */
internal fun navFromPointer(back: Boolean, forward: Boolean): NavCommand? = when {
    back -> NavCommand.Back
    forward -> NavCommand.Forward
    else -> null
}

internal fun restoredMode(saved: NoteViewMode, editable: Boolean): NoteViewMode =
    if (saved == NoteViewMode.Editing && editable) NoteViewMode.Editing else NoteViewMode.Reading

internal fun clampAnchor(anchor: ScrollAnchor, itemCount: Int): ScrollAnchor {
    if (itemCount <= 0) return ScrollAnchor(0, 0)
    val index = anchor.firstVisibleIndex.coerceIn(0, itemCount - 1)
    val offset = if (index == anchor.firstVisibleIndex) anchor.offset.coerceAtLeast(0) else 0
    return ScrollAnchor(index, offset)
}

internal fun ellipsizeFileName(name: String, maxChars: Int = 40): String {
    if (name.length <= maxChars) return name
    val dot = name.lastIndexOf('.')
    val ext = if (dot > 0 && name.length - dot <= 8) name.substring(dot) else ""
    val base = if (ext.isEmpty()) name else name.substring(0, name.length - ext.length)
    val budget = maxChars - 1 - ext.length
    if (budget <= 1) return name.take(maxChars - 1) + "…"
    val head = budget / 2
    val tail = budget - head
    if (base.length <= head + tail) return name
    return base.take(head) + "…" + base.takeLast(tail) + ext
}

internal fun navTooltip(
    command: NavCommand,
    fileName: String?,
    enabled: Boolean,
    mac: Boolean,
): String {
    if (!enabled || fileName.isNullOrBlank()) {
        return if (command == NavCommand.Back) "沒有上一則" else "沒有下一則"
    }
    val shown = ellipsizeFileName(fileName)
    val key = when {
        mac && command == NavCommand.Back -> "⌘["
        mac && command == NavCommand.Forward -> "⌘]"
        command == NavCommand.Back -> "Alt+←"
        else -> "Alt+→"
    }
    val verb = if (command == NavCommand.Back) "返回" else "前進"
    return "$verb：$shown（$key）"
}

internal fun missingNoteMessage(direction: NavDirection): String = when (direction) {
    NavDirection.Back -> "上一則筆記已不存在"
    NavDirection.Forward -> "下一則筆記已不存在"
}
