package com.neojou.mystudy.wiki

import com.neojou.mystudy.wiki.agent.AgentAction
import com.neojou.mystudy.wiki.agent.ChatAgent
import com.neojou.mystudy.wiki.agent.ChatAgentResult
import com.neojou.mystudy.wiki.agent.ChatReference
import com.neojou.mystudy.wiki.agent.SearchHit
import com.neojou.mystudy.wiki.agent.SearchOutcome
import com.neojou.mystudy.wiki.agent.ToolObservation
import com.neojou.mystudy.wiki.agent.WikiSearch
import com.neojou.mystudy.wiki.agent.WikiTools
import com.neojou.mystudy.wiki.agent.exactNameHits
import com.neojou.mystudy.wiki.agent.formatSearchLog
import com.neojou.mystudy.wiki.agent.normalizeReadPath
import com.neojou.mystudy.wiki.agent.parseAgentAction
import com.neojou.mystudy.wiki.agent.tokenizeSearchQuery
import com.neojou.mystudy.wiki.ask.schemaExcerpt
import com.neojou.mystudy.wiki.index.WikiIndex
import com.neojou.mystudy.wiki.ollama.ChatClient
import com.neojou.mystudy.wiki.ollama.ChatRequest
import com.neojou.mystudy.wiki.vault.scaffoldWiki
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatAgentBehaviorTest {
    @Test
    fun searchTokenizerKeepsNamesWithStopChars() {
        val named = tokenizeSearchQuery("請介紹植田和男")
        assertEquals(listOf("植田和男"), named.runs)
        assertEquals(listOf("植田和男", "植田", "田和", "和男"), named.terms)
        assertTrue(named.terms.none { it.length < 2 })
        assertEquals(listOf("自我介紹的方法"), tokenizeSearchQuery("自我介紹的方法").runs)
        assertEquals(listOf("卡片盒筆記法"), tokenizeSearchQuery("什麼是卡片盒筆記法").runs)
        assertEquals(listOf("植田和男"), tokenizeSearchQuery("植田和男是誰").runs)
        assertEquals(listOf("植田和男"), tokenizeSearchQuery("請問植田和男是誰？").runs)
        assertEquals(listOf("植田和男"), tokenizeSearchQuery("介紹一下植田和男").runs)
    }

    @Test
    fun exactNameHitsFindsLongestSubstring() {
        val hits = exactNameHits(listOf("日本央行總裁植田和男的政策")) { key ->
            when (key) {
                "植田和男" -> listOf("wiki/entities/植田和男.md", "raw/sources/x.md")
                "植田" -> listOf("wiki/entities/植田和男.md")
                "政策" -> listOf("raw/sources/x.md")
                else -> emptyList()
            }
        }
        assertEquals(listOf("植田和男"), hits.map { it.key })
        assertEquals(listOf("wiki/entities/植田和男.md"), hits.single().paths)
    }

    @Test
    fun searchRanksEntityFirstDespiteFrequentFrameWord() {
        uedaVault().use { index ->
            val search = WikiSearch(index)
            val queries = listOf(
                "植田和男",
                "請介紹植田和男",
                "植田和男是誰",
                "介紹一下植田和男",
                "請問植田和男是誰？",
                "日本央行總裁植田和男的政策",
            )
            for (query in queries) {
                val outcome = search.search(query)
                assertEquals("wiki/entities/植田和男.md", outcome.hits.first().path, query)
                assertTrue(outcome.nameHits >= 1, query)
                assertTrue(outcome.hits.take(5).none { it.path.startsWith("wiki/concepts/") }, query)
            }
            val hybrid = search.search("請介紹植田和男")
            assertEquals("hybrid", hybrid.mode)
            assertTrue(hybrid.graphHits >= 1)
            val entity = hybrid.hits.first()
            assertTrue(entity.links.contains("日本銀行"), entity.links)
            val graph = hybrid.hits.first { it.kind == "graph" }
            assertTrue(graph.snippet.startsWith("Graph neighbor of "), graph.snippet)
            assertTrue(graph.snippet.contains("中央銀行"), graph.snippet)
            val alone = search.search("c01").hits.first { it.path.endsWith("c01.md") }
            assertEquals("(none)", alone.links)
        }
    }

    @Test
    fun searchMatchesParenthesizedTitleAndStemOnly() {
        val parenthetical = scaffoldWiki(tempDir())
        writePage(
            parenthetical,
            "wiki/entities/ueda.md",
            "植田和男 (Kazuo Ueda)",
            "entity",
            "日本銀行總裁。",
        )
        WikiIndex.open(parenthetical, tempDir().resolve("paren.sqlite")).use { index ->
            index.reconcileAll()
            assertTrue(index.pathsForAlias("植田和男").isEmpty())
            val outcome = WikiSearch(index).search("植田和男")
            assertEquals("wiki/entities/ueda.md", outcome.hits.first().path)
            assertTrue(outcome.nameHits >= 1)
        }
        val stemOnly = scaffoldWiki(tempDir())
        writePage(stemOnly, "wiki/entities/植田和男.md", "Kazuo Ueda", "entity", "governor")
        WikiIndex.open(stemOnly, tempDir().resolve("stem.sqlite")).use { index ->
            index.reconcileAll()
            val outcome = WikiSearch(index).search("請介紹植田和男")
            assertEquals("wiki/entities/植田和男.md", outcome.hits.first().path)
            assertTrue(outcome.nameHits >= 1)
        }
    }

    @Test
    fun parseAgentActionToleratesFencesAndMissingAction() {
        val fenced = parseAgentAction("note\n```json\n{\"action\":\"tool\",\"tool\":\"wiki.search\",\"query\":\"植田和男\"}\n```\nthanks")
        assertEquals(AgentAction.Search("植田和男"), fenced)
        val alias = parseAgentAction("{\"tool\":\"search\",\"query\":\"植田和男\"}")
        assertEquals(AgentAction.Search("植田和男"), alias)
        val missing = parseAgentAction("{\"answer\":\"植田和男是日本銀行總裁\"}")
        assertEquals(AgentAction.Final("植田和男是日本銀行總裁"), missing)
        val read = parseAgentAction("{\"tool\":\"read\",\"path\":\"`wiki/entities/植田和男.md`\"}")
        assertEquals(AgentAction.ReadPage("wiki/entities/植田和男.md"), read)
        assertIs<AgentAction.Invalid>(parseAgentAction("{\"action\":\"final\",\"answer\":\"  \"}"))
    }

    @Test
    fun agentSearchesReadsAndAnswersUeda() {
        val calls = mutableListOf<ChatRequest>()
        var step = 0
        uedaVault().use { index ->
            val result = runAgent(index, "請介紹植田和男", ChatClient { request ->
                calls += request
                when (step++) {
                    0 -> """{"action":"tool","tool":"wiki.search","query":"植田和男"}"""
                    1 -> """{"action":"tool","tool":"wiki.read_page","path":"wiki/entities/植田和男.md"}"""
                    2 -> """{"action":"final","answer":"植田和男是日本銀行總裁 [1]。"}"""
                    else -> """{"action":"final","answer":"植田和男是日本銀行總裁 [1]。"}"""
                }
            })
            val answer = assertIs<ChatAgentResult.Answer>(result)
            assertEquals("wiki/entities/植田和男.md", answer.references.first().path)
            assertEquals(listOf(1), answer.cited)
            assertEquals(answer.references.map { it.path }, answer.shown.map { it.path })
            assertTrue(answer.shown.any { it.path.startsWith("wiki/sources/") })
            assertEquals(4, calls.size)
            assertEquals(4, answer.llmCalls)
            assertTrue(calls.all { it.json })
            assertTrue(calls[2].user.contains("日本銀行總裁"))
            assertTrue(calls[2].user.contains("連結："))
            assertTrue(calls[3].user.contains("仍有未讀來源"))
            assertTrue(!calls[2].user.contains("RAW_SECRET_MARKER"))
            assertTrue(!calls[2].user.contains("This folder is an llm-wiki tree"))
        }
    }

    @Test
    fun agentAcceptsFinalWhenRetrievalBudgetIsFull() {
        val calls = mutableListOf<ChatRequest>()
        var step = 0
        uedaVault().use { index ->
            val result = runAgent(index, "請介紹植田和男", ChatClient { request ->
                calls += request
                when (step++) {
                    0 -> """{"action":"tool","tool":"wiki.search","query":"植田和男"}"""
                    1, 2, 3 -> """{"action":"tool","tool":"wiki.read_page","path":"wiki/entities/植田和男.md"}"""
                    else -> """{"action":"final","answer":"植田和男是日本銀行總裁 [1]。"}"""
                }
            })
            val answer = assertIs<ChatAgentResult.Answer>(result)
            assertEquals("植田和男是日本銀行總裁 [1]。", answer.text)
            assertEquals(5, calls.size)
            assertTrue(calls.none { it.user.contains("仍有未讀來源") })
        }
    }

    @Test
    fun searchLinksIncludeFrontmatterSources() {
        val root = scaffoldWiki(tempDir())
        writePage(
            root,
            "wiki/entities/植田和男.md",
            "植田和男",
            "entity",
            "日本銀行總裁。",
            sources = listOf("日圓匯率新聞"),
        )
        WikiIndex.open(root, tempDir().resolve("sources.sqlite")).use { index ->
            index.reconcileAll()
            val hit = WikiSearch(index).search("植田和男").hits.first()
            assertTrue(hit.links.contains("日圓匯率新聞"), hit.links)
        }
    }

    @Test
    fun agentRecoversWhenModelUsesWholeSentence() {
        uedaVault().use { index ->
            var step = 0
            val result = runAgent(index, "請介紹植田和男", ChatClient {
                when (step++) {
                    0 -> """{"action":"tool","tool":"wiki.search","query":"請介紹植田和男"}"""
                    else -> """{"action":"final","answer":"植田和男是日本銀行總裁 [1]。"}"""
                }
            })
            val answer = assertIs<ChatAgentResult.Answer>(result)
            assertEquals("wiki/entities/植田和男.md", answer.references.first().path)
        }
    }

    @Test
    fun agentRejectsFinalBeforeAnyRetrieval() {
        val queries = mutableListOf<String>()
        uedaVault().use { index ->
            var step = 0
            val result = runAgent(index, "請介紹植田和男", ChatClient {
                when (step++) {
                    0 -> """{"action":"final","answer":"太早"}"""
                    else -> """{"action":"final","answer":"植田和男是日本銀行總裁 [1]。"}"""
                }
            }, onSearch = { queries += it })
            val answer = assertIs<ChatAgentResult.Answer>(result)
            assertEquals("請介紹植田和男", queries.single())
            assertEquals("植田和男是日本銀行總裁 [1]。", answer.text)
        }
    }

    @Test
    fun agentForcesFinalOnDuplicateQueryAndBudget() {
        uedaVault().use { index ->
            val duplicateCalls = mutableListOf<ChatRequest>()
            var searches = 0
            runAgent(index, "請介紹植田和男", ChatClient { request ->
                duplicateCalls += request
                if (request.system.contains("不能再用工具")) {
                    """{"action":"final","answer":"植田和男 [1]。"}"""
                } else {
                    """{"action":"tool","tool":"wiki.search","query":"植田和男"}"""
                }
            }, onSearch = { searches += 1 })
            assertTrue(duplicateCalls.last().system.contains("不能再用工具"))
            assertEquals(1, searches)

            val budgetCalls = mutableListOf<ChatRequest>()
            var budgetSearches = 0
            var n = 0
            runAgent(index, "請介紹植田和男", ChatClient { request ->
                budgetCalls += request
                if (request.system.contains("不能再用工具")) {
                    """{"action":"final","answer":"沒有"}"""
                } else {
                    n += 1
                    """{"action":"tool","tool":"wiki.search","query":"${listOf("植田和男", "日本銀行", "總裁", "介紹")[n - 1]}"}"""
                }
            }, onSearch = { budgetSearches += 1 })
            assertTrue(budgetCalls.size <= 7)
            assertTrue(budgetSearches <= 3)
            assertTrue(budgetCalls.any { it.system.contains("不能再用工具") })
        }
    }

    @Test
    fun readPageRejectsUnsafePaths() {
        uedaVault().use { index ->
            val tools = WikiTools(index)
            val refs = listOf(
                ChatReference(1, "wiki/entities/植田和男.md", "植田和男", "entity", "", "wiki", false),
            )
            val byNumber = normalizeReadPath("[1]", refs) { emptyList() }
            assertEquals("wiki/entities/植田和男.md", (byNumber as com.neojou.mystudy.wiki.agent.ReadPath.Ok).path)
            val byTitle = normalizeReadPath("植田和男", refs, index::pathsForLooseAlias)
            assertEquals("wiki/entities/植田和男.md", (byTitle as com.neojou.mystudy.wiki.agent.ReadPath.Ok).path)
            for (unsafe in listOf("raw/sources/x.md", "../x.md", "/etc/passwd", "wiki/schema.md", "wiki/review.md")) {
                val observed = tools.read(unsafe, refs)
                assertTrue(observed.readPath == null, unsafe)
                assertTrue(observed.text.contains("error"), observed.text)
            }
            val missing = tools.read("wiki/entities/沒有.md", refs)
            assertTrue(missing.text.contains("not found"))
        }
    }

    @Test
    fun agentReturnsNoMatchWithoutFinalCall() {
        val calls = mutableListOf<ChatRequest>()
        uedaVault().use { index ->
            var step = 0
            val result = runAgent(index, "火星總裁是誰", ChatClient { request ->
                calls += request
                when (step++) {
                    0 -> """{"action":"tool","tool":"wiki.search","query":"zzqqnomatch"}"""
                    1 -> """{"action":"tool","tool":"wiki.search","query":"yyqqnomatch"}"""
                    else -> """{"action":"final","answer":"wiki 裡沒有"}"""
                }
            })
            assertIs<ChatAgentResult.NoMatch>(result)
            assertTrue(calls.none { it.system.contains("不能再用工具") })
        }
    }

    @Test
    fun agentNotCalledOnMissingPromptOrTinyContext() {
        val dir = tempDir().resolve("ai-prompts")
        Files.createDirectories(dir)
        dir.resolve("chat-agent.md").writeText("--- system ---\n只有 system {{schema}}\n")
        uedaVault().use { index ->
            var calls = 0
            val missing = runAgent(index, "請介紹植田和男", ChatClient { calls += 1; "no" }, prompts = dir)
            assertIs<ChatAgentResult.NotCalled>(missing)
            assertEquals(0, calls)
            val tiny = runAgent(index, "請介紹植田和男", ChatClient { calls += 1; "no" }, numCtx = 4096)
            assertIs<ChatAgentResult.NotCalled>(tiny)
            assertEquals(0, calls)
        }
    }

    @Test
    fun searchLogLineFormat() {
        val hits = List(7) { index ->
            SearchHit("p$index", "t", "entity", "s", if (index < 5) "wiki" else "graph", 1.0, index == 0)
        }
        val outcome = SearchOutcome(hits, tokenHits = 11, nameHits = 1, vectorHits = 0, graphHits = 2, mode = "hybrid")
        val line = formatSearchLog(outcome, 7, listOf("wiki/entities/植田和男.md", "wiki/entities/日本銀行.md"))
        assertEquals(
            "wiki.search: 7 result(s), 7 new, mode=hybrid, tokenHits=11, nameHits=1, vectorHits=0, graphHits=2, top=[wiki/entities/植田和男.md, wiki/entities/日本銀行.md]",
            line,
        )
    }

    @Test
    fun invalidJsonTwiceForcesFinal() {
        val calls = mutableListOf<ChatRequest>()
        uedaVault().use { index ->
            var step = 0
            val result = runAgent(index, "請介紹植田和男", ChatClient { request ->
                calls += request
                when (step++) {
                    0 -> """{"action":"tool","tool":"wiki.search","query":"植田和男"}"""
                    1 -> "not json"
                    2 -> "still not"
                    else -> """{"action":"final","answer":"植田和男是日本銀行總裁 [1]。"}"""
                }
            })
            assertIs<ChatAgentResult.Answer>(result)
            assertTrue(calls.last().system.contains("不能再用工具"))
        }
    }
}

