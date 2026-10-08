package com.neojou.mystudy.wiki.markdown

import java.util.Locale

data class ParsedNote(
    val title: String,
    val type: String,
    val tags: List<String>,
    val aliases: List<String>,
    val sources: List<String>,
    val updated: String?,
    val created: String?,
    val headings: List<String>,
    val body: String,
    val frontmatterUnparsed: Boolean,
    val wikilinks: List<String>,
)

private val wikilinkRegex = Regex("""\[\[([^\[\]|#]+)(?:#[^\[\]|]*)?(?:\|[^\[\]]*)?]]""")

fun normalizeKey(value: String): String = value.trim().lowercase(Locale.ROOT)

fun parseWikilinks(text: String): List<String> =
    wikilinkRegex.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()

fun firstWikilinkTarget(text: String): String? = parseWikilinks(text).firstOrNull()

fun parseNote(text: String, filenameStem: String): ParsedNote {
    val split = splitFrontmatter(text)
    val fields = split.fields
    val headings = headingsOf(split.body)
    val title = fields.scalars["title"]?.takeIf { it.isNotBlank() }
        ?: headings.firstOrNull()
        ?: filenameStem
    return ParsedNote(
        title = title,
        type = fields.scalars["type"].orEmpty(),
        tags = fields.lists["tags"].orEmpty(),
        aliases = fields.lists["aliases"].orEmpty(),
        sources = fields.lists["sources"].orEmpty(),
        updated = fields.scalars["updated"],
        created = fields.scalars["created"],
        headings = headings,
        body = split.body,
        frontmatterUnparsed = fields.unparsed,
        wikilinks = parseWikilinks(split.body),
    )
}

fun headingsOf(body: String): List<String> = body.lineSequence()
    .map { it.trim() }
    .filter { it.startsWith("#") }
    .map { it.trimStart('#').trim() }
    .filter { it.isNotEmpty() }
    .take(50)
    .toList()

private data class FrontmatterSplit(
    val fields: Fields,
    val body: String,
)

private data class Fields(
    val scalars: Map<String, String>,
    val lists: Map<String, List<String>>,
    val unparsed: Boolean,
)

private fun splitFrontmatter(text: String): FrontmatterSplit {
    val normalized = text.replace("\r\n", "\n")
    if (!normalized.startsWith("---\n") && normalized.trimStart() != "---") {
        return FrontmatterSplit(Fields(emptyMap(), emptyMap(), unparsed = false), normalized)
    }
    val lines = normalized.split('\n')
    if (lines.first().trim() != "---") {
        return FrontmatterSplit(Fields(emptyMap(), emptyMap(), unparsed = false), normalized)
    }
    var end = -1
    for (index in 1 until lines.size) {
        if (lines[index].trim() == "---") {
            end = index
            break
        }
    }
    if (end < 0) {
        return FrontmatterSplit(Fields(emptyMap(), emptyMap(), unparsed = true), normalized)
    }
    val fields = parseFields(lines.subList(1, end))
    val body = lines.drop(end + 1).joinToString("\n")
    return FrontmatterSplit(fields, body)
}

private fun parseFields(lines: List<String>): Fields {
    val scalars = linkedMapOf<String, String>()
    val lists = linkedMapOf<String, List<String>>()
    var unparsed = false
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.isBlank()) {
            index += 1
            continue
        }
        if (line.trimStart().startsWith("- ")) {
            unparsed = true
            index += 1
            continue
        }
        val colon = line.indexOf(':')
        if (colon <= 0 || line.startsWith(" ") || line.startsWith("\t")) {
            unparsed = true
            index += 1
            continue
        }
        val key = line.substring(0, colon).trim()
        val rest = line.substring(colon + 1).trim()
        if (rest.isEmpty() || rest == "|" || rest == ">") {
            val items = mutableListOf<String>()
            var cursor = index + 1
            var nested = false
            while (cursor < lines.size && (lines[cursor].startsWith(" ") || lines[cursor].startsWith("\t"))) {
                val trimmed = lines[cursor].trim()
                if (trimmed.startsWith("- ")) {
                    items += unquote(trimmed.removePrefix("- ").trim())
                } else {
                    nested = true
                }
                cursor += 1
            }
            if (nested || items.isEmpty()) unparsed = true
            if (items.isNotEmpty()) lists[key] = items
            index = cursor
            continue
        }
        if (rest.startsWith("[") && rest.endsWith("]")) {
            lists[key] = rest.removePrefix("[").removeSuffix("]")
                .split(',')
                .map { unquote(it.trim()) }
                .filter { it.isNotEmpty() }
            index += 1
            continue
        }
        if (rest.startsWith("{") || rest.startsWith("[")) {
            unparsed = true
            scalars[key] = rest
            index += 1
            continue
        }
        scalars[key] = unquote(rest)
        index += 1
    }
    return Fields(scalars, lists, unparsed)
}

private fun unquote(value: String): String {
    if (value.length >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
        return value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\'", "'")
    }
    return value
}

fun queryTerms(question: String): List<String> {
    val cjk = Regex("[\\p{IsHan}]{2,}").findAll(question).flatMap { match -> cjkPieces(match.value) }
    val latin = Regex("[A-Za-z][A-Za-z0-9_]{2,}").findAll(question).map { it.value }
    return (cjk + latin).distinct().toList()
}

private fun cjkPieces(run: String): List<String> {
    if (run.length <= 4) return listOf(run)
    val parts = mutableListOf<String>()
    var index = 0
    while (index + 4 <= run.length) {
        parts += run.substring(index, index + 4)
        index += 2
    }
    if (run.length <= 12) parts += run
    return parts
}

fun safeFileStem(title: String): String {
    val cleaned = title.trim()
        .replace(Regex("""[\\/:*?"<>|]"""), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(80)
    return cleaned.ifBlank { "untitled" }
}

fun yamlQuote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
