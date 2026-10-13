package com.neojou.mystudy.wiki.lint

import com.neojou.mystudy.wiki.index.WikiIndex
import com.neojou.mystudy.wiki.markdown.parseWikilinks

/**
 * Reads the ask-page snapshot. Call this on the wiki-index dispatcher.
 * Rules after this return do not touch SQLite.
 */
fun lintSnapshot(index: WikiIndex): LintSnapshot {
    val pages = index.indexedNotes().map { note ->
        val stem = note.path.substringAfterLast('/').removeSuffix(".md")
        val oversized = note.oversized
        LintPage(
            path = note.path,
            stem = stem,
            title = note.title.ifBlank { stem },
            aliases = note.aliases,
            type = note.type,
            tags = note.tags,
            body = if (oversized) "" else note.body,
            outlinks = if (oversized) emptyList() else parseWikilinks(note.body),
            oversized = oversized,
        )
    }
    val tokens = linkedSetOf<String>()
    for (page in pages) {
        tokens += page.outlinks
        tokens += page.tags
    }
    val resolutions = linkedMapOf<String, com.neojou.mystudy.wiki.model.Resolution>()
    for (token in tokens) {
        if (token.isBlank()) continue
        resolutions[token] = index.resolve(token)
    }
    return LintSnapshot(pages, resolutions)
}
