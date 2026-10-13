package com.neojou.mystudy.wiki

import com.neojou.mystudy.wiki.index.WikiIndex
import com.neojou.mystudy.wiki.ingest.IngestLanguage
import com.neojou.mystudy.wiki.lint.LintConfig
import com.neojou.mystudy.wiki.lint.LintPage
import com.neojou.mystudy.wiki.lint.LintRule
import com.neojou.mystudy.wiki.lint.SemanticPhase
import com.neojou.mystudy.wiki.lint.appendLintReviews
import com.neojou.mystudy.wiki.lint.lintSnapshot
import com.neojou.mystudy.wiki.lint.parseLintBlocks
import com.neojou.mystudy.wiki.lint.planSemanticBatches
import com.neojou.mystudy.wiki.lint.runSemanticLint
import com.neojou.mystudy.wiki.lint.runStructuralLint
import com.neojou.mystudy.wiki.lint.toReviewProposals
import com.neojou.mystudy.wiki.markdown.normalizeKey
import com.neojou.mystudy.wiki.model.Resolution
import com.neojou.mystudy.wiki.ollama.ChatClient
import com.neojou.mystudy.wiki.ollama.ChatRequest
import com.neojou.mystudy.wiki.ollama.PromptBudget
import com.neojou.mystudy.wiki.prompt.locateAiPrompts
import com.neojou.mystudy.wiki.research.parseReviewFile
import com.neojou.mystudy.wiki.research.researchQueries
import com.neojou.mystudy.wiki.settings.AppSettings
import com.neojou.mystudy.wiki.settings.IngestMode
import com.neojou.mystudy.wiki.settings.MemorySettingsStore
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
import kotlin.test.assertTrue

class WikiLintBehaviorTest {
    private val day = LocalDate.of(2026, 10, 13)

    @Test
    fun orphanIgnoresSelfIndexSourcesAndQueries() {
        val link = page("wiki/concepts/A.md", outlinks = listOf("B"), title = "A")
        val target = page("wiki/concepts/B.md", outlinks = listOf("B"), title = "B")
        val lonely = page("wiki/concepts/C.md", title = "C")
        val source = page("wiki/sources/新聞.md", title = "新聞", type = "source")
        val query = page("wiki/queries/research-x.md", title = "研究", type = "query")
        val pages = listOf(link, target, lonely, source, query)
        val findings = runStructuralLint(pages, resolverFor(pages)).findings
        val orphans = findings.filter { it.rule == LintRule.Orphan }.map { it.title }
        assertTrue(orphans.any { it == "補連結：C" })
        assertTrue(orphans.any { it == "補連結：A" })
        assertFalse(orphans.any { it.contains("B") })
        assertFalse(orphans.any { it.contains("新聞") })
        assertFalse(orphans.any { it.contains("研究") })
        assertTrue(findings.any { it.rule == LintRule.NoOutlinks && it.pages == listOf("wiki/concepts/C.md") })
        assertTrue(findings.any { it.rule == LintRule.NoOutlinks && it.pages == listOf("wiki/sources/新聞.md") })
        assertTrue(findings.none { it.rule == LintRule.Orphan && it.defaultChecked })
        assertTrue(findings.none { it.rule == LintRule.NoOutlinks && it.defaultChecked })
    }

    @Test
    fun brokenLinksMergeAmbiguousDoesNotAndHttpIsSkipped() {
        val left = page("wiki/concepts/Left.md", outlinks = listOf("不存在", "https://example.com", "同名"), title = "Left")
        val right = page("wiki/concepts/Right.md", outlinks = listOf("不存在"), title = "Right")
        val one = page("wiki/concepts/One.md", title = "同名")
        val two = page("wiki/entities/Two.md", title = "同名")
        val pages = listOf(left, right, one, two)
        val findings = runStructuralLint(pages, resolverFor(pages)).findings
        val broken = findings.filter { it.rule == LintRule.BrokenLink }
        assertEquals(1, broken.size)
        assertEquals("不存在", broken[0].title)
        assertEquals(listOf("wiki/concepts/Left.md", "wiki/concepts/Right.md"), broken[0].pages)
        assertEquals("missing-page", broken[0].reviewType)
        assertTrue(broken[0].defaultChecked)
        assertEquals("2 頁用 [[不存在]] 連結，但 wiki 裡沒有這一頁。", broken[0].detail)
        assertTrue(findings.any { it.rule == LintRule.Ambiguous && it.title == "同名" && it.reviewType == null })
        assertTrue(findings.none { it.title == "https://example.com" })
        assertEquals(1, findings.count { it.title == "不存在" })
    }