private fun runAgent(
    index: WikiIndex,
    question: String,
    client: ChatClient,
    numCtx: Int = 65536,
    prompts: Path? = null,
    onSearch: (String) -> Unit = {},
): ChatAgentResult = runBlocking {
    val schema = schemaExcerpt(index.load("wiki/schema.md")?.body)
    val tools = WikiTools(index)
    ChatAgent(
        client = client,
        tools = { action, refs ->
            when (action) {
                is com.neojou.mystudy.wiki.agent.AgentAction.Search -> {
                    onSearch(action.query)
                    ToolObservation(text = "", outcome = tools.search(action.query))
                }
                is com.neojou.mystudy.wiki.agent.AgentAction.ReadPage -> tools.read(action.path, refs)
                else -> ToolObservation("unsupported")
            }
        },
        promptDirectory = prompts,
    ).run(question, numCtx, emptyList(), schema)
}

private fun uedaVault(): WikiIndex {
    val root = scaffoldWiki(tempDir())
    writePage(root, "wiki/entities/植田和男.md", "植田和男", "entity", "日本銀行總裁。見 [[日本銀行]]。")
    writePage(root, "wiki/entities/日本銀行.md", "日本銀行", "entity", "日本的中央銀行。")
    writePage(root, "wiki/sources/boj-2023.md", "2023", "source", "見 [[植田和男]] 與 [[日本銀行]]。")
    for (number in 1..45) {
        val stem = "c%02d".format(number)
        writePage(root, "wiki/concepts/$stem.md", stem, "concept", "介紹")
    }
    root.resolve("raw").resolve("sources").resolve("x.md").writeText("RAW_SECRET_MARKER\n")
    val index = WikiIndex.open(root, tempDir().resolve("chat.sqlite"))
    index.reconcileAll()
    return index
}

private fun writePage(
    root: Path,
    relative: String,
    title: String,
    type: String,
    body: String,
    sources: List<String> = emptyList(),
) {
    val file = root.resolve(relative)
    Files.createDirectories(file.parent)
    val sourceList = sources.joinToString(", ") { "\"$it\"" }
    file.writeText(
        """
        ---
        title: "$title"
        type: $type
        tags: []
        aliases: []
        sources: [$sourceList]
        updated: "2026-10-08"
        ---

        $body
        """.trimIndent() + "\n",
    )
}

private fun tempDir(): Path = Files.createTempDirectory("mystudy-chat")
