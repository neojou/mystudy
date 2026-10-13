package com.neojou.mystudy.wiki.lint

import com.neojou.mystudy.wiki.model.Resolution
import java.text.Normalizer
import java.util.Locale

enum class LintRule {
    BrokenLink,
    Ambiguous,
    MissingPage,
    Orphan,
    NoOutlinks,
    Contradiction,
    Stale,
    Suggestion,
}

enum class LintSeverity { Warning, Info }

data class LintPage(
    val path: String,
    val stem: String,
    val title: String,
    val aliases: List<String>,
    val type: String,
    val tags: List<String>,
    val body: String,
    val outlinks: List<String>,
    val oversized: Boolean,
)

data class LintConfig(
    val ignore: Set<String> = emptySet(),
    val excludeOrphanPrefixes: List<String> = listOf("wiki/sources/", "wiki/queries/"),
    val tagSignal: Boolean = true,
    val missingLinkMin: Int = 2,
    val missingTermMinPages: Int = 3,
)

data class LintFinding(
    val id: String,
    val rule: LintRule,
    val severity: LintSeverity,
    val title: String,
    val detail: String,
    val pages: List<String>,
    val searches: List<String>,
    val reviewType: String?,
    val defaultChecked: Boolean,
    val alreadyInReview: Boolean = false,
)

data class LintSnapshot(
    val pages: List<LintPage>,
    val resolutions: Map<String, Resolution>,
)

sealed class LintProgress {
    data class Structural(val done: Int, val total: Int, val cancelled: Boolean) : LintProgress()

    data class Semantic(
        val batch: Int,
        val batches: Int,
        val pageFrom: Int,
        val pageTo: Int,
        val phase: SemanticPhase,
        val detail: String,
    ) : LintProgress()
}

enum class SemanticPhase { Running, Done, Cancelled, Skipped, Failed }

data class SemanticBatch(
    val index: Int,
    val pages: List<LintPage>,
    val pageFrom: Int,
    val pageTo: Int,
)

const val LINT_BATCH_MAX_PAGES: Int = 20
const val LINT_EXCERPT_CHARS: Int = 500
const val LINT_PROGRESS_STRIDE: Int = 25
const val LINT_MAX_LISTED_PAGES: Int = 10
const val LINT_MAX_SEARCHES: Int = 3
const val LINT_MAX_BLOCKS: Int = 8
const val LINT_TITLE_BUDGET_FRACTION: Double = 0.30

fun normalizeConcept(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFKC).lowercase(Locale.ROOT)

fun parseIgnoreList(text: String): Set<String> =
    text.split(',', '，', '\n', '\r')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .toSet()

fun isIgnored(path: String, ignore: Set<String>): Boolean {
    if (path in ignore) return true
    return path.removeSuffix(".md") in ignore
}

fun existingNames(pages: List<LintPage>): Set<String> = buildSet {
    for (page in pages) {
        if (page.oversized) continue
        if (page.stem.isNotBlank()) add(normalizeConcept(page.stem))
        if (page.title.isNotBlank()) add(normalizeConcept(page.title))
        for (alias in page.aliases) {
            if (alias.isNotBlank()) add(normalizeConcept(alias))
        }
    }
    remove("")
}

fun lintRuleLabel(rule: LintRule): String = when (rule) {
    LintRule.BrokenLink -> "broken-link"
    LintRule.Ambiguous -> "ambiguous"
    LintRule.MissingPage -> "missing-page"
    LintRule.Orphan -> "orphan"
    LintRule.NoOutlinks -> "no-outlinks"
    LintRule.Contradiction -> "contradiction"
    LintRule.Stale -> "stale"
    LintRule.Suggestion -> "suggestion"
}

fun lintSeverityLabel(severity: LintSeverity): String =
    if (severity == LintSeverity.Warning) "warning" else "info"

fun reviewTypeFor(rule: LintRule): String? = when (rule) {
    LintRule.BrokenLink, LintRule.MissingPage -> "missing-page"
    LintRule.Contradiction -> "contradiction"
    LintRule.Orphan, LintRule.NoOutlinks, LintRule.Stale, LintRule.Suggestion -> "suggestion"
    LintRule.Ambiguous -> null
}

fun defaultCheckedFor(rule: LintRule): Boolean = when (rule) {
    LintRule.BrokenLink, LintRule.MissingPage, LintRule.Contradiction, LintRule.Stale, LintRule.Suggestion -> true
    LintRule.Orphan, LintRule.NoOutlinks, LintRule.Ambiguous -> false
}

fun formatStructural(done: Int, total: Int, cancelled: Boolean): String = when {
    total <= 0 -> "沒有可檢查的 wiki 頁。"
    cancelled && done < total -> "本地規則：已取消（保留 $done/$total 頁的結果）"
    done >= total -> "本地規則：$done/$total 頁 ✓"
    else -> "本地規則：$done/$total 頁"
}

fun formatSemantic(progress: LintProgress.Semantic): String = when (progress.phase) {
    SemanticPhase.Running ->
        "語意健檢：批次 ${progress.batch}/${progress.batches}（第 ${progress.pageFrom}–${progress.pageTo} 頁）…"
    SemanticPhase.Done -> "語意健檢：✓ ${progress.batches}/${progress.batches}"
    SemanticPhase.Cancelled -> "語意健檢：已取消（保留 ${progress.batch}/${progress.batches} 批的結果）"
    SemanticPhase.Skipped -> "語意健檢跳過：${progress.detail}"
    SemanticPhase.Failed -> when {
        progress.detail.isBlank() -> "語意健檢失敗"
        progress.batch > 0 -> "第 ${progress.batch} 批失敗：${progress.detail}（其餘照常）"
        else -> progress.detail
    }
}
