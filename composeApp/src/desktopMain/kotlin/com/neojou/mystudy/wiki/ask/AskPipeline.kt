package com.neojou.mystudy.wiki.ask

import com.neojou.mystudy.wiki.index.WikiIndex
import com.neojou.mystudy.wiki.markdown.analyzeQuery
import com.neojou.mystudy.wiki.markdown.catalogLine
import com.neojou.mystudy.wiki.markdown.catalogTarget
import com.neojou.mystudy.wiki.markdown.headingForType
import com.neojou.mystudy.wiki.markdown.renderNewPage
import com.neojou.mystudy.wiki.markdown.safeFileStem
import com.neojou.mystudy.wiki.model.DraftProposal
import com.neojou.mystudy.wiki.model.PageProposal
import com.neojou.mystudy.wiki.ollama.ChatClient
import com.neojou.mystudy.wiki.ollama.ChatRequest
import com.neojou.mystudy.wiki.ollama.PromptBudget
import java.time.LocalDate

val ASK_SYSTEM: String = """
你只回答使用者這一個問題。用繁體中文。
一句話只放一個主張，句末用對應編號，例如 [1]。
不要複製頁面的標題或小標。不要用訓練記憶補內容。不要上網。
下面的頁面沒有寫的，只回答「沒有」。
只能使用給定的編號。不要自己寫檔名或路徑。
""".trimIndent()

const val PACKET_PAGES: Int = 8
const val PACKET_CHARS: Int = 6_000
const val SCHEMA_EXCERPT: Int = 800

data class PacketPage(
    val number: Int,
    val path: String,
    val title: String,
    val type: String,
    val excerpt: String,
    val fromGraph: Boolean,
)

data class PacketEdge(
    val from: String,
    val to: String,
    val line: String,
)

data class DraftPage(
    val path: String,
    val title: String,
    val type: String,
    val text: String,
    val fromGraph: Boolean,
)

sealed class AskResult {
    data class NoMatch(val message: String, val tokenHits: Int, val graphHits: Int) : AskResult()

    data class NotCalled(val message: String) : AskResult()

    data class Answer(
        val text: String,
        val sourcePaths: List<String>,
        val tokenHits: Int,
        val graphHits: Int,
        val pages: List<PacketPage>,
        val edges: List<PacketEdge>,
    ) : AskResult()
}

class AskPipeline(
    private val index: WikiIndex,
    private val client: ChatClient,
) {
    fun ask(question: String, numCtx: Int): AskResult {
        if (PromptBudget.tooSmall(numCtx)) {
            return AskResult.NotCalled("Context setting is too small. Raise num_ctx above ${PromptBudget.RESERVE_TOKENS}.")
        }
        val analysis = analyzeQuery(question)
        if (analysis.phrase.isBlank() && analysis.bigrams.isEmpty() && analysis.extras.isEmpty()) {
            return AskResult.NoMatch("Nothing in the wiki matches.", 0, 0)
        }
        val (tokenHits, pool) = lexicalPool(index, analysis)
        val frequency = documentFrequency(index, analysis.bigrams + analysis.extras)
        val noteCount = index.askNoteCount()
        val scored = pool.mapNotNull { scoreNote(it, analysis, frequency, noteCount) }
            .sortedWith(compareByDescending<ScoredNote> { it.score }.thenBy { pathRank(it.path) }.thenBy { it.path })
        val seeds = scored.filter { it.canSeed }.take(5)
        if (seeds.isEmpty()) {
            return AskResult.NoMatch("Nothing in the wiki matches.", tokenHits, 0)
        }
        val graph = index.wikiGraph()
        val neighbors = expandOneHop(graph, seeds.map { it.path })
        val byPath = (pool + index.notesForAsk()).associateBy { it.path }
        val seedPaths = seeds.map { it.path }.toSet()
        val ordered = seeds.map { seed ->
            DraftPage(seed.path, seed.title, seed.type, excerptFor(seed.body, analysis), fromGraph = false)
        } + neighbors.mapNotNull { neighbor ->
            val note = byPath[neighbor.path] ?: return@mapNotNull null
            DraftPage(note.path, note.title, note.type, excerptFor(note.body, analysis), fromGraph = neighbor.path !in seedPaths)
        }
        val packed = limitPacket(ordered, PACKET_CHARS)
        if (packed.isEmpty()) return AskResult.NoMatch("Nothing in the wiki matches.", tokenHits, 0)
        val pages = packed.mapIndexed { index, page ->
            PacketPage(index + 1, page.path, page.title, page.type, page.text, page.fromGraph)
        }
        val edges = packetEdges(graph, pages.map { it.path }.toSet()).map { (from, target) ->
            PacketEdge(from, target.first, target.second)
        }
        val schema = schemaExcerpt(index.load("wiki/schema.md")?.body)
        val user = renderPacket(question, schema, pages)
        if (ASK_SYSTEM.length + user.length > PromptBudget.inputCharBudget(numCtx)) {
            return AskResult.NotCalled("The matching page does not fit in the prompt budget. Nothing was sent.")
        }
        val answer = try {
            client.complete(ChatRequest(ASK_SYSTEM, user, json = false))
        } catch (error: Exception) {
            return AskResult.NotCalled(error.message ?: "Ollama failed")
        }
        return AskResult.Answer(
            text = answer,
            sourcePaths = pages.map { it.path },
            tokenHits = tokenHits,
            graphHits = pages.count { it.fromGraph },
            pages = pages,
            edges = edges,
        )
    }
}

