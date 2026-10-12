package com.neojou.mystudy.wiki.research

import com.neojou.mystudy.wiki.ingest.IngestLanguage
import com.neojou.mystudy.wiki.ollama.ChatRequest
import com.neojou.mystudy.wiki.ollama.PromptBudget
import com.neojou.mystudy.wiki.prompt.PromptException
import com.neojou.mystudy.wiki.prompt.loadPrompt
import com.neojou.mystudy.wiki.prompt.render
import java.nio.file.Path
import java.time.LocalDate

enum class ResearchPhase {
    Idle,
    Searching,
    Synthesizing,
    Saving,
    Done,
    Failed,
}

data class ResearchUpdate(
    val phase: ResearchPhase,
    val hits: List<SearxngHit>,
    val synthesis: String,
)

sealed class ResearchResult {
    data class Done(val path: String, val synthesis: String) : ResearchResult()
    data class Failed(val message: String) : ResearchResult()
    data object Cancelled : ResearchResult()
}

const val INDEX_EXCERPT_CAP: Int = 8_000

/**
 * One review: load the prompt, search, stream one synthesis, then write.
 * Search and the model run on the caller thread, which must not be the wiki-index dispatcher.
 * [beginSaving] must return false when the run was cancelled. After it returns true, the write finishes.
 */
class ResearchRunner(
    private val prompts: Path,
    internal val numCtx: Int,
    private val today: LocalDate,
    private val search: (String) -> List<SearxngHit>,
    private val stream: (ChatRequest, (String) -> Unit) -> String,
    private val noteExists: (String) -> Boolean = { false },
) {
    fun run(
        root: Path,
        entry: ReviewEntry,
        indexExcerpt: String,
        cancelled: () -> Boolean,
        rootStillOpen: () -> Boolean,
        beginSaving: () -> Boolean,
        onUpdate: (ResearchUpdate) -> Unit,
    ): ResearchResult {
        if (cancelled()) return ResearchResult.Cancelled
        if (!rootStillOpen()) return failedRoot()
        val prompt = try {
            loadPrompt(prompts, "deep-research.md")
        } catch (error: PromptException) {
            return ResearchResult.Failed(promptFailure(error))
        }
        val prepared = try {
            prepareTemplate(prompt.fileName, prompt.require("system"), prompt.require("language"), prompt.require("user"))
        } catch (error: PromptException) {
            return ResearchResult.Failed(promptFailure(error))
        }
        if (PromptBudget.tooSmall(numCtx)) {
            return ResearchResult.Failed("Context setting is too small. Nothing was written.")
        }
        val queries = researchQueries(entry)
        if (queries.isEmpty()) return ResearchResult.Failed("沒有搜尋結果")
        val hits = mutableListOf<SearxngHit>()
        for (query in queries) {
            if (cancelled()) return ResearchResult.Cancelled
            if (!rootStillOpen()) return failedRoot()
            val batch = try {
                search(query).take(PER_QUERY_CAP)
            } catch (error: SearxngException) {
                return ResearchResult.Failed(error.message ?: "SearXNG is not reachable.")
            }
            val merged = mergeSearxngHits(hits.toList(), batch)
            hits.clear()
            hits += merged
            onUpdate(ResearchUpdate(ResearchPhase.Searching, hits.toList(), ""))
        }
        if (hits.isEmpty()) return ResearchResult.Failed("沒有搜尋結果")
        val request = fitRequest(prepared, entry, hits.toList(), indexExcerpt.take(INDEX_EXCERPT_CAP))
            ?: return ResearchResult.Failed("The research prompt does not fit. Nothing was written.")
        if (cancelled()) return ResearchResult.Cancelled
        if (!rootStillOpen()) return failedRoot()
        val accumulated = StringBuilder()
        onUpdate(ResearchUpdate(ResearchPhase.Synthesizing, hits.toList(), ""))
        val raw = try {
            stream(request) { delta ->
                accumulated.append(delta)
                onUpdate(ResearchUpdate(ResearchPhase.Synthesizing, hits.toList(), accumulated.toString()))
            }
        } catch (error: Exception) {
            return ResearchResult.Failed(error.message ?: "Ollama request failed.")
        }
        val synthesis = prepareSynthesis(raw)
        val problem = validateSynthesis(synthesis, hits.size)
        if (problem != null) return ResearchResult.Failed("$problem Nothing was written.")
        if (cancelled()) return ResearchResult.Cancelled
        if (!rootStillOpen()) return failedRoot()
        if (!beginSaving()) return ResearchResult.Cancelled
        onUpdate(ResearchUpdate(ResearchPhase.Saving, hits.toList(), synthesis))
        return when (
            val saved = writeResearch(
                root = root,
                topic = researchTopic(entry),
                reviewType = entry.type,
                reviewTitle = entry.title,
                queries = queries,
                synthesis = synthesis,
                hits = hits.toList(),
                rawBlock = entry.rawBlock,
                today = today,
                exists = noteExists,
            )
        ) {
            is SaveOutcome.Ok -> ResearchResult.Done(saved.path, synthesis)
            is SaveOutcome.Stopped -> ResearchResult.Failed(saved.message)
        }
    }
}

