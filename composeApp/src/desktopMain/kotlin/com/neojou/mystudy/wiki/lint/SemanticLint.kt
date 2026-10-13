package com.neojou.mystudy.wiki.lint

import com.neojou.mystudy.wiki.ingest.IngestLanguage
import com.neojou.mystudy.wiki.model.Resolution
import com.neojou.mystudy.wiki.ollama.ChatClient
import com.neojou.mystudy.wiki.ollama.ChatRequest
import com.neojou.mystudy.wiki.ollama.PromptBudget
import com.neojou.mystudy.wiki.prompt.PromptException
import com.neojou.mystudy.wiki.prompt.PromptFile
import com.neojou.mystudy.wiki.prompt.loadPrompt
import com.neojou.mystudy.wiki.prompt.render
import java.nio.file.Path

data class RawLintBlock(
    val type: String,
    val severity: String,
    val title: String,
    val body: String,
    val pages: List<String>,
    val searches: List<String>,
)

data class BatchPlan(
    val batches: List<SemanticBatch>,
    val skippedPages: List<String>,
)

data class SemanticOutcome(
    val findings: List<LintFinding>,
    val warnings: List<String>,
    val modelCalls: Int,
    val cancelled: Boolean,
    val completedBatches: Int,
    val totalBatches: Int,
)

private val lintStart = Regex("""^---LINT:\s*([a-z-]+)\s*\|\s*(warning|info)\s*\|\s*(.+?)\s*---$""")
private val lintTypes = setOf("contradiction", "stale", "missing-page", "suggestion")
private val folderRank = listOf("concepts", "entities", "sources", "queries")

fun parseLintBlocks(text: String): Pair<List<RawLintBlock>, List<String>> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').lines()
    val warnings = mutableListOf<String>()
    val blocks = mutableListOf<RawLintBlock>()
    var open = false
    var type = ""
    var severity = ""
    var title = ""
    val body = StringBuilder()
    var inFence = false

    fun discardOpen() {
        if (!open) return
        warnings += "Unclosed LINT discarded: $title"
        open = false
        inFence = false
        body.clear()
    }

    fun close() {
        if (!open) return
        val explanation = body.lineSequence()
            .map { it.trimEnd() }
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.startsWith("PAGES:") || trimmed.startsWith("SEARCH:") || trimmed.startsWith("OPTIONS:")
            }
            .joinToString("\n")
            .trim()
        val pages = LinkedHashSet<String>()
        val searches = LinkedHashSet<String>()
        for (line in body.lineSequence()) {
            val trimmed = line.trim()
            when {
                trimmed.startsWith("PAGES:") -> {
                    for (part in trimmed.removePrefix("PAGES:").split(',', '，')) {
                        val path = part.trim()
                        if (path.isNotEmpty()) pages += path
                    }
                }
                trimmed.startsWith("SEARCH:") -> {
                    for (part in trimmed.removePrefix("SEARCH:").split('|')) {
                        val query = part.trim()
                        if (query.isNotEmpty()) searches += query
                    }
                }
            }
        }
        if (type !in lintTypes) {
            warnings += "Ignored LINT type: $type"
        } else if (blocks.size >= LINT_MAX_BLOCKS) {
            warnings += "Ignored extra LINT blocks"
        } else {
            blocks += RawLintBlock(type, severity, title.trim(), explanation, pages.toList(), searches.toList())
        }
        open = false
        inFence = false
        body.clear()
    }

    for (line in lines) {
        val trimmed = line.trim()
        if (open && trimmed.startsWith("```")) {
            inFence = !inFence
            body.append(line).append('\n')
            continue
        }
        if (inFence && open) {
            body.append(line).append('\n')
            continue
        }
        val header = lintStart.matchEntire(trimmed)
        when {
            header != null -> {
                discardOpen()
                open = true
                type = header.groupValues[1]
                severity = header.groupValues[2]
                title = header.groupValues[3]
                inFence = false
                body.clear()
            }
            trimmed.startsWith("---LINT:") -> warnings += "Ignored LINT header"
            trimmed == "---END LINT---" && open -> close()
            open -> body.append(line).append('\n')
        }
    }
    discardOpen()
    return blocks to warnings
}

