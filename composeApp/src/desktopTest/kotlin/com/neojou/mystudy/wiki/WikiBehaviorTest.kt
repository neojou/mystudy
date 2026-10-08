package com.neojou.mystudy.wiki

import com.neojou.mystudy.wiki.ask.AskBundle
import com.neojou.mystudy.wiki.ask.AskPipeline
import com.neojou.mystudy.wiki.ask.AskResult
import com.neojou.mystudy.wiki.ask.Excerpt
import com.neojou.mystudy.wiki.ask.Fit
import com.neojou.mystudy.wiki.ask.excludedAskPath
import com.neojou.mystudy.wiki.ask.fitAskPrompt
import com.neojou.mystudy.wiki.ask.mergeCandidates
import com.neojou.mystudy.wiki.index.WikiIndex
import com.neojou.mystudy.wiki.ingest.IngestPipeline
import com.neojou.mystudy.wiki.ingest.ExtractResult
import com.neojou.mystudy.wiki.ingest.buildIngestProposal
import com.neojou.mystudy.wiki.ingest.keepVerbatim
import com.neojou.mystudy.wiki.markdown.appendLog
import com.neojou.mystudy.wiki.markdown.insertCatalogLine
import com.neojou.mystudy.wiki.markdown.parseNote
import com.neojou.mystudy.wiki.markdown.parseWikilinks
import com.neojou.mystudy.wiki.model.Claim
import com.neojou.mystudy.wiki.model.Resolution
import com.neojou.mystudy.wiki.model.defaultChecked
import com.neojou.mystudy.wiki.ollama.ChatClient
import com.neojou.mystudy.wiki.ollama.ChatException
import com.neojou.mystudy.wiki.ollama.endpointOrigin
import com.neojou.mystudy.wiki.vault.applyProposal
import com.neojou.mystudy.wiki.vault.assertNewRawFile
import com.neojou.mystudy.wiki.vault.assertWikiWrite
import com.neojou.mystudy.wiki.vault.discoverWikiRoot
import com.neojou.mystudy.wiki.vault.ingestTargets
import com.neojou.mystudy.wiki.vault.scaffoldWiki
import com.neojou.mystudy.wiki.vault.stageIntoRaw
import com.neojou.mystudy.wiki.vault.Discovery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WikiBehaviorTest {
    @Test
    fun finderAcceptsANestedRootAndIgnoresAnOutsideSymlink() {
        val vault = tempDir()
        val nested = vault.resolve("notes").resolve("project")
        Files.createDirectories(nested.resolve("raw"))
        Files.createDirectories(nested.resolve("wiki"))
        nested.resolve("wiki").resolve("index.md").writeText("# Index\n")
        val outside = tempDir()
        Files.createDirectories(outside.resolve("raw"))
        Files.createDirectories(outside.resolve("wiki"))
        outside.resolve("wiki").resolve("index.md").writeText("# Index\n")
        Files.createSymbolicLink(vault.resolve("escape"), outside)
        val notes = vault.resolve("loose.md")
        notes.writeText("not a wiki")
        val before = vault.toFile().walkTopDown().map { it.path }.toSet()
        val found = discoverWikiRoot(vault, savedRoot = null, honorSaved = false)
        assertEquals(before, vault.toFile().walkTopDown().map { it.path }.toSet())
        val one = assertIs<Discovery.One>(found)
        assertEquals(nested.toRealPath(), one.root.toRealPath())
    }

    @Test
    fun finderReturnsEveryRootAndHonorsASavedRoot() {
        val vault = tempDir()
        val created = scaffoldWiki(vault)
        Files.createDirectories(vault.resolve("raw"))
        Files.createDirectories(vault.resolve("wiki"))
        vault.resolve("wiki").resolve("index.md").writeText("# Index\n")
        val many = assertIs<Discovery.Many>(discoverWikiRoot(vault, null, honorSaved = false))
        assertEquals(2, many.roots.size)
        val saved = assertIs<Discovery.One>(discoverWikiRoot(vault, created, honorSaved = true))
        assertEquals(created.toRealPath(), saved.root.toRealPath())
        assertIs<Discovery.VaultMissing>(discoverWikiRoot(vault.resolve("missing"), null, false))
    }

    @Test
    fun scaffolderLeavesAnExistingSchemaUntouched() {
        val vault = tempDir()
        val keep = vault.resolve("keep.md")
        keep.writeText("leave me\n")
        val root = scaffoldWiki(vault)
        val schema = root.resolve("wiki").resolve("schema.md")
        schema.writeText("custom schema\n")
        scaffoldWiki(vault)
        assertEquals("custom schema\n", schema.readText())
        assertEquals("leave me\n", keep.readText())
        assertTrue(root.resolve("raw").toFile().isDirectory)
        assertTrue(root.resolve("wiki").resolve("index.md").toFile().isFile)
    }

    @Test
    fun writeGuardRejectsSchemaRawAndEscape() {
        val root = scaffoldWiki(tempDir())
        assertFails { assertWikiWrite(root, "wiki/schema.md") }
        assertFails { assertWikiWrite(root, "../outside.md") }
        assertFails { assertWikiWrite(root, "raw/note.md") }
        val raw = root.resolve("raw").resolve("note.md")
        raw.writeText("kept\n")
        assertFails { assertNewRawFile(root, "raw/note.md") }
        assertEquals("kept\n", raw.readText())
    }

    @Test
    fun stagingCopiesWithoutOverwritingRawOrTheOriginal() {
        val vault = tempDir()
        val root = scaffoldWiki(vault)
        val first = vault.resolve("note.md")
        first.writeText("one")
        val staged = stageIntoRaw(root, first)
        assertEquals("one", first.readText())
        assertEquals("one", root.resolve(staged.relativePath).readText())
        val secondDir = vault.resolve("elsewhere")
        Files.createDirectories(secondDir)
        val second = secondDir.resolve("note.md")
        second.writeText("two")
        val again = stageIntoRaw(root, second)
        assertEquals("one", root.resolve("raw").resolve("note.md").readText())
        assertEquals("two", root.resolve(again.relativePath).readText())
        assertFalse(staged.relativePath == again.relativePath)
        val same = stageIntoRaw(root, first)
        assertEquals(staged.relativePath, same.relativePath)
        assertEquals(listOf("note (2).md", "note.md"), root.resolve("raw").toFile().list()?.sorted())
    }

    @Test
    fun ingestTargetsSkipTheWikiTree() {
        val vault = tempDir()
        val wiki = scaffoldWiki(vault)
        val inbox = vault.resolve("Inbox")
        Files.createDirectories(inbox)
        val note = inbox.resolve("idea.md")
        note.writeText("idea")
        val inside = wiki.resolve("wiki").resolve("concepts").resolve("secret.md")
        inside.writeText("secret")
        assertEquals(listOf(note.toRealPath()), ingestTargets(note, wiki, vault).map { it.toRealPath() })
        assertTrue(ingestTargets(inside, wiki, vault).isEmpty())
        assertEquals(listOf(note.toRealPath()), ingestTargets(vault, wiki, vault).map { it.toRealPath() })
    }

    @Test
    fun frontmatterKeepsChineseTitlesAndWikilinks() {
        val parsed = parseNote(
            """
            ---
            title: 注意力
            type: concept
            tags: [心智, 認知]
            aliases:
              - attention
            sources: ["raw/a.md"]
            updated: "2026-10-08"
            ---

            見 [[注意力|注意]]
            """.trimIndent(),
            "file",
        )
        assertEquals("注意力", parsed.title)
        assertEquals(listOf("心智", "認知"), parsed.tags)
        assertEquals(listOf("attention"), parsed.aliases)
        assertEquals(listOf("raw/a.md"), parsed.sources)
        assertEquals(listOf("注意力"), parseWikilinks(parsed.body))
    }

    @Test
    fun catalogInsertDoesNotReorderAndLogKeepsItsPrefix() {
        val original = """
            ## Concepts

            - [[concepts/b]] — b · type: concept · sources: raw/b.md
            - [[concepts/a]] — a · type: concept · sources: raw/a.md
        """.trimIndent()
        val updated = insertCatalogLine(
            original,
            "## Concepts",
            "- [[concepts/a]] — updated · type: concept · sources: raw/a.md",
        )
        assertTrue(updated.indexOf("concepts/b") < updated.indexOf("concepts/a"))
        assertTrue(updated.contains("updated"))
        val existing = "# Log\n\n## [2020-01-01] lint | old\n\n- kept\n"
        val out = appendLog(existing, LocalDate.of(2026, 10, 8), "ingest", "標題", listOf("wiki/sources/a.md"))
        assertTrue(out.startsWith(existing))
        assertTrue(out.contains("## [2026-10-08] ingest | 標題"))
    }

    @Test
    fun indexFindsChineseAndRebuildsOneNote() {
        val root = scaffoldWiki(tempDir())
        val concept = root.resolve("wiki").resolve("concepts").resolve("db.md")
        concept.writeText(
            """
            ---
            title: 資料庫
            type: concept
            tags: []
            aliases: []
            sources: []
            updated: "2026-10-08"
            ---

            向量資料庫不在這裡
            """.trimIndent(),
        )
        val other = root.resolve("wiki").resolve("concepts").resolve("other.md")
        other.writeText("# Other\n\nunchanged body\n")
        val index = WikiIndex.open(root, tempDir().resolve("index.sqlite"))
        index.use {
            it.reconcileAll()
            assertTrue(it.searchFts(listOf("資料庫"), 5).contains("wiki/concepts/db.md"))
            val hash = it.contentHash("wiki/concepts/other.md")
            concept.writeText(concept.readText() + "\n更多\n")
            it.reconcileAll()
            assertEquals(hash, it.contentHash("wiki/concepts/other.md"))
            Files.delete(concept)
            it.reconcileAll()
            assertNull(it.load("wiki/concepts/db.md"))
            assertEquals(hash, it.contentHash("wiki/concepts/other.md"))
        }
    }

    @Test
    fun aliasResolutionIsMissOneOrAmbiguous() {
        val root = scaffoldWiki(tempDir())
        writeConcept(root, "注意力.md", "注意力", emptyList())
        writeConcept(root, "a.md", "A", listOf("GPU"))
        writeConcept(root, "b.md", "B", listOf("GPU"))
        WikiIndex.open(root, tempDir().resolve("index.sqlite")).use { index ->
            index.reconcileAll()
            assertIs<Resolution.None>(index.resolve("沒有這頁"))
            assertEquals("wiki/concepts/注意力.md", (index.resolve("注意力") as Resolution.One).path)
            assertIs<Resolution.Ambiguous>(index.resolve("GPU"))
        }
    }

    @Test
    fun emptyAskDoesNotCallTheModelAndCatalogOutranksFts() {
        val root = scaffoldWiki(tempDir())
        WikiIndex.open(root, tempDir().resolve("empty.sqlite")).use { index ->
            index.reconcileAll()
            var calls = 0
            val result = AskPipeline(index, ChatClient { calls += 1; "no" }).ask("什麼是注意力機制", 65536)
            assertIs<AskResult.NoMatch>(result)
            assertEquals(0, calls)
        }
        val ranked = scaffoldWiki(tempDir())
        ranked.resolve("wiki").resolve("index.md").writeText(
            """
            # Index

            ## Concepts

            - [[concepts/special]] — 特殊標記 · type: concept · sources: raw/s.md
            """.trimIndent(),
        )
        ranked.resolve("wiki").resolve("concepts").resolve("special.md").writeText("# Special\n\na quiet page\n")
        ranked.resolve("wiki").resolve("concepts").resolve("other.md").writeText("# Other\n\n這裡提到特殊標記一次\n")
        var prompt = ""
        WikiIndex.open(ranked, tempDir().resolve("rank.sqlite")).use { index ->
            index.reconcileAll()
            val result = AskPipeline(index, ChatClient { request ->
                prompt = request.user
                "見 wiki/concepts/special.md"
            }).ask("特殊標記在哪裡", 65536)
            assertIs<AskResult.Answer>(result)
        }
        assertTrue(prompt.indexOf("wiki/concepts/special.md") < prompt.indexOf("wiki/concepts/other.md"))
        val merged = mergeCandidates(
            listOf("wiki/concepts/special.md"),
            listOf("wiki/concepts/other.md"),
            ::excludedAskPath,
        )
        assertEquals(listOf("wiki/concepts/special.md", "wiki/concepts/other.md"), merged)
    }

    @Test
    fun promptBudgetShedsHopsAndRefusesAnOverlongRawNote() {
        val page = Excerpt("wiki/concepts/a.md", "a", emptyList(), "p".repeat(500))
        val hop = Excerpt("wiki/concepts/b.md", "b", emptyList(), "h".repeat(5000))
        val fit = fitAskPrompt("sys", AskBundle(listOf("line"), listOf(page), listOf(hop)), budget = 800)
        val ready = assertIs<Fit.Ready>(fit)
        assertTrue(ready.bundle.hops.isEmpty())
        assertEquals(1, ready.bundle.pages.size)
        val over = fitAskPrompt("s".repeat(50), AskBundle(emptyList(), listOf(page.copy(text = "p".repeat(5000))), emptyList()), 200)
        assertIs<Fit.Over>(over)
        val pipeline = IngestPipeline(ChatClient { error("should not be called") })
        val stopped = pipeline.extract("字".repeat(100_000), 65536)
        assertIs<ExtractResult.Stopped>(stopped)
    }

    @Test
    fun badExcerptsAreDroppedAndConflictsStayOutOfTheWrite() {
        val kept = keepVerbatim(
            "alpha beta",
            listOf(
                Claim("no", "missing quote", "", "No", emptyList()),
                Claim("yes", "alpha", "concept", "Yes", emptyList()),
            ),
        )
        assertEquals(listOf("Yes"), kept.map { it.suggestedTitle })
        val root = scaffoldWiki(tempDir())
        val concept = root.resolve("wiki").resolve("concepts").resolve("注意力.md")
        val original = conceptText("注意力")
        concept.writeText(original)
        val model = Json.parseToJsonElement(
            """
            {"sourceSummary":"摘要","sourceBody":"來源正文","pages":[{"title":"注意力","type":"concept","summary":"摘要","section":"追加小節","conflict":true,"conflictNote":"前後不一致","aliases":[],"tags":[]}],"logTitle":"注意力"}
            """.trimIndent(),
        ).jsonObject
        val proposal = buildIngestProposal(
            rawRelative = "raw/note.md",
            claims = listOf(Claim("注意力是一種選擇", "注意力是一種選擇", "concept", "注意力", emptyList())),
            model = model,
            schema = root.resolve("wiki").resolve("schema.md").readText(),
            schemaTruncated = false,
            today = LocalDate.of(2026, 10, 8),
            read = { relative -> if (relative == "wiki/concepts/注意力.md") original else null },
            resolve = { title, _ -> if (title == "注意力") Resolution.One("wiki/concepts/注意力.md") else Resolution.None },
            findSource = { null },
            exists = { relative -> relative == "wiki/concepts/注意力.md" },
            rawLink = "note",
        )
        val conflict = proposal.pages.first { it.relativePath == "wiki/concepts/注意力.md" }
        assertTrue(conflict.conflict)
        assertFalse(conflict.relativePath in defaultChecked(proposal.pages))
        val logBefore = root.resolve("wiki").resolve("log.md").readBytes()
        val applied = applyProposal(
            root,
            proposal.pages,
            defaultChecked(proposal.pages),
            proposal.logTitle,
            proposal.operation,
            LocalDate.of(2026, 10, 8),
        )
        assertNull(applied.failed)
        assertEquals(original, concept.readText())
        assertTrue(root.resolve("wiki").resolve("sources").resolve("note.md").toFile().isFile)
        val log = root.resolve("wiki").resolve("log.md").readText()
        assertTrue(log.startsWith(logBefore.decodeToString()))
        assertTrue(log.contains("## [2026-10-08] ingest | 注意力"))
    }

    @Test
    fun ambiguousTitlesAreNotWritable() {
        val model = Json.parseToJsonElement(
            """{"sourceSummary":"s","sourceBody":"body","pages":[],"logTitle":"t"}""",
        ).jsonObject
        val proposal = buildIngestProposal(
            rawRelative = "raw/a.md",
            claims = listOf(Claim("stmt", "stmt", "concept", "GPU", emptyList())),
            model = model,
            schema = "plain schema",
            schemaTruncated = false,
            today = LocalDate.of(2026, 10, 8),
            read = { "old page\n" },
            resolve = { _, _ -> Resolution.Ambiguous(listOf("wiki/concepts/a.md", "wiki/concepts/b.md")) },
            findSource = { null },
            exists = { false },
            rawLink = "a",
        )
        val ambiguous = proposal.pages.first { it.relativePath == "wiki/concepts/a.md" }
        assertFalse(ambiguous.selectable)
        val root = scaffoldWiki(tempDir())
        val file = root.resolve("wiki").resolve("concepts").resolve("a.md")
        file.writeText("old page\n")
        applyProposal(
            root,
            proposal.pages.filter { !it.selectable },
            setOf(ambiguous.relativePath),
            "t",
            "ingest",
            LocalDate.of(2026, 10, 8),
        )
        assertEquals("old page\n", file.readText())
    }

    @Test
    fun ollamaOriginMustBeLoopback() {
        assertEquals("http://127.0.0.1:11434", endpointOrigin("http://127.0.0.1:11434/v1"))
        assertFails { endpointOrigin("https://example.com/v1") }
        assertIs<ChatException>(runCatching { endpointOrigin("https://example.com/v1") }.exceptionOrNull())
    }
}

private fun tempDir(): Path = Files.createTempDirectory("mystudy-wiki")

private fun writeConcept(root: Path, name: String, title: String, aliases: List<String>) {
    root.resolve("wiki").resolve("concepts").resolve(name).writeText(conceptText(title, aliases))
}

private fun conceptText(title: String, aliases: List<String> = emptyList()): String {
    val aliasBlock = if (aliases.isEmpty()) "[]" else aliases.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
    return """
        ---
        title: "$title"
        type: concept
        tags: []
        aliases: $aliasBlock
        sources: []
        updated: "2026-10-08"
        ---

        #$title
    """.trimIndent() + "\n"
}
