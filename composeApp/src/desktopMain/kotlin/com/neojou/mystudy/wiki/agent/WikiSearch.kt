package com.neojou.mystudy.wiki.agent

import com.neojou.mystudy.wiki.ask.WikiGraph
import com.neojou.mystudy.wiki.ask.expandOneHop
import com.neojou.mystudy.wiki.ask.includedAskPath
import com.neojou.mystudy.wiki.ask.pathRank
import com.neojou.mystudy.wiki.index.WikiIndex
import java.util.Locale

data class SearchQuery(
    val runs: List<String>,
    val phrase: String,
    val terms: List<String>,
)

data class NameHit(
    val key: String,
    val paths: List<String>,
)

data class ScoreInput(
    val path: String,
    val title: String,
    val stem: String,
    val headings: List<String>,
    val body: String,
    val phrase: String,
    val terms: List<String>,
    val nameHit: Boolean,
    val nameKeys: Set<String>,
    val highFrequency: Set<String>,
)

data class SearchHit(
    val path: String,
    val title: String,
    val type: String,
    val snippet: String,
    val kind: String,
    val score: Double,
    val nameHit: Boolean,
    val links: String = "(none)",
)

data class SearchOutcome(
    val hits: List<SearchHit>,
    val tokenHits: Int,
    val nameHits: Int,
    val vectorHits: Int,
    val graphHits: Int,
    val mode: String,
)

private val leadVerbs = listOf(
    "介紹", "介绍", "說明", "说明", "解釋", "解释", "簡介", "简介",
    "描述", "概述", "談談", "谈谈", "講講", "讲讲", "告訴我", "告诉我",
    "什麼是", "什么是", "何謂", "何谓",
)

private val leadFrames: List<String> = buildList {
    add("請問")
    for (verb in leadVerbs) {
        add("請${verb}一下")
        add("請$verb")
        add("${verb}一下")
        add(verb)
    }
    add("請")
}.distinct().sortedByDescending { it.length }

private val trailFrames = listOf(
    "是誰", "是谁", "是什麼", "是什么", "是甚麼", "的介紹", "嗎", "吗", "呢", "？",
).sortedByDescending { it.length }

private val questionBigrams = setOf(
    "什麼", "什么", "與否", "何謂", "何為", "何为", "為何", "为何",
    "如何", "怎麼", "怎么", "怎樣", "怎样", "是否", "請問", "對於",
)

private val latinStops = setOf(
    "the", "is", "a", "an", "what", "how", "are", "was", "were",
    "do", "does", "did", "be", "been", "being", "have", "has", "had",
    "it", "its", "in", "on", "at", "to", "for", "of", "with", "by",
    "this", "that", "these", "those",
)

private val splitChars: Set<Char> = buildSet {
    "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~".forEach { add(it) }
    "，。！？、；：「」『』“”‘’（）()·～…".forEach { add(it) }
}

private val hanChar = Regex("\\p{IsHan}")

fun stripRequestFrame(run: String): String {
    var text = run.trim()
    while (text.isNotEmpty()) {
        val lead = leadFrames.firstOrNull { text.startsWith(it) }
        if (lead != null) {
            text = text.removePrefix(lead).trim()
            continue
        }
        val trail = trailFrames.firstOrNull { text.endsWith(it) }
        if (trail != null) {
            text = text.removeSuffix(trail).trim()
            continue
        }
        break
    }
    return text
}

/**
 * Splits on whitespace and punctuation only. Stop characters stay inside a name.
 * The agent chooses the query; this only scores that query.
 */
fun tokenizeSearchQuery(query: String): SearchQuery {
    val lower = query.lowercase(Locale.ROOT)
    val pieces = mutableListOf<String>()
    val buffer = StringBuilder()
    fun flush() {
        if (buffer.isNotEmpty()) {
            pieces += buffer.toString()
            buffer.clear()
        }
    }
    for (ch in lower) {
        if (ch.isWhitespace() || ch in splitChars) flush() else buffer.append(ch)
    }
    flush()
    val runs = pieces.map(::stripRequestFrame).filter { it.isNotEmpty() }
    val phrase = runs.maxByOrNull { it.length }.orEmpty()
    val terms = linkedSetOf<String>()
    for (run in runs) {
        if (hanChar.containsMatchIn(run)) {
            if (run.length >= 2) terms += run
            if (run.length >= 3) {
                for (index in 0 until run.length - 1) {
                    val bigram = run.substring(index, index + 2)
                    if (bigram !in questionBigrams) terms += bigram
                }
            }
        } else if (run.length >= 2 && run !in latinStops) {
            terms += run
        }
    }
    return SearchQuery(runs, phrase, terms.toList())
}

