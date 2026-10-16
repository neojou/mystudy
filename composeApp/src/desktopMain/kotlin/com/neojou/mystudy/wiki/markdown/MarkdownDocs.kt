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

sealed interface FrontmatterEntry {
    val key: String

    data class Scalar(override val key: String, val value: String) : FrontmatterEntry

    data class ListValue(override val key: String, val values: List<String>) : FrontmatterEntry
}

/**
 * Closed YAML front matter for the properties panel.
 *
 * [raw] is the text between the fences. It is null when there is no closed fence,
 * including a missing closer, so the note body stays visible. [unparsed] still
 * follows the existing field parser. A closed fence with a bad line keeps [raw]
 * and sets [unparsed].
 */
data class FrontmatterView(
    val entries: List<FrontmatterEntry>,
    val unparsed: Boolean,
    val raw: String?,
)

fun readFrontmatter(text: String): FrontmatterView {
    val split = splitFrontmatter(text)
    val raw = split.rawInner ?: return FrontmatterView(emptyList(), split.fields.unparsed, null)
    return FrontmatterView(split.fields.ordered, split.fields.unparsed, raw)
}

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
    val rawInner: String?,
)

private data class Fields(
    val scalars: Map<String, String>,
    val lists: Map<String, List<String>>,
    val unparsed: Boolean,
    val ordered: List<FrontmatterEntry> = emptyList(),
)

private fun splitFrontmatter(text: String): FrontmatterSplit {
    val normalized = text.replace("\r\n", "\n")
    if (!normalized.startsWith("---\n") && normalized.trimStart() != "---") {
        return FrontmatterSplit(Fields(emptyMap(), emptyMap(), unparsed = false), normalized, null)
    }
    val lines = normalized.split('\n')
    if (lines.first().trim() != "---") {
        return FrontmatterSplit(Fields(emptyMap(), emptyMap(), unparsed = false), normalized, null)
    }
    var end = -1
    for (index in 1 until lines.size) {
        if (lines[index].trim() == "---") {
            end = index
            break
        }
    }
    if (end < 0) {
        return FrontmatterSplit(Fields(emptyMap(), emptyMap(), unparsed = true), normalized, null)
    }
    val fields = parseFields(lines.subList(1, end))
    val body = lines.drop(end + 1).joinToString("\n")
    val raw = lines.subList(1, end).joinToString("\n")
    return FrontmatterSplit(fields, body, raw)
}

private fun parseFields(lines: List<String>): Fields {
    val scalars = linkedMapOf<String, String>()
    val lists = linkedMapOf<String, List<String>>()
    val ordered = mutableListOf<FrontmatterEntry>()
    var unparsed = false
    fun put(entry: FrontmatterEntry) {
        val existing = ordered.indexOfFirst { it.key == entry.key }
        if (existing >= 0) ordered[existing] = entry else ordered += entry
    }
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
            if (items.isNotEmpty()) {
                lists[key] = items
                put(FrontmatterEntry.ListValue(key, items))
            }
            index = cursor
            continue
        }
        if (rest.startsWith("[") && rest.endsWith("]")) {
            val items = rest.removePrefix("[").removeSuffix("]")
                .split(',')
                .map { unquote(it.trim()) }
                .filter { it.isNotEmpty() }
            lists[key] = items
            put(FrontmatterEntry.ListValue(key, items))
            index += 1
            continue
        }
        if (rest.startsWith("{") || rest.startsWith("[")) {
            unparsed = true
            scalars[key] = rest
            put(FrontmatterEntry.Scalar(key, rest))
            index += 1
            continue
        }
        val value = unquote(rest)
        scalars[key] = value
        put(FrontmatterEntry.Scalar(key, value))
        index += 1
    }
    return Fields(scalars, lists, unparsed, ordered)
}

private fun unquote(value: String): String {
    if (value.length >= 2 && ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'")))) {
        return value.substring(1, value.length - 1).replace("\\\"", "\"").replace("\\'", "'")
    }
    return value
}

/**
 * Pieces of one question.
 *
 * Tokenization stays local, as in llm-wiki `tokenize_query`: overlapping CJK bigrams, not an Ollama call.
 * Retrieval has to decide "nothing matches" before the model is allowed to run.
 * Single Han characters are not emitted. llm-wiki does emit them; a character such as 現 matches too many pages
 * once it is allowed to seed. FTS5 trigram cannot MATCH a 2-character token, so [ftsTerms] are length ≥ 3.
 * Two-character content is retrieved later by LIKE on [pieces], not by FTS.
 *
 * [pieces] are the content fragments: Han runs after interrogative stops, plus Latin tokens of length ≥ 3.
 * Interrogatives are a closed class. Content nouns are not listed here.
 */
data class QueryAnalysis(
    val phrase: String,
    val bigrams: List<String>,
    val extras: List<String>,
    val ftsTerms: List<String>,
    val pieces: List<String>,
)

private val hanStops = listOf(
    "什麼", "什么", "與否",
    "何謂", "何為", "何为", "為何", "为何", "為什麼", "为什么",
    "如何", "怎麼", "怎么", "怎樣", "怎样", "是否", "請問",
    "對於", "對", "对", "從", "从", "與", "与",
)
private val hanStopChars = setOf('是', '的', '了', '在', '有', '和')
private val latinStops = setOf(
    "the", "is", "a", "an", "what", "how", "are", "was", "were",
    "do", "does", "did", "be", "been", "being", "have", "has", "had",
    "it", "its", "in", "on", "at", "to", "for", "of", "with", "by",
    "this", "that", "these", "those",
)

fun analyzeQuery(question: String): QueryAnalysis {
    val lower = question.lowercase(Locale.ROOT)
    val hanPieces = Regex("[\\p{IsHan}]+").findAll(lower).flatMap { match ->
        stripHanStops(match.value).split(Regex("\\s+")).filter { it.length >= 2 }
    }.toList()
    val phrase = hanPieces.maxByOrNull { it.length }.orEmpty()
    val bigrams = hanPieces.flatMap { bigramsOf(it) }.distinct()
    val extras = Regex("[a-z][a-z0-9_]{2,}").findAll(lower).map { it.value }.filter { it !in latinStops }.distinct().toList()
    val pieces = (hanPieces + extras).distinct()
    val fts = (hanPieces.flatMap { ftsWindows(it) } + extras).filter { it.length >= 3 }.distinct()
    return QueryAnalysis(phrase, bigrams, extras, fts, pieces)
}

private fun stripHanStops(run: String): String {
    var text = run
    for (stop in hanStops.sortedByDescending { it.length }) {
        text = text.replace(stop, " ")
    }
    return buildString {
        for (ch in text) {
            if (ch in hanStopChars) append(' ') else append(ch)
        }
    }
}

private fun bigramsOf(run: String): List<String> {
    if (run.length < 2) return emptyList()
    return (0 until run.length - 1).map { run.substring(it, it + 2) }
}

private fun ftsWindows(run: String): List<String> {
    if (run.length < 3) return emptyList()
    val windows = mutableListOf<String>()
    if (run.length <= 12) windows += run
    var index = 0
    while (index + 3 <= run.length) {
        windows += run.substring(index, index + 3)
        index += 1
    }
    return windows
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
