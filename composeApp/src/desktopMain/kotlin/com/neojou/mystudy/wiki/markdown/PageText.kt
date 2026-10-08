package com.neojou.mystudy.wiki.markdown

import java.time.LocalDate

fun renderNewPage(
    title: String,
    type: String,
    tags: List<String>,
    aliases: List<String>,
    sources: List<String>,
    today: LocalDate,
    body: String,
): String {
    val front = buildString {
        append("---\n")
        append("title: ${yamlQuote(title)}\n")
        append("type: ${yamlQuote(type)}\n")
        append(yamlList("tags", tags))
        append(yamlList("aliases", aliases))
        append(yamlList("sources", sources))
        append("created: ${yamlQuote(today.toString())}\n")
        append("updated: ${yamlQuote(today.toString())}\n")
        append("---\n\n")
    }
    return front + body.trim() + "\n"
}

fun appendSection(existing: String, section: String, today: LocalDate): String {
    val trimmed = section.trim()
    val touched = touchUpdated(existing, today)
    if (trimmed.isEmpty() || touched.contains(trimmed)) return touched
    val newline = if (touched.contains("\r\n")) "\r\n" else "\n"
    val base = if (touched.endsWith(newline)) touched else touched + newline
    return base + newline + trimmed + newline
}

fun touchUpdated(existing: String, today: LocalDate): String {
    val newline = if (existing.contains("\r\n")) "\r\n" else "\n"
    val lines = existing.split(newline).toMutableList()
    val end = lines.indexOfFirst { it.trim() == "---" }.let { first ->
        if (first != 0) return existing
        lines.indexOfFirstIndexed(1) { it.trim() == "---" }
    }
    if (end < 0) return existing
    val updatedLine = "updated: ${yamlQuote(today.toString())}"
    var updatedAt = -1
    for (index in 1 until end) {
        if (lines[index].trimStart().startsWith("updated:")) {
            updatedAt = index
            break
        }
    }
    if (updatedAt >= 0) {
        lines[updatedAt] = updatedLine
    } else {
        lines.add(end, updatedLine)
    }
    return lines.joinToString(newline)
}

private fun yamlList(key: String, values: List<String>): String {
    if (values.isEmpty()) return "$key: []\n"
    return buildString {
        append(key)
        append(":\n")
        for (value in values) {
            append("  - ")
            append(yamlQuote(value))
            append("\n")
        }
    }
}

private inline fun <T> List<T>.indexOfFirstIndexed(start: Int, predicate: (T) -> Boolean): Int {
    for (index in start until size) {
        if (predicate(this[index])) return index
    }
    return -1
}

fun ensureWikilink(section: String, link: String): String {
    if (section.contains("[[$link]]") || section.contains("[[$link|")) return section
    return section.trimEnd() + "\n\n[[$link]]\n"
}

fun catalogTarget(relativePage: String): String {
    val without = relativePage.removePrefix("wiki/").removeSuffix(".md")
    return without
}
