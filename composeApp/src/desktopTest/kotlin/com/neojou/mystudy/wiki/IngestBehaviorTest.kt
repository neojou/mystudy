package com.neojou.mystudy.wiki

import com.neojou.mystudy.wiki.agent.ReadPath
import com.neojou.mystudy.wiki.agent.normalizeReadPath
import com.neojou.mystudy.wiki.ask.includedAskPath
import com.neojou.mystudy.wiki.ask.proposeQueryFile
import com.neojou.mystudy.wiki.index.WikiIndex
import com.neojou.mystudy.wiki.ingest.IngestInput
import com.neojou.mystudy.wiki.ingest.IngestLanguage
import com.neojou.mystudy.wiki.ingest.IngestPipeline
import com.neojou.mystudy.wiki.ingest.IngestResult
import com.neojou.mystudy.wiki.ingest.isSafeWikiPagePath
import com.neojou.mystudy.wiki.ingest.parseIngestBlocks
import com.neojou.mystudy.wiki.ingest.parsePageWorthy
import com.neojou.mystudy.wiki.model.DraftProposal
import com.neojou.mystudy.wiki.model.Resolution
import com.neojou.mystudy.wiki.model.defaultChecked
import com.neojou.mystudy.wiki.model.defaultCheckedReviews
import com.neojou.mystudy.wiki.ollama.ChatClient
import com.neojou.mystudy.wiki.ollama.ChatRequest
import com.neojou.mystudy.wiki.vault.applyProposal
import com.neojou.mystudy.wiki.vault.scaffoldWiki
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IngestBehaviorTest {
    @Test
    fun parserKeepsClosedBlocksAndDropsUnsafeOnes() {
        val parsed = parseIngestBlocks(
            """
            外面的字
            ---FILE: wiki/concepts/卡片.md---
            ```
            ---END FILE---
            ```
            正文還在
            ---END FILE---
            ---FILE: wiki/concepts/半截.md---
            沒有結尾
            ---FILE: wiki/sources/其他名字.md---
            來源正文
            ---END FILE---
            ---FILE: wiki/sources/又一個.md---
            第二來源
            ---END FILE---
            ---FILE: wiki/concepts/空.md---
            ---END FILE---
            ---FILE: ../secret.md---
            逃
            ---END FILE---
            ---FILE: /etc/passwd---
            逃
            ---END FILE---
            ---FILE: raw/note.md---
            逃
            ---END FILE---
            ---FILE: wiki/schema.md---
            逃
            ---END FILE---
            ---FILE: wiki/index.md---
            逃
            ---END FILE---
            ---FILE: wiki/overview.md---
            逃
            ---END FILE---
            ---FILE: wiki/log.md---
            逃
            ---END FILE---
            ---FILE: wiki/purpose.md---
            逃
            ---END FILE---
            ---FILE: wiki/review.md---
            逃
            ---END FILE---
            ---FILE: wiki/queries/q.md---
            逃
            ---END FILE---
            ---FILE: wiki/sources/schema.md---
            逃
            ---END FILE---
            ---REVIEW: contradiction | 舊說法---
            前後不一致
            OPTIONS: Create Page | Skip
            ---END REVIEW---
            ---REVIEW: duplicate | 另一頁---
            可能重複
            OPTIONS: Create Page | Skip
            ---END REVIEW---
            ---REVIEW: missing-page | 缺的頁---
            缺一頁
            OPTIONS: Create Page | Skip
            SEARCH: 盧曼
            SEARCH: Niklas Luhmann
            ---END REVIEW---
            ---REVIEW: suggestion | 補連結---
            建議
            OPTIONS: Create Page | Skip
            SEARCH: 卡片盒
            SEARCH: Zettelkasten
            SEARCH: 筆記法
            ---END REVIEW---
            ---REVIEW: other | 不要---
            壞型別
            ---END REVIEW---
            ---REVIEW: suggestion | 沒關---
            半截
            """.trimIndent(),
            "卡片盒筆記法",
        )
        assertEquals(listOf("wiki/concepts/卡片.md", "wiki/sources/卡片盒筆記法.md"), parsed.files.map { it.relativePath })
        assertTrue(parsed.files[0].body.contains("---END FILE---"))
        assertTrue(parsed.files[0].body.contains("正文還在"))
        assertEquals("來源正文", parsed.files[1].body)
        assertTrue(parsed.warnings.any { it == "Unclosed FILE discarded: wiki/concepts/半截.md" })
        assertTrue(parsed.warnings.any { it == "Ignored extra source FILE: wiki/sources/又一個.md" })
        assertTrue(parsed.warnings.any { it == "Skipped empty FILE" })
        assertTrue(parsed.warnings.any { it == "Ignored FILE: ../secret.md" })
        assertTrue(parsed.warnings.any { it == "Ignored FILE: /etc/passwd" })
        assertTrue(parsed.warnings.any { it == "Ignored FILE: raw/note.md" })
        assertTrue(parsed.warnings.any { it == "Ignored FILE: wiki/schema.md" })
        assertTrue(parsed.warnings.any { it == "Ignored FILE: wiki/index.md" })
        assertTrue(parsed.warnings.any { it == "Ignored FILE: wiki/overview.md" })
        assertTrue(parsed.warnings.any { it == "Ignored FILE: wiki/log.md" })
        assertTrue(parsed.warnings.any { it == "Ignored FILE: wiki/purpose.md" })
        assertTrue(parsed.warnings.any { it == "Ignored FILE: wiki/review.md" })
        assertTrue(parsed.warnings.any { it == "Ignored FILE: wiki/queries/q.md" })
        assertTrue(parsed.warnings.any { it == "Ignored FILE: wiki/sources/schema.md" })
        assertTrue(parsed.warnings.any { it == "Ignored text outside FILE/REVIEW." })
        assertTrue(parsed.warnings.any { it == "Ignored REVIEW: other | 不要" })
        assertTrue(parsed.warnings.any { it.startsWith("Unclosed REVIEW discarded:") })
        assertEquals(listOf("contradiction", "duplicate", "missing-page", "suggestion"), parsed.reviews.map { it.type })
        assertEquals(listOf("盧曼", "Niklas Luhmann"), parsed.reviews[2].searches)
        assertEquals(3, parsed.reviews[3].searches.size)
        assertFalse(isSafeWikiPagePath("../secret.md"))
        assertFalse(isSafeWikiPagePath("wiki/review.md"))
        assertTrue(isSafeWikiPagePath("wiki/sources/其他名字.md"))
        assertTrue(isSafeWikiPagePath("wiki/concepts/卡片.md"))

        val worthy = parsePageWorthy(
            """
            ## Key Concepts
            - Title: 卡片盒
              Page-worthy: yes
              Aliases: Zettelkasten, 卡
              Existing: wiki/concepts/卡片盒.md
            ## Main Arguments
            - Title: 忽略
              Page-worthy: yes
            ## Contradictions
            - Title: 也不摘
              Page-worthy: yes
            ## Key Entities
            - Title: 盧曼
              Page-worthy: no
              Aliases: none
              Existing: none
            """.trimIndent(),
        )
        assertEquals(1, worthy.size)
        assertEquals("卡片盒", worthy.single().title)
        assertEquals("concept", worthy.single().kind)
        assertEquals(listOf("Zettelkasten", "卡"), worthy.single().aliases)
        assertEquals("wiki/concepts/卡片盒.md", worthy.single().existingHint)
    }

    @Test
    fun twoCallsStayOffDiskUntilConfirm() {
        val vault = tempDir()
        val root = scaffoldWiki(vault)
        val inbox = vault.resolve("Zettelkasten").resolve("Inbox").resolve(SOURCE_NAME)
        Files.createDirectories(inbox.parent)
        inbox.writeText(NOTE)
        val noteBytes = inbox.readBytes()
        val schemaBytes = root.resolve("wiki").resolve("schema.md").readBytes()
        val before = snapshot(root)
        val rawNames = names(root.resolve("raw").resolve("sources"))
        val calls = mutableListOf<ChatRequest>()
        val stages = mutableListOf<String>()
        val result = IngestPipeline(ChatClient { request ->
            calls += request
            if (calls.size == 1) ANALYSIS else GENERATE
        }).ingest(input(schema = schemaBytes.decodeToString()), { stages += it })
        val proposal = assertIs<IngestResult.Proposal>(result).proposal
        assertEquals(listOf("Analyzing…", "Generating…"), stages)
        assertEquals(2, calls.size)
        assertTrue(calls.all { !it.json })
        assertTrue(calls.all { it.system.contains(IngestLanguage.NAME) })
        assertTrue(calls.all { !it.system.contains("這段前言不會送給模型") })
        assertTrue(calls.all { !it.user.contains("這段前言不會送給模型") })
        assertTrue(calls[1].system.contains("wiki/sources/$STEM.md"))
        assertFalse(calls[1].system.contains("{{sourceStem}}"))
        assertTrue(calls[1].user.contains(SOURCE_PATH))
        assertTrue(calls[1].user.contains(NOTE))
        assertEquals(before, snapshot(root))
        assertEquals(rawNames, names(root.resolve("raw").resolve("sources")))
        assertEquals(noteBytes.toList(), inbox.readBytes().toList())
        assertFalse(root.resolve("wiki").resolve("review.md").toFile().exists())

        val source = proposal.pages.first { it.relativePath == "wiki/sources/$STEM.md" }
        val concept = proposal.pages.first { it.relativePath == "wiki/concepts/卡片盒.md" }
        val entity = proposal.pages.first { it.relativePath == "wiki/entities/盧曼.md" }
        assertTrue(source.selectable)
        assertTrue(concept.selectable)
        assertFalse(concept.conflict)
        assertTrue(source.newText.contains("這是來源頁。"))
        assertFalse(source.newText.contains("[["))
        assertTrue(source.newText.contains(SOURCE_PATH))
        assertFalse(source.newText.contains("related:"))
        assertTrue(concept.newText.contains("[[sources/$STEM]]"))
        assertTrue(concept.newText.contains("Zettelkasten"))
        assertTrue(concept.newText.contains("type: \"concept\""))
        assertTrue(entity.newText.contains("type: \"entity\""))
        assertTrue(entity.newText.contains("[[sources/$STEM]]"))
        assertEquals(listOf("contradiction", "duplicate", "missing-page", "suggestion"), proposal.reviews.map { it.type })
        assertEquals(listOf("review-0", "review-1", "review-2", "review-3"), proposal.reviews.map { it.id })
        assertEquals(proposal.reviews.map { it.id }.toSet(), defaultCheckedReviews(proposal.reviews))
        assertTrue(defaultChecked(proposal.pages).containsAll(listOf(source.relativePath, concept.relativePath, entity.relativePath)))
        assertTrue(proposal.warnings.any { it == "Ignored extra source FILE: wiki/sources/又一個.md" })

        val applied = applyProposal(
            root,
            proposal.pages,
            defaultChecked(proposal.pages),
            proposal.logTitle,
            proposal.operation,
            TODAY,
            proposal.reviews,
            defaultCheckedReviews(proposal.reviews),
            proposal.sourcePath,
        )
        assertNull(applied.failed)
        val sourceText = root.resolve("wiki/sources/$STEM.md").readText()
        val conceptText = root.resolve("wiki/concepts/卡片盒.md").readText()
        assertTrue(sourceText.contains("sources:\n  - \"$SOURCE_PATH\""))
        assertTrue(conceptText.contains("[[sources/$STEM]]"))
        assertEquals(1, Regex("^title:", RegexOption.MULTILINE).findAll(conceptText).count())
        val index = root.resolve("wiki").resolve("index.md").readText()
        assertTrue(index.contains("[[sources/$STEM]]"))
        assertTrue(index.contains("[[concepts/卡片盒]]"))
        assertTrue(index.contains("[[entities/盧曼]]"))
        val log = root.resolve("wiki").resolve("log.md").readText()
        assertTrue(log.contains("## [$TODAY] ingest | 卡片盒筆記法"))
        val review = root.resolve("wiki").resolve("review.md").readText()
        assertTrue(review.startsWith("# Review"))
        assertTrue(review.contains("## [$TODAY] contradiction | 舊說法"))
        assertTrue(review.contains("## [$TODAY] duplicate | 另一頁"))
        assertTrue(review.contains("## [$TODAY] missing-page | 缺的頁"))
        assertTrue(review.contains("SEARCH: Niklas Luhmann"))
        assertTrue(review.contains("## [$TODAY] suggestion | 補連結"))
        assertTrue(review.contains("- source: $SOURCE_PATH"))
        assertEquals(schemaBytes.toList(), root.resolve("wiki").resolve("schema.md").readBytes().toList())
        assertEquals(noteBytes.toList(), inbox.readBytes().toList())
        assertEquals(rawNames, names(root.resolve("raw").resolve("sources")))
    }

    @Test
    fun existingHintIsReadOnlyWhenThePathIsSafe() {
        val body = "既有正文摘錄標記"
        val calls = mutableListOf<ChatRequest>()
        val analysis = """
            ## Key Concepts
            - Title: 注意力
              Page-worthy: yes
              Aliases: none
              Existing: wiki/concepts/注意力.md
        """.trimIndent()
        val result = IngestPipeline(ChatClient { request ->
            calls += request
            if (calls.size == 1) analysis else "沒有區塊"
        }).ingest(
            input(
                resolve = { _, _ -> error("hint should be used before resolve") },
                read = { path -> if (path == "wiki/concepts/注意力.md") "# 注意力\n\n$body\n" else null },
                exists = { path -> path == "wiki/concepts/注意力.md" },
            ),
        )
        assertIs<IngestResult.Proposal>(result)
        assertEquals(2, calls.size)
        assertTrue(calls[1].user.contains("wiki/concepts/注意力.md"))
        assertTrue(calls[1].user.contains(body))
        assertFalse(calls[1].user.contains("wiki/sources/untitled.md"))
    }

    @Test
    fun stopsWhenTheCallWouldExceedTheBudget() {
        val root = scaffoldWiki(tempDir())
        val before = snapshot(root)
        val tooSmall = runIngest(numCtx = 4096)
        assertEquals(0, tooSmall.calls)
        assertTrue(message(tooSmall.result).contains("Context setting is too small"))
        val missingSchema = runIngest(schema = "  ")
        assertEquals(0, missingSchema.calls)
        assertTrue(message(missingSchema.result).contains("wiki/schema.md is missing"))
        val missingFile = runIngest(prompts = tempDir())
        assertEquals(0, missingFile.calls)
        assertTrue(message(missingFile.result).contains("ingest-analyze.md"))
        val prompts = tempDir().resolve("ai-prompts")
        Files.createDirectories(prompts)
        prompts.resolve("ingest-analyze.md").writeText("--- user ---\n{{sourceName}}\n{{raw}}\n")
        prompts.resolve("ingest-generate.md").writeText(" \n")
        val missingSection = runIngest(prompts = prompts)
        assertEquals(0, missingSection.calls)
        assertTrue(message(missingSection.result).contains("system"))
        val empty = runIngest(raw = "  ")
        assertEquals(0, empty.calls)
        assertTrue(message(empty.result).contains("This note is empty"))
        val huge = runIngest(raw = "字".repeat(100_000))
        assertEquals(0, huge.calls)
        assertTrue(message(huge.result).contains("too big"))
        val blankAnalysis = runIngest(reply = " \n ")
        assertEquals(1, blankAnalysis.calls)
        assertTrue(message(blankAnalysis.result).contains("The analysis was empty"))
        val generateOver = runIngest(raw = "字".repeat(30_000), reply = "分".repeat(20_000))
        assertEquals(1, generateOver.calls)
        assertTrue(message(generateOver.result).contains("The generate prompt does not fit"))
        assertEquals(before, snapshot(root))
    }

    @Test
    fun existingPagesAppendAndAmbiguousRowsStayUnwritable() {
        val attention = """
            ---
            title: "注意力"
            type: concept
            tags: []
            aliases: []
            sources: []
            updated: "2026-10-08"
            ---

            原來的句子還在。
        """.trimIndent() + "\n"
        val conflictPage = """
            ---
            title: "衝突頁"
            type: concept
            tags: []
            aliases: []
            sources: []
            updated: "2026-10-08"
            ---

            不要改這頁。
        """.trimIndent() + "\n"
        val ambiguous = "old page\n"
        val result = IngestPipeline(ChatClient { request ->
            if (request.user.startsWith("Analyze")) ANALYSIS else APPEND_GENERATE
        }).ingest(
            input(
                resolve = { title, _ ->
                    when (title) {
                        "注意力" -> Resolution.One("wiki/concepts/注意力.md")
                        "衝突頁" -> Resolution.One("wiki/concepts/衝突.md")
                        "GPU" -> Resolution.Ambiguous(listOf("wiki/concepts/a.md", "wiki/concepts/b.md"))
                        else -> Resolution.None
                    }
                },
                read = { path ->
                    when (path) {
                        "wiki/concepts/注意力.md" -> attention
                        "wiki/concepts/衝突.md" -> conflictPage
                        "wiki/concepts/a.md" -> ambiguous
                        else -> null
                    }
                },
                exists = { path ->
                    path == "wiki/concepts/注意力.md" || path == "wiki/concepts/衝突.md" || path == "wiki/concepts/a.md"
                },
            ),
        )
        val proposal = assertIs<IngestResult.Proposal>(result).proposal
        val appended = proposal.pages.first { it.relativePath == "wiki/concepts/注意力.md" }
        val conflict = proposal.pages.first { it.relativePath == "wiki/concepts/衝突.md" }
        val freshConflict = proposal.pages.first { it.relativePath == "wiki/concepts/新衝突.md" }
        val ambiguousPage = proposal.pages.first { it.relativePath == "wiki/concepts/a.md" }
        assertTrue(appended.newText.contains("原來的句子還在。"))
        assertTrue(appended.newText.contains("新的一節。"))
        assertTrue(appended.newText.indexOf("原來的句子還在。") < appended.newText.indexOf("新的一節。"))
        assertEquals(1, Regex("^title:", RegexOption.MULTILINE).findAll(appended.newText).count())
        assertTrue(appended.newText.contains("updated: \"$TODAY\""))
        assertFalse(appended.conflict)
        assertTrue(proposal.warnings.any { it == "Skipped duplicate page: wiki/concepts/注意力.md" })
        assertFalse(appended.newText.contains("重複節"))
        assertTrue(conflict.conflict)
        assertTrue(conflict.selectable)
        assertTrue(conflict.note.contains("appends a section"))
        assertFalse(conflict.relativePath in defaultChecked(proposal.pages))
        assertTrue(freshConflict.conflict)
        assertTrue(freshConflict.note.contains("writes a new page"))
        assertFalse(freshConflict.relativePath in defaultChecked(proposal.pages))
        assertFalse(ambiguousPage.selectable)
        assertEquals(ambiguous, ambiguousPage.newText)

        val root = scaffoldWiki(tempDir())
        root.resolve("wiki/concepts/注意力.md").writeText(attention)
        root.resolve("wiki/concepts/衝突.md").writeText(conflictPage)
        root.resolve("wiki/concepts/a.md").writeText(ambiguous)
        val applied = applyProposal(
            root,
            proposal.pages,
            defaultChecked(proposal.pages),
            proposal.logTitle,
            proposal.operation,
            TODAY,
        )
        assertNull(applied.failed)
        val written = root.resolve("wiki/concepts/注意力.md").readText()
        assertTrue(written.contains("原來的句子還在。"))
        assertTrue(written.contains("新的一節。"))
        assertEquals(1, Regex("^title:", RegexOption.MULTILINE).findAll(written).count())
        assertTrue(written.contains("updated: \"$TODAY\""))
        assertEquals(conflictPage, root.resolve("wiki/concepts/衝突.md").readText())
        assertFalse(root.resolve("wiki/concepts/新衝突.md").toFile().exists())
        val blocked = applyProposal(
            root,
            proposal.pages,
            setOf(ambiguousPage.relativePath),
            proposal.logTitle,
            proposal.operation,
            TODAY,
        )
        assertNull(blocked.failed)
        assertEquals(ambiguous, root.resolve("wiki/concepts/a.md").readText())
    }

    @Test
    fun sourcePathReusesOrAllocatesBesideADifferentNote() {
        val other = """
            ---
            title: "別的來源"
            type: source
            tags: []
            aliases: []
            sources:
              - "Other/note.md"
            updated: "2026-10-01"
            ---

            舊的別的來源。
        """.trimIndent() + "\n"
        val same = """
            ---
            title: "卡片盒筆記法"
            type: source
            tags: []
            aliases: []
            sources: []
            updated: "2026-10-01"
            ---

            舊來源句子。
        """.trimIndent() + "\n"
        val canonical = "wiki/sources/$STEM.md"
        val collided = ingestProposal(
            read = { path -> if (path == canonical) other else null },
            exists = { path -> path == canonical },
            reply = SOURCE_ONLY,
        )
        assertEquals("wiki/sources/$STEM (2).md", collided.pages.first().relativePath)
        assertNull(collided.pages.first().previousText)

        val reused = ingestProposal(
            read = { path -> if (path == canonical) same else null },
            exists = { path -> path == canonical },
            reply = SOURCE_ONLY,
        )
        val page = reused.pages.first()
        assertEquals(canonical, page.relativePath)
        assertNotNull(page.previousText)
        assertTrue(page.newText.contains("舊來源句子。"))
        assertTrue(page.newText.contains("這是來源頁。"))
    }

    @Test
    fun reviewsAppendWithoutWritingUncheckedPages() {
        val root = scaffoldWiki(tempDir())
        val indexBytes = root.resolve("wiki").resolve("index.md").readBytes()
        val proposal = ingestProposal(reply = REVIEW_ONLY)
        assertFalse(root.resolve("wiki").resolve("review.md").toFile().exists())
        val applied = applyProposal(
            root,
            proposal.pages,
            emptySet(),
            proposal.logTitle,
            proposal.operation,
            TODAY,
            proposal.reviews,
            defaultCheckedReviews(proposal.reviews),
            proposal.sourcePath,
        )
        assertNull(applied.failed)
        assertFalse(root.resolve("wiki/concepts/只是概念.md").toFile().exists())
        assertFalse(root.resolve("wiki/sources/$STEM.md").toFile().exists())
        assertEquals(indexBytes.toList(), root.resolve("wiki").resolve("index.md").readBytes().toList())
        val review = root.resolve("wiki").resolve("review.md").readText()
        assertTrue(review.startsWith("# Review"))
        assertTrue(review.contains("## [$TODAY] suggestion | 補連結"))
        val log = root.resolve("wiki").resolve("log.md").readText()
        assertTrue(log.contains("## [$TODAY] ingest |"))
        assertTrue(log.contains("- wiki/review.md"))

        val older = scaffoldWiki(tempDir())
        older.resolve("wiki").resolve("review.md").writeText("# Review\n\n舊條目\n")
        applyProposal(
            older,
            proposal.pages,
            emptySet(),
            proposal.logTitle,
            proposal.operation,
            TODAY,
            proposal.reviews,
            defaultCheckedReviews(proposal.reviews),
            proposal.sourcePath,
        )
        val kept = older.resolve("wiki").resolve("review.md").readText()
        assertTrue(kept.startsWith("# Review\n\n舊條目"))
        assertTrue(kept.contains("## [$TODAY] suggestion | 補連結"))
    }

    @Test
    fun emptyFileListStillOffersALocalSourcePage() {
        val root = scaffoldWiki(tempDir())
        val before = snapshot(root)
        val proposal = ingestProposal(reply = "沒有任何區塊")
        assertEquals(before, snapshot(root))
        assertEquals(1, proposal.pages.size)
        val source = proposal.pages.single()
        assertEquals("wiki/sources/$STEM.md", source.relativePath)
        assertTrue(source.selectable)
        assertTrue(source.newText.contains("分析用的句子"))
        assertTrue(proposal.warnings.any { it.startsWith("No FILE block") })
        assertFalse(root.resolve(source.relativePath).toFile().exists())

        val added = ingestProposal(reply = CONCEPT_ONLY)
        assertTrue(added.warnings.any { it.contains("did not emit one") })
        assertTrue(added.pages.any { it.relativePath == "wiki/sources/$STEM.md" })
        assertTrue(added.pages.any { it.relativePath == "wiki/concepts/卡片盒.md" })
    }

    @Test
    fun reviewFileStaysOutOfAskChatAndQueryFiling() {
        assertFalse(includedAskPath("wiki/review.md"))
        assertTrue(includedAskPath("wiki/concepts/卡片盒.md"))
        val rejected = normalizeReadPath("wiki/review.md", emptyList()) { emptyList() }
        assertIs<ReadPath.Reject>(rejected)

        val root = scaffoldWiki(tempDir())
        root.resolve("wiki").resolve("review.md").writeText("# Review\n\n複審標記出現在這裡。\n")
        root.resolve("wiki/concepts/標記.md").writeText(
            """
            ---
            title: "標記"
            type: concept
            tags: []
            aliases: []
            sources: []
            updated: "2026-10-12"
            ---

            複審標記出現在概念頁。
            """.trimIndent() + "\n",
        )
        WikiIndex.open(root, tempDir().resolve("review.sqlite")).use { index ->
            index.reconcileAll()
            assertNotNull(index.load("wiki/review.md"))
            val hits = index.searchAskTerm("複審標記", 10)
            assertFalse(hits.contains("wiki/review.md"))
            assertTrue(hits.contains("wiki/concepts/標記.md"))
        }

        val filed = proposeQueryFile("問題", "答案", listOf("wiki/concepts/標記.md"), TODAY) { false }
        applyProposal(root, filed.pages, defaultChecked(filed.pages), filed.logTitle, filed.operation, TODAY)
        assertTrue(filed.reviews.isEmpty())
        assertFalse(root.resolve("wiki").resolve("review.md").toFile().let { file ->
            file.readText().contains("## [$TODAY]")
        })
        assertTrue(root.resolve(filed.pages.single().relativePath).toFile().exists())
    }
}

