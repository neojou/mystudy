package com.neojou.mystudy.wiki

import com.neojou.mystudy.study.breadcrumbCrumbs
import com.neojou.mystudy.study.noteChromeLabel
import com.neojou.mystudy.study.propertyCount
import com.neojou.mystudy.wiki.settings.EditorSubMode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UiChromeTest {
    @Test
    fun breadcrumbOmitsTheFileAndCollapsesTheMiddle() {
        val root = Files.createTempDirectory("crumbs")
        val file = root.resolve("a/b/c/d/note.md")
        Files.createDirectories(file.parent)
        Files.writeString(file, "x")
        val crumbs = breadcrumbCrumbs(root, file)
        assertEquals(listOf(root.fileName.toString(), "…", "c", "d"), crumbs.map { it.label })
        assertNull(crumbs[1].path)
        assertEquals(root, crumbs.first().path)
    }

    @Test
    fun shortPathKeepsTheParentAndDropsTheFileName() {
        val root = Files.createTempDirectory("crumbs-short")
        val file = root.resolve("folder/note.md")
        Files.createDirectories(file.parent)
        Files.writeString(file, "x")
        val crumbs = breadcrumbCrumbs(root, file)
        assertEquals(listOf(root.fileName.toString(), "folder"), crumbs.map { it.label })
    }

    @Test
    fun chromeLabelNamesTheSplitAndSource() {
        assertEquals("閱讀", noteChromeLabel(editing = false, EditorSubMode.LivePreview))
        assertEquals("即時預覽 · 分割檢視", noteChromeLabel(editing = true, EditorSubMode.LivePreview))
        assertEquals("Source", noteChromeLabel(editing = true, EditorSubMode.Source))
    }

    @Test
    fun propertyCountIgnoresABrokenFence() {
        assertEquals(2, propertyCount("---\ntags: [a]\ndate: 2026-01-02\n---\n# Hi\n"))
        assertEquals(0, propertyCount("---\ntags: [\n---\n"))
        assertEquals(0, propertyCount("# no fence\n"))
    }
}
