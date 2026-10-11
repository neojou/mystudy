package com.neojou.mystudy.wiki.agent

import com.neojou.mystudy.wiki.ask.PacketEdge
import com.neojou.mystudy.wiki.ask.PacketPage
import com.neojou.mystudy.wiki.ollama.ChatClient
import com.neojou.mystudy.wiki.ollama.ChatRequest
import com.neojou.mystudy.wiki.ollama.PromptBudget
import com.neojou.mystudy.wiki.prompt.PromptException
import com.neojou.mystudy.wiki.prompt.PromptFile
import com.neojou.mystudy.wiki.prompt.loadPrompt
import com.neojou.mystudy.wiki.prompt.locateAiPrompts
import com.neojou.mystudy.wiki.prompt.render
import com.neojou.tools.MyLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.coroutines.coroutineContext

sealed class AgentEvent {
    data class Thinking(val step: Int) : AgentEvent()
    data class ToolStart(val tool: String, val arg: String) : AgentEvent()
    data class ToolEnd(val summary: String) : AgentEvent()
    data class ReferenceAdded(val number: Int, val path: String) : AgentEvent()
}

sealed class ChatAgentResult {
    data class NoMatch(val message: String, val steps: List<String>) : ChatAgentResult()
    data class NotCalled(val message: String) : ChatAgentResult()
    data class Answer(
        val text: String,
        val references: List<ChatReference>,
        val shown: List<ChatReference>,
        val cited: List<Int>,
        val steps: List<String>,
        val pages: List<PacketPage>,
        val edges: List<PacketEdge>,
        val llmCalls: Int,
    ) : ChatAgentResult()
}