fun sortLintPages(pages: List<LintPage>): List<LintPage> =
    pages.sortedWith(
        compareBy<LintPage>(
            { path ->
                val rank = folderRank.indexOf(path.path.split('/').getOrNull(1).orEmpty())
                if (rank < 0) Int.MAX_VALUE else rank
            },
            { it.title },
            { it.path },
        ),
    )

fun fitTitles(pages: List<LintPage>, charBudget: Int): String {
    val lines = pages.map { page -> page.title.ifBlank { page.stem } }
    val full = lines.joinToString("\n")
    val cap = (charBudget * LINT_TITLE_BUDGET_FRACTION).toInt()
    if (cap <= 0) return "…（共 ${pages.size} 頁）"
    if (full.length <= cap) return full
    val suffix = "…（共 ${pages.size} 頁）"
    if (suffix.length >= cap) return suffix
    val kept = StringBuilder()
    for (line in lines) {
        val nextLen = kept.length + (if (kept.isEmpty()) 0 else 1) + line.length + 1 + suffix.length
        if (nextLen > cap) break
        if (kept.isNotEmpty()) kept.append('\n')
        kept.append(line)
    }
    if (kept.isNotEmpty()) kept.append('\n')
    kept.append(suffix)
    return kept.toString()
}

fun pageBlock(page: LintPage, excerptChars: Int): String {
    val excerpt = page.body.replace("\r\n", "\n").take(excerptChars.coerceAtLeast(0))
    val tags = page.tags.joinToString(", ")
    return buildString {
        append("### ")
        append(page.path)
        append('\n')
        append("title: ")
        append(page.title)
        append('\n')
        append("type: ")
        append(page.type)
        append('\n')
        append("tags: ")
        append(tags)
        if (excerpt.isNotEmpty()) {
            append('\n')
            append(excerpt)
        }
    }
}

fun planSemanticBatches(
    pages: List<LintPage>,
    charBudget: Int,
    fixedChars: Int,
    maxPages: Int = LINT_BATCH_MAX_PAGES,
): BatchPlan {
    val room = charBudget - fixedChars
    if (room <= 0 || pages.isEmpty()) return BatchPlan(emptyList(), emptyList())
    val ordered = sortLintPages(pages)
    val batches = mutableListOf<SemanticBatch>()
    val skipped = mutableListOf<String>()
    var current = mutableListOf<Pair<Int, LintPage>>()
    var currentLen = 0

    fun flush() {
        if (current.isEmpty()) return
        batches += SemanticBatch(
            index = batches.size + 1,
            pages = current.map { it.second },
            pageFrom = current.minOf { it.first },
            pageTo = current.maxOf { it.first },
        )
        current = mutableListOf()
        currentLen = 0
    }

    for ((offset, page) in ordered.withIndex()) {
        val ordinal = offset + 1
        val fitted = fitBlock(page, room) ?: run {
            skipped += page.path
            continue
        }
        val extra = fitted.length + if (current.isEmpty()) 0 else 2
        if (current.isNotEmpty() && (current.size >= maxPages || currentLen + extra > room)) flush()
        val again = fitted.length + if (current.isEmpty()) 0 else 2
        if (again > room) {
            skipped += page.path
            continue
        }
        current += ordinal to page
        currentLen += again
    }
    flush()
    return BatchPlan(batches, skipped)
}

