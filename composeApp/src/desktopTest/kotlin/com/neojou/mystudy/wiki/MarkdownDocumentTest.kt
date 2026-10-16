package com.neojou.mystudy.wiki

import com.neojou.mystudy.study.inlineMarkdown
import com.neojou.mystudy.study.markdown.ColumnAlign
import com.neojou.mystudy.study.markdown.MarkdownBlock
import com.neojou.mystudy.study.markdown.markdownBlocks
import com.neojou.mystudy.study.markdown.markdownTreeDebug
import com.neojou.mystudy.study.markdown.parseMarkdownDocument
import com.neojou.mystudy.study.markdown.spliceBlock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarkdownDocumentTest {
    @Test
    fun legacyFixtureKeepsHeadingParagraphBulletAndCode() {
        val blocks = markdownBlocks("# 標題\n\n這是**重點**與*斜體*和`程式`\n\n- 一項\n\n```\ncode\n```\n")
        assertEquals("標題", assertIs<MarkdownBlock.Heading>(blocks[0]).text)
        val paragraph = assertIs<MarkdownBlock.Paragraph>(blocks[1])
        assertEquals("這是重點與斜體和程式", inlineMarkdown(paragraph.text).text)
        assertIs<MarkdownBlock.Bullet>(blocks[2])
        assertEquals("code", assertIs<MarkdownBlock.Code>(blocks[3]).text)
        assertEquals(4, blocks.size)
    }

    @Test
    fun tableKeepsAlignmentCjkEscapedPipeAndRaggedRows() {
        val source = """
            | 項目 | 內容 | 備註 |
            | :--- | :---: | ---: |
            | 甲 | 乙\|丙 | 右 |
            | 只有一欄 |
        """.trimIndent()
        val table = parseMarkdownDocument(source).blocks.filterIsInstance<MarkdownBlock.Table>().singleOrNull()
            ?: error(markdownTreeDebug(source))
        assertEquals(listOf("項目", "內容", "備註"), table.header)
        assertEquals(listOf(ColumnAlign.Left, ColumnAlign.Center, ColumnAlign.Right), table.aligns)
        assertEquals("乙|丙", table.rows[0][1])
        assertEquals(listOf("只有一欄", "", ""), table.rows[1])
        assertCovered(source)
    }

    @Test
    fun fencePipeIsNotATable() {
        val source = "前文\n\n```\n| a | b |\n| --- | --- |\n```\n"
        val doc = parseMarkdownDocument(source)
        assertTrue(doc.blocks.none { it is MarkdownBlock.Table }, markdownTreeDebug(source))
        val code = doc.blocks.filterIsInstance<MarkdownBlock.Code>().single()
        assertTrue(code.text.contains("| a | b |"))
        assertCovered(source)
    }

    @Test
    fun frontmatterStaysOutOfTheBodyAndALaterRuleRemains() {
        val source = "---\ntitle: 甲\n---\n\n# 標題\n\n---\n"
        val doc = parseMarkdownDocument(source)
        assertEquals("title: 甲", doc.frontmatter?.raw)
        assertEquals(false, doc.frontmatter?.unparsed)
        assertTrue(doc.blocks.any { it is MarkdownBlock.Heading })
        assertTrue(doc.blocks.any { it is MarkdownBlock.Rule }, markdownTreeDebug(source))
        assertCovered(source)
    }

    @Test
    fun quoteCalloutOrderedListAndTask() {
        val source = """
            > 引用

            > [!warning] 小心
            > 內文

            1. 第一項
            2. 第二項

            - [x] 完成
            - [ ] 未做
        """.trimIndent()
        val doc = parseMarkdownDocument(source)
        assertTrue(doc.blocks.any { it is MarkdownBlock.Quote }, markdownTreeDebug(source))
        val callout = doc.blocks.filterIsInstance<MarkdownBlock.Callout>().singleOrNull()
            ?: error(markdownTreeDebug(source))
        assertEquals("warning", callout.kind)
        assertEquals("小心", callout.title)
        assertTrue(doc.blocks.filterIsInstance<MarkdownBlock.Ordered>().map { it.text }.containsAll(listOf("第一項", "第二項")))
        val tasks = doc.blocks.filterIsInstance<MarkdownBlock.Task>()
        assertEquals(listOf(true, false), tasks.map { it.checked })
        assertEquals(listOf("完成", "未做"), tasks.map { it.text })
        assertCovered(source)
    }

    @Test
    fun topLevelRangesTileTheSource() {
        val source = "---\ntags:\n  - 甲\n---\n\n# 一\n\n段落\n\n- 項\n"
        assertCovered(source)
        val doc = parseMarkdownDocument(source)
        assertTrue(doc.contentStart > 0)
        val rebuilt = buildString {
            append(source.substring(0, doc.contentStart))
            doc.blocks.forEach { append(source.substring(it.range)) }
        }
        assertEquals(source, rebuilt)
    }
}

private fun assertCovered(source: String) {
    val doc = parseMarkdownDocument(source)
    var cursor = doc.contentStart
    if (doc.blocks.isEmpty()) {
        assertEquals(source.length, cursor, markdownTreeDebug(source))
        return
    }
    for (block in doc.blocks) {
        assertEquals(cursor, block.range.first, markdownTreeDebug(source))
        cursor = block.range.last + 1
    }
    assertEquals(source.length, cursor, markdownTreeDebug(source))
}

class LivePreviewEditTest {
    @Test
    fun spliceReplacesOnlyTheRangeAndKeepsLf() {
        val source = "甲\n乙\n丙"
        val replaced = spliceBlock(source, 2..2, "丁丁")
        assertEquals("甲\n丁丁\n丙", replaced)
        assertTrue(replaced.contains('\n'))
        assertTrue(!replaced.contains('\r'))
    }
}