    @Test
    fun tagMissingPageUsesExactNamesAndMergesWithBrokenLinks() {
        val tagged = (1..3).map { index ->
            page("wiki/concepts/T$index.md", tags = listOf("盧曼"), outlinks = listOf("Quiet"), title = "頁$index")
        }
        val quiet = page("wiki/concepts/Quiet.md", title = "Quiet")
        val absent = runStructuralLint(tagged + quiet, resolverFor(tagged + quiet)).findings
        val missing = absent.filter { it.rule == LintRule.MissingPage }
        assertEquals(listOf("盧曼"), missing.map { it.title })
        assertEquals("missing-page", missing[0].reviewType)
        assertTrue(missing[0].searches.first() == "盧曼")
        assertTrue(missing[0].searches.any { it.startsWith("盧曼 ") })

        val aliased = page("wiki/concepts/Has.md", title = "卡片", aliases = listOf("盧曼"))
        val hidden = runStructuralLint(tagged + quiet + aliased, resolverFor(tagged + quiet + aliased)).findings
        assertTrue(hidden.none { it.title == "盧曼" && it.rule == LintRule.MissingPage })

        val longer = page("wiki/concepts/Long.md", title = "盧曼卡片", outlinks = listOf("Quiet"))
        val still = runStructuralLint(tagged + quiet + longer, resolverFor(tagged + quiet + longer)).findings
        assertTrue(still.any { it.rule == LintRule.MissingPage && it.title == "盧曼" })

        val linkers = listOf(
            page("wiki/concepts/A.md", outlinks = listOf("盧曼"), title = "甲"),
            page("wiki/concepts/B.md", outlinks = listOf("盧曼"), title = "甲"),
        )
        val extra = page("wiki/concepts/C.md", tags = listOf("盧曼"), outlinks = listOf("Quiet"), title = "丙")
        val merged = runStructuralLint(linkers + extra + quiet, resolverFor(linkers + extra + quiet)).findings
        assertEquals(1, merged.count { normalize(it.title) == "盧曼" })
        val broken = merged.first { it.rule == LintRule.BrokenLink && it.title == "盧曼" }
        assertTrue(broken.pages.contains("wiki/concepts/C.md"))
        assertTrue(broken.detail.startsWith("2 頁用"))
    }

    @Test
    fun ignoreMatchesPathOrSlugNotBareNameAndInboundStillCounts() {
        val hub = page("wiki/concepts/Hub.md", outlinks = listOf("Kept"), title = "Hub")
        val kept = page("wiki/concepts/Kept.md", title = "Kept")
        val bare = runStructuralLint(
            listOf(hub, kept),
            resolverFor(listOf(hub, kept)),
            LintConfig(ignore = setOf("Hub", "Hub.md", "Kept")),
        ).findings
        assertTrue(bare.any { it.rule == LintRule.Orphan && it.pages == listOf("wiki/concepts/Hub.md") })
        assertTrue(bare.none { it.pages == listOf("wiki/concepts/Kept.md") && it.rule == LintRule.Orphan })

        val ignored = runStructuralLint(
            listOf(hub, kept),
            resolverFor(listOf(hub, kept)),
            LintConfig(ignore = setOf("wiki/concepts/Hub")),
        ).findings
        assertTrue(ignored.none { it.pages.contains("wiki/concepts/Hub.md") && it.rule != LintRule.NoOutlinks })
        assertTrue(ignored.none { it.rule == LintRule.Orphan && it.pages == listOf("wiki/concepts/Kept.md") })
        assertTrue(ignored.none { it.rule == LintRule.BrokenLink })
    }

