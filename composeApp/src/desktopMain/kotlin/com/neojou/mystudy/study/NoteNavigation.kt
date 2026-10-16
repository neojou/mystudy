package com.neojou.mystudy.study

import java.nio.file.Path

internal sealed interface NavEffect {
    data class Load(val path: Path, val restore: HistoryEntry?) : NavEffect
    data class NeedsConfirm(val pending: PendingNav.OpenFile) : NavEffect
    data object Ignored : NavEffect
    data class NoneLeft(val message: String) : NavEffect
}

internal sealed interface SkipResult {
    data class Found(val path: Path) : SkipResult
    data class NoneLeft(val message: String) : SkipResult
    data object Empty : SkipResult
}

/**
 * Decides whether a preview change moves [history].
 * Callers load the file only after [NavEffect.Load]. A dirty note returns
 * [NavEffect.NeedsConfirm] and leaves the stacks where they are.
 */
internal class NoteNavigation(
    private val history: NavigationHistory = NavigationHistory(),
) {
    val canGoBack: Boolean get() = history.canGoBack
    val canGoForward: Boolean get() = history.canGoForward

    fun current(): HistoryEntry? = history.current()

    fun peek(direction: NavDirection): HistoryEntry? = history.peek(direction)

    fun updateCurrent(mode: NoteViewMode, scroll: ScrollAnchor?) {
        history.updateCurrent(mode, scroll)
    }

    fun request(
        session: NoteSession,
        path: Path,
        origin: NavOrigin,
        mode: NoteViewMode,
        scroll: ScrollAnchor?,
    ): NavEffect {
        val normalized = normalizeNotePath(path)
        if (origin == NavOrigin.System) return NavEffect.Load(normalized, restore = null)
        if (origin != NavOrigin.Back && origin != NavOrigin.Forward && sameNotePath(session.path, normalized)) {
            return NavEffect.Ignored
        }
        val pending = PendingNav.OpenFile(normalized, origin)
        if (session.requestLeave(pending)) return NavEffect.NeedsConfirm(pending)
        return move(normalized, origin, mode, scroll)
    }

    fun commitPending(
        nav: PendingNav,
        mode: NoteViewMode,
        scroll: ScrollAnchor?,
    ): NavEffect {
        val open = nav as? PendingNav.OpenFile ?: return NavEffect.Ignored
        return move(normalizeNotePath(open.path), open.origin, mode, scroll)
    }

    fun skipMissing(direction: NavDirection, exists: (Path) -> Boolean): SkipResult {
        var removed = false
        while (true) {
            val peek = history.peek(direction)
            if (peek == null) {
                return if (removed) SkipResult.NoneLeft(missingNoteMessage(direction)) else SkipResult.Empty
            }
            if (exists(peek.path)) return SkipResult.Found(peek.path)
            history.dropPeek(direction)
            removed = true
        }
    }

    fun clear() {
        history.clear()
    }

    private fun move(
        path: Path,
        origin: NavOrigin,
        mode: NoteViewMode,
        scroll: ScrollAnchor?,
    ): NavEffect {
        history.updateCurrent(mode, scroll)
        return when (origin) {
            NavOrigin.Back -> {
                val target = history.commit(NavDirection.Back) ?: return NavEffect.Ignored
                NavEffect.Load(target.path, target)
            }
            NavOrigin.Forward -> {
                val target = history.commit(NavDirection.Forward) ?: return NavEffect.Ignored
                NavEffect.Load(target.path, target)
            }
            NavOrigin.System -> NavEffect.Load(path, restore = null)
            NavOrigin.Link, NavOrigin.Tree -> {
                history.visit(HistoryEntry(path, NoteViewMode.Reading, scroll = null))
                NavEffect.Load(path, restore = null)
            }
        }
    }
}
