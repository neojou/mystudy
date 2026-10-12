package com.neojou.mystudy.wiki

import com.neojou.mystudy.AppVersion
import com.neojou.mystudy.wiki.ingest.IngestLanguage
import com.neojou.mystudy.wiki.markdown.appendReviews
import com.neojou.mystudy.wiki.model.ReviewProposal
import com.neojou.mystudy.wiki.ollama.ChatRequest
import com.neojou.mystudy.wiki.ollama.OllamaClient
import com.neojou.mystudy.wiki.prompt.locateAiPrompts
import com.neojou.mystudy.wiki.research.MIN_SYNTHESIS_CHARS
import com.neojou.mystudy.wiki.research.ResearchPhase
import com.neojou.mystudy.wiki.research.ResearchResult
import com.neojou.mystudy.wiki.research.ResearchRunner
import com.neojou.mystudy.wiki.research.ReviewEntry
import com.neojou.mystudy.wiki.research.SearxngClient
import com.neojou.mystudy.wiki.research.SearxngException
import com.neojou.mystudy.wiki.research.SearxngHit
import com.neojou.mystudy.wiki.research.SearxngStatus
import com.neojou.mystudy.wiki.research.allocateResearchPath
import com.neojou.mystudy.wiki.research.canResearch
import com.neojou.mystudy.wiki.research.mergeSearxngHits
import com.neojou.mystudy.wiki.research.normalizeHttpUrl
import com.neojou.mystudy.wiki.research.parseReviewFile
import com.neojou.mystudy.wiki.research.removeReviewBlock
import com.neojou.mystudy.wiki.research.researchQueries
import com.neojou.mystudy.wiki.research.researchSlug
import com.neojou.mystudy.wiki.research.writeResearch
import com.neojou.mystudy.wiki.settings.AppSettings
import com.neojou.mystudy.wiki.settings.MemorySettingsStore
import com.neojou.mystudy.wiki.vault.assertWikiWrite
import com.neojou.mystudy.wiki.vault.scaffoldWiki
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.Executors
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeepResearchBehaviorTest {
    private val day = LocalDate.of(2026, 10, 12)

    @Test
    fun appendReviewsRoundTripsAndRemovalKeepsOtherBytes() {
        val reviews = listOf(
            ReviewProposal(
                id = "1",
                type = "suggestion",
                title = "甲",
                body = "說明\nOPTIONS: Create Page | Skip\nSEARCH: 一 | 二\nSEARCH: 一\nSEARCH: 丙\nSEARCH: 丁",
                options = listOf("Create Page", "Skip"),
                searches = listOf("一 | 二", "一", "丙", "丁"),
            ),
            ReviewProposal(
                id = "2",
                type = "duplicate",
                title = "乙",
                body = "另一段\n## 內文標題\n還在",
                options = emptyList(),
                searches = emptyList(),
            ),
        )
        val written = appendReviews("# Review\n\n這段不要動\n", day, reviews, "Zettelkasten/Inbox/卡片盒筆記法.md")
        val parsed = parseReviewFile(written)
        assertEquals(2, parsed.size)
        assertEquals("suggestion", parsed[0].type)
        assertEquals("甲", parsed[0].title)
        assertEquals("2026-10-12", parsed[0].date)
        assertEquals("Zettelkasten/Inbox/卡片盒筆記法.md", parsed[0].source)
        assertEquals("說明", parsed[0].description)
        assertEquals(listOf("一", "二", "丙"), parsed[0].searchQueries)
        assertEquals(listOf("一", "二", "丙"), researchQueries(parsed[0]))
        assertTrue(parsed[1].description.contains("另一段"))
        assertTrue(parsed[1].description.contains("## 內文標題"))
        assertTrue(parsed[1].rawBlock.contains("## 內文標題"))

        val removed = removeReviewBlock(written, parsed[0].rawBlock)
        assertTrue(removed.contains("這段不要動"))
        assertTrue(removed.contains("# Review"))
        val again = parseReviewFile(removed)
        assertEquals(1, again.size)
        assertEquals(parsed[1].rawBlock, again[0].rawBlock)

        val doubled = written + parsed[0].rawBlock
        assertFailsWith<IllegalStateException> { removeReviewBlock(doubled, parsed[0].rawBlock) }
        assertFailsWith<IllegalStateException> { removeReviewBlock(written, parsed[0].rawBlock + "NOPE") }
        assertTrue(removeReviewBlock(written, parsed[0].rawBlock).contains(parsed[1].rawBlock))
    }

    @Test
    fun blankSourceAndTitleFallbackAndCrlfStayIntact() {
        val noSearch = ReviewProposal("1", "missing-page", "Create 卡片盒", "只有說明", emptyList(), emptyList())
        val parsed = parseReviewFile(appendReviews("", day, listOf(noSearch), "")).single()
        assertEquals("", parsed.source)
        assertEquals(listOf("卡片盒"), researchQueries(parsed))

        val bare = ReviewProposal("1", "suggestion", "Create", "第一行說明\nOPTIONS: Skip", emptyList(), emptyList())
        assertEquals(listOf("第一行說明"), researchQueries(parseReviewFile(appendReviews("# Review\n", day, listOf(bare), "")).single()))

        val crlf = appendReviews("# Review\r\n", day, listOf(noSearch, ReviewProposal("2", "duplicate", "乙", "另一段", emptyList(), emptyList())), "notes/乙.md")
        assertFalse(crlf.replace("\r\n", "").contains("\n"))
        val entries = parseReviewFile(crlf)
        val kept = removeReviewBlock(crlf, entries[0].rawBlock)
        assertFalse(kept.replace("\r\n", "").contains("\n"))
        assertEquals(entries[1].rawBlock, parseReviewFile(kept).single().rawBlock)
    }

    @Test
    fun extraBlankLinesAroundACutCollapseToTwo() {
        val block = "## [2026-10-12] suggestion | 甲\n\nbody\n"
        val tail = "## [2026-10-12] duplicate | 乙\n\nkeep\n"
        val text = "head\n\n\n\n\n$block\n\n\n$tail"
        val removed = removeReviewBlock(text, block)
        assertEquals("head\n\n\n$tail", removed)
    }

    @Test
    fun slugKeepsCjkAndAllocateUsesNumericSuffix() {
        assertEquals("hello-world", researchSlug("Hello:World"))
        assertEquals("卡片盒-notes", researchSlug("卡片盒 Notes"))
        assertEquals("untitled", researchSlug("???"))
        assertEquals(60, researchSlug("a".repeat(80)).length)
        val slug = "卡片盒"
        val first = "wiki/queries/research-$slug-2026-10-12.md"
        assertEquals(first, allocateResearchPath(slug, day) { false })
        assertEquals(
            "wiki/queries/research-$slug-2026-10-12-2.md",
            allocateResearchPath(slug, day) { it == first },
        )
        assertFailsWith<IllegalStateException> { allocateResearchPath(slug, day) { true } }
    }

    @Test
    fun writeGuardAllowsResearchPagesAndStillBlocksSchemaAndRaw() {
        val root = Files.createTempDirectory("research-guard")
        val allowed = assertWikiWrite(root, "wiki/queries/research-a.md")
        assertTrue(allowed.endsWith("wiki/queries/research-a.md"))
        assertFailsWith<IllegalStateException> { assertWikiWrite(root, "wiki/schema.md") }
        assertFailsWith<IllegalStateException> { assertWikiWrite(root, "raw/note.md") }
    }

    @Test
    fun blankSearxngUrlRestoresDefaultAndOtherHostsAreRejected() {
        val app = AppSettings(MemorySettingsStore())
        assertEquals(AppSettings.DEFAULT_SEARXNG_URL, app.load().searxngUrl)
        app.save(app.load().copy(searxngUrl = "  "))
        assertEquals(AppSettings.DEFAULT_SEARXNG_URL, app.load().searxngUrl)
        app.save(app.load().copy(searxngUrl = "http://LOCALHOST:9090/"))
        assertEquals("http://localhost:9090", app.load().searxngUrl)
        app.save(app.load().copy(searxngUrl = "https://127.0.0.1:8080"))
        assertEquals("https://127.0.0.1:8080", app.load().searxngUrl)
        assertFailsWith<IllegalArgumentException> {
            app.save(app.load().copy(searxngUrl = "http://example.com:8080"))
        }
        assertEquals("https://127.0.0.1:8080", app.load().searxngUrl)
        assertFailsWith<IllegalArgumentException> {
            AppSettings.normalizeSearxngUrl("http://127.0.0.1:8080/search")
        }
        assertFailsWith<SearxngException> { SearxngClient("http://10.0.0.1:8080").testConnection() }
    }

    @Test
    fun canResearchRequiresOkSearxngAnOpenRootAndNoRun() {
        val ok = SearxngStatus.Ok(1, 3)
        assertFalse(canResearch(SearxngStatus.Unknown, ResearchPhase.Idle, true, true))
        assertFalse(canResearch(SearxngStatus.Testing, ResearchPhase.Idle, true, true))
        assertFalse(canResearch(SearxngStatus.Failed("down"), ResearchPhase.Idle, true, true))
        assertFalse(canResearch(ok, ResearchPhase.Searching, true, true))
        assertFalse(canResearch(ok, ResearchPhase.Synthesizing, true, true))
        assertFalse(canResearch(ok, ResearchPhase.Saving, true, true))
        assertFalse(canResearch(ok, ResearchPhase.Idle, false, true))
        assertFalse(canResearch(ok, ResearchPhase.Idle, true, false))
        assertTrue(canResearch(ok, ResearchPhase.Idle, true, true))
        assertTrue(canResearch(ok, ResearchPhase.Done, true, true))
        assertTrue(canResearch(ok, ResearchPhase.Failed, true, true))
    }

    @Test
    fun searxngParsesResultsCapsAndDropsNonHttp() {
        withSearchServer({ exchange ->
            val query = decodedQuery(exchange)
            assertEquals("mystudy/${AppVersion.NAME}", exchange.requestHeaders.getFirst("User-Agent"))
            assertTrue(query.contains("format=json"))
            assertTrue(query.contains("categories=general"))
            assertTrue(query.contains("language=auto"))
            assertFalse(query.contains("VAULTSECRET"))
            val name = query.substringAfter("q=").substringBefore("&")
            val body = when (name) {
                "one" -> results(
                    "http://Example.com/a#frag" to "A",
                    "http://example.com/a?x=1" to "A query",
                    "file:///tmp/a" to "file",
                    "ftp://example.com/a" to "ftp",
                    "http://example.com/b" to "B",
                )
                "two" -> results(
                    "HTTP://example.com/a" to "dup",
                    "http://example.com/c" to "C",
                )
                "many" -> results(*(1..8).map { "http://example.com/m$it" to "M$it" }.toTypedArray())
                else -> results(*(1..6).map { "http://example.com/$name$it" to name }.toTypedArray())
            }
            exchange.respond(200, body)
        }) { port ->
            val client = SearxngClient("http://127.0.0.1:$port")
            val merged = client.searchAll(listOf("one", "two"))
            assertEquals(
                listOf("http://Example.com/a#frag", "http://example.com/a?x=1", "http://example.com/b", "http://example.com/c"),
                merged.map { it.url },
            )
            assertEquals(5, client.search("many").size)
            assertNull(normalizeHttpUrl("file:///tmp/a"))
            assertEquals("http://example.com/a", normalizeHttpUrl("http://Example.com/a#frag"))
            val capped = mergeSearxngHits(
                (1..10).map { SearxngHit("t", "http://example.com/p$it", "", "") },
                (11..20).map { SearxngHit("t", "http://example.com/p$it", "", "") },
            )
            assertEquals(15, capped.size)
            val four = client.searchAll(listOf("a", "b", "c", "d"))
            assertEquals(15, four.size)
        }
    }

    @Test
    fun connectionTestAcceptsEmptyResultsAndReportsFailures() {
        withSearchServer({ exchange -> exchange.respond(200, """{"results":[]}""") }) { port ->
            val ok = SearxngClient("http://127.0.0.1:$port").testConnection()
            assertEquals(0, ok.count)
        }
        withSearchServer({ exchange -> exchange.respond(200, """{"results":[{"title":"T","url":"http://example.com","content":"s","engine":"g"}]}""") }) { port ->
            val hits = SearxngClient("http://127.0.0.1:$port").search("test")
            assertEquals("T", hits.single().title)
            assertEquals("s", hits.single().snippet)
            assertEquals("g", hits.single().engine)
        }
        withSearchServer({ exchange -> exchange.respond(403, "disabled") }) { port ->
            val message = assertFailsWith<SearxngException> {
                SearxngClient("http://127.0.0.1:$port").testConnection()
            }.message.orEmpty()
            assertTrue(message.contains("JSON format is not enabled"))
            assertTrue(message.contains("search.formats"))
        }
        withSearchServer({ exchange -> exchange.respond(200, "hello") }) { port ->
            val message = assertFailsWith<SearxngException> {
                SearxngClient("http://127.0.0.1:$port").testConnection()
            }.message.orEmpty()
            assertEquals("SearXNG did not return JSON.", message)
        }
        withSearchServer({ exchange -> exchange.respond(500, "nope") }) { port ->
            val message = assertFailsWith<SearxngException> {
                SearxngClient("http://127.0.0.1:$port").testConnection()
            }.message.orEmpty()
            assertEquals("SearXNG HTTP 500.", message)
        }
        withSearchServer({ exchange ->
            try {
                Thread.sleep(3_000)
                exchange.respond(200, """{"results":[]}""")
            } catch (_: Exception) {
            }
        }) { port ->
            val message = assertFailsWith<SearxngException> {
                SearxngClient("http://127.0.0.1:$port", timeout = Duration.ofMillis(300)).testConnection()
            }.message.orEmpty()
            assertEquals("SearXNG timed out.", message)
        }
        val refused = assertFailsWith<SearxngException> {
            SearxngClient("http://127.0.0.1:1", timeout = Duration.ofSeconds(2)).testConnection()
        }
        assertEquals("SearXNG is not reachable.", refused.message)
    }

    @Test
    fun ollamaStreamReadsNdjsonAndCompleteStaysUnstreamed() {
        val bodies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/chat") { exchange ->
            val request = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            bodies += request
            val stream = request.contains("\"stream\":true")
            val bytes = if (stream) {
                """
                {"message":{"content":"abc"},"done":false}
                {"message":{"content":"def"},"done":true}
                """.trimIndent().toByteArray()
            } else {
                """{"message":{"content":"plain"}}""".toByteArray()
            }
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val client = OllamaClient("http://127.0.0.1:${server.address.port}/v1", "test-model", 8192)
            val deltas = mutableListOf<String>()
            val streamed = client.stream(ChatRequest("sys", "user", json = false)) { deltas += it }
            val completed = client.complete(ChatRequest("sys", "user", json = false))
            assertEquals("abcdef", streamed)
            assertEquals(listOf("abc", "def"), deltas)
            assertEquals("plain", completed)
            assertTrue(bodies[0].contains("\"stream\":true"))
            assertTrue(bodies[0].contains("\"think\":false"))
            assertFalse(bodies[0].contains("\"format\""))
            assertTrue(bodies[1].contains("\"stream\":false"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun missingPromptAndSmallContextDoNotSearchOrWrite() {
        val root = wikiRoot()
        val review = writeReviews(root)
        val before = snapshot(root)
        var searches = 0
        var models = 0
        val missing = runner(
            prompts = Files.createTempDirectory("no-prompts"),
            search = { searches += 1; emptyList() },
            stream = { _, _ -> models += 1; synthesis(1) },
        ).run(root, review, "index", { false }, { true }, { true }, {})
        assertIs<ResearchResult.Failed>(missing)
        assertTrue(missing.message.contains("deep-research.md"))
        assertTrue(missing.message.contains("Nothing was written."))
        assertEquals(0, searches)
        assertEquals(0, models)
        assertEquals(before, snapshot(root))

        searches = 0
        models = 0
        val small = runner(
            numCtx = 4096,
            search = { searches += 1; listOf(hit("http://example.com/a")) },
            stream = { _, _ -> models += 1; synthesis(1) },
        ).run(root, review, "index", { false }, { true }, { true }, {})
        assertIs<ResearchResult.Failed>(small)
        assertTrue(small.message.contains("Nothing was written."))
        assertEquals(0, searches)
        assertEquals(0, models)
        assertEquals(before, snapshot(root))
    }

    @Test
    fun zeroHitsAndInvalidSynthesisWriteNothing() {
        val root = wikiRoot()
        val review = writeReviews(root)
        val before = snapshot(root)
        var models = 0
        val empty = run(root, review, hits = emptyList(), answer = synthesis(1), models = { models += 1 })
        assertIs<ResearchResult.Failed>(empty)
        assertEquals("沒有搜尋結果", empty.message)
        assertEquals(0, models)
        assertEquals(before, snapshot(root))

        models = 0
        val noCite = run(root, review, hits = listOf(hit("http://example.com/a")), answer = "補".repeat(90), models = { models += 1 })
        assertIs<ResearchResult.Failed>(noCite)
        assertEquals(1, models)
        assertEquals(before, snapshot(root))

        val ranged = run(root, review, hits = listOf(hit("http://example.com/a")), answer = synthesis(1, 9))
        assertIs<ResearchResult.Failed>(ranged)
        assertEquals(before, snapshot(root))

        val short = run(root, review, hits = listOf(hit("http://example.com/a")), answer = "短[1]")
        assertIs<ResearchResult.Failed>(short)
        assertEquals(before, snapshot(root))
    }

    @Test
    fun successWritesPageIndexLogThenRemovesOnlyThatReview() {
        val root = wikiRoot()
        val review = writeReviews(root)
        val other = parseReviewFile(root.resolve("wiki/review.md").readText())[1].rawBlock
        val schemaBefore = root.resolve("wiki/schema.md").readBytes()
        val requests = mutableListOf<ChatRequest>()
        val queries = mutableListOf<String>()
        val result = run(
            root,
            review,
            hits = listOf(hit("http://example.com/a", "甲"), hit("http://example.com/b", "乙")),
            answer = synthesis(2),
            queries = queries,
            requests = requests,
            indexExcerpt = "INDEXMARKER" + "i".repeat(8_000),
            numCtx = 10_000,
        )
        val done = assertIs<ResearchResult.Done>(result)
        assertTrue(done.path.endsWith("wiki/queries/research-卡片盒-2026-10-12.md"))
        val page = root.resolve(done.path).readText()
        assertTrue(page.contains("type: query"))
        assertTrue(page.contains("origin: deep-research"))
        assertTrue(page.contains("http://example.com/b"))
        assertFalse(page.contains("http://example.com/a"))
        assertTrue(page.contains("\n2. ["))
        assertFalse(page.contains("\n1. ["))
        val index = root.resolve("wiki/index.md").readText()
        assertTrue(index.contains("[[queries/research-卡片盒-2026-10-12]]"))
        val log = root.resolve("wiki/log.md").readText()
        assertTrue(log.contains("deep-research | 卡片盒"))
        assertTrue(log.contains(done.path))
        val left = parseReviewFile(root.resolve("wiki/review.md").readText())
        assertEquals(1, left.size)
        assertEquals(other, left[0].rawBlock)
        assertTrue(schemaBefore.contentEquals(root.resolve("wiki/schema.md").readBytes()))
        assertEquals(listOf("卡片"), queries)
        assertFalse(queries.any { it.contains("VAULTSECRET") })
        val request = requests.single()
        assertFalse(request.json)
        assertTrue(request.system.contains(IngestLanguage.NAME))
        assertFalse(request.system.contains("這段前言不會送給模型"))
        assertTrue(request.user.contains("VAULTSECRET"))
        assertFalse(request.user.contains("INDEXMARKER"))
        assertTrue(request.user.contains("SNIPPETMARKER"))
    }

    @Test
    fun existingPageUsesSuffixAndEarlierWriteFailuresLeaveTheReview() {
        val root = wikiRoot()
        val review = writeReviews(root)
        val first = root.resolve("wiki/queries/research-卡片盒-2026-10-12.md")
        first.writeText("OLD")
        val old = first.readBytes()
        val result = run(root, review, hits = listOf(hit("http://example.com/a")), answer = synthesis(1))
        val done = assertIs<ResearchResult.Done>(result)
        assertTrue(done.path.endsWith("-2.md"))
        assertTrue(old.contentEquals(first.readBytes()))

        val mismatchRoot = wikiRoot()
        val entry = writeReviews(mismatchRoot)
        val reviewBefore = mismatchRoot.resolve("wiki/review.md").readBytes()
        val mismatch = run(
            mismatchRoot,
            entry.copy(rawBlock = entry.rawBlock + "NOPE"),
            hits = listOf(hit("http://example.com/a")),
            answer = synthesis(1),
        )
        assertIs<ResearchResult.Failed>(mismatch)
        assertTrue(mismatch.message.contains("review.md changed. Refresh and try again."))
        assertTrue(reviewBefore.contentEquals(mismatchRoot.resolve("wiki/review.md").readBytes()))
        assertTrue(Files.isRegularFile(mismatchRoot.resolve("wiki/queries/research-卡片盒-2026-10-12.md")))
        assertTrue(mismatchRoot.resolve("wiki/index.md").readText().contains("queries/research-卡片盒"))
        assertTrue(mismatchRoot.resolve("wiki/log.md").readText().contains("deep-research"))

        val indexRoot = wikiRoot()
        val indexEntry = writeReviews(indexRoot)
        val logBefore = indexRoot.resolve("wiki/log.md").readBytes()
        val reviewBytes = indexRoot.resolve("wiki/review.md").readBytes()
        Files.delete(indexRoot.resolve("wiki/index.md"))
        Files.createDirectory(indexRoot.resolve("wiki/index.md"))
        val indexStopped = run(indexRoot, indexEntry, hits = listOf(hit("http://example.com/a")), answer = synthesis(1))
        assertIs<ResearchResult.Failed>(indexStopped)
        assertTrue(indexStopped.message.contains("wiki/index.md"))
        assertTrue(Files.isRegularFile(indexRoot.resolve("wiki/queries/research-卡片盒-2026-10-12.md")))
        assertTrue(Files.isDirectory(indexRoot.resolve("wiki/index.md")))
        assertTrue(logBefore.contentEquals(indexRoot.resolve("wiki/log.md").readBytes()))
        assertTrue(reviewBytes.contentEquals(indexRoot.resolve("wiki/review.md").readBytes()))

        val logRoot = wikiRoot()
        val logEntry = writeReviews(logRoot)
        val reviewLog = logRoot.resolve("wiki/review.md").readBytes()
        Files.delete(logRoot.resolve("wiki/log.md"))
        Files.createDirectory(logRoot.resolve("wiki/log.md"))
        val logStopped = run(logRoot, logEntry, hits = listOf(hit("http://example.com/a")), answer = synthesis(1))
        assertIs<ResearchResult.Failed>(logStopped)
        assertTrue(logStopped.message.contains("wiki/log.md"))
        assertTrue(logRoot.resolve("wiki/index.md").readText().contains("queries/research-卡片盒"))
        assertTrue(Files.isDirectory(logRoot.resolve("wiki/log.md")))
        assertTrue(reviewLog.contentEquals(logRoot.resolve("wiki/review.md").readBytes()))
    }

    @Test
    fun fenceOrFrontmatterIsUnwrappedOnceAndPromptDirectoryDoesNotRequireResearchFile() {
        val root = wikiRoot()
        val review = writeReviews(root)
        val fenced = run(root, review, hits = listOf(hit("http://example.com/a")), answer = "```\n${synthesis(1)}\n```")
        assertIs<ResearchResult.Done>(fenced)

        val other = wikiRoot()
        val second = writeReviews(other)
        val front = "---\ntitle: \"nope\"\n---\n${synthesis(1)}"
        val unwrapped = run(other, second, hits = listOf(hit("http://example.com/a")), answer = front)
        assertIs<ResearchResult.Done>(unwrapped)

        val parent = Files.createTempDirectory("prompts-home")
        val prompts = Files.createDirectories(parent.resolve("ai-prompts"))
        Files.writeString(prompts.resolve("ask.md"), "x")
        Files.writeString(prompts.resolve("ingest-analyze.md"), "x")
        Files.writeString(prompts.resolve("ingest-generate.md"), "x")
        assertEquals(prompts.toAbsolutePath().normalize(), locateAiPrompts(parent).toAbsolutePath().normalize())
    }

    @Test
    fun oversizedSnippetsAreShortenedBeforeTheModelCall() {
        val root = wikiRoot()
        val review = writeReviews(root)
        val requests = mutableListOf<ChatRequest>()
        val result = run(
            root,
            review,
            hits = listOf(
                hit("http://example.com/a", snippet = "keep-head"),
                hit("http://example.com/b", snippet = "s".repeat(20_000) + "TAILMARKER"),
            ),
            answer = synthesis(1),
            requests = requests,
            numCtx = 12_000,
        )
        assertIs<ResearchResult.Done>(result)
        val user = requests.single().user
        assertTrue(user.contains("keep-head"))
        assertFalse(user.contains("TAILMARKER"))
    }

    private fun run(
        root: Path,
        entry: ReviewEntry,
        hits: List<SearxngHit>,
        answer: String,
        models: () -> Unit = {},
        queries: MutableList<String>? = null,
        requests: MutableList<ChatRequest>? = null,
        indexExcerpt: String = "index",
        numCtx: Int = 65536,
    ): ResearchResult {
        var calls = 0
        return runner(
            numCtx = numCtx,
            search = { query ->
                queries?.add(query)
                hits
            },
            stream = { request, onDelta ->
                calls += 1
                models()
                requests?.add(request)
                onDelta(answer)
                answer
            },
        ).run(root, entry, indexExcerpt, { false }, { true }, { true }, {})
            .also { if (hits.isEmpty()) assertEquals(0, calls) }
    }

    private fun runner(
        prompts: Path = locateAiPrompts(),
        numCtx: Int = 65536,
        search: (String) -> List<SearxngHit>,
        stream: (ChatRequest, (String) -> Unit) -> String,
    ) = ResearchRunner(
        prompts = prompts,
        numCtx = numCtx,
        today = day,
        search = search,
        stream = stream,
    )

    private fun wikiRoot(): Path {
        val vault = Files.createTempDirectory("research-vault")
        return scaffoldWiki(vault)
    }

    private fun writeReviews(root: Path): ReviewEntry {
        val reviews = listOf(
            ReviewProposal(
                "1",
                "suggestion",
                "卡片盒",
                "VAULTSECRET\nSEARCH: 卡片",
                emptyList(),
                listOf("卡片"),
            ),
            ReviewProposal("2", "duplicate", "別段", "保留這段", emptyList(), emptyList()),
        )
        val text = appendReviews("", day, reviews, "Zettelkasten/Inbox/卡片盒筆記法.md")
        root.resolve("wiki/review.md").writeText(text)
        return parseReviewFile(text).first()
    }

    private fun snapshot(root: Path): Map<String, String> {
        val names = listOf("wiki/index.md", "wiki/log.md", "wiki/schema.md", "wiki/review.md")
        return names.associateWith { root.resolve(it).readText() }
    }

    private fun hit(url: String, title: String = "來源", snippet: String = "SNIPPETMARKER") =
        SearxngHit(title, url, snippet, "test")

    private fun synthesis(vararg marks: Int): String {
        val core = "研究綜整。" + marks.joinToString("") { "[$it]" }
        return core.padEnd(MIN_SYNTHESIS_CHARS, '補')
    }

    private fun results(vararg rows: Pair<String, String>): String {
        val items = rows.joinToString(",") { (url, title) ->
            """{"title":"$title","url":"$url","content":"snip","engine":"test"}"""
        }
        return """{"results":[$items]}"""
    }

    private fun decodedQuery(exchange: HttpExchange): String =
        URLDecoder.decode(exchange.requestURI.rawQuery ?: "", StandardCharsets.UTF_8)

    private fun withSearchServer(handler: (HttpExchange) -> Unit, block: (Int) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val pool = Executors.newCachedThreadPool()
        server.executor = pool
        server.createContext("/search") { exchange ->
            try {
                handler(exchange)
            } finally {
                exchange.close()
            }
        }
        server.start()
        try {
            block(server.address.port)
        } finally {
            server.stop(0)
            pool.shutdownNow()
        }
    }

    private fun HttpExchange.respond(code: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        responseHeaders.add("Content-Type", "application/json")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }
}