fun exactNameHits(runs: List<String>, lookup: (String) -> List<String>): List<NameHit> {
    val hits = mutableListOf<NameHit>()
    val accepted = mutableListOf<String>()
    for (run in runs) {
        val text = run.take(32)
        for (length in text.length downTo 2) {
            for (start in 0..text.length - length) {
                val span = text.substring(start, start + length)
                if (accepted.any { it.contains(span) }) continue
                val paths = lookup(span).filter(::includedAskPath).distinct()
                if (paths.isEmpty()) continue
                accepted += span
                hits += NameHit(span, paths)
            }
        }
    }
    return hits
}

fun scoreSearchHit(input: ScoreInput): Double? {
    val names = listOf(input.title, input.stem)
    val phrase = input.phrase
    val phraseInName = phrase.isNotBlank() && names.any { it.contains(phrase, ignoreCase = true) }
    val phraseInHeading = phrase.isNotBlank() && input.headings.any { it.contains(phrase, ignoreCase = true) }
    val phraseInBody = if (phrase.isBlank()) 0 else countOccurrences(input.body, phrase).coerceAtMost(10)
    var score = 0.0
    if (input.nameHit) score += 200
    if (phraseInName) score += 50
    score += phraseInBody * 20.0
    var matchedNonHigh = false
    for (term in input.terms) {
        if (term.isBlank()) continue
        val inName = names.any { it.contains(term, ignoreCase = true) }
        val inBody = input.headings.any { it.contains(term, ignoreCase = true) } ||
            input.body.contains(term, ignoreCase = true)
        if (!inName && !inBody) continue
        var points = (if (inName) 5.0 else 0.0) + (if (inBody) 1.0 else 0.0)
        val penalize = term in input.highFrequency && term !in input.nameKeys
        if (penalize) points *= 0.1 else matchedNonHigh = true
        score += points
    }
    if (input.path.startsWith("wiki/concepts/") ||
        input.path.startsWith("wiki/entities/") ||
        input.path.startsWith("wiki/sources/")
    ) {
        score += 2
    }
    val phraseHit = phraseInName || phraseInHeading || phraseInBody > 0
    if (!input.nameHit && !phraseHit && !matchedNonHigh) return null
    return score
}

internal fun referenceLinks(graph: WikiGraph, sources: List<String>, path: String): String {
    val titles = linkedSetOf<String>()
    val node = graph.nodes[path]
    if (node != null) {
        for (neighbor in node.neighbors) {
            if (titles.size >= 8) break
            val title = graph.nodes[neighbor]?.title?.trim().orEmpty()
            if (title.isNotBlank()) titles += title
        }
    }
    for (source in sources) {
        val text = source.trim()
        if (text.isNotBlank()) titles += text
    }
    return if (titles.isEmpty()) "(none)" else titles.joinToString("；")
}

fun formatSearchLog(outcome: SearchOutcome, added: Int, top: List<String>): String =
    "wiki.search: ${outcome.hits.size} result(s), $added new, mode=${outcome.mode}, " +
        "tokenHits=${outcome.tokenHits}, nameHits=${outcome.nameHits}, vectorHits=${outcome.vectorHits}, " +
        "graphHits=${outcome.graphHits}, top=[${top.joinToString(", ")}]"

