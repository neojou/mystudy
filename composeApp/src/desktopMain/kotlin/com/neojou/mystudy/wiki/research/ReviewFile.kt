package com.neojou.mystudy.wiki.research

import java.security.MessageDigest

/**
 * One `wiki/review.md` entry. [id] is only a UI key.
 * Removal compares [rawBlock] and refuses unless that exact text occurs once.
 */
data class ReviewEntry(
    val id: String,
    val date: String,
    val type: String,
    val title: String,
    val description: String,
    val searchQueries: List<String>,
    val source: String,
    val rawBlock: String,
)

private val reviewHeader = Regex(
    """^## \[(\d{4}-\d{2}-\d{2})\] ([a-z-]+) \| ([^\r\n]+)$""",
    RegexOption.MULTILINE,
)

private val topicPrefix = Regex("""(?i)^(create|research|save to wiki)(?:\s+|$)""")

fun parseReviewFile(text: String): List<ReviewEntry> {
    val newline = newlineOf(text)
    val matches = reviewHeader.findAll(text).toList()
    return matches.mapIndexed { index, match ->
        val start = match.range.first
        val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
        val rawBlock = withoutSeparator(text.substring(start, end), newline)
        val body = rawBlock.split(newline).drop(1).joinToString(newline).trim()
        val type = match.groupValues[2]
        val title = match.groupValues[3].trim()
        val date = match.groupValues[1]
        val source = sourceOf(body)
        ReviewEntry(
            id = reviewEntryId(type, title, date, source),
            date = date,
            type = type,
            title = title,
            description = descriptionOf(body),
            searchQueries = searchQueriesOf(body),
            source = source,
            rawBlock = rawBlock,
        )
    }
}

/**
 * Deletes the one span equal to [rawBlock].
 * Three or more blank lines touching the cut become two. Any other bytes stay.
 */
fun removeReviewBlock(text: String, rawBlock: String): String {
    if (rawBlock.isEmpty() || occurrences(text, rawBlock) != 1) {
        error("review.md changed. Refresh and try again.")
    }
    val start = text.indexOf(rawBlock)
    val before = text.substring(0, start)
    val after = text.substring(start + rawBlock.length)
    return collapseExtraBlanks(before, after)
}

fun researchQueries(entry: ReviewEntry): List<String> {
    if (entry.searchQueries.isNotEmpty()) return entry.searchQueries
    val fromTitle = stripResearchPrefix(entry.title)
    if (fromTitle.isNotEmpty()) return listOf(fromTitle)
    val line = entry.description.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    return listOfNotNull(line)
}

fun researchTopic(entry: ReviewEntry): String {
    val fromTitle = stripResearchPrefix(entry.title)
    if (fromTitle.isNotEmpty()) return fromTitle
    val line = entry.description.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    if (!line.isNullOrEmpty()) return line
    return entry.searchQueries.firstOrNull()?.takeIf { it.isNotBlank() } ?: "untitled"
}

fun stripResearchPrefix(title: String): String {
    var current = title.trim()
    while (true) {
        val next = topicPrefix.replace(current, "").trim()
        if (next == current) return current
        current = next
    }
}

fun reviewEntryId(type: String, title: String, date: String, source: String): String {
    val raw = "$type\u0000$title\u0000$date\u0000$source"
    val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
    return digest.take(8).joinToString("") { "%02x".format(it) }
}

private fun sourceOf(body: String): String {
    val line = body.lineSequence().map { it.trim() }.lastOrNull { it.startsWith("- source:") } ?: return ""
    return line.removePrefix("- source:").trim()
}

private fun descriptionOf(body: String): String =
    body.lineSequence()
        .filterNot { line ->
            val trimmed = line.trim()
            trimmed.startsWith("OPTIONS:") || trimmed.startsWith("SEARCH:") || trimmed.startsWith("- source:")
        }
        .joinToString("\n")
        .trim()

private fun searchQueriesOf(body: String): List<String> {
    val found = LinkedHashSet<String>()
    for (line in body.lineSequence()) {
        val trimmed = line.trim()
        if (!trimmed.startsWith("SEARCH:")) continue
        for (part in trimmed.removePrefix("SEARCH:").split('|')) {
            val query = part.trim()
            if (query.isEmpty()) continue
            found += query
            if (found.size >= 3) return found.toList()
        }
    }
    return found.toList()
}

internal fun newlineOf(text: String): String = if (text.contains("\r\n")) "\r\n" else "\n"

private fun withoutSeparator(region: String, newline: String): String =
    if (region.endsWith(newline + newline)) region.dropLast(newline.length) else region

private fun occurrences(text: String, needle: String): Int {
    var count = 0
    var from = 0
    while (from <= text.length - needle.length) {
        val found = text.indexOf(needle, from)
        if (found < 0) break
        count += 1
        from = found + needle.length
    }
    return count
}

private fun collapseExtraBlanks(before: String, after: String): String {
    val newline = newlineOf(before + after)
    val trailing = suffixCount(before, newline)
    val leading = prefixCount(after, newline)
    val total = trailing + leading
    val blankLines = if (total == 0) 0 else total - 1
    if (blankLines < 3) return before + after
    val kept = before.dropLast(trailing * newline.length) + newline.repeat(3) + after.drop(leading * newline.length)
    return kept
}

private fun suffixCount(text: String, newline: String): Int {
    var count = 0
    var end = text.length
    while (end >= newline.length && text.startsWith(newline, end - newline.length)) {
        count += 1
        end -= newline.length
    }
    return count
}

private fun prefixCount(text: String, newline: String): Int {
    var count = 0
    var start = 0
    while (start + newline.length <= text.length && text.startsWith(newline, start)) {
        count += 1
        start += newline.length
    }
    return count
}