private const val SOURCE_NAME = "卡片盒筆記法.md"
private const val SOURCE_PATH = "Zettelkasten/Inbox/卡片盒筆記法.md"
private const val STEM = "卡片盒筆記法"
private const val NOTE = "卡片盒筆記把想法連成網。"
private val TODAY: LocalDate = LocalDate.of(2026, 10, 12)

private val ANALYSIS = """
    ## Key Concepts
    - Title: 卡片盒
      Page-worthy: yes
      Aliases: Zettelkasten
      Existing: none
    ## Key Entities
    - Title: 盧曼
      Page-worthy: yes
      Aliases: Niklas Luhmann
      Existing: none
    分析用的句子
""".trimIndent()

private val GENERATE = """
    ---FILE: wiki/sources/其他名字.md---
    ---
    title: "卡片盒筆記法"
    type: source
    tags: []
    aliases: []
    sources:
      - "$SOURCE_PATH"
    ---
    這是來源頁。
    ---END FILE---
    ---FILE: wiki/sources/又一個.md---
    不要這頁
    ---END FILE---
    ---FILE: wiki/concepts/任意檔名.md---
    ---
    title: "卡片盒"
    type: concept
    tags: [筆記]
    aliases: [Zettelkasten]
    sources: []
    ---
    卡片盒把筆記連起來。
    ---END FILE---
    ---FILE: wiki/concepts/人名.md---
    ---
    title: "盧曼"
    type: entity
    tags: []
    aliases: [Niklas Luhmann]
    ---
    人物頁。
    ---END FILE---
    ---REVIEW: contradiction | 舊說法---
    前後不一致
    OPTIONS: Create Page | Skip
    ---END REVIEW---
    ---REVIEW: duplicate | 另一頁---
    可能重複
    OPTIONS: Create Page | Skip
    ---END REVIEW---
    ---REVIEW: missing-page | 缺的頁---
    缺一頁
    OPTIONS: Create Page | Skip
    SEARCH: 盧曼
    SEARCH: Niklas Luhmann
    ---END REVIEW---
    ---REVIEW: suggestion | 補連結---
    建議
    OPTIONS: Create Page | Skip
    SEARCH: 卡片盒
    SEARCH: Zettelkasten
    SEARCH: 筆記法
    ---END REVIEW---
""".trimIndent()