fun runSemanticLint(
    pages: List<LintPage>,
    resolver: (String) -> Resolution,
    prompts: Path,
    numCtx: Int,
    client: ChatClient,
    onProgress: (LintProgress.Semantic) -> Unit = {},
    isActive: () -> Boolean = { true },
    maxPagesPerBatch: Int = LINT_BATCH_MAX_PAGES,
): SemanticOutcome {
    if (pages.isEmpty()) {
        return SemanticOutcome(emptyList(), emptyList(), 0, cancelled = false, 0, 0)
    }
    if (PromptBudget.tooSmall(numCtx)) {
        val progress = LintProgress.Semantic(0, 0, 0, 0, SemanticPhase.Skipped, "context 太小")
        onProgress(progress)
        return SemanticOutcome(emptyList(), listOf(formatSemantic(progress)), 0, false, 0, 0)
    }
    val prompt = try {
        loadPrompt(prompts, "wiki-lint-semantic.md")
    } catch (error: PromptException) {
        val message = error.message ?: "Prompt file ai-prompts/wiki-lint-semantic.md is missing."
        onProgress(LintProgress.Semantic(0, 0, 0, 0, SemanticPhase.Failed, message))
        return SemanticOutcome(emptyList(), listOf(message), 0, false, 0, 0)
    }
    val budget = PromptBudget.inputCharBudget(numCtx)
    val ordered = sortLintPages(pages)
    val titles = fitTitles(ordered, budget)
    val fixedRequest = renderLintRequest(prompt, 1, 1, "", titles)
    val fixedChars = fixedRequest.system.length + fixedRequest.user.length
    if (fixedChars > budget) {
        val progress = LintProgress.Semantic(0, 0, 0, 0, SemanticPhase.Skipped, "context 不夠放下標題與說明")
        onProgress(progress)
        return SemanticOutcome(emptyList(), listOf(formatSemantic(progress)), 0, false, 0, 0)
    }
    val plan = planSemanticBatches(ordered, budget, fixedChars, maxPagesPerBatch)
    val warnings = mutableListOf<String>()
    for (path in plan.skippedPages) warnings += "Skipped page that does not fit the context: $path"
    if (plan.batches.isEmpty()) {
        val progress = LintProgress.Semantic(0, 0, 0, 0, SemanticPhase.Skipped, "context 不夠放下標題與說明")
        onProgress(progress)
        warnings += formatSemantic(progress)
        return SemanticOutcome(emptyList(), warnings, 0, false, 0, 0)
    }
    val names = existingNames(pages)
    val known = pages.map { it.path }.toSet()
    val findings = mutableListOf<LintFinding>()
    var calls = 0
    var completed = 0
    val total = plan.batches.size
    for (batch in plan.batches) {
        if (!isActive()) {
            onProgress(cancelProgress(completed, total))
            return SemanticOutcome(findings, warnings, calls, true, completed, total)
        }
        onProgress(
            LintProgress.Semantic(
                batch.index,
                total,
                batch.pageFrom,
                batch.pageTo,
                SemanticPhase.Running,
                "",
            ),
        )
        val room = budget - fixedChars
        val pagesText = batch.pages.mapNotNull { fitBlock(it, room) }.joinToString("\n\n")
        val request = renderLintRequest(prompt, batch.index, total, pagesText, titles)
        if (request.system.length + request.user.length > budget) {
            warnings += "第 ${batch.index} 批失敗：prompt exceeds the context budget"
            onProgress(LintProgress.Semantic(batch.index, total, batch.pageFrom, batch.pageTo, SemanticPhase.Failed, "prompt exceeds the context budget"))
            continue
        }
        val answer = try {
            calls += 1
            client.complete(request)
        } catch (error: Exception) {
            if (!isActive()) {
                onProgress(cancelProgress(completed, total))
                return SemanticOutcome(findings, warnings, calls, true, completed, total)
            }
            val reason = error.message ?: "Ollama request failed"
            warnings += "第 ${batch.index} 批失敗：$reason"
            onProgress(
                LintProgress.Semantic(batch.index, total, batch.pageFrom, batch.pageTo, SemanticPhase.Failed, reason),
            )
            continue
        }
        if (!isActive()) {
            onProgress(cancelProgress(completed, total))
            return SemanticOutcome(findings, warnings, calls, true, completed, total)
        }
        val parsed = parseLintBlocks(answer)
        warnings += parsed.second
        findings += interpretLintBlocks(parsed.first, known, names, resolver)
        completed += 1
    }
    onProgress(LintProgress.Semantic(total, total, plan.batches.last().pageFrom, plan.batches.last().pageTo, SemanticPhase.Done, ""))
    return SemanticOutcome(findings, warnings, calls, false, completed, total)
}

