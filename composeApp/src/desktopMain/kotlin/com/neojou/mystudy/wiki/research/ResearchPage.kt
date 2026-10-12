package com.neojou.mystudy.wiki.research

import com.neojou.mystudy.wiki.markdown.appendLog
import com.neojou.mystudy.wiki.markdown.catalogLine
import com.neojou.mystudy.wiki.markdown.catalogTarget
import com.neojou.mystudy.wiki.markdown.headingForType
import com.neojou.mystudy.wiki.markdown.insertCatalogLine
import com.neojou.mystudy.wiki.markdown.parseNote
import com.neojou.mystudy.wiki.markdown.yamlQuote
import com.neojou.mystudy.wiki.ollama.stripJsonFence
import com.neojou.mystudy.wiki.vault.assertWikiWrite
import com.neojou.mystudy.wiki.vault.atomicWrite
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.Locale
import kotlin.io.path.readText

const val MIN_SYNTHESIS_CHARS: Int = 80

private val citation = Regex("""\[(\d+)]""")

fun researchSlug(topic: String): String {
    val mapped = buildString {
        for (ch in topic.trim().lowercase(Locale.ROOT)) {
            if (ch.isLetterOrDigit()) append(ch) else append('-')
        }
    }
    val collapsed = mapped.replace(Regex("-{2,}"), "-").trim('-').take(60).trim('-')
    return collapsed.ifBlank { "untitled" }
}

fun allocateResearchPath(slug: String, date: LocalDate, exists: (String) -> Boolean): String {
    val stem = "wiki/queries/research-$slug-$date"
    val first = "$stem.md"
    if (!exists(first)) return first
    for (suffix in 2..50) {
        val candidate = "$stem-$suffix.md"
        if (!exists(candidate)) return candidate
    }
    error("Too many research pages for $slug.")
}

fun prepareSynthesis(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.startsWith("```")) return stripJsonFence(trimmed).trim()
    if (trimmed.startsWith("---")) return parseNote(trimmed, "research").body.trim()
    return trimmed
}

/** Null when the synthesis can be saved. Any out-of-range citation fails the page. */
fun validateSynthesis(text: String, sourceCount: Int): String? {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.length < MIN_SYNTHESIS_CHARS) {
        return "Synthesis is shorter than $MIN_SYNTHESIS_CHARS characters."
    }
    val numbers = citation.findAll(trimmed).map { it.groupValues[1].toInt() }.toList()
    if (numbers.isEmpty()) return "Synthesis has no [n] citation."
    if (numbers.any { it < 1 || it > sourceCount }) {
        return "Synthesis cites a source outside 1..$sourceCount."
    }
    return null
}

fun citedNumbers(text: String, sourceCount: Int): List<Int> =
    citation.findAll(text)
        .map { it.groupValues[1].toInt() }
        .filter { it in 1..sourceCount }
        .distinct()
        .sorted()
        .toList()

sealed class SaveOutcome {
    data class Ok(val path: String, val written: List<String>) : SaveOutcome()
    data class Stopped(val message: String, val written: List<String>) : SaveOutcome()
}

/**
 * Page, then index, then log, then review removal.
 * A later failure leaves earlier files in place and does not delete the review.
 */
fun writeResearch(
    root: Path,
    topic: String,
    reviewType: String,
    reviewTitle: String,
    queries: List<String>,
    synthesis: String,
    hits: List<SearxngHit>,
    rawBlock: String,
    today: LocalDate,
    exists: (String) -> Boolean = { false },
): SaveOutcome {
    val relative = allocateResearchPath(researchSlug(topic), today) { candidate ->
        Files.isRegularFile(root.resolve(candidate)) || exists(candidate)
    }
    val page = renderResearchPage(topic, reviewType, reviewTitle, queries, synthesis, hits, today)
    val written = mutableListOf<String>()
    val pageFailed = writeText(root, relative, page)
    if (pageFailed != null) return SaveOutcome.Stopped(pageFailed, written)
    written += relative

    val indexPath = root.resolve("wiki").resolve("index.md")
    val currentIndex = readRegular(indexPath, "# Index\n")
    val summary = synthesis.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?: topic
    val updatedIndex = insertCatalogLine(
        currentIndex,
        headingForType("query"),
        catalogLine(catalogTarget(relative), summary, "query", emptyList()),
    )
    if (updatedIndex != currentIndex) {
        val indexFailed = writeText(root, "wiki/index.md", updatedIndex)
        if (indexFailed != null) return SaveOutcome.Stopped(indexFailed, written)
        written += "wiki/index.md"
    }

    val logPath = root.resolve("wiki").resolve("log.md")
    val currentLog = readRegular(logPath, "# Log\n")
    val updatedLog = appendLog(currentLog, today, "deep-research", topic, listOf(relative))
    val logFailed = writeText(root, "wiki/log.md", updatedLog)
    if (logFailed != null) return SaveOutcome.Stopped(logFailed, written)
    written += "wiki/log.md"

    val reviewPath = root.resolve("wiki").resolve("review.md")
    val currentReview = readRegular(reviewPath, "")
    val updatedReview = try {
        removeReviewBlock(currentReview, rawBlock)
    } catch (error: IllegalStateException) {
        return SaveOutcome.Stopped(error.message ?: "review.md changed. Refresh and try again.", written)
    }
    val reviewFailed = writeText(root, "wiki/review.md", updatedReview)
    if (reviewFailed != null) return SaveOutcome.Stopped(reviewFailed, written)
    written += "wiki/review.md"
    return SaveOutcome.Ok(relative, written)
}

fun renderResearchPage(
    topic: String,
    reviewType: String,
    reviewTitle: String,
    queries: List<String>,
    synthesis: String,
    hits: List<SearxngHit>,
    today: LocalDate,
): String {
    val cited = citedNumbers(synthesis, hits.size)
    return buildString {
        append("---\n")
        append("title: ${yamlQuote(topic)}\n")
        append("type: query\n")
        append("tags: []\n")
        append("aliases: []\n")
        append("created: ${yamlQuote(today.toString())}\n")
        append("updated: ${yamlQuote(today.toString())}\n")
        append("origin: deep-research\n")
        append("review: ${yamlQuote("$reviewType | $reviewTitle")}\n")
        append(yamlQueries(queries))
        append("---\n\n")
        append(synthesis.trim())
        append("\n\n## Sources\n")
        for (number in cited) {
            val hit = hits[number - 1]
            append(number)
            append(". [")
            append(hit.title.replace('\n', ' ').replace("[", " ").replace("]", " "))
            append("](")
            append(hit.url)
            append(")\n")
        }
    }
}

private fun yamlQueries(queries: List<String>): String {
    if (queries.isEmpty()) return "queries: []\n"
    return buildString {
        append("queries:\n")
        for (query in queries) {
            append("  - ")
            append(yamlQuote(query.replace('\n', ' ')))
            append("\n")
        }
    }
}

private fun readRegular(path: Path, fallback: String): String =
    if (Files.isRegularFile(path)) path.readText(StandardCharsets.UTF_8) else fallback

private fun writeText(root: Path, relative: String, text: String): String? = try {
    val target = assertWikiWrite(root, relative)
    atomicWrite(target, text)
    null
} catch (error: Exception) {
    "Stopped at $relative. ${error.message ?: "write failed"}"
}
