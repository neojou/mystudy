package com.neojou.mystudy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class OpenWindowListTest {
    @Test
    fun registerKeepsOrderAndDroppingAWindowRemovesIt() {
        val list = OpenWindowList()
        val main = list.register("mystudy", null) {}
        val graph = list.register("Knowledge graph", "Ask") {}
        assertEquals(listOf(main, graph), list.windows)
        assertFalse(main.focused)

        main.title = "renamed"
        assertEquals("renamed", list.windows.first().title)

        list.unregister(main)
        assertEquals(listOf(graph), list.windows)
    }

    @Test
    fun uniqueTitlesStayAsTheyAre() {
        val main = window(1, "mystudy", null)
        val page = window(2, "卡片", "Ask")
        assertEquals(listOf("mystudy", "卡片"), menuLabels(listOf(main, page)))
    }

    @Test
    fun repeatedTitlesUseTheAskOrChatSource() {
        val ask = window(1, "Knowledge graph", "Ask")
        val chat = window(2, "Knowledge graph", "Chat")
        assertEquals(
            listOf("Knowledge graph (Ask)", "Knowledge graph (Chat)"),
            menuLabels(listOf(ask, chat)),
        )

        ask.title = "Other"
        assertEquals(listOf("Other", "Knowledge graph"), menuLabels(listOf(ask, chat)))
    }

    @Test
    fun repeatedTitlesWithoutASourceAreNumbered() {
        val list = OpenWindowList()
        val first = list.register("Note", null) {}
        val second = list.register("Note", "  ") {}
        assertEquals(null, first.kind)
        assertEquals(null, second.kind)
        assertEquals(listOf("Note (1)", "Note (2)"), menuLabels(list.windows))
    }

    private fun window(id: Long, title: String, kind: String?) = OpenWindow(id, title, kind, {})
}