class WikiSearch(
    private val index: WikiIndex,
) {
    fun search(query: String): SearchOutcome {
        val parsed = tokenizeSearchQuery(query)
        if (parsed.runs.isEmpty()) {
            return SearchOutcome(emptyList(), 0, 0, 0, 0, "keyword")
        }
        val names = exactNameHits(parsed.runs, index::pathsForLooseAlias)
        val namePaths = names.flatMap { it.paths }.distinct()
        val nameKeys = names.map { it.key }.toSet()
        val noteCount = index.askNoteCount()
        val highFrequency = parsed.terms.filter { term ->
            term !in nameKeys && noteCount > 0 &&
                index.countAskTerm(term).toDouble() / noteCount > 0.20
        }.toSet()
        val ordered = linkedSetOf<String>()
        ordered += namePaths
        val rankedTerms = parsed.terms.sortedWith(
            compareByDescending<String> { it.length }.thenBy { index.countAskTerm(it) },
        )
        for (term in rankedTerms) {
            ordered += index.searchAskTerm(term, PER_TERM_POOL)
        }
        ordered += index.searchFts(parsed.terms.filter { it.length >= 3 }, FTS_LIMIT)
        val filtered = ordered.filter(::includedAskPath)
        val pool = filtered.take(SEARCH_POOL)
        val scored = pool.mapNotNull { path ->
            val note = index.load(path) ?: return@mapNotNull null
            val stem = path.substringAfterLast('/').removeSuffix(".md")
            val nameHit = path in namePaths
            val score = scoreSearchHit(
                ScoreInput(
                    path = path,
                    title = note.title,
                    stem = stem,
                    headings = note.headings,
                    body = note.body,
                    phrase = parsed.phrase,
                    terms = parsed.terms,
                    nameHit = nameHit,
                    nameKeys = nameKeys,
                    highFrequency = highFrequency,
                ),
            ) ?: return@mapNotNull null
            Ranked(note.path, note.title, note.type, note.body, note.sources, stem, score, nameHit)
        }.sortedWith(compareByDescending<Ranked> { it.score }.thenBy { pathRank(it.path) }.thenBy { it.path })
        val keyword = scored.take(TOP_K)
        val seedPaths = keyword.take(3).map { it.path }
        val graph = index.wikiGraph()
        val neighbors = expandOneHop(graph, seedPaths)
            .filter { neighbor -> keyword.none { it.path == neighbor.path } }
            .take(GRAPH_QUOTA)
        val keywordHits = keyword.map { row ->
            val center = names.firstOrNull { row.path in it.paths }?.key
                ?: parsed.phrase.takeIf { it.isNotBlank() }
                ?: parsed.terms.firstOrNull { it !in highFrequency }.orEmpty()
            SearchHit(
                path = row.path,
                title = row.title,
                type = row.type,
                snippet = snippetAround(row.body, center),
                kind = "wiki",
                score = row.score,
                nameHit = row.nameHit,
                links = referenceLinks(graph, row.sources, row.path),
            )
        }
        val graphHits = neighbors.map { neighbor ->
            val seed = seedPaths.firstOrNull { seed -> graph.nodes[seed]?.neighbors?.contains(neighbor.path) == true }
            val seedTitle = seed?.let { path -> keyword.firstOrNull { it.path == path }?.title }.orEmpty()
            val note = index.load(neighbor.path)
            val bodySnippet = snippetAround(note?.body.orEmpty(), seedTitle)
            val snippet = if (bodySnippet.isBlank()) {
                "Graph neighbor of $seedTitle"
            } else {
                "Graph neighbor of $seedTitle. $bodySnippet"
            }
            SearchHit(
                path = neighbor.path,
                title = note?.title ?: neighbor.path,
                type = note?.type.orEmpty(),
                snippet = snippet,
                kind = "graph",
                score = neighbor.score,
                nameHit = false,
                links = referenceLinks(graph, note?.sources.orEmpty(), neighbor.path),
            )
        }
        val hits = keywordHits + graphHits
        val mode = if (graphHits.isNotEmpty()) "hybrid" else "keyword"
        return SearchOutcome(
            hits = hits,
            tokenHits = filtered.size,
            nameHits = namePaths.size,
            vectorHits = 0,
            graphHits = graphHits.size,
            mode = mode,
        )
    }

    private data class Ranked(
        val path: String,
        val title: String,
        val type: String,
        val body: String,
        val sources: List<String>,
        val stem: String,
        val score: Double,
        val nameHit: Boolean,
    )

    companion object {
        const val PER_TERM_POOL: Int = 40
        const val SEARCH_POOL: Int = 200
        const val FTS_LIMIT: Int = 200
        const val TOP_K: Int = 5
        const val GRAPH_QUOTA: Int = 2
        const val SNIPPET_CHARS: Int = 240
    }
}

internal fun snippetAround(body: String, center: String): String {
    val collapsed = body.replace(Regex("\\s+"), " ").trim()
    if (collapsed.length <= WikiSearch.SNIPPET_CHARS) return collapsed
    val at = if (center.isBlank()) 0 else {
        val found = collapsed.indexOf(center, ignoreCase = true)
        if (found < 0) 0 else found
    }
    val start = (at - 120).coerceAtLeast(0)
    val end = (start + WikiSearch.SNIPPET_CHARS).coerceAtMost(collapsed.length)
    return collapsed.substring(start, end)
}

internal fun countOccurrences(text: String, needle: String): Int {
    if (needle.isEmpty()) return 0
    val haystack = text.lowercase()
    val wanted = needle.lowercase()
    var count = 0
    var from = 0
    while (from <= haystack.length - wanted.length) {
        val at = haystack.indexOf(wanted, from)
        if (at < 0) break
        count += 1
        from = at + wanted.length
    }
    return count
}
