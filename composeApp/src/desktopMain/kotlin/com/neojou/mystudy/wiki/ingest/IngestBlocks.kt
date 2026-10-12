package com.neojou.mystudy.wiki.ingest

import com.neojou.mystudy.wiki.markdown.safeFileStem

/** Output language filled into `{{lang}}`. Not a preference and not written to the vault. */
object IngestLanguage {
    const val NAME: String = "Traditional Chinese (Taiwan, 繁體中文)"
}

data class PageWorthy(
    val title: String,
    val kind: String,
    val aliases: List<String>,
    val existingHint: String?,
)

data class ParsedFile(
    val directory: String,
    val relativePath: String,
    val body: String,
)

data class ParsedReview(
    val type: String,
    val title: String,
    val body: String,
    val options: List<String>,
    val searches: List<String>,
)

data class BlockParse(
    val files: List<ParsedFile>,
    val reviews: List<ParsedReview>,
    val warnings: List<String>,
)

private val fileStart = Regex("""^---FILE:\s*(.+?)\s*---$""")
private val reviewStart = Regex("""^---REVIEW:\s*([a-z-]+)\s*\|\s*(.+?)\s*---$""")
private val allowedDirs = setOf("sources", "concepts", "entities")
private val deniedNames = setOf("index.md", "overview.md", "log.md", "schema.md", "purpose.md", "review.md")
private val reviewTypes = setOf("contradiction", "duplicate", "missing-page", "suggestion")

/**
 * Reads FILE and REVIEW blocks. A closing marker inside a ``` fence stays in the body.
 * Source files are rewritten to `wiki/sources/{sourceStem}.md`. Unsafe paths are dropped.
 */
fun parseIngestBlocks(text: String, sourceStem: String): BlockParse {
    val stem = safeFileStem(sourceStem)
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
    val warnings = mutableListOf<String>()
    val files = mutableListOf<ParsedFile>()
    val reviews = mutableListOf<ParsedReview>()
    var mode: String? = null
    var header = ""
    var reviewType = ""
    var reviewTitle = ""
    val body = StringBuilder()
    var inFence = false
    var outside = false
    var keptSource = false

    fun discardOpen() {
        val open = mode ?: return
        warnings += "Unclosed ${open.uppercase()} discarded: $header"
        mode = null
        inFence = false
        body.clear()
    }

    fun closeFile() {
        val text = body.toString().trim()
        val guarded = guardIngestFile(header, stem)
        if (guarded == null) {
            warnings += "Ignored FILE: $header"
        } else if (text.isEmpty()) {
            warnings += "Skipped empty FILE"
        } else if (guarded.directory == "sources") {
            if (keptSource) {
                warnings += "Ignored extra source FILE: $header"
            } else {
                keptSource = true
                files += ParsedFile("sources", "wiki/sources/$stem.md", body.toString().trim())
            }
        } else {
            files += ParsedFile(guarded.directory, guarded.relativePath, body.toString().trim())
        }
        mode = null
        inFence = false
        body.clear()
    }

    fun closeReview() {
        if (reviewType !in reviewTypes || reviewTitle.isBlank()) {
            warnings += "Ignored REVIEW: $reviewType | $reviewTitle"
        } else {
            val rawBody = body.toString().trim()
            val options = mutableListOf<String>()
            val searches = mutableListOf<String>()
            rawBody.lineSequence().forEach { line ->
                val trimmed = line.trim()
                when {
                    trimmed.startsWith("OPTIONS:") -> options += trimmed.removePrefix("OPTIONS:").trim()
                    trimmed.startsWith("SEARCH:") -> searches += trimmed.removePrefix("SEARCH:").trim()
                }
            }
            reviews += ParsedReview(reviewType, reviewTitle, rawBody, options, searches)
        }
        mode = null
        inFence = false
        body.clear()
    }

    for (line in lines) {
        val trimmed = line.trim()
        if (mode != null && trimmed.startsWith("```")) {
            inFence = !inFence
            body.append(line).append('\n')
            continue
        }
        if (inFence && mode != null) {
            body.append(line).append('\n')
            continue
        }
        val fileMatch = fileStart.matchEntire(trimmed)
        val reviewMatch = reviewStart.matchEntire(trimmed)
        when {
            fileMatch != null -> {
                discardOpen()
                mode = "file"
                header = fileMatch.groupValues[1].trim()
                inFence = false
                body.clear()
            }
            reviewMatch != null -> {
                discardOpen()
                mode = "review"
                reviewType = reviewMatch.groupValues[1].trim()
                reviewTitle = reviewMatch.groupValues[2].trim()
                header = "$reviewType | $reviewTitle"
                inFence = false
                body.clear()
            }
            trimmed == "---END FILE---" && mode == "file" -> closeFile()
            trimmed == "---END REVIEW---" && mode == "review" -> closeReview()
            mode != null -> body.append(line).append('\n')
            trimmed.isNotEmpty() -> outside = true
        }
    }
    discardOpen()
    if (outside) warnings += "Ignored text outside FILE/REVIEW."
    return BlockParse(files, reviews, warnings)
}

