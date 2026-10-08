package com.neojou.mystudy.wiki.markdown

import java.time.LocalDate

fun catalogLine(linkTarget: String, summary: String, type: String, sources: List<String>): String {
    val oneLine = summary.replace("\n", " ").trim().ifBlank { linkTarget }
    val sourceText = sources.joinToString(", ").ifBlank { "none" }
    return "- [[$linkTarget]] — $oneLine · type: $type · sources: $sourceText"
}

fun headingForType(type: String): String = when (type) {
    "source" -> "## Sources"
    "concept" -> "## Concepts"
    "entity" -> "## Entities"
    "query" -> "## Queries"
    else -> "## Other"
}

/**
 * Inserts [line] under [heading] without reordering other entries.
 * An existing row with the same wikilink target is replaced in place.
 */
fun insertCatalogLine(markdown: String, heading: String, line: String): String {
    val newline = if (markdown.contains("\r\n")) "\r\n" else "\n"
    val lines = markdown.split(newline).toMutableList()
    val link = firstWikilinkTarget(line)
    if (link != null) {
        val existing = lines.indexOfFirst { firstWikilinkTarget(it) == link }
        if (existing >= 0) {
            lines[existing] = line
            return lines.joinToString(newline)
        }
    }
    val headingIndex = lines.indexOfFirst { it.trim() == heading }
    if (headingIndex < 0) {
        val base = if (markdown.isEmpty() || markdown.endsWith(newline)) markdown else markdown + newline
        val gap = if (base.isEmpty() || base.endsWith(newline + newline)) "" else newline
        return base + gap + heading + newline + newline + line + newline
    }
    var insertAt = headingIndex + 1
    if (insertAt < lines.size && lines[insertAt].isBlank()) insertAt += 1
    lines.add(insertAt, line)
    return lines.joinToString(newline)
}

fun appendLog(
    existing: String,
    date: LocalDate,
    operation: String,
    title: String,
    details: List<String>,
): String {
    val newline = if (existing.contains("\r\n")) "\r\n" else "\n"
    val entry = buildString {
        append("## [")
        append(date)
        append("] ")
        append(operation)
        append(" | ")
        append(title.trim())
        append(newline)
        append(newline)
        for (detail in details) {
            append("- ")
            append(detail)
            append(newline)
        }
    }
    if (existing.isEmpty()) return entry
    val gap = when {
        existing.endsWith(newline + newline) -> ""
        existing.endsWith(newline) -> newline
        else -> newline + newline
    }
    return existing + gap + entry
}

fun schemaCompatibilityWarning(schema: String): String? {
    val needles = listOf("concept-table", "overview.md", "backlinks.md", "source-summary", "type: article")
    val hits = needles.filter { schema.contains(it, ignoreCase = true) }
    if (hits.isEmpty()) return null
    return "schema.md mentions ${hits.joinToString(", ")}. This app will not create those files and will not overwrite schema.md."
}

fun unifiedDiff(before: String?, after: String): String {
    if (before == null) {
        val lines = after.split('\n')
        val shown = lines.take(400).joinToString("\n") { "+$it" }
        return if (lines.size > 400) "$shown\n… truncated" else shown
    }
    val oldLines = before.split('\n')
    val newLines = after.split('\n')
    if (oldLines.size > 350 || newLines.size > 350) {
        return newLines.take(350).joinToString("\n") { "+$it" }
    }
    val rows = oldLines.size
    val columns = newLines.size
    val scores = Array(rows + 1) { IntArray(columns + 1) }
    for (row in rows - 1 downTo 0) {
        for (column in columns - 1 downTo 0) {
            scores[row][column] = if (oldLines[row] == newLines[column]) {
                scores[row + 1][column + 1] + 1
            } else {
                maxOf(scores[row + 1][column], scores[row][column + 1])
            }
        }
    }
    val out = mutableListOf<String>()
    var row = 0
    var column = 0
    while (row < rows && column < columns) {
        when {
            oldLines[row] == newLines[column] -> {
                out += " ${oldLines[row]}"
                row += 1
                column += 1
            }
            scores[row + 1][column] >= scores[row][column + 1] -> {
                out += "-${oldLines[row]}"
                row += 1
            }
            else -> {
                out += "+${newLines[column]}"
                column += 1
            }
        }
    }
    while (row < rows) {
        out += "-${oldLines[row]}"
        row += 1
    }
    while (column < columns) {
        out += "+${newLines[column]}"
        column += 1
    }
    return out.joinToString("\n")
}
