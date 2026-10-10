package com.neojou.mystudy.wiki.ask

import com.neojou.mystudy.wiki.index.NoteRecord
import com.neojou.mystudy.wiki.index.WikiIndex
import com.neojou.mystudy.wiki.markdown.QueryAnalysis
import com.neojou.mystudy.wiki.markdown.analyzeQuery

data class ScoredNote(
    val path: String,
    val title: String,
    val type: String,
    val body: String,
    val sources: List<String>,
    val score: Double,
    val canSeed: Boolean,
)

fun includedAskPath(path: String): Boolean = when {
    path == "wiki/index.md" || path == "wiki/log.md" || path == "wiki/schema.md" -> false
    path.startsWith("raw/") -> false
    path.startsWith("wiki/") -> true
    else -> false
}

fun scoreNote(
    note: NoteRecord,
    analysis: QueryAnalysis,
    documentFrequency: Map<String, Int>,
    noteCount: Int,
    allowRaw: Boolean = false,
    anchors: List<String> = emptyList(),
): ScoredNote? {
    if (!allowRaw && !includedAskPath(note.path)) return null
    if (note.path == "wiki/index.md" || note.path == "wiki/log.md" || note.path == "wiki/schema.md") return null
    val names = listOf(note.title) + note.aliases
    val tokens = analysis.bigrams + analysis.extras
    val phrase = analysis.phrase
    val phraseInTitle = phrase.isNotBlank() && names.any { it.contains(phrase, ignoreCase = true) }
    val phraseInBody = phrase.isNotBlank() && (
        note.headings.any { it.contains(phrase, ignoreCase = true) } || note.body.contains(phrase, ignoreCase = true)
        )
    val anchorInTitle = anchors.any { anchor -> names.any { name -> name.contains(anchor, ignoreCase = true) } }
    val anchorInBody = anchors.any { anchor ->
        note.headings.any { it.contains(anchor, ignoreCase = true) } || note.body.contains(anchor, ignoreCase = true)
    }
    var score = 0.0
    if (phraseInTitle) score += 50
    if (anchorInTitle) score += 50
    if (anchorInBody) score += 8
    val matched = mutableListOf<String>()
    for (token in tokens) {
        val inTitle = names.any { it.contains(token, ignoreCase = true) }
        val inBody = note.headings.any { it.contains(token, ignoreCase = true) } ||
            note.body.contains(token, ignoreCase = true)
        if (!inTitle && !inBody) continue
        var points = if (inTitle) 10.0 else 1.0
        val itselfAnAnchor = anchors.any { it.equals(token, ignoreCase = true) }
        if (!itselfAnAnchor && isGeneric(token, documentFrequency[token] ?: 0, noteCount)) points *= 0.1
        score += points
        matched += token
    }
    if (matched.isEmpty() && !phraseInTitle && !phraseInBody && !anchorInTitle && !anchorInBody) return null
    if (note.path.startsWith("wiki/concepts/") || note.path.startsWith("wiki/entities/") || note.path.startsWith("wiki/sources/")) {
        score += 2
    }
    val canSeed = includedAskPath(note.path) && (anchorInTitle || anchorInBody)
    return ScoredNote(note.path, note.title, note.type, note.body, note.sources, score, canSeed)
}

/**
 * Anchors are the longest substrings of the longest content piece that actually occur in the index.
 * A shorter sibling piece, such as 原子 beside 卡片盒筆記法, cannot seed by itself.
 * Returns an empty list when the primary piece has no occurring substring of length ≥ 2.
 */
fun contentAnchors(pieces: List<String>, occurs: (String) -> Boolean): List<String> {
    if (pieces.isEmpty()) return emptyList()
    val longest = pieces.maxOf { it.length }
    return pieces.filter { it.length == longest }
        .flatMap { maximalHits(it, occurs) }
        .distinct()
}

private fun maximalHits(piece: String, occurs: (String) -> Boolean): List<String> {
    val hits = mutableListOf<String>()
    for (len in piece.length downTo 2) {
        for (start in 0..piece.length - len) {
            val span = piece.substring(start, start + len)
            if (hits.any { it.contains(span) }) continue
            if (occurs(span)) hits += span
        }
    }
    return hits
}

fun isGeneric(token: String, documentFrequency: Int, noteCount: Int): Boolean {
    if (noteCount <= 0) return false
    return documentFrequency.toDouble() / noteCount > 0.20
}

fun lexicalPool(index: WikiIndex, analysis: QueryAnalysis, anchors: List<String> = emptyList()): Pair<Int, List<NoteRecord>> {
    val ordered = linkedSetOf<String>()
    anchors.filter { it.isNotBlank() }.forEach { anchor -> ordered += index.searchAskTerm(anchor, 40) }
    val keys = listOf(analysis.phrase) + analysis.bigrams + analysis.extras
    keys.filter { it.isNotBlank() }.forEach { key -> ordered += index.pathsForAlias(key) }
    ordered += index.searchFts(analysis.ftsTerms, 500)
    val askPaths = ordered.filter(::includedAskPath)
    val pool = askPaths.take(40).mapNotNull { index.load(it) }
    return askPaths.size to pool
}

fun documentFrequency(index: WikiIndex, tokens: List<String>): Map<String, Int> =
    tokens.distinct().associateWith { index.countAskTerm(it) }

fun browseHits(index: WikiIndex, query: String): List<com.neojou.mystudy.wiki.index.NoteSummary> {
    val analysis = analyzeQuery(query)
    if (analysis.phrase.isBlank() && analysis.bigrams.isEmpty() && analysis.extras.isEmpty()) {
        return emptyList()
    }
    val tokens = analysis.bigrams + analysis.extras
    val frequency = documentFrequency(index, tokens)
    val count = index.askNoteCount()
    val anchors = contentAnchors(analysis.pieces) { index.countAskTerm(it) > 0 }
    val (_, pool) = lexicalPool(index, analysis, anchors)
    val extra = index.searchFts(analysis.ftsTerms, 40).filter { it.startsWith("raw/") }.mapNotNull { index.load(it) }
    return (pool + extra).mapNotNull { note ->
        scoreNote(note, analysis, frequency, count, allowRaw = true, anchors = anchors)
    }.sortedByDescending { it.score }.map {
        com.neojou.mystudy.wiki.index.NoteSummary(it.path, it.title, it.type)
    }
}
