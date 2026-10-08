package com.neojou.mystudy.wiki.ask

import com.neojou.mystudy.wiki.index.NoteRecord
import com.neojou.mystudy.wiki.index.WikiIndex
import com.neojou.mystudy.wiki.markdown.QueryAnalysis
import com.neojou.mystudy.wiki.markdown.analyzeQuery

private val genericBigrams = setOf("原子", "筆記", "方法", "概念", "系統", "设计", "設計", "知識", "知识")

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
    var score = 0.0
    if (phraseInTitle) score += 50
    val matched = mutableListOf<String>()
    for (token in tokens) {
        val inTitle = names.any { it.contains(token, ignoreCase = true) }
        val inBody = note.headings.any { it.contains(token, ignoreCase = true) } ||
            note.body.contains(token, ignoreCase = true)
        if (inTitle) {
            score += 10
            matched += token
        } else if (inBody) {
            score += 1
            matched += token
        }
    }
    if (matched.isEmpty() && !phraseInTitle && !phraseInBody) return null
    if (note.path.startsWith("wiki/concepts/") || note.path.startsWith("wiki/entities/") || note.path.startsWith("wiki/sources/")) {
        score += 2
    }
    val allGeneric = matched.isNotEmpty() && matched.all { isGeneric(it, documentFrequency[it] ?: 0, noteCount) }
    if (allGeneric && !phraseInTitle) score *= 0.1
    val canSeed = includedAskPath(note.path) && (phraseInTitle || !allGeneric)
    return ScoredNote(note.path, note.title, note.type, note.body, note.sources, score, canSeed)
}

fun isGeneric(token: String, documentFrequency: Int, noteCount: Int): Boolean {
    if (token in genericBigrams) return true
    if (noteCount <= 0) return false
    return documentFrequency.toDouble() / noteCount > 0.20
}

fun lexicalPool(index: WikiIndex, analysis: QueryAnalysis): Pair<Int, List<NoteRecord>> {
    val keys = listOf(analysis.phrase) + analysis.bigrams + analysis.extras
    val ordered = linkedSetOf<String>()
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
    val (_, pool) = lexicalPool(index, analysis)
    val extra = index.searchFts(analysis.ftsTerms, 40).filter { it.startsWith("raw/") }.mapNotNull { index.load(it) }
    return (pool + extra).mapNotNull { note ->
        scoreNote(note, analysis, frequency, count, allowRaw = true)
    }.sortedByDescending { it.score }.map {
        com.neojou.mystudy.wiki.index.NoteSummary(it.path, it.title, it.type)
    }
}