/** Titles marked `Page-worthy: yes` under Key Entities and Key Concepts. */
fun parsePageWorthy(analysis: String): List<PageWorthy> {
    val lines = analysis.replace("\r\n", "\n").replace('\r', '\n').lines()
    val items = mutableListOf<PageWorthy>()
    var kind: String? = null
    var open = false
    var title = ""
    var worthy = false
    var aliases = emptyList<String>()
    var existing: String? = null

    fun flush() {
        if (!open) return
        open = false
        if (worthy && title.isNotBlank() && kind != null) {
            items += PageWorthy(title.trim(), kind!!, aliases, existing)
        }
        title = ""
        worthy = false
        aliases = emptyList()
        existing = null
    }

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("## ")) {
            flush()
            kind = when (trimmed.removePrefix("## ").trim()) {
                "Key Entities" -> "entity"
                "Key Concepts" -> "concept"
                else -> null
            }
            continue
        }
        if (kind == null) continue
        if (trimmed.startsWith("- Title:")) {
            flush()
            open = true
            title = trimmed.removePrefix("- Title:").trim()
            continue
        }
        if (!open) continue
        when {
            trimmed.startsWith("Page-worthy:") ->
                worthy = trimmed.removePrefix("Page-worthy:").trim().equals("yes", ignoreCase = true)
            trimmed.startsWith("Aliases:") -> {
                val raw = trimmed.removePrefix("Aliases:").trim()
                aliases = if (raw.isEmpty() || raw.equals("none", ignoreCase = true)) {
                    emptyList()
                } else {
                    raw.split(',').map { it.trim() }.filter { it.isNotEmpty() && !it.equals("none", ignoreCase = true) }
                }
            }
            trimmed.startsWith("Existing:") -> {
                val raw = trimmed.removePrefix("Existing:").trim()
                existing = if (raw.isEmpty() || raw.equals("none", ignoreCase = true)) null else raw
            }
        }
    }
    flush()
    return items
}

/** Relative wiki page path under sources, concepts, or entities. Source paths are not rewritten here. */
fun isSafeWikiPagePath(raw: String): Boolean = guardIngestFile(raw, "untitled") != null

private data class GuardedPath(val directory: String, val relativePath: String)

private fun guardIngestFile(raw: String, sourceStem: String): GuardedPath? {
    val path = raw.trim()
    if (path.isEmpty() || path.contains('\\') || path.contains("..") || path.startsWith("/") || path.contains(':')) {
        return null
    }
    val parts = path.split('/').filter { it.isNotEmpty() }
    if (parts.size != 3 || parts[0] != "wiki") return null
    val directory = parts[1]
    if (directory !in allowedDirs) return null
    val name = parts[2]
    if (!name.endsWith(".md") || name == ".md" || name in deniedNames) return null
    if (directory == "sources") return GuardedPath("sources", "wiki/sources/$sourceStem.md")
    return GuardedPath(directory, "wiki/$directory/$name")
}