fun limitPacket(pages: List<DraftPage>, charCap: Int = PACKET_CHARS): List<DraftPage> {
    val capped = pages.take(PACKET_PAGES).map { page ->
        if (page.text.length <= 1_500) page else page.copy(text = page.text.take(1_500))
    }.toMutableList()
    while (capped.size > 1 && capped.sumOf { it.text.length } > charCap) {
        capped.removeAt(capped.lastIndex)
    }
    if (capped.size == 1 && capped[0].text.length > charCap) {
        capped[0] = capped[0].copy(text = capped[0].text.take(charCap))
    }
    return capped
}

fun schemaExcerpt(text: String?): String {
    if (text.isNullOrBlank()) return "（沒有 schema.md）"
    val frontmatter = text.indexOf("## Frontmatter")
    val links = text.indexOf("## Links")
    val start = listOf(frontmatter, links).filter { it >= 0 }.minOrNull() ?: 0
    return text.substring(start).take(SCHEMA_EXCERPT)
}

fun renderPacket(question: String, schema: String, pages: List<PacketPage>): String = buildString {
    append("問題：")
    append(question.trim())
    append("\n\n規則節錄（不是答案，不要引用）：\n")
    append(schema)
    append('\n')
    for (page in pages) {
        append("\n[")
        append(page.number)
        append("] ")
        append(page.path)
        append("\ntype: ")
        append(page.type)
        append('\n')
        append(page.excerpt)
        append('\n')
    }
}

private fun excerptFor(body: String, analysis: com.neojou.mystudy.wiki.markdown.QueryAnalysis): String {
    val anchors = analysis.bigrams.filter { it !in setOf("原子", "筆記", "方法", "概念", "系統", "设计", "設計", "知識", "知识") } +
        listOf(analysis.phrase)
    return excerptAround(body, anchors.filter { it.isNotBlank() }, 1_500)
}

private fun pathRank(path: String): Int = when {
    path.startsWith("wiki/concepts/") -> 0
    path.startsWith("wiki/entities/") -> 1
    path.startsWith("wiki/sources/") -> 2
    path.startsWith("wiki/queries/") -> 3
    else -> 4
}

fun excerptAround(body: String, terms: List<String>, cap: Int): String {
    if (body.length <= cap) return body
    val lower = body.lowercase()
    val at = terms.map { lower.indexOf(it.lowercase()) }.filter { it >= 0 }.minOrNull() ?: 0
    val start = (at - cap / 4).coerceAtLeast(0)
    val end = (start + cap).coerceAtMost(body.length)
    return body.substring(start.coerceAtMost(end), end)
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
