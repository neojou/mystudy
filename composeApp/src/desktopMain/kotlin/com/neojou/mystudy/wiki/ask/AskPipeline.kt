package com.neojou.mystudy.wiki.ask

import com.neojou.mystudy.wiki.index.NoteRecord
import com.neojou.mystudy.wiki.index.WikiIndex
import com.neojou.mystudy.wiki.markdown.catalogLine
import com.neojou.mystudy.wiki.markdown.catalogTarget
import com.neojou.mystudy.wiki.markdown.firstWikilinkTarget
import com.neojou.mystudy.wiki.markdown.headingForType
import com.neojou.mystudy.wiki.markdown.queryTerms
import com.neojou.mystudy.wiki.markdown.renderNewPage
import com.neojou.mystudy.wiki.markdown.safeFileStem
import com.neojou.mystudy.wiki.model.DraftProposal
import com.neojou.mystudy.wiki.model.PageProposal
import com.neojou.mystudy.wiki.model.Resolution
import com.neojou.mystudy.wiki.ollama.ChatClient
import com.neojou.mystudy.wiki.ollama.ChatRequest
import com.neojou.mystudy.wiki.ollama.PromptBudget
import java.time.LocalDate

const val ASK_SYSTEM: String =
    "只根據下列 wiki 頁回答。頁面沒寫的就說沒有。不要用訓練記憶補內容，不要上網。回答必須列出來源檔名。"

data class Excerpt(
    val path: String,
    val title: String,
    val sources: List<String>,
    val text: String,
)

data class AskBundle(
    val catalog: List<String>,
    val pages: List<Excerpt>,
    val hops: List<Excerpt>,
)

sealed class Fit {
    data class Ready(val bundle: AskBundle, val userText: String) : Fit()

    data class Over(val message: String) : Fit()
}

sealed class AskResult {
    data class NoMatch(val message: String) : AskResult()

    data class NotCalled(val message: String) : AskResult()

    data class Answer(val text: String, val sourcePaths: List<String>) : AskResult()
}

class AskPipeline(
    private val index: WikiIndex,
    private val client: ChatClient,
) {
    fun ask(question: String, numCtx: Int): AskResult {
        if (PromptBudget.tooSmall(numCtx)) {
            return AskResult.NotCalled("Context setting is too small. Raise num_ctx above ${PromptBudget.RESERVE_TOKENS}.")
        }
        val terms = queryTerms(question)
        val indexBody = index.load("wiki/index.md")?.body.orEmpty()
        val catalogLines = rankedCatalog(indexBody, terms)
        val catalogPaths = catalogLines.mapNotNull { line ->
            val target = firstWikilinkTarget(line) ?: return@mapNotNull null
            resolveCatalogTarget(index, target)
        }
        val found = linkedSetOf<String>()
        found += index.searchFts(terms, 8)
        for (term in terms.filter { it.length == 2 }) found += index.searchLike(term, 8)
        val merged = mergeCandidates(catalogPaths, found.toList(), ::excludedAskPath)
        val pages = merged.take(4).mapNotNull { path -> toExcerpt(index.load(path), terms, PromptBudget.PAGE_EXCERPT) }
        if (pages.isEmpty()) return AskResult.NoMatch("Nothing in the wiki matches.")
        val opened = pages.map { it.path }.toSet()
        val hops = pages.flatMap { index.linksFrom(it.path) }
            .filter { it !in opened && !excludedAskPath(it) }
            .distinct()
            .mapNotNull { index.load(it) }
            .sortedByDescending { note -> terms.count { term -> note.title.contains(term, ignoreCase = true) } }
            .take(4)
            .mapNotNull { toExcerpt(it, terms, PromptBudget.HOP_EXCERPT) }
        val fit = fitAskPrompt(
            ASK_SYSTEM,
            AskBundle(catalogLines, pages, hops),
            PromptBudget.inputCharBudget(numCtx),
        )
        return when (fit) {
            is Fit.Over -> AskResult.NotCalled(fit.message)
            is Fit.Ready -> {
                val answer = try {
                    client.complete(ChatRequest(ASK_SYSTEM, fit.userText, json = false))
                } catch (error: Exception) {
                    return AskResult.NotCalled(error.message ?: "Ollama failed")
                }
                val sources = (fit.bundle.pages + fit.bundle.hops).map { it.path }.distinct()
                AskResult.Answer(answer, sources)
            }
        }
    }
}