private val APPEND_GENERATE = """
    ---FILE: wiki/sources/$STEM.md---
    ---
    title: "卡片盒筆記法"
    type: source
    ---
    這是來源頁。
    ---END FILE---
    ---FILE: wiki/concepts/忽略檔名.md---
    ---
    title: "注意力"
    type: concept
    ---
    新的一節。
    ---END FILE---
    ---FILE: wiki/concepts/注意力2.md---
    ---
    title: "注意力"
    type: concept
    ---
    重複節。
    ---END FILE---
    ---FILE: wiki/concepts/衝突檔.md---
    ---
    title: "衝突頁"
    type: concept
    ---
    衝突追加。
    ---END FILE---
    ---FILE: wiki/concepts/新衝突檔.md---
    ---
    title: "新衝突"
    type: concept
    ---
    新衝突正文。
    ---END FILE---
    ---FILE: wiki/concepts/gpu.md---
    ---
    title: "GPU"
    type: concept
    ---
    不要寫。
    ---END FILE---
    ---REVIEW: contradiction | 衝突頁---
    前後不一致
    OPTIONS: Create Page | Skip
    ---END REVIEW---
    ---REVIEW: contradiction | 新衝突---
    新頁衝突
    OPTIONS: Create Page | Skip
    ---END REVIEW---
""".trimIndent()

