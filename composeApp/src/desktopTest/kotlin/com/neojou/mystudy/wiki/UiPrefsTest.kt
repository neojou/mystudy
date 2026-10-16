package com.neojou.mystudy.wiki

import com.neojou.mystudy.ui.theme.StudyTokens
import com.neojou.mystudy.ui.theme.contrastRatio
import com.neojou.mystudy.wiki.settings.AppSettings
import com.neojou.mystudy.wiki.settings.EditorSubMode
import com.neojou.mystudy.wiki.settings.MemorySettingsStore
import com.neojou.mystudy.wiki.settings.SettingsStore
import com.neojou.mystudy.wiki.settings.SidebarDragEnd
import com.neojou.mystudy.wiki.settings.SidebarWidthDrag
import com.neojou.mystudy.wiki.settings.clampSidebarWidth
import com.neojou.mystudy.wiki.settings.editorSubModeFromStored
import com.neojou.mystudy.wiki.settings.finishSidebarDrag
import com.neojou.mystudy.wiki.settings.parseSidebarWidth
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UiPrefsTest {
    @Test
    fun widthClampsAndAShortDragCollapses() {
        assertEquals(260, parseSidebarWidth(""))
        assertEquals(260, parseSidebarWidth("nope"))
        assertEquals(180, parseSidebarWidth("10"))
        assertEquals(560, parseSidebarWidth("900"))
        assertEquals(360f, clampSidebarWidth(400f, 800f))
        assertEquals(180f, clampSidebarWidth(150f, 1400f))
        assertIs<SidebarDragEnd.Collapse>(finishSidebarDrag(80f, 1400f))
        assertEquals(310, (finishSidebarDrag(310f, 1400f) as SidebarDragEnd.Persist).dp)
    }

    @Test
    fun dragWritesOnceWhenTheGestureEnds() {
        val store = CountingStore()
        val app = AppSettings(store)
        val drag = SidebarWidthDrag(260f, 1400f)
        repeat(5) { drag.moveBy(10f) }
        assertEquals(0, store.puts)
        val end = assertIs<SidebarDragEnd.Persist>(drag.finish())
        app.saveSidebarWidth(end.dp)
        assertEquals(1, store.puts)
        assertEquals("310", store.get("ui.sidebarWidthDp", ""))
    }

    @Test
    fun unknownEditorModeFallsBackAndStudySaveKeepsUiKeys() {
        assertEquals(EditorSubMode.LivePreview, editorSubModeFromStored(" "))
        assertEquals(EditorSubMode.LivePreview, editorSubModeFromStored("nope"))
        assertEquals(EditorSubMode.Source, editorSubModeFromStored("SOURCE"))
        val store = MemorySettingsStore()
        val app = AppSettings(store)
        app.saveSidebarWidth(240)
        app.saveEditorSubMode(EditorSubMode.Source)
        app.saveLimitLineWidth(false)
        app.save(app.load())
        assertEquals("240", store.get("ui.sidebarWidthDp", ""))
        assertEquals("source", store.get("ui.editorSubMode", ""))
        assertEquals("false", store.get("ui.limitLineWidth", ""))
        assertEquals(240, app.loadUi().sidebarWidthDp)
        assertEquals(EditorSubMode.Source, app.loadUi().editorSubMode)
        assertEquals(false, app.loadUi().limitLineWidth)
    }

    @Test
    fun headingColorsClearTheContrastFloor() {
        val surface = StudyTokens.Surface
        val tokens = StudyTokens.Light
        listOf(
            tokens.heading1,
            tokens.heading2,
            tokens.heading3,
            tokens.heading4,
            tokens.heading5,
            StudyTokens.OnSurface,
        ).forEach { color ->
            assertTrue(contrastRatio(color, surface) >= 4.5, contrastRatio(color, surface).toString())
        }
    }
}

private class CountingStore : SettingsStore {
    private val inner = MemorySettingsStore()
    var puts: Int = 0

    override fun get(key: String, default: String): String = inner.get(key, default)

    override fun put(key: String, value: String) {
        puts += 1
        inner.put(key, value)
    }
}