fun proposeQueryFile(
    question: String,
    answer: String,
    sourcePaths: List<String>,
    today: LocalDate,
    exists: (String) -> Boolean,
): DraftProposal {
    val stem = safeFileStem(question.take(40))
    var relative = "wiki/queries/$stem.md"
    var suffix = 2
    while (exists(relative)) {
        relative = "wiki/queries/$stem ($suffix).md"
        suffix += 1
    }
    val body = buildString {
        append("## Question\n\n")
        append(question.trim())
        append("\n\n## Answer\n\n")
        append(answer.trim())
        append("\n")
    }
    val page = renderNewPage(
        title = question.trim().take(80),
        type = "query",
        tags = emptyList(),
        aliases = emptyList(),
        sources = sourcePaths,
        today = today,
        body = body,
    )
    val proposal = PageProposal(
        relativePath = relative,
        previousText = null,
        newText = page,
        conflict = false,
        selectable = true,
        note = "",
        catalogHeading = headingForType("query"),
        catalogLine = catalogLine(catalogTarget(relative), question.trim().take(80), "query", sourcePaths),
    )
    return DraftProposal(
        pages = listOf(proposal),
        logTitle = question.trim().take(80),
        operation = "query",
        schemaWarning = null,
        schemaTruncated = false,
    )
}

fun mergeCandidates(catalog: List<String>, fts: List<String>, excluded: (String) -> Boolean): List<String> {
    val out = linkedSetOf<String>()
    for (path in catalog + fts) {
        if (!excluded(path)) out += path
    }
    return out.toList()
}

fun excludedAskPath(path: String): Boolean = when {
    path == "wiki/log.md" || path == "wiki/schema.md" || path == "wiki/index.md" -> true
    path.startsWith("raw/") -> true
    !path.startsWith("wiki/") -> true
    else -> false
}

fun rankedCatalog(indexBody: String, terms: List<String>): List<String> {
    if (terms.isEmpty()) return emptyList()
    return indexBody.split('\n')
        .map { it.trimEnd() }
        .filter { it.isNotBlank() }
        .map { line -> line to terms.count { term -> line.contains(term, ignoreCase = true) } }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(8)
        .map { it.first }
}

fun fitAskPrompt(system: String, bundle: AskBundle, budget: Int): Fit {
    var catalog = bundle.catalog.take(8)
    var pages = bundle.pages
    var hops = bundle.hops
    fun size(): Int = system.length + renderUser(catalog, pages, hops).length
    while (size() > budget && hops.isNotEmpty()) hops = hops.dropLast(1)
    while (size() > budget && pages.size > 1) pages = pages.dropLast(1)
    while (size() > budget && catalog.isNotEmpty()) catalog = catalog.dropLast(1)
    if (pages.isEmpty() || size() > budget) {
        return Fit.Over("The matching page does not fit in the prompt budget. Nothing was sent.")
    }
    return Fit.Ready(AskBundle(catalog, pages, hops), renderUser(catalog, pages, hops))
}

fun renderUser(catalog: List<String>, pages: List<Excerpt>, hops: List<Excerpt>): String = buildString {
    append("## Catalog\n")
    if (catalog.isEmpty()) append("(no catalog lines)\n")
    catalog.forEach { append(it).append('\n') }
    for (page in pages + hops) {
        append("\n## ")
        append(page.path)
        append("\ntitle: ")
        append(page.title)
        append('\n')
        if (page.sources.isNotEmpty()) {
            append("sources: ")
            append(page.sources.joinToString(", "))
            append('\n')
        }
        append(page.text)
        append('\n')
    }
}

internal fun resolveCatalogTarget(index: WikiIndex, target: String): String? {
    val prefixed = when {
        target.startsWith("wiki/") || target.startsWith("raw/") -> target
        else -> "wiki/$target"
    }.removeSuffix(".md") + ".md"
    if (index.noteExists(prefixed) && !excludedAskPath(prefixed)) return prefixed
    return when (val resolved = index.resolve(target)) {
        is Resolution.One -> resolved.path.takeUnless(::excludedAskPath)
        else -> null
    }
}

private fun toExcerpt(note: NoteRecord?, terms: List<String>, cap: Int): Excerpt? {
    if (note == null || note.body.isBlank()) return null
    return Excerpt(note.path, note.title, note.sources, excerptAround(note.body, terms, cap))
}

fun excerptAround(body: String, terms: List<String>, cap: Int): String {
    if (body.length <= cap) return body
    val lower = body.lowercase()
    val at = terms.map { lower.indexOf(it.lowercase()) }.filter { it >= 0 }.minOrNull() ?: 0
    val start = (at - cap / 4).coerceAtLeast(0)
    val end = (start + cap).coerceAtMost(body.length)
    val sliceStart = start.coerceAtMost(end)
    return body.substring(sliceStart, end)
}