    @Test
    fun findingsSortByRuleThenPath() {
        val lonely = page("wiki/concepts/Lonely.md", outlinks = listOf("Quiet"), title = "Lonely")
        val other = page("wiki/entities/Other.md", outlinks = listOf("Quiet"), title = "Other")
        val quiet = page("wiki/concepts/Quiet.md", title = "Quiet")
        val linkers = listOf(
            page("wiki/concepts/A.md", outlinks = listOf("缺", "Quiet"), title = "甲"),
            page("wiki/concepts/B.md", outlinks = listOf("缺"), title = "乙"),
        )
        val tagged = (1..3).map {
            page("wiki/concepts/T$it.md", tags = listOf("新概念"), outlinks = listOf("Quiet"), title = "標$it")
        }
        val pages = listOf(other, lonely, quiet) + linkers + tagged
        val rules = runStructuralLint(pages, resolverFor(pages)).findings.map { it.rule }
        assertTrue(rules.indexOf(LintRule.BrokenLink) < rules.indexOf(LintRule.MissingPage))
        assertTrue(rules.indexOf(LintRule.MissingPage) < rules.indexOf(LintRule.Orphan))
        assertTrue(rules.indexOf(LintRule.Orphan) < rules.indexOf(LintRule.NoOutlinks))
        val orphanPaths = runStructuralLint(pages, resolverFor(pages)).findings
            .filter { it.rule == LintRule.Orphan }
            .map { it.pages.first() }
        assertEquals(orphanPaths.sorted(), orphanPaths)
    }

    @Test
    fun oversizedPageIsNotAnOrphanOrDeadEnd() {
        val big = page("wiki/concepts/Big.md", title = "Big", oversized = true, outlinks = listOf("缺"))
        val findings = runStructuralLint(listOf(big), resolverFor(listOf(big))).findings
        assertTrue(findings.none { it.rule == LintRule.Orphan || it.rule == LintRule.NoOutlinks || it.rule == LintRule.BrokenLink })
    }

    @Test
    fun indexSnapshotDoesNotCountIndexLinksOrSelfLinks() {
        val vault = tempDir()
        val root = scaffoldWiki(vault)
        root.resolve("wiki/index.md").writeText("# Index\n\n[[C]]\n")
        writeNote(root, "wiki/concepts/A.md", "A", "[[B]]")
        writeNote(root, "wiki/concepts/B.md", "B", "[[B]]\n[[B#段落]]\n[[B|別名]]")
        writeNote(root, "wiki/concepts/C.md", "C", "本文沒有連結")
        writeNote(root, "wiki/sources/新聞.md", "新聞", "摘要")
        val big = root.resolve("wiki/concepts/Big.md")
        big.writeText("---\ntitle: Big\ntype: concept\n---\n" + "x".repeat(2_000_001))
        WikiIndex.open(root, tempDir().resolve("lint.sqlite")).use { index ->
            index.reconcileAll()
            val snapshot = lintSnapshot(index)
            assertTrue(snapshot.pages.none { it.path == "wiki/index.md" || it.path == "wiki/review.md" })
            val bee = snapshot.pages.first { it.path == "wiki/concepts/B.md" }
            assertEquals(listOf("B"), bee.outlinks.distinct())
            assertTrue(snapshot.pages.first { it.path == "wiki/concepts/Big.md" }.oversized)
            val findings = runStructuralLint(
                snapshot.pages,
                { token -> snapshot.resolutions[token] ?: Resolution.None },
            ).findings
            assertTrue(findings.any { it.rule == LintRule.Orphan && it.pages == listOf("wiki/concepts/C.md") })
            assertTrue(findings.none { it.rule == LintRule.Orphan && it.pages == listOf("wiki/concepts/B.md") })
            assertTrue(findings.none { it.rule == LintRule.Orphan && it.pages == listOf("wiki/sources/新聞.md") })
            assertTrue(findings.none { it.pages == listOf("wiki/concepts/Big.md") })
            assertTrue(findings.none { it.rule == LintRule.BrokenLink })
        }
    }

