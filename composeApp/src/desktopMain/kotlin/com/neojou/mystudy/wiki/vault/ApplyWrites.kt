package com.neojou.mystudy.wiki.vault

import com.neojou.mystudy.wiki.markdown.appendLog
import com.neojou.mystudy.wiki.markdown.insertCatalogLine
import com.neojou.mystudy.wiki.model.ApplyResult
import com.neojou.mystudy.wiki.model.PageProposal
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.exists
import kotlin.io.path.readText

/**
 * Writes checked pages, then index.md, then log.md.
 * A failure stops the rest. [ApplyResult.written] lists files that already landed.
 */
fun applyProposal(
    wikiRoot: Path,
    pages: List<PageProposal>,
    checked: Set<String>,
    logTitle: String,
    operation: String,
    date: LocalDate,
): ApplyResult {
    val active = pages.filter { it.selectable && it.relativePath in checked }
    if (active.isEmpty()) return ApplyResult(emptyList(), null)
    val written = mutableListOf<String>()
    for (page in active) {
        val failed = writeOne(wikiRoot, page.relativePath, page.newText)
        if (failed != null) return ApplyResult(written, failed)
        written += page.relativePath
    }
    val indexPath = wikiRoot.resolve("wiki").resolve("index.md")
    val currentIndex = if (indexPath.exists()) indexPath.readText(StandardCharsets.UTF_8) else "# Index\n"
    var updatedIndex = currentIndex
    for (page in active) {
        if (page.catalogLine.isBlank() || page.catalogHeading.isBlank()) continue
        updatedIndex = insertCatalogLine(updatedIndex, page.catalogHeading, page.catalogLine)
    }
    if (updatedIndex != currentIndex) {
        val failed = writeOne(wikiRoot, "wiki/index.md", updatedIndex)
        if (failed != null) return ApplyResult(written, failed)
        written += "wiki/index.md"
    }
    val logPath = wikiRoot.resolve("wiki").resolve("log.md")
    val currentLog = if (logPath.exists()) logPath.readText(StandardCharsets.UTF_8) else "# Log\n"
    val details = active.map { it.relativePath }
    val updatedLog = appendLog(currentLog, date, operation, logTitle.ifBlank { "untitled" }, details)
    val failed = writeOne(wikiRoot, "wiki/log.md", updatedLog)
    if (failed != null) return ApplyResult(written, failed)
    written += "wiki/log.md"
    return ApplyResult(written, null)
}

private fun writeOne(root: Path, relative: String, text: String): String? = try {
    val target = assertWikiWrite(root, relative)
    atomicWrite(target, text)
    null
} catch (error: Exception) {
    val landed = error.message ?: "write failed"
    "Stopped at $relative. $landed"
}

fun readTextIfExists(path: Path): String? {
    if (!Files.isRegularFile(path)) return null
    return path.readText(StandardCharsets.UTF_8)
}
