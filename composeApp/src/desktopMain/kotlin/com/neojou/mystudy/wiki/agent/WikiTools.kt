package com.neojou.mystudy.wiki.agent

import com.neojou.mystudy.wiki.ask.includedAskPath
import com.neojou.mystudy.wiki.index.WikiIndex

sealed class ReadPath {
    data class Ok(val path: String) : ReadPath()
    data class Reject(val reason: String) : ReadPath()
}

data class ToolObservation(
    val text: String,
    val outcome: SearchOutcome? = null,
    val readPath: String? = null,
    val readTitle: String? = null,
    val readType: String? = null,
    val readBody: String? = null,
    val readLinks: String? = null,
)

fun normalizeReadPath(
    raw: String,
    references: List<ChatReference>,
    lookupTitle: (String) -> List<String>,
): ReadPath {
    var text = raw.trim().replace("`", "").trim()
    if (text.isEmpty()) return ReadPath.Reject("empty path")
    if (text.startsWith("/") || text.startsWith("\\") || text.contains("..")) {
        return ReadPath.Reject("unsafe path")
    }
    val number = text.removePrefix("[").removeSuffix("]")
    if (number.isNotEmpty() && number.all { it.isDigit() }) {
        val ref = number.toIntOrNull()?.let { value -> references.firstOrNull { it.number == value } }
            ?: return ReadPath.Reject("unknown reference")
        text = ref.path
    } else if (!text.endsWith(".md") && !text.contains("/")) {
        val hits = lookupTitle(text).filter(::includedAskPath).distinct()
        text = when (hits.size) {
            1 -> hits.first()
            0 -> return ReadPath.Reject("not found")
            else -> return ReadPath.Reject("ambiguous title")
        }
    }
    if (!text.endsWith(".md")) text = "$text.md"
    if (!includedAskPath(text)) return ReadPath.Reject("not an ask page")
    return ReadPath.Ok(text)
}

class WikiTools(
    private val index: WikiIndex,
) {
    private val searcher = WikiSearch(index)

    fun search(query: String): SearchOutcome = searcher.search(query)

    fun read(rawPath: String, references: List<ChatReference>): ToolObservation {
        val normalized = try {
            normalizeReadPath(rawPath, references, index::pathsForLooseAlias)
        } catch (error: Exception) {
            return ToolObservation("wiki.read_page error: ${error.message ?: "unreadable path"} $rawPath")
        }
        return when (normalized) {
            is ReadPath.Reject -> ToolObservation("wiki.read_page error: ${normalized.reason} $rawPath")
            is ReadPath.Ok -> {
                val note = index.load(normalized.path)
                    ?: return ToolObservation("wiki.read_page error: not found ${normalized.path}")
                val body = if (note.body.length <= ChatAgent.READ_PAGE_CHARS) {
                    note.body
                } else {
                    note.body.take(ChatAgent.READ_PAGE_CHARS) + "…(truncated)"
                }
                val links = referenceLinks(index.wikiGraph(), note.sources, note.path)
                ToolObservation(
                    text = "$body\n連結：$links",
                    readPath = note.path,
                    readTitle = note.title,
                    readType = note.type,
                    readBody = body,
                    readLinks = links,
                )
            }
        }
    }
}