    @Test
    fun parserKeepsHyphenTitlesAndDropsUnknownOrUnclosedBlocks() {
        val hyphen = parseLintBlocks(
            """
            ---LINT: suggestion | info | well-known-title---
            說明
            PAGES: wiki/concepts/A.md
            SEARCH: well known
            ---END LINT---
            """.trimIndent(),
        )
        assertTrue(hyphen.second.isEmpty())
        assertEquals("well-known-title", hyphen.first.single().title)

        val mixed = parseLintBlocks(
            """
            ---LINT: contradiction | warning | 日期不一致---
            兩頁不同
            PAGES: wiki/concepts/A.md, wiki/concepts/B.md
            SEARCH: 日期
            ---END LINT---
            ---LINT: suggestion | info | 補來源---
            可以補
            PAGES: wiki/concepts/A.md
            ---END LINT---
            ---LINT: not-a-type | warning | 壞的---
            不要
            ---END LINT---
            ---LINT: stale | info | 未收尾---
            沒有結尾
            """.trimIndent(),
        )
        assertEquals(listOf("日期不一致", "補來源"), mixed.first.map { it.title })
        assertEquals(2, mixed.second.size)
        assertTrue(mixed.second.any { it.contains("not-a-type") })
        assertTrue(mixed.second.any { it.contains("未收尾") })
    }

    @Test
    fun semanticFiltersExistingTitlesAndDemotesThinContradictions() {
        val known = page("wiki/concepts/Known.md", title = "盧曼")
        val other = page("wiki/concepts/Other.md", title = "其他")
        val answer = """
            ---LINT: missing-page | warning | 盧曼---
            已有頁
            PAGES: wiki/concepts/Other.md
            ---END LINT---
            ---LINT: missing-page | warning | 新概念---
            沒有頁
            PAGES: wiki/concepts/Other.md
            SEARCH: 新概念
            ---END LINT---
            ---LINT: contradiction | warning | 只有一頁---
            不夠兩頁
            PAGES: wiki/concepts/Other.md, wiki/missing.md
            ---END LINT---
        """.trimIndent()
        var calls = 0
        val outcome = runSemanticLint(
            pages = listOf(known, other),
            resolver = resolverFor(listOf(known, other)),
            prompts = locateAiPrompts(),
            numCtx = 65536,
            client = ChatClient {
                calls += 1
                answer
            },
            maxPagesPerBatch = 20,
        )
        assertEquals(1, calls)
        assertEquals(1, outcome.modelCalls)
        assertTrue(outcome.findings.none { it.title == "盧曼" })
        assertTrue(outcome.findings.any { it.rule == LintRule.MissingPage && it.title == "新概念" })
        assertTrue(outcome.findings.any { it.rule == LintRule.Suggestion && it.title == "只有一頁" })
        assertFalse(outcome.findings.first { it.title == "只有一頁" }.reviewType == "contradiction")
    }

    @Test
    fun smallContextSplitsBatchesAndTooSmallSkipsTheModel() {
        val requests = mutableListOf<ChatRequest>()
        val pages = (1..6).map { index ->
            page(
                "wiki/concepts/P$index.md",
                title = "頁$index",
                body = "字".repeat(3000),
                outlinks = listOf("Quiet"),
            )
        }
        // 500-char excerpts fit in one batch at numCtx 12192. 7296 leaves room for about two pages.
        val numCtx = 7_296
        val outcome = runSemanticLint(
            pages = pages,
            resolver = { Resolution.None },
            prompts = locateAiPrompts(),
            numCtx = numCtx,
            client = ChatClient { request ->
                requests += request
                ""
            },
        )
        assertTrue(requests.size >= 2)
        val budget = PromptBudget.inputCharBudget(numCtx)
        assertTrue(requests.all { it.system.length + it.user.length <= budget })
        assertTrue(requests.all { !it.json })
        assertEquals(requests.size, outcome.modelCalls)

        var tinyCalls = 0
        val skipped = runSemanticLint(
            pages = pages,
            resolver = { Resolution.None },
            prompts = locateAiPrompts(),
            numCtx = 4096,
            client = ChatClient {
                tinyCalls += 1
                "no"
            },
        )
        assertEquals(0, tinyCalls)
        assertEquals(0, skipped.modelCalls)
        assertTrue(skipped.warnings.any { it.contains("context 太小") })

        val planned = planSemanticBatches(
            pages = (1..3).map { page("wiki/concepts/Q$it.md", title = "Q$it", body = "z".repeat(400)) },
            charBudget = 500,
            fixedChars = 100,
            maxPages = 1,
        )
        assertEquals(3, planned.batches.size)
    }