class ChatAgent(
    private val client: ChatClient,
    private val tools: suspend (AgentAction, List<ChatReference>) -> ToolObservation,
    private val promptDirectory: Path? = null,
    private val onEvent: (AgentEvent) -> Unit = {},
    private val edgesFor: suspend (Set<String>) -> List<PacketEdge> = { emptyList() },
) {
    suspend fun run(question: String, numCtx: Int, history: List<ChatTurn>, schema: String): ChatAgentResult {
        if (PromptBudget.tooSmall(numCtx)) {
            return ChatAgentResult.NotCalled(
                "Context setting is too small. Raise num_ctx above ${PromptBudget.RESERVE_TOKENS}.",
            )
        }
        val prompt = try {
            val file = loadPrompt(promptDirectory ?: locateAiPrompts(), "chat-agent.md")
            file.require("system")
            file.require("user")
            file.require("reference")
            file.require("final-system")
            file.require("final-user")
            file
        } catch (error: PromptException) {
            return ChatAgentResult.NotCalled(error.message ?: "Prompt file ai-prompts/chat-agent.md could not be read. Nothing was sent.")
        }
        val started = System.nanoTime()
        val references = mutableListOf<ChatReference>()
        val observations = mutableListOf<String>()
        val steps = mutableListOf<String>()
        val searched = mutableSetOf<String>()
        var searches = 0
        var reads = 0
        var toolsUsed = 0
        var llmCalls = 0
        var invalidStreak = 0
        var loopStep = 0
        var nudgedSources = false
        var failed: ChatAgentResult.NotCalled? = null

        suspend fun callModel(system: String, user: String): String? {
            coroutineContext.ensureActive()
            val text = try {
                withContext(Dispatchers.IO) {
                    client.complete(ChatRequest(system, user, json = true))
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                failed = ChatAgentResult.NotCalled(error.message ?: "Ollama failed")
                return null
            }
            coroutineContext.ensureActive()
            llmCalls += 1
            return text
        }

        suspend fun runSearch(query: String, auto: Boolean) {
            val key = searchKey(query)
            searched += key
            searches += 1
            toolsUsed += 1
            onEvent(AgentEvent.ToolStart("wiki.search", query))
            val outcome = tools(AgentAction.Search(query), references.toList()).outcome
                ?: SearchOutcome(emptyList(), 0, 0, 0, 0, "keyword")
            val added = mutableListOf<String>()
            val numbers = mutableListOf<Int>()
            for (hit in outcome.hits) {
                val existing = references.firstOrNull { it.path == hit.path }
                if (existing != null) {
                    numbers += existing.number
                    continue
                }
                val number = references.size + 1
                references += ChatReference(
                    number = number,
                    path = hit.path,
                    title = hit.title,
                    type = hit.type,
                    snippet = hit.snippet,
                    kind = hit.kind,
                    read = false,
                    links = hit.links.ifBlank { "(none)" },
                )
                added += hit.path
                numbers += number
                onEvent(AgentEvent.ReferenceAdded(number, hit.path))
            }
            val log = formatSearchLog(outcome, added.size, outcome.hits.map { it.path })
            val logged = if (auto) "$log auto=true" else log
            val emptyTerms = tokenizeSearchQuery(query).runs.isEmpty()
            val observation = if (emptyTerms) {
                "wiki.search \"$query\": 0 result(s), query had no searchable terms"
            } else {
                val marks = numbers.joinToString(" ") { "[$it]" }
                "wiki.search \"$query\": ${outcome.hits.size} result(s), ${added.size} new, mode=${outcome.mode}, " +
                    "tokenHits=${outcome.tokenHits}, nameHits=${outcome.nameHits}, vectorHits=0, " +
                    "graphHits=${outcome.graphHits} → $marks"
            }
            steps += observation
            MyLog.add("ChatAgent", "step=$loopStep action=tool tool=wiki.search query=$query${if (auto) " auto=true" else ""}")
            MyLog.add("ChatAgent", logged)
            onEvent(AgentEvent.ToolEnd(observation))
            observations += observation
        }

        suspend fun forceFinal(): ChatAgentResult {
            if (references.isEmpty()) {
                logFinal(llmCalls, searches, reads, references, emptyList(), forced = true, started, steps)
                return ChatAgentResult.NoMatch("Nothing in the wiki matches.", steps.toList())
            }
            val rendered = fit(prompt, true, question, history, references, observations, loopStep, searches, reads, toolsUsed, schema, numCtx)
                ?: return ChatAgentResult.NotCalled("The chat prompt does not fit in the prompt budget. Nothing was sent.")
            val raw = callModel(rendered.first, rendered.second) ?: return failed!!
            return when (val action = parseAgentAction(raw)) {
                is AgentAction.Final -> accept(action.answer, references, steps, llmCalls, searches, reads, true, started)
                is AgentAction.Invalid -> {
                    if (action.raw.isNotBlank() && '{' !in action.raw) {
                        accept(action.raw.trim(), references, steps, llmCalls, searches, reads, true, started)
                    } else {
                        ChatAgentResult.NotCalled("模型沒有回傳可解析的答案")
                    }
                }
                else -> ChatAgentResult.NotCalled("模型沒有回傳可解析的答案")
            }
        }

        while (true) {
            coroutineContext.ensureActive()
            if (loopStep >= MAX_STEPS) return forceFinal()
            loopStep += 1
            onEvent(AgentEvent.Thinking(loopStep))
            val rendered = fit(prompt, false, question, history, references, observations, loopStep, searches, reads, toolsUsed, schema, numCtx)
                ?: return ChatAgentResult.NotCalled("The chat prompt does not fit in the prompt budget. Nothing was sent.")
            val raw = callModel(rendered.first, rendered.second) ?: return failed!!
            when (val action = parseAgentAction(raw)) {
                is AgentAction.Search -> {
                    invalidStreak = 0
                    val key = searchKey(action.query)
                    if (key in searched || searches >= MAX_SEARCHES || toolsUsed >= RETRIEVAL_BUDGET) {
                        return forceFinal()
                    }
                    runSearch(action.query, auto = false)
                }
                is AgentAction.ReadPage -> {
                    invalidStreak = 0
                    if (toolsUsed >= RETRIEVAL_BUDGET) return forceFinal()
                    toolsUsed += 1
                    reads += 1
                    onEvent(AgentEvent.ToolStart("wiki.read_page", action.path))
                    val observed = tools(action, references.toList())
                    val line = if (observed.readPath == null) {
                        observed.text
                    } else {
                        val existing = references.indexOfFirst { it.path == observed.readPath }
                        val number = if (existing >= 0) {
                            references[existing] = references[existing].copy(read = true)
                            references[existing].number
                        } else {
                            val created = references.size + 1
                            references += ChatReference(
                                number = created,
                                path = observed.readPath,
                                title = observed.readTitle.orEmpty(),
                                type = observed.readType.orEmpty(),
                                snippet = observed.readBody.orEmpty().take(WikiSearch.SNIPPET_CHARS),
                                kind = "wiki",
                                read = true,
                                links = observed.readLinks ?: "(none)",
                            )
                            onEvent(AgentEvent.ReferenceAdded(created, observed.readPath))
                            created
                        }
                        val links = observed.readLinks ?: "(none)"
                        "wiki.read_page [$number] ${observed.readPath}\n${observed.readBody.orEmpty()}\n連結：$links"
                    }
                    val chars = observed.readBody?.length ?: 0
                    steps += line.lineSequence().first()
                    observations += line
                    MyLog.add("ChatAgent", "step=$loopStep action=tool tool=wiki.read_page path=${action.path} chars=$chars")
                    onEvent(AgentEvent.ToolEnd(line.lineSequence().first()))
                }
                is AgentAction.Final -> {
                    invalidStreak = 0
                    if (references.isEmpty() && searches == 0) {
                        if (toolsUsed >= RETRIEVAL_BUDGET) {
                            return ChatAgentResult.NoMatch("Nothing in the wiki matches.", steps.toList())
                        }
                        runSearch(question, auto = true)
                        continue
                    }
                    if (references.isEmpty()) {
                        logFinal(llmCalls, searches, reads, references, emptyList(), forced = false, started, steps)
                        return ChatAgentResult.NoMatch("Nothing in the wiki matches.", steps.toList())
                    }
                    val unreadSources = references.filter { !it.read && it.path.startsWith("wiki/sources/") }
                    if (!nudgedSources && unreadSources.isNotEmpty() && toolsUsed < RETRIEVAL_BUDGET) {
                        nudgedSources = true
                        val listed = unreadSources.joinToString(" ") { "[${it.number}] ${it.path}" }
                        val line = "仍有未讀來源：$listed。先讀再回答，不要只留一句。"
                        observations += line
                        steps += line
                        MyLog.add("ChatAgent", line)
                        onEvent(AgentEvent.ToolEnd(line))
                        continue
                    }
                    return accept(action.answer, references, steps, llmCalls, searches, reads, false, started)
                }
                is AgentAction.Invalid -> {
                    invalidStreak += 1
                    val line = "invalid action: ${action.reason}. Return one JSON object."
                    observations += line
                    steps += line
                    MyLog.add("ChatAgent", line)
                    onEvent(AgentEvent.ToolEnd(line))
                    if (invalidStreak >= 2) return forceFinal()
                }
            }
        }
    }

    private suspend fun accept(
        answer: String,
        references: List<ChatReference>,
        steps: List<String>,
        llmCalls: Int,
        searches: Int,
        reads: Int,
        forced: Boolean,
        started: Long,
    ): ChatAgentResult {
        val cited = citedNumbers(answer, references.size)
        val invalid = invalidCitations(answer, references.size)
        val shown = references.toList()
        val pages = shown.take(8).map { ref ->
            PacketPage(ref.number, ref.path, ref.title, ref.type, ref.snippet, ref.kind == "graph")
        }
        val edges = edgesFor(pages.map { it.path }.toSet())
        logFinal(llmCalls, searches, reads, references, cited, forced, started, steps, invalid)
        return ChatAgentResult.Answer(
            text = answer,
            references = references,
            shown = shown,
            cited = cited,
            steps = steps,
            pages = pages,
            edges = edges,
            llmCalls = llmCalls,
        )
    }

    companion object {
        const val MAX_STEPS: Int = 6
        const val RETRIEVAL_BUDGET: Int = 4
        const val MAX_SEARCHES: Int = 3
        const val READ_PAGE_CHARS: Int = 4000
        const val OBSERVATION_CHARS: Int = 12_000
        const val MAX_REFERENCES_IN_PROMPT: Int = 14
        const val HISTORY_TURNS: Int = 3
        const val HISTORY_CHARS: Int = 1_200
    }
}

private fun searchKey(query: String): String = tokenizeSearchQuery(query).runs.joinToString("\u0000")

private fun citedNumbers(answer: String, size: Int): List<Int> =
    citationPattern.findAll(answer).map { it.groupValues[1].toInt() }.filter { it in 1..size }.distinct().toList()

private fun invalidCitations(answer: String, size: Int): List<Int> =
    citationPattern.findAll(answer).map { it.groupValues[1].toInt() }.filter { it !in 1..size }.distinct().toList()

private val citationPattern = Regex("""\[(\d+)]""")

private fun logFinal(
    llmCalls: Int,
    searches: Int,
    reads: Int,
    references: List<ChatReference>,
    cited: List<Int>,
    forced: Boolean,
    started: Long,
    steps: List<String>,
    invalid: List<Int> = emptyList(),
) {
    val elapsed = (System.nanoTime() - started) / 1_000_000
    val extra = if (invalid.isEmpty()) "" else " invalidCitations=$invalid"
    MyLog.add(
        "ChatAgent",
        "final: steps=${steps.size} llmCalls=$llmCalls searches=$searches reads=$reads " +
            "references=${references.size} cited=$cited forced=$forced elapsedMs=$elapsed$extra",
    )
}

private fun fit(
    prompt: PromptFile,
    finalMode: Boolean,
    question: String,
    history: List<ChatTurn>,
    references: List<ChatReference>,
    observations: List<String>,
    step: Int,
    searches: Int,
    reads: Int,
    toolsUsed: Int,
    schema: String,
    numCtx: Int,
): Pair<String, String>? {
    var hist = history.takeLast(ChatAgent.HISTORY_TURNS)
    var obs = observations.toMutableList()
    var refs = references
    val budget = PromptBudget.inputCharBudget(numCtx)
    repeat(48) {
        val built = renderMessages(prompt, finalMode, question, hist, refs, obs, step, toolsUsed, schema)
        if (built.first.length + built.second.length <= budget) return built
        if (hist.isNotEmpty()) {
            hist = hist.drop(1)
            return@repeat
        }
        val long = obs.indexOfFirst { it.length > 1_500 }
        if (long >= 0) {
            obs[long] = obs[long].take(1_500)
            return@repeat
        }
        if (refs.size > 1) {
            refs = refs.dropLast(1)
            return@repeat
        }
        return null
    }
    if (searches < 0 || reads < 0) return null
    return null
}

private fun renderMessages(
    prompt: PromptFile,
    finalMode: Boolean,
    question: String,
    history: List<ChatTurn>,
    references: List<ChatReference>,
    observations: List<String>,
    step: Int,
    toolsUsed: Int,
    schema: String,
): Pair<String, String> {
    val system = render(
        prompt.require(if (finalMode) "final-system" else "system"),
        mapOf("schema" to schema),
        prompt.fileName,
    )
    val shownRefs = references.take(ChatAgent.MAX_REFERENCES_IN_PROMPT)
    val refsText = if (shownRefs.isEmpty()) {
        "(none)"
    } else {
        shownRefs.joinToString("\n") { ref ->
            render(
                prompt.require("reference"),
                mapOf(
                    "number" to ref.number.toString(),
                    "kind" to ref.kind,
                    "title" to ref.title,
                    "path" to ref.path,
                    "snippet" to ref.snippet,
                    "links" to ref.links.ifBlank { "(none)" },
                ),
                prompt.fileName,
            )
        }
    }
    val historyText = if (history.isEmpty()) {
        "(none)"
    } else {
        history.joinToString("\n\n") { turn ->
            "Q: ${turn.question.take(ChatAgent.HISTORY_CHARS)}\nA: ${turn.answer.take(ChatAgent.HISTORY_CHARS)}"
        }
    }
    var obsText = observations.joinToString("\n\n")
    if (obsText.length > ChatAgent.OBSERVATION_CHARS && observations.size > 1) {
        val shrunk = observations.toMutableList()
        var index = 0
        while (shrunk.joinToString("\n\n").length > ChatAgent.OBSERVATION_CHARS && index < shrunk.lastIndex) {
            shrunk[index] = shrunk[index].take(1_500)
            index += 1
        }
        obsText = shrunk.joinToString("\n\n")
    }
    if (obsText.isBlank()) obsText = "(none)"
    val userValues = linkedMapOf(
        "history" to historyText,
        "references" to refsText,
        "observations" to obsText,
        "question" to question.trim(),
    )
    if (!finalMode) {
        userValues["step"] = step.toString()
        userValues["max_steps"] = ChatAgent.MAX_STEPS.toString()
        userValues["retrievals_left"] = (ChatAgent.RETRIEVAL_BUDGET - toolsUsed).coerceAtLeast(0).toString()
    }
    val user = render(
        prompt.require(if (finalMode) "final-user" else "user"),
        userValues,
        prompt.fileName,
    )
    return system to user
}
