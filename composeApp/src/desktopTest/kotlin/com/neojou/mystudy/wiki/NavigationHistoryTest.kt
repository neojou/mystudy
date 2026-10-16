package com.neojou.mystudy.wiki

import com.neojou.mystudy.study.HistoryEntry
import com.neojou.mystudy.study.NavCommand
import com.neojou.mystudy.study.NavDirection
import com.neojou.mystudy.study.NavEffect
import com.neojou.mystudy.study.NavKey
import com.neojou.mystudy.study.NavOrigin
import com.neojou.mystudy.study.NavigationHistory
import com.neojou.mystudy.study.NoteNavigation
import com.neojou.mystudy.study.NoteSession
import com.neojou.mystudy.study.NoteViewMode
import com.neojou.mystudy.study.PendingNav
import com.neojou.mystudy.study.ScrollAnchor
import com.neojou.mystudy.study.SkipResult
import com.neojou.mystudy.study.clampAnchor
import com.neojou.mystudy.study.ellipsizeFileName
import com.neojou.mystudy.study.isExistingNote
import com.neojou.mystudy.study.navFromPointer
import com.neojou.mystudy.study.navShortcut
import com.neojou.mystudy.study.navTooltip
import com.neojou.mystudy.study.restoredMode
import com.neojou.mystudy.wiki.vault.EditPermission
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NavigationHistoryTest {
    @Test
    fun firstVisitCannotGoBack() {
        val history = NavigationHistory()
        assertFalse(history.canGoBack)
        assertFalse(history.canGoForward)
        history.visit(entry("a.md"))
        assertFalse(history.canGoBack)
        assertEquals("a.md", history.current()?.path?.fileName?.toString())
    }

    @Test
    fun backAndForwardWalkTheStacks() {
        val history = NavigationHistory()
        history.visit(entry("a.md"))
        history.visit(entry("b.md"))
        history.visit(entry("c.md"))
        assertEquals("b.md", name(history.commit(NavDirection.Back)))
        assertEquals("a.md", name(history.commit(NavDirection.Back)))
        assertEquals("b.md", name(history.commit(NavDirection.Forward)))
        assertEquals("b.md", history.current()?.path?.fileName?.toString())
    }

    @Test
    fun visitAfterBackClearsForward() {
        val history = NavigationHistory()
        history.visit(entry("a.md"))
        history.visit(entry("b.md"))
        history.visit(entry("c.md"))
        history.commit(NavDirection.Back)
        history.visit(entry("d.md"))
        assertFalse(history.canGoForward)
        assertEquals("b.md", name(history.commit(NavDirection.Back)))
    }

    @Test
    fun consecutiveSameFileIsDedupedButALaterReturnIsKept() {
        val history = NavigationHistory()
        val a = entry("a.md")
        history.visit(a)
        history.visit(a)
        assertFalse(history.canGoBack)
        history.visit(entry("b.md"))
        history.visit(entry("a.md"))
        assertEquals("b.md", name(history.commit(NavDirection.Back)))
        assertEquals("a.md", name(history.commit(NavDirection.Back)))
        assertFalse(history.canGoBack)
    }

    @Test
    fun backStackDropsTheOldestPastFifty() {
        val history = NavigationHistory(limit = 50)
        val dir = Files.createTempDirectory("nav-limit")
        val paths = (1..61).map { dir.resolve("n$it.md") }
        paths.forEach { history.visit(entry(it)) }
        var steps = 0
        while (history.canGoBack) {
            history.commit(NavDirection.Back)
            steps++
        }
        assertEquals(50, steps)
        assertEquals("n11.md", history.current()?.path?.fileName?.toString())
        assertFalse(history.canGoBack)
    }

    @Test
    fun forwardStackAlsoCaps() {
        val history = NavigationHistory(limit = 2)
        history.visit(entry("a.md"))
        history.visit(entry("b.md"))
        history.visit(entry("c.md"))
        history.visit(entry("d.md"))
        history.commit(NavDirection.Back)
        history.commit(NavDirection.Back)
        assertTrue(history.canGoForward)
        assertEquals("c.md", history.peek(NavDirection.Forward)?.path?.fileName?.toString())
        assertEquals(false, history.canGoBack)
    }

    @Test
    fun peekDoesNotMoveAndCommitDoes() {
        val history = NavigationHistory()
        history.visit(entry("a.md"))
        history.visit(entry("b.md"))
        val peeked = history.peek(NavDirection.Back)
        assertEquals(peeked, history.peek(NavDirection.Back))
        assertEquals("b.md", history.current()?.path?.fileName?.toString())
        assertEquals(peeked, history.commit(NavDirection.Back))
        assertEquals("a.md", history.current()?.path?.fileName?.toString())
    }

    @Test
    fun dropPeekMergesAdjacentDuplicates() {
        val history = NavigationHistory()
        history.visit(entry("a.md"))
        history.visit(entry("b.md"))
        history.visit(entry("a.md"))
        history.dropPeek(NavDirection.Back)
        assertFalse(history.canGoBack)
        assertEquals("a.md", history.current()?.path?.fileName?.toString())
    }

    @Test
    fun updateCurrentIsWhatBackRestores() {
        val history = NavigationHistory()
        history.visit(entry("a.md"))
        history.visit(entry("b.md"))
        val scroll = ScrollAnchor(3, 12)
        history.updateCurrent(NoteViewMode.Editing, scroll)
        history.commit(NavDirection.Back)
        val saved = history.peek(NavDirection.Forward)
        assertEquals(NoteViewMode.Editing, saved?.mode)
        assertEquals(scroll, saved?.scroll)
        history.commit(NavDirection.Forward)
        assertEquals(scroll, history.current()?.scroll)
        assertEquals(NoteViewMode.Editing, history.current()?.mode)
    }

    @Test
    fun clearDropsBothStacks() {
        val history = NavigationHistory()
        history.visit(entry("a.md"))
        history.visit(entry("b.md"))
        history.commit(NavDirection.Back)
        history.clear()
        assertFalse(history.canGoBack)
        assertFalse(history.canGoForward)
        assertNull(history.current())
    }

    @Test
    fun restoredModeAndClampFollowTheRules() {
        assertEquals(NoteViewMode.Reading, restoredMode(NoteViewMode.Editing, editable = false))
        assertEquals(NoteViewMode.Editing, restoredMode(NoteViewMode.Editing, editable = true))
        assertEquals(NoteViewMode.Reading, restoredMode(NoteViewMode.Reading, editable = true))
        assertEquals(ScrollAnchor(2, 0), clampAnchor(ScrollAnchor(9, 4), itemCount = 3))
        assertEquals(ScrollAnchor(0, 0), clampAnchor(ScrollAnchor(1, 4), itemCount = 0))
        assertEquals(ScrollAnchor(1, 4), clampAnchor(ScrollAnchor(1, 4), itemCount = 3))
    }

    @Test
    fun tooltipKeepsTheExtensionAndNamesTheShortcut() {
        val long = "甲".repeat(30) + "乙".repeat(20) + ".md"
        val shown = ellipsizeFileName(long)
        assertTrue(shown.endsWith(".md"))
        assertTrue(shown.length <= 40)
        assertTrue(shown.contains("…"))
        assertEquals("筆記.md", ellipsizeFileName("筆記.md"))
        assertEquals("返回：筆記.md（⌘[）", navTooltip(NavCommand.Back, "筆記.md", enabled = true, mac = true))
        assertEquals("前進：筆記.md（Alt+→）", navTooltip(NavCommand.Forward, "筆記.md", enabled = true, mac = false))
        assertEquals("沒有上一則", navTooltip(NavCommand.Back, "筆記.md", enabled = false, mac = true))
        assertEquals("沒有下一則", navTooltip(NavCommand.Forward, null, enabled = true, mac = false))
    }

    @Test
    fun shortcutsLeaveBareAltArrowsToTheEditor() {
        assertEquals(NavCommand.Back, shortcut(NavKey.LeftBracket, meta = true, mac = true))
        assertEquals(NavCommand.Forward, shortcut(NavKey.RightBracket, meta = true, mac = true))
        assertEquals(NavCommand.Back, shortcut(NavKey.Left, meta = true, alt = true, mac = true))
        assertEquals(NavCommand.Forward, shortcut(NavKey.Right, meta = true, alt = true, mac = true))
        assertNull(shortcut(NavKey.Left, alt = true, mac = true, editing = false))
        assertNull(shortcut(NavKey.Left, alt = true, mac = true, editing = true))
        assertNull(shortcut(NavKey.Left, alt = true, mac = false, editing = true))
        assertEquals(NavCommand.Back, shortcut(NavKey.Left, alt = true, mac = false, editing = false))
        assertEquals(NavCommand.Forward, shortcut(NavKey.Right, alt = true, mac = false, editing = false))
        assertEquals(NavCommand.Back, shortcut(NavKey.Left, ctrl = true, alt = true, mac = false, editing = true))
        assertEquals(NavCommand.Forward, shortcut(NavKey.Right, ctrl = true, alt = true, mac = false, editing = false))
        assertNull(shortcut(NavKey.LeftBracket, ctrl = true, mac = false))
        assertNull(shortcut(NavKey.LeftBracket, ctrl = true, mac = true))
        assertNull(shortcut(NavKey.Left, alt = true, shift = true, mac = false, editing = false))
        assertNull(shortcut(NavKey.Other, ctrl = true, mac = false))
        assertEquals(NavCommand.Back, navFromPointer(back = true, forward = true))
        assertEquals(NavCommand.Forward, navFromPointer(back = false, forward = true))
        assertNull(navFromPointer(back = false, forward = false))
    }

    @Test
    fun treeAndLinkThenBackAndForward() {
        val dir = Files.createTempDirectory("nav-walk")
        val a = note(dir, "a.md")
        val b = note(dir, "b.md")
        val c = note(dir, "c.md")
        val nav = NoteNavigation()
        val session = cleanSession()
        open(nav, session, a, NavOrigin.Tree)
        open(nav, session, b, NavOrigin.Tree)
        open(nav, session, c, NavOrigin.Link)
        assertEquals(b.fileName.toString(), go(nav, session, NavDirection.Back))
        assertEquals(a.fileName.toString(), go(nav, session, NavDirection.Back))
        assertEquals(b.fileName.toString(), go(nav, session, NavDirection.Forward))
        assertEquals(b.fileName.toString(), nav.current()?.path?.fileName?.toString())
    }

    @Test
    fun dirtyBackWaitsAndCancelLeavesTheStack() {
        val dir = Files.createTempDirectory("nav-dirty")
        val a = note(dir, "a.md")
        val b = note(dir, "b.md")
        val nav = NoteNavigation()
        val session = cleanSession()
        open(nav, session, a, NavOrigin.Tree)
        open(nav, session, b, NavOrigin.Tree)
        session.draft = "edited"
        val found = nav.skipMissing(NavDirection.Back, ::isExistingNote)
        assertIs<SkipResult.Found>(found)
        val blocked = nav.request(session, found.path, NavOrigin.Back, NoteViewMode.Editing, ScrollAnchor(2, 1))
        val pending = assertIs<NavEffect.NeedsConfirm>(blocked)
        assertEquals(NavOrigin.Back, pending.pending.origin)
        assertEquals(a.fileName.toString(), nav.peek(NavDirection.Back)?.path?.fileName?.toString())
        session.cancelLeave()
        assertEquals(a.fileName.toString(), nav.peek(NavDirection.Back)?.path?.fileName?.toString())
        assertEquals(b.fileName.toString(), nav.current()?.path?.fileName?.toString())
        assertTrue(session.dirty)
    }

    @Test
    fun discardThenCommitMovesAndKeepsForward() {
        val dir = Files.createTempDirectory("nav-discard")
        val a = note(dir, "a.md")
        val b = note(dir, "b.md")
        val nav = NoteNavigation()
        val session = cleanSession()
        open(nav, session, a, NavOrigin.Tree)
        open(nav, session, b, NavOrigin.Tree)
        session.mode = NoteViewMode.Editing
        session.draft = "edited"
        val found = assertIs<SkipResult.Found>(nav.skipMissing(NavDirection.Back, ::isExistingNote))
        assertIs<NavEffect.NeedsConfirm>(
            nav.request(session, found.path, NavOrigin.Back, session.mode, ScrollAnchor(4, 8)),
        )
        val pending = session.discardAndProceed()
        assertIs<PendingNav.OpenFile>(pending)
        val loaded = nav.commitPending(pending, NoteViewMode.Editing, ScrollAnchor(4, 8))
        val effect = assertIs<NavEffect.Load>(loaded)
        assertEquals(a.fileName.toString(), effect.path.fileName?.toString())
        assertEquals(NoteViewMode.Reading, effect.restore?.mode)
        assertEquals(NoteViewMode.Editing, nav.peek(NavDirection.Forward)?.mode)
        assertEquals(ScrollAnchor(4, 8), nav.peek(NavDirection.Forward)?.scroll)
        assertEquals(b.fileName.toString(), nav.peek(NavDirection.Forward)?.path?.fileName?.toString())
    }

    @Test
    fun confirmCancelledWithoutCommitDoesNotMove() {
        val dir = Files.createTempDirectory("nav-cancel")
        val a = note(dir, "a.md")
        val b = note(dir, "b.md")
        val nav = NoteNavigation()
        val session = cleanSession()
        open(nav, session, a, NavOrigin.Link)
        open(nav, session, b, NavOrigin.Link)
        session.draft = "edited"
        val found = assertIs<SkipResult.Found>(nav.skipMissing(NavDirection.Back, ::isExistingNote))
        assertIs<NavEffect.NeedsConfirm>(
            nav.request(session, found.path, NavOrigin.Back, NoteViewMode.Reading, null),
        )
        session.pendingNav = null
        assertEquals(a.fileName.toString(), nav.peek(NavDirection.Back)?.path?.fileName?.toString())
        assertFalse(nav.canGoForward)
    }

    @Test
    fun deletedTargetIsSkipped() {
        val dir = Files.createTempDirectory("nav-deleted")
        val a = note(dir, "a.md")
        val b = note(dir, "b.md")
        val c = note(dir, "c.md")
        val nav = NoteNavigation()
        val session = cleanSession()
        open(nav, session, a, NavOrigin.Tree)
        open(nav, session, b, NavOrigin.Tree)
        open(nav, session, c, NavOrigin.Link)
        Files.delete(a)
        assertEquals(b.fileName.toString(), go(nav, session, NavDirection.Back))
        val skipped = nav.skipMissing(NavDirection.Back, ::isExistingNote)
        val none = assertIs<SkipResult.NoneLeft>(skipped)
        assertEquals("上一則筆記已不存在", none.message)
        assertFalse(nav.canGoBack)
    }

    @Test
    fun clearAndSystemAndSameFile() {
        val dir = Files.createTempDirectory("nav-clear")
        val a = note(dir, "a.md")
        val b = note(dir, "b.md")
        val nav = NoteNavigation()
        val session = cleanSession()
        open(nav, session, a, NavOrigin.Tree)
        val same = nav.request(session, a, NavOrigin.Link, NoteViewMode.Reading, null)
        assertIs<NavEffect.Ignored>(same)
        assertFalse(nav.canGoBack)
        val system = nav.request(session, b, NavOrigin.System, NoteViewMode.Reading, null)
        assertIs<NavEffect.Load>(system)
        assertFalse(nav.canGoBack)
        assertEquals(a.fileName.toString(), nav.current()?.path?.fileName?.toString())
        nav.clear()
        assertNull(nav.current())
        assertFalse(nav.canGoBack)
        assertFalse(nav.canGoForward)
    }

    private fun shortcut(
        key: NavKey,
        ctrl: Boolean = false,
        meta: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        editing: Boolean = false,
        mac: Boolean,
    ): NavCommand? = navShortcut(key, ctrl, meta, alt, shift, editing, mac)

    private fun entry(name: String) = HistoryEntry(Path.of("/tmp/mystudy-nav/$name"), NoteViewMode.Reading, null)

    private fun entry(path: Path) = HistoryEntry(path, NoteViewMode.Reading, null)

    private fun name(entry: HistoryEntry?): String? = entry?.path?.fileName?.toString()

    private fun note(dir: Path, name: String): Path {
        val path = dir.resolve(name)
        Files.writeString(path, "body\n")
        return path
    }

    private fun cleanSession(): NoteSession {
        val session = NoteSession()
        session.permission = EditPermission.Editable(null)
        session.loadedText = "body"
        session.draft = "body"
        session.mode = NoteViewMode.Reading
        return session
    }

    private fun open(nav: NoteNavigation, session: NoteSession, path: Path, origin: NavOrigin) {
        val effect = nav.request(session, path, origin, session.mode, null)
        val load = assertIs<NavEffect.Load>(effect)
        session.path = load.path
        session.mode = NoteViewMode.Reading
        session.draft = session.loadedText
    }

    private fun go(nav: NoteNavigation, session: NoteSession, direction: NavDirection): String {
        val found = assertIs<SkipResult.Found>(nav.skipMissing(direction) { isExistingNote(it) })
        val origin = if (direction == NavDirection.Back) NavOrigin.Back else NavOrigin.Forward
        val effect = nav.request(session, found.path, origin, session.mode, null)
        val load = assertIs<NavEffect.Load>(effect)
        session.path = load.path
        return load.path.fileName.toString()
    }
}