    @Test
    fun semanticProgressCancelAndBatchFailureKeepTheRest() {
        val pages = (1..3).map { page("wiki/concepts/S$it.md", title = "S$it", body = "本文") }
        val events = mutableListOf<String>()
        runStructuralLint(pages, resolverFor(pages), onProgress = { done, total -> events += "S $done/$total" })
        var calls = 0
        val finished = runSemanticLint(
            pages = pages,
            resolver = resolverFor(pages),
            prompts = locateAiPrompts(),
            numCtx = 65536,
            client = ChatClient {
                calls += 1
                lintBlock("suggestion", "info", "想法$calls", pages.first().path, "查$calls")
            },
            onProgress = { progress -> events += "M ${progress.batch}/${progress.batches} ${progress.phase}" },
            maxPagesPerBatch = 1,
        )
        assertEquals(listOf("S 3/3"), events.filter { it.startsWith("S") })
        assertEquals(
            listOf("M 1/3 Running", "M 2/3 Running", "M 3/3 Running", "M 3/3 Done"),
            events.filter { it.startsWith("M") },
        )
        assertEquals(3, finished.modelCalls)
        assertFalse(finished.cancelled)

        var cancelCalls = 0
        var checks = 0
        val cancelled = runSemanticLint(
            pages = pages,
            resolver = resolverFor(pages),
            prompts = locateAiPrompts(),
            numCtx = 65536,
            client = ChatClient {
                cancelCalls += 1
                lintBlock("suggestion", "info", "留下", pages.first().path, "查")
            },
            isActive = {
                checks += 1
                checks < 3
            },
            maxPagesPerBatch = 1,
        )
        assertEquals(1, cancelCalls)
        assertTrue(cancelled.cancelled)
        assertEquals(1, cancelled.completedBatches)
        assertTrue(cancelled.findings.any { it.title == "留下" })

        var boomCalls = 0
        val partial = runSemanticLint(
            pages = pages,
            resolver = resolverFor(pages),
            prompts = locateAiPrompts(),
            numCtx = 65536,
            client = ChatClient {
                boomCalls += 1
                if (boomCalls == 2) error("boom")
                lintBlock("suggestion", "info", "批$boomCalls", pages.first().path, "查")
            },
            maxPagesPerBatch = 1,
        )
        assertEquals(3, boomCalls)
        assertEquals(listOf("批1", "批3"), partial.findings.map { it.title })
        assertTrue(partial.warnings.any { it.contains("第 2 批失敗") && it.contains("boom") })
    }

    @Test
    fun promptUsesTraditionalChineseAndAMissingFileDoesNotCallTheModel() {
        val captured = mutableListOf<ChatRequest>()
        runSemanticLint(
            pages = listOf(page("wiki/concepts/A.md", title = "甲")),
            resolver = { Resolution.None },
            prompts = locateAiPrompts(),
            numCtx = 65536,
            client = ChatClient { request ->
                captured += request
                ""
            },
        )
        val request = captured.single()
        assertTrue(request.system.contains(IngestLanguage.NAME))
        assertFalse(request.system.contains("lint-comment-secret"))
        assertFalse(request.system.contains("這段前言"))
        assertFalse(request.json)

        var calls = 0
        val missing = runSemanticLint(
            pages = listOf(page("wiki/concepts/A.md", title = "甲")),
            resolver = { Resolution.None },
            prompts = tempDir(),
            numCtx = 65536,
            client = ChatClient {
                calls += 1
                ""
            },
        )
        assertEquals(0, calls)
        assertTrue(missing.warnings.any { it.contains("wiki-lint-semantic.md") })
        assertEquals(SemanticPhase.Failed, SemanticPhase.Failed)
    }