fun interpretLintBlocks(
    blocks: List<RawLintBlock>,
    knownPaths: Set<String>,
    names: Set<String>,
    resolver: (String) -> Resolution,
): List<LintFinding> {
    val findings = mutableListOf<LintFinding>()
    for (block in blocks) {
        if (block.title.isBlank()) continue
        val paths = block.pages.filter { it in knownPaths }.distinct()
        var rule = when (block.type) {
            "contradiction" -> LintRule.Contradiction
            "stale" -> LintRule.Stale
            "missing-page" -> LintRule.MissingPage
            else -> LintRule.Suggestion
        }
        if (rule == LintRule.MissingPage) {
            val concept = normalizeConcept(block.title)
            if (concept.isEmpty() || concept in names || resolver(block.title) !is Resolution.None) continue
        }
        if (rule == LintRule.Contradiction && paths.size < 2) rule = LintRule.Suggestion
        val searches = block.searches.flatMap { it.split('|') }.map { it.trim() }.filter { it.isNotEmpty() }
            .distinct()
            .take(LINT_MAX_SEARCHES)
            .ifEmpty { listOf(block.title.trim()) }
        findings += LintFinding(
            id = "semantic\u0000${rule.name}\u0000${block.title}\u0000${paths.firstOrNull().orEmpty()}",
            rule = rule,
            severity = if (block.severity == "warning") LintSeverity.Warning else LintSeverity.Info,
            title = block.title.trim(),
            detail = block.body.ifBlank { block.title.trim() },
            pages = paths,
            searches = searches,
            reviewType = reviewTypeFor(rule),
            defaultChecked = defaultCheckedFor(rule),
        )
    }
    return findings
}

fun mergeLintFindings(structural: List<LintFinding>, semantic: List<LintFinding>): List<LintFinding> {
    val kept = structural.toMutableList()
    val keys = kept.mapNotNull { reviewDedupKey(it) }.toMutableSet()
    for (finding in semantic) {
        val key = reviewDedupKey(finding)
        if (key != null && key in keys) continue
        if (key != null) keys += key
        kept += finding
    }
    return sortFindings(kept)
}

fun reviewDedupKey(finding: LintFinding): String? {
    val type = finding.reviewType ?: return null
    return "$type|${normalizeConcept(finding.title)}"
}

private fun cancelProgress(completed: Int, total: Int): LintProgress.Semantic =
    LintProgress.Semantic(completed, total, 0, 0, SemanticPhase.Cancelled, "")

private fun fitBlock(page: LintPage, room: Int): String? {
    if (room <= 0) return null
    var best: String? = null
    var low = 0
    var high = LINT_EXCERPT_CHARS
    while (low <= high) {
        val mid = (low + high) / 2
        val text = pageBlock(page, mid)
        if (text.length <= room) {
            best = text
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}

private fun renderLintRequest(prompt: PromptFile, batch: Int, batches: Int, pages: String, titles: String): ChatRequest {
    val languageRule = render(prompt.require("language"), mapOf("lang" to IngestLanguage.NAME), prompt.fileName)
    val system = render(prompt.require("system"), mapOf("languageRule" to languageRule), prompt.fileName)
    val user = render(
        prompt.require("user"),
        mapOf(
            "batch" to batch.toString(),
            "batches" to batches.toString(),
            "pages" to pages,
            "titles" to titles,
        ),
        prompt.fileName,
    )
    return ChatRequest(system = system, user = user, json = false)
}
