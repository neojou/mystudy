package com.neojou.mystudy.wiki

import com.neojou.mystudy.wiki.markdown.FrontmatterEntry
import com.neojou.mystudy.wiki.markdown.parseNote
import com.neojou.mystudy.wiki.markdown.readFrontmatter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PropertiesViewTest {
    @Test
    fun tagsAcceptAListAndAnInlineSequence() {
        val listed = readFrontmatter("---\ntags:\n  - 心智\n  - 認知\n---\n")
        val inline = readFrontmatter("---\ntags: [心智, 認知]\n---\n")
        assertEquals(listOf("心智", "認知"), (listed.entries.single() as FrontmatterEntry.ListValue).values)
        assertEquals(listOf("心智", "認知"), (inline.entries.single() as FrontmatterEntry.ListValue).values)
    }

    @Test
    fun scalarsKeepDateUrlWikilinkAndUnknownKeyOrder() {
        val view = readFrontmatter(
            """
            ---
            created: "2026-10-08"
            site: https://example.com
            see: "[[注意力]]"
            custom: 備註
            ---
            """.trimIndent(),
        )
        assertEquals(false, view.unparsed)
        assertEquals(listOf("created", "site", "see", "custom"), view.entries.map { it.key })
        assertEquals("2026-10-08", (view.entries[0] as FrontmatterEntry.Scalar).value)
        assertEquals("https://example.com", (view.entries[1] as FrontmatterEntry.Scalar).value)
        assertEquals("[[注意力]]", (view.entries[2] as FrontmatterEntry.Scalar).value)
        assertEquals("備註", (view.entries[3] as FrontmatterEntry.Scalar).value)
    }

    @Test
    fun unclosedFenceHasNoRawAndABadClosedFenceIsUnparsed() {
        val open = readFrontmatter("---\ntitle: 甲\n正文")
        assertTrue(open.unparsed)
        assertNull(open.raw)
        val bad = readFrontmatter("---\ntitle: 甲\n: 壞\n---\n\n正文")
        assertTrue(bad.unparsed)
        assertTrue(bad.raw?.contains("title: 甲") == true)
        val parsed = parseNote("---\ntitle: 注意力\ntype: concept\n---\n\n見 [[注意力]]\n", "file")
        assertEquals("注意力", parsed.title)
        assertIs<String>(parsed.body)
    }
}