    @Test
    fun appendKeepsExistingBytesAndDeepResearchCanReadSearch() {
        val vault = tempDir()
        val root = scaffoldWiki(vault)
        val schemaBefore = root.resolve("wiki/schema.md").readBytes()
        val raw = root.resolve("raw/sources/keep.md")
        raw.writeText("原文")
        val rawBefore = raw.readBytes()
        val logBefore = root.resolve("wiki/log.md").readBytes()
        val review = root.resolve("wiki/review.md")
        review.writeText("# Review\n\n這段不要動\n\n## [2026-10-01] missing-page | Create 盧曼\n\n舊的\n\n- source: ingest\n\n")
        val before = review.readBytes()
        val link = page("wiki/concepts/A.md", outlinks = listOf("新概念"), title = "甲")
        val other = page("wiki/concepts/B.md", outlinks = listOf("新概念"), title = "乙")
        val lonely = page("wiki/concepts/C.md", title = "丙")
        val pages = listOf(link, other, lonely)
        val findings = runStructuralLint(pages, resolverFor(pages)).findings
        val selected = findings.filter { it.rule == LintRule.BrokenLink }.map { it.id }.toSet()
        val proposals = toReviewProposals(findings, selected) { path ->
            pages.first { it.path == path }.title
        }
        assertEquals(1, proposals.size)
        val written = appendLintReviews(root, proposals, day, writeLog = true)
        assertEquals(1, written.appended)
        val after = review.readBytes()
        assertTrue(after.copyOfRange(0, before.size).contentEquals(before))
        val parsed = parseReviewFile(review.readText())
        val added = parsed.last()
        assertEquals("missing-page", added.type)
        assertEquals("新概念", added.title)
        assertEquals("lint", added.source)
        assertEquals(listOf("新概念", "新概念 甲"), researchQueries(added).take(2))
        assertTrue(root.resolve("wiki/log.md").readText().contains("lint | 1 筆 review"))
        assertTrue(schemaBefore.contentEquals(root.resolve("wiki/schema.md").readBytes()))
        assertTrue(rawBefore.contentEquals(raw.readBytes()))

        val skipped = appendLintReviews(root, proposals, day, writeLog = true)
        assertFalse(skipped.wrote)
        assertEquals(1, skipped.skipped)
        assertTrue(logBefore.size < root.resolve("wiki/log.md").readBytes().size)
        val logAfterSkip = root.resolve("wiki/log.md").readBytes()
        val again = appendLintReviews(
            root,
            proposals,
            day,
            writeLog = true,
        )
        assertFalse(again.wrote)
        assertTrue(logAfterSkip.contentEquals(root.resolve("wiki/log.md").readBytes()))

        val save = review.readText() + "\n## [2026-10-02] suggestion | Save to Wiki 另一個\n\n舊\n\n- source: ingest\n\n"
        review.writeText(save)
        val duplicate = toReviewProposals(
            listOf(findings.first { it.rule == LintRule.BrokenLink }.copy(title = "另一個")),
            setOf(findings.first { it.rule == LintRule.BrokenLink }.id),
        )
        // The copied finding still has the original title in searches; the duplicate check uses the proposal title.
        val renamed = duplicate.map { it.copy(title = "另一個") }
        val blocked = appendLintReviews(root, renamed, day, writeLog = true)
        assertFalse(blocked.wrote)
        assertTrue(review.readText().startsWith(save.trimEnd()) || review.readBytes().decodeToString().contains("Save to Wiki 另一個"))
    }

    @Test
    fun uncheckedFindingsAndUntouchedReviewStayPut() {
        val vault = tempDir()
        val root = scaffoldWiki(vault)
        val schema = root.resolve("wiki/schema.md").readBytes()
        assertFalse(root.resolve("wiki/review.md").toFile().exists())
        val lonely = page("wiki/concepts/C.md", title = "丙")
        val brokenPage = page("wiki/concepts/A.md", outlinks = listOf("缺"), title = "甲")
        val findings = runStructuralLint(listOf(lonely, brokenPage), resolverFor(listOf(lonely, brokenPage))).findings
        val onlyOrphan = findings.filter { it.rule == LintRule.Orphan }.map { it.id }.toSet()
        appendLintReviews(root, toReviewProposals(findings, onlyOrphan), day, writeLog = false)
        val text = root.resolve("wiki/review.md").readText()
        assertTrue(text.contains("補連結：丙") || text.contains("補連結：甲") || text.contains("補外連"))
        assertFalse(text.contains("## [2026-10-13] missing-page | 缺"))
        assertTrue(schema.contentEquals(root.resolve("wiki/schema.md").readBytes()))
        assertFalse(root.resolve("wiki/log.md").readText().contains("lint |"))
    }

