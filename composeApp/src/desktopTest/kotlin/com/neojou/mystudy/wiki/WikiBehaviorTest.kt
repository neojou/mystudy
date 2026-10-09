package com.neojou.mystudy.wiki

import com.neojou.mystudy.study.GraphHit
import com.neojou.mystudy.study.MarkdownBlock
import com.neojou.mystudy.study.circleLayout
import com.neojou.mystudy.study.hitGraph
import com.neojou.mystudy.study.previewOffersIngest
import com.neojou.mystudy.study.vaultTreeLabel
import com.neojou.mystudy.study.inlineMarkdown
import com.neojou.mystudy.study.markdownBlocks
import com.neojou.mystudy.wiki.ask.ASK_SYSTEM
import com.neojou.mystudy.wiki.ask.AskPipeline
import com.neojou.mystudy.wiki.ask.AskResult
import com.neojou.mystudy.wiki.ask.DraftPage
import com.neojou.mystudy.wiki.ask.GraphNode
import com.neojou.mystudy.wiki.ask.PacketEdge
import com.neojou.mystudy.wiki.ask.PacketPage
import com.neojou.mystudy.wiki.ask.WikiGraph
import com.neojou.mystudy.wiki.ask.documentFrequency
import com.neojou.mystudy.wiki.ask.hopScore
import com.neojou.mystudy.wiki.ask.limitPacket
import com.neojou.mystudy.wiki.ask.schemaExcerpt
import com.neojou.mystudy.wiki.ask.scoreNote
import com.neojou.mystudy.wiki.index.WikiIndex
import com.neojou.mystudy.wiki.index.ftsQuery
import com.neojou.mystudy.wiki.markdown.analyzeQuery
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
import com.neojou.mystudy.wiki.vault.listMarkdownUnder
import com.neojou.mystudy.wiki.vault.linkIntoRawSource
import com.neojou.mystudy.wiki.vault.scaffoldWiki
import com.neojou.mystudy.wiki.vault.Discovery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.ln
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WikiBehaviorTest {
    @Test
    fun fileBrowserShowsOnlyTheLastPathSegment() {
        assertEquals("第二大腦", vaultTreeLabel(Path.of("/Users/neojou/Knowledge/第二大腦")))
        assertEquals("raw", vaultTreeLabel(Path.of("/Users/neojou/Knowledge/第二大腦/raw")))
        assertEquals("/", vaultTreeLabel(Path.of("/")))
    }

    @Test
    fun previewOffersIngestOutsideTheWikiRoot() {
        val vault = tempDir()
        val wiki = scaffoldWiki(vault)
        val inbox = vault.resolve("Zettelkasten").resolve("Inbox").resolve("卡片盒筆記法.md")
        Files.createDirectories(inbox.parent)
        inbox.writeText("note")
        assertTrue(previewOffersIngest(inbox, wiki, vault))
        assertTrue(previewOffersIngest(inbox, null, vault))
        assertFalse(previewOffersIngest(wiki.resolve("wiki").resolve("concepts").resolve("a.md"), wiki, vault))
        assertFalse(previewOffersIngest(wiki.resolve("raw").resolve("sources").resolve("a.md"), wiki, vault))
        assertFalse(previewOffersIngest(inbox.parent, wiki, vault))
    }

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
        assertTrue(root.resolve("raw").resolve("sources").toFile().isDirectory)
        assertTrue(root.resolve("wiki").resolve("index.md").toFile().isFile)
    }

    @Test
    fun writeGuardRejectsSchemaRawAndEscape() {
        val root = scaffoldWiki(tempDir())
        assertFails { assertWikiWrite(root, "wiki/schema.md") }
        assertFails { assertWikiWrite(root, "../outside.md") }
        assertFails { assertWikiWrite(root, "raw/note.md") }
        assertFails { assertNewRawFile(root, "raw/note.md") }
        val raw = root.resolve("raw").resolve("sources").resolve("note.md")
        raw.writeText("kept\n")
        assertFails { assertNewRawFile(root, "raw/sources/note.md") }
        assertEquals("kept\n", raw.readText())
    }

    @Test
    fun stagingLinksWithoutCopyingOrOverwritingTheOriginal() {
        val vault = tempDir()
        val root = scaffoldWiki(vault)
        val first = vault.resolve("note.md")
        first.writeText("one")
        val staged = linkIntoRawSource(root, first)
        assertEquals("raw/sources/note.md", staged.relativePath)
        assertTrue(staged.created)
        val linked = root.resolve(staged.relativePath)
        assertTrue(Files.isSymbolicLink(linked))
        assertEquals("one", first.readText())
        assertEquals("one", linked.readText())
        val secondDir = vault.resolve("elsewhere")
        Files.createDirectories(secondDir)
        val second = secondDir.resolve("note.md")
        second.writeText("two")
        val again = linkIntoRawSource(root, second)
        assertEquals("one", root.resolve("raw").resolve("sources").resolve("note.md").readText())
        assertEquals("two", root.resolve(again.relativePath).readText())
        assertTrue(Files.isSymbolicLink(root.resolve(again.relativePath)))
        assertFalse(staged.relativePath == again.relativePath)
        val same = linkIntoRawSource(root, first)
        assertEquals(staged.relativePath, same.relativePath)
        assertFalse(same.created)
        assertEquals(
            listOf("note (2).md", "note.md"),
            root.resolve("raw").resolve("sources").toFile().list()?.sorted(),
        )
        assertEquals(listOf("raw/sources/note (2).md", "raw/sources/note.md"), listMarkdownUnder(root, "raw"))
        WikiIndex.open(root, tempDir().resolve("link.sqlite")).use { index ->
            index.reconcileAll()
            assertNotNull(index.load("raw/sources/note.md"))
            assertNotNull(index.load("raw/sources/note (2).md"))
        }
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
    fun vaultAsWikiRootLeavesInboxIngestible() {
        val vault = vaultAsWikiRoot()
        val inbox = vault.resolve("Zettelkasten").resolve("Inbox").resolve("卡片盒筆記法.md")
        Files.createDirectories(inbox.parent)
        inbox.writeText("zettel")
        val concept = vault.resolve("wiki").resolve("concepts").resolve("secret.md")
        concept.writeText("secret")
        val rawNote = vault.resolve("raw").resolve("sources").resolve("copied.md")
        rawNote.writeText("copied")
        assertTrue(previewOffersIngest(inbox, vault, vault))
        assertFalse(previewOffersIngest(concept, vault, vault))
        assertFalse(previewOffersIngest(rawNote, vault, vault))
        assertEquals(listOf(inbox.toRealPath()), ingestTargets(inbox, vault, vault).map { it.toRealPath() })
        val fromRoot = ingestTargets(vault, vault, vault).map { it.toRealPath() }
        assertTrue(inbox.toRealPath() in fromRoot)
        assertFalse(concept.toRealPath() in fromRoot)
        assertFalse(rawNote.toRealPath() in fromRoot)
        val staged = linkIntoRawSource(vault, inbox)
        assertEquals("raw/sources/卡片盒筆記法.md", staged.relativePath)
        val linked = vault.resolve(staged.relativePath)
        assertTrue(Files.isSymbolicLink(linked))
        assertEquals("zettel", inbox.readText())
        assertEquals("zettel", linked.readText())
        assertEquals(
            Path.of("../../Zettelkasten/Inbox/卡片盒筆記法.md"),
            Files.readSymbolicLink(linked),
        )
        assertFails { linkIntoRawSource(vault, concept) }
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
    fun queryAnalysisEmitsCjkBigramsNotSingleCharacters() {
        val analysis = analyzeQuery("什麼是卡片盒筆記法")
        assertEquals("卡片盒筆記法", analysis.phrase)
        assertEquals(listOf("卡片", "片盒", "盒筆", "筆記", "記法"), analysis.bigrams)
        assertTrue(analysis.bigrams.none { it == "原子" || it.length == 1 })
        assertTrue(analysis.ftsTerms.all { it.length >= 3 })
        assertNull(ftsQuery(listOf("原子", "卡")))
        assertEquals("\"卡片盒\"", ftsQuery(listOf("原子", "卡片盒")))
    }

    @Test
    fun cardQuestionOutranksAGenericFalseFriendAndAddsOneGraphHop() {
        val root = scaffoldWiki(tempDir())
        writeWikiPage(
            root,
            "wiki/concepts/卡片盒筆記法.md",
            "卡片盒筆記法",
            "concept",
            emptyList(),
            emptyList(),
            "卡片盒筆記法把一則想法寫成一張卡片。\n見 [[閃卡]] 的做法。\n",
        )
        writeWikiPage(
            root,
            "wiki/concepts/其他.md",
            "其他說明",
            "concept",
            emptyList(),
            emptyList(),
            "有人把卡片盒筆記法寫在正文裡。\n",
        )
        writeWikiPage(
            root,
            "wiki/concepts/閃卡.md",
            "閃卡",
            "concept",
            emptyList(),
            emptyList(),
            "閃卡用來回憶。\n",
        )
        writeWikiPage(
            root,
            "wiki/concepts/原子設計.md",
            "原子設計",
            "concept",
            listOf("原子"),
            emptyList(),
            "原子化是另一件事。\n",
        )
        for (title in listOf("蘋果", "香蕉", "橙子", "葡萄", "西瓜", "芒果")) {
            writeWikiPage(root, "wiki/concepts/$title.md", title, "concept", emptyList(), emptyList(), "水果。\n")
        }
        root.resolve("raw").resolve("loose.md").writeText("# 卡片盒筆記法\n\n全文\n")
        var system = ""
        var prompt = ""
        WikiIndex.open(root, tempDir().resolve("rank.sqlite")).use { index ->
            index.reconcileAll()
            assertTrue(index.pathsForAlias("原子").contains("wiki/concepts/原子設計.md"))
            val analysis = analyzeQuery("什麼是卡片盒筆記法與原子")
            val frequency = documentFrequency(index, analysis.bigrams + analysis.extras)
            val count = index.askNoteCount()
            val zettel = assertNotNull(scoreNote(index.load("wiki/concepts/卡片盒筆記法.md")!!, analysis, frequency, count))
            val other = assertNotNull(scoreNote(index.load("wiki/concepts/其他.md")!!, analysis, frequency, count))
            val atomic = assertNotNull(scoreNote(index.load("wiki/concepts/原子設計.md")!!, analysis, frequency, count))
            assertTrue(zettel.canSeed)
            assertTrue(other.canSeed)
            assertFalse(atomic.canSeed)
            assertTrue(zettel.score > other.score)
            assertTrue(zettel.score > atomic.score)
            val result = AskPipeline(index, ChatClient { request ->
                system = request.system
                prompt = request.user
                "原子化見 wiki/concepts/atomic-design-原子設計.md"
            }).ask("什麼是卡片盒筆記法與原子", 65536)
            val answer = assertIs<AskResult.Answer>(result)
            assertEquals(ASK_SYSTEM, system)
            assertFalse(prompt.contains("## Catalog"))
            assertTrue(prompt.contains("[1]"))
            assertTrue(prompt.contains("規則節錄（不是答案，不要引用）："))
            assertTrue(prompt.contains("## Frontmatter"))
            assertFalse(prompt.contains("This folder is an llm-wiki"))
            val schema = root.resolve("wiki").resolve("schema.md").readText()
            val excerpt = schemaExcerpt(schema)
            assertTrue(excerpt.length < schema.length)
            assertTrue(excerpt.length <= 800)
            assertTrue(prompt.contains(excerpt))
            assertEquals(
                listOf("wiki/concepts/卡片盒筆記法.md", "wiki/concepts/其他.md", "wiki/concepts/閃卡.md"),
                answer.pages.map { it.path },
            )
            assertEquals(listOf(1, 2, 3), answer.pages.map { it.number })
            assertEquals(1, answer.graphHits)
            assertTrue(answer.pages[2].fromGraph)
            assertTrue(answer.tokenHits >= 3)
            assertTrue(answer.pages.none { it.path.contains("原子") || it.path.startsWith("raw/") || it.path == "wiki/index.md" })
            assertEquals(answer.pages.map { it.path }, answer.sourcePaths)
            assertTrue(answer.text.contains("atomic-design-原子設計.md"))
            assertTrue(answer.edges.any { it.line.contains("[[閃卡]]") })
            assertTrue(prompt.indexOf("wiki/concepts/卡片盒筆記法.md") < prompt.indexOf("wiki/concepts/其他.md"))
        }
    }

    @Test
    fun genericOnlyOverlapDoesNotCallTheModel() {
        val root = scaffoldWiki(tempDir())
        WikiIndex.open(root, tempDir().resolve("empty.sqlite")).use { index ->
            index.reconcileAll()
            var calls = 0
            val empty = AskPipeline(index, ChatClient { calls += 1; "no" }).ask("什麼是注意力機制", 65536)
            assertIs<AskResult.NoMatch>(empty)
            assertEquals(0, calls)
        }
        val generic = scaffoldWiki(tempDir())
        writeWikiPage(
            generic,
            "wiki/concepts/原子設計.md",
            "原子設計",
            "concept",
            listOf("原子"),
            emptyList(),
            "原子化是另一件事。\n",
        )
        WikiIndex.open(generic, tempDir().resolve("generic.sqlite")).use { index ->
            index.reconcileAll()
            var calls = 0
            val result = AskPipeline(index, ChatClient { calls += 1; "no" }).ask("什麼是卡片盒筆記法與原子", 65536)
            val missed = assertIs<AskResult.NoMatch>(result)
            assertEquals(0, calls)
            assertTrue(missed.tokenHits >= 1)
            assertEquals(0, missed.graphHits)
        }
    }

    @Test
    fun phraseInTitleStillRetrievesAtomicDesign() {
        val root = scaffoldWiki(tempDir())
        writeWikiPage(
            root,
            "wiki/concepts/原子設計.md",
            "原子設計",
            "concept",
            listOf("原子"),
            emptyList(),
            "原子化是另一件事。\n",
        )
        WikiIndex.open(root, tempDir().resolve("phrase.sqlite")).use { index ->
            index.reconcileAll()
            var calls = 0
            val result = AskPipeline(index, ChatClient { calls += 1; "是一種設計方法" }).ask("什麼是原子設計", 65536)
            val answer = assertIs<AskResult.Answer>(result)
            assertEquals(1, calls)
            assertEquals("wiki/concepts/原子設計.md", answer.pages.single().path)
            assertEquals(listOf("wiki/concepts/原子設計.md"), answer.sourcePaths)
        }
    }

    @Test
    fun oneHopScoreUsesDirectSourcesAdamicAndSameType() {
        val left = GraphNode(
            "wiki/concepts/a.md",
            "甲",
            "concept",
            setOf("raw/s.md"),
            setOf("wiki/concepts/b.md", "wiki/concepts/n.md"),
            emptyMap(),
        )
        val right = GraphNode(
            "wiki/concepts/b.md",
            "乙",
            "concept",
            setOf("raw/s.md"),
            setOf("wiki/concepts/a.md", "wiki/concepts/n.md"),
            emptyMap(),
        )
        val hub = GraphNode(
            "wiki/concepts/n.md",
            "丙",
            "concept",
            emptySet(),
            setOf("wiki/concepts/a.md", "wiki/concepts/b.md"),
            emptyMap(),
        )
        val graph = WikiGraph(mapOf(left.path to left, right.path to right, hub.path to hub))
        val expected = 3.0 + 4.0 + 1.5 / ln(2.0) + 1.0
        assertEquals(expected, hopScore(left, right, graph), 0.0001)
        val entity = right.copy(type = "entity")
        val mixed = WikiGraph(mapOf(left.path to left, entity.path to entity, hub.path to hub))
        assertEquals(expected - 1.0, hopScore(left, entity, mixed), 0.0001)
    }

    @Test
    fun limitPacketCapsPagesAndExtractStillRefusesAHugeRawNote() {
        val pages = (1..9).map { number ->
            DraftPage("wiki/concepts/$number.md", "$number", "concept", "字".repeat(1_000), fromGraph = number > 5)
        }
        val limited = limitPacket(pages, 6_000)
        assertEquals(6, limited.size)
        assertEquals("wiki/concepts/1.md", limited.first().path)
        assertEquals("wiki/concepts/6.md", limited.last().path)
        val shortened = limitPacket(
            listOf(DraftPage("wiki/concepts/a.md", "a", "concept", "字".repeat(2_000), false)),
            400,
        )
        assertEquals(400, shortened.single().text.length)
        val pipeline = IngestPipeline(ChatClient { error("should not be called") })
        val stopped = pipeline.extract("字".repeat(100_000), 65536)
        assertIs<ExtractResult.Stopped>(stopped)
    }

    @Test
    fun markdownRendererDropsMarkerCharacters() {
        val blocks = markdownBlocks("# 標題\n\n這是**重點**與*斜體*和`程式`\n\n- 一項\n\n```\ncode\n```\n")
        assertIs<MarkdownBlock.Heading>(blocks[0])
        assertEquals("標題", (blocks[0] as MarkdownBlock.Heading).text)
        val paragraph = assertIs<MarkdownBlock.Paragraph>(blocks[1])
        assertEquals("這是重點與斜體和程式", inlineMarkdown(paragraph.text).text)
        assertFalse(inlineMarkdown(paragraph.text).text.contains("**"))
        assertFalse(inlineMarkdown(paragraph.text).text.contains("`"))
        assertIs<MarkdownBlock.Bullet>(blocks[2])
        val code = assertIs<MarkdownBlock.Code>(blocks[3])
        assertEquals("code", code.text)
    }

    @Test
    fun answerGraphPrefersANodeOverAnEdge() {
        val pages = listOf(
            PacketPage(1, "wiki/concepts/a.md", "甲", "concept", "", false),
            PacketPage(2, "wiki/concepts/b.md", "乙", "entity", "", true),
        )
        val points = circleLayout(pages, Size(400f, 400f), 28f)
        val edges = listOf(PacketEdge("wiki/concepts/a.md", "wiki/concepts/b.md", "見 [[乙]]"))
        val node = assertIs<GraphHit.Node>(hitGraph(points, edges, points[0].center, 28f, 12f))
        assertEquals("wiki/concepts/a.md", node.path)
        val middle = Offset(
            (points[0].center.x + points[1].center.x) / 2f,
            (points[0].center.y + points[1].center.y) / 2f,
        )
        val edge = assertIs<GraphHit.Edge>(hitGraph(points, edges, middle, 28f, 12f))
        assertEquals("見 [[乙]]", edge.line)
        assertIs<GraphHit.Miss>(hitGraph(points, edges, Offset(1f, 1f), 28f, 8f))
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

/** Vault itself is the wiki root: `raw/` and `wiki/index.md` sit next to notes such as Zettelkasten. */
private fun vaultAsWikiRoot(): Path {
    val vault = tempDir()
    Files.createDirectories(vault.resolve("raw").resolve("sources"))
    Files.createDirectories(vault.resolve("wiki").resolve("concepts"))
    vault.resolve("wiki").resolve("index.md").writeText("# Index\n")
    return vault
}

private fun writeConcept(root: Path, name: String, title: String, aliases: List<String>) {
    root.resolve("wiki").resolve("concepts").resolve(name).writeText(conceptText(title, aliases))
}

private fun writeWikiPage(
    root: Path,
    relative: String,
    title: String,
    type: String,
    aliases: List<String>,
    sources: List<String>,
    body: String,
) {
    val file = root.resolve(relative)
    Files.createDirectories(file.parent)
    file.writeText(
        buildString {
            append("---\n")
            append("title: \"")
            append(title)
            append("\"\n")
            append("type: ")
            append(type)
            append("\n")
            append("tags: []\n")
            append("aliases: ")
            append(yamlList(aliases))
            append("\n")
            append("sources: ")
            append(yamlList(sources))
            append("\n")
            append("updated: \"2026-10-08\"\n")
            append("---\n\n")
            append(body.trim())
            append('\n')
        },
    )
}

private fun yamlList(values: List<String>): String =
    if (values.isEmpty()) "[]" else values.joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

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