private val SOURCE_ONLY = """
    ---FILE: wiki/sources/其他名字.md---
    ---
    title: "卡片盒筆記法"
    type: source
    ---
    這是來源頁。
    ---END FILE---
""".trimIndent()

private val CONCEPT_ONLY = """
    ---FILE: wiki/concepts/任意.md---
    ---
    title: "卡片盒"
    type: concept
    ---
    概念正文
    ---END FILE---
""".trimIndent()

private val REVIEW_ONLY = """
    ---FILE: wiki/concepts/任意.md---
    ---
    title: "只是概念"
    type: concept
    ---
    不該寫出。
    ---END FILE---
    ---REVIEW: suggestion | 補連結---
    建議
    OPTIONS: Create Page | Skip
    SEARCH: 卡片盒
    SEARCH: Zettelkasten
    ---END REVIEW---
""".trimIndent()

private data class Counted(val result: IngestResult, val calls: Int)

private fun runIngest(
    raw: String = NOTE,
    schema: String = "schema",
    numCtx: Int = 65536,
    prompts: Path? = null,
    reply: String = "分析",
): Counted {
    var calls = 0
    val result = IngestPipeline(ChatClient {
        calls += 1
        if (calls > 1) error("unexpected second call")
        reply
    }, prompts).ingest(input(raw = raw, schema = schema, numCtx = numCtx))
    return Counted(result, calls)
}