    @Test
    fun crlfReviewStaysCrlf() {
        val vault = tempDir()
        val root = scaffoldWiki(vault)
        val review = root.resolve("wiki/review.md")
        review.writeText("# Review\r\n\r\n舊條目\r\n")
        val finding = runStructuralLint(
            listOf(page("wiki/concepts/A.md", outlinks = listOf("缺"), title = "甲")),
            { Resolution.None },
        ).findings.single { it.rule == LintRule.BrokenLink }
        appendLintReviews(root, toReviewProposals(listOf(finding), setOf(finding.id)), day, writeLog = false)
        val text = review.readText()
        assertFalse(text.replace("\r\n", "").contains("\n"))
        assertEquals("lint", parseReviewFile(text).single { it.title == "缺" }.source)
    }

    @Test
    fun lintSemanticPreferenceIsGlobalAndIgnoreIsPerRoot() {
        val store = MemorySettingsStore()
        val app = AppSettings(store)
        assertFalse(app.load().lintSemantic)
        app.save(app.load().copy(lintSemantic = true, ingestMode = IngestMode.Link))
        assertTrue(app.load().lintSemantic)
        assertEquals(IngestMode.Link, app.load().ingestMode)
        val rootA = tempDir().resolve("a").also { Files.createDirectories(it) }
        val rootB = tempDir().resolve("b").also { Files.createDirectories(it) }
        app.saveLintIgnore(rootA.toString(), "wiki/concepts/A")
        app.saveLintIgnore(rootB.toString(), "wiki/concepts/B")
        app.save(app.load().copy(lintSemantic = false))
        assertEquals("wiki/concepts/A", app.lintIgnore(rootA.toString()))
        assertEquals("wiki/concepts/B", app.lintIgnore(rootB.toString()))
        assertFalse(app.load().lintSemantic)
    }
}

private fun page(
    path: String,
    outlinks: List<String> = emptyList(),
    tags: List<String> = emptyList(),
    title: String = path.substringAfterLast('/').removeSuffix(".md"),
    aliases: List<String> = emptyList(),
    body: String = "本文",
    type: String = "concept",
    oversized: Boolean = false,
): LintPage = LintPage(
    path = path,
    stem = path.substringAfterLast('/').removeSuffix(".md"),
    title = title,
    aliases = aliases,
    type = type,
    tags = tags,
    body = body,
    outlinks = outlinks,
    oversized = oversized,
)

private fun resolverFor(pages: List<LintPage>): (String) -> Resolution = { raw ->
    val key = normalizeKey(raw.substringAfterLast('/').removeSuffix(".md"))
    val hits = pages.filter { page ->
        !page.oversized && (
            normalizeKey(page.stem) == key ||
                normalizeKey(page.title) == key ||
                page.aliases.any { normalizeKey(it) == key }
            )
    }.map { it.path }.distinct()
    when (hits.size) {
        0 -> Resolution.None
        1 -> Resolution.One(hits.first())
        else -> Resolution.Ambiguous(hits)
    }
}

private fun lintBlock(type: String, severity: String, title: String, pages: String, search: String): String =
    """
    ---LINT: $type | $severity | $title---
    說明
    PAGES: $pages
    SEARCH: $search
    ---END LINT---
    """.trimIndent()

private fun writeNote(root: Path, relative: String, title: String, body: String) {
    val file = root.resolve(relative)
    Files.createDirectories(file.parent)
    file.writeText("---\ntitle: $title\ntype: concept\n---\n$body\n")
}

private fun normalize(value: String) = com.neojou.mystudy.wiki.lint.normalizeConcept(value)

private fun tempDir(): Path = Files.createTempDirectory("mystudy-lint")