private data class PreparedPrompt(
    val fileName: String,
    val systemTemplate: String,
    val languageTemplate: String,
    val userTemplate: String,
)

private fun prepareTemplate(fileName: String, system: String, language: String, user: String): PreparedPrompt {
    val languageRule = render(language, mapOf("lang" to IngestLanguage.NAME), fileName)
    render(system, mapOf("languageRule" to languageRule), fileName)
    render(
        user,
        mapOf(
            "topic" to "t",
            "reviewType" to "suggestion",
            "description" to "d",
            "sources" to "s",
            "index" to "i",
        ),
        fileName,
    )
    return PreparedPrompt(fileName, system, language, user)
}

private fun ResearchRunner.fitRequest(
    prepared: PreparedPrompt,
    entry: ReviewEntry,
    hits: List<SearxngHit>,
    indexExcerpt: String,
): ChatRequest? {
    val languageRule = render(prepared.languageTemplate, mapOf("lang" to IngestLanguage.NAME), prepared.fileName)
    val system = render(prepared.systemTemplate, mapOf("languageRule" to languageRule), prepared.fileName)
    var indexText = indexExcerpt
    val snippets = hits.map { it.snippet }.toMutableList()
    fun userText(): String = render(
        prepared.userTemplate,
        mapOf(
            "topic" to researchTopic(entry),
            "reviewType" to entry.type,
            "description" to entry.description.ifBlank { "(none)" },
            "sources" to formatSources(hits, snippets),
            "index" to indexText.ifBlank { "(none)" },
        ),
        prepared.fileName,
    )
    val budget = PromptBudget.inputCharBudget(numCtx)
    if (system.length + userText().length > budget) indexText = ""
    while (system.length + userText().length > budget) {
        val index = snippets.indexOfLast { it.isNotEmpty() }
        if (index < 0) return null
        val over = system.length + userText().length - budget
        snippets[index] = if (snippets[index].length > over) snippets[index].dropLast(over) else ""
    }
    return ChatRequest(system, userText(), json = false)
}

private fun formatSources(hits: List<SearxngHit>, snippets: List<String>): String =
    hits.mapIndexed { index, hit ->
        buildString {
            append("[${index + 1}] **")
            append(hit.title)
            append("** (")
            append(hit.url)
            append(")")
            val snippet = snippets.getOrElse(index) { "" }.trim()
            if (snippet.isNotEmpty()) {
                append("\n")
                append(snippet)
            }
        }
    }.joinToString("\n\n").ifBlank { "(none)" }

private fun promptFailure(error: PromptException): String =
    error.message.orEmpty().replace("Nothing was sent.", "Nothing was written.").ifBlank {
        "Prompt file ai-prompts/deep-research.md is missing. Nothing was written."
    }

private fun failedRoot(): ResearchResult =
    ResearchResult.Failed("Wiki root changed. Nothing was written.")

fun researchBlockReason(
    status: SearxngStatus,
    phase: ResearchPhase,
    wikiOpen: Boolean,
    modelSet: Boolean,
): String? {
    if (!wikiOpen) return "尚未開啟 wiki。"
    if (!modelSet) return "尚未設定 Ollama 模型。"
    when (status) {
        is SearxngStatus.Failed -> return status.reason
        SearxngStatus.Testing -> return "正在測試 SearXNG。"
        SearxngStatus.Unknown -> return "尚未測試 SearXNG。"
        is SearxngStatus.Ok -> Unit
    }
    if (phase == ResearchPhase.Searching || phase == ResearchPhase.Synthesizing || phase == ResearchPhase.Saving) {
        return "已有研究進行中。"
    }
    return null
}

fun canResearch(status: SearxngStatus, phase: ResearchPhase, wikiOpen: Boolean, modelSet: Boolean): Boolean =
    researchBlockReason(status, phase, wikiOpen, modelSet) == null