private fun message(result: IngestResult): String = assertIs<IngestResult.Stopped>(result).message

private fun ingestProposal(
    read: (String) -> String? = { null },
    exists: (String) -> Boolean = { false },
    reply: String,
): DraftProposal {
    var calls = 0
    val result = IngestPipeline(ChatClient {
        calls += 1
        if (calls == 1) "分析用的句子" else reply
    }).ingest(input(read = read, exists = exists))
    return assertIs<IngestResult.Proposal>(result).proposal
}

private fun input(
    raw: String = NOTE,
    schema: String = "Pages use YAML and wikilinks.",
    numCtx: Int = 65536,
    resolve: (String, List<String>) -> Resolution = { _, _ -> Resolution.None },
    read: (String) -> String? = { null },
    findSource: (String) -> String? = { null },
    exists: (String) -> Boolean = { false },
): IngestInput = IngestInput(
    sourceName = SOURCE_NAME,
    sourcePath = SOURCE_PATH,
    rawText = raw,
    schema = schema,
    purpose = null,
    indexText = "",
    overview = null,
    numCtx = numCtx,
    today = TODAY,
    resolve = resolve,
    read = read,
    findSource = findSource,
    exists = exists,
)

private fun tempDir(): Path = Files.createTempDirectory("mystudy-ingest")

private fun names(dir: Path): List<String> {
    if (!Files.isDirectory(dir)) return emptyList()
    return Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }
}

private fun snapshot(root: Path): Map<String, List<Byte>> {
    return Files.walk(root).use { stream ->
        stream.filter { Files.isRegularFile(it) && !Files.isSymbolicLink(it) }
            .map { path ->
                root.relativize(path).toString().replace('\\', '/') to path.readBytes().toList()
            }
            .toList()
            .toMap()
    }
}
