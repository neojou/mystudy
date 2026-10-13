package com.neojou.mystudy.wiki.lint

import com.neojou.mystudy.wiki.markdown.appendLog
import com.neojou.mystudy.wiki.markdown.appendReviews
import com.neojou.mystudy.wiki.model.ReviewProposal
import com.neojou.mystudy.wiki.research.ReviewEntry
import com.neojou.mystudy.wiki.research.parseReviewFile
import com.neojou.mystudy.wiki.research.stripResearchPrefix
import com.neojou.mystudy.wiki.vault.assertWikiWrite
import com.neojou.mystudy.wiki.vault.atomicWrite
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText

data class LintWriteResult(
    val appended: Int,
    val skipped: Int,
    val message: String,
    val wrote: Boolean,
)

fun toReviewProposals(
    findings: List<LintFinding>,
    selected: Set<String>,
    titleOf: (String) -> String = { path -> path.substringAfterLast('/').removeSuffix(".md") },
): List<ReviewProposal> = findings.mapNotNull { finding ->
    val type = finding.reviewType ?: return@mapNotNull null
    if (finding.id !in selected || finding.alreadyInReview) return@mapNotNull null
    ReviewProposal(
        id = finding.id,
        type = type,
        title = finding.title,
        body = reviewBody(finding, titleOf),
        options = if (type == "missing-page") listOf("Create Page", "Skip") else listOf("Research", "Skip"),
        searches = finding.searches,
    )
}

fun reviewBody(finding: LintFinding, titleOf: (String) -> String): String {
    val listed = finding.pages.take(LINT_MAX_LISTED_PAGES)
    val related = listed.joinToString("、") { path ->
        val label = titleOf(path).ifBlank { path.substringAfterLast('/').removeSuffix(".md") }
        "[[$label]]"
    }
    val options = if (finding.reviewType == "missing-page") "Create Page | Skip" else "Research | Skip"
    return buildString {
        append("Lint：")
        append(lintRuleLabel(finding.rule))
        append("（")
        append(lintSeverityLabel(finding.severity))
        append("）\n")
        append(finding.detail.trim())
        if (related.isNotEmpty()) {
            append("\n相關頁：")
            append(related)
            if (finding.pages.size > listed.size) append("、等 ${finding.pages.size} 頁")
        }
        append("\nOPTIONS: ")
        append(options)
        for (search in finding.searches.take(LINT_MAX_SEARCHES)) {
            append("\nSEARCH: ")
            append(search)
        }
    }.trim()
}

fun isDuplicateReview(type: String, title: String, existing: List<ReviewEntry>): Boolean {
    val key = "$type|${normalizeConcept(title)}"
    if (existing.any { "${it.type}|${normalizeConcept(it.title)}" == key }) return true
    if (type != "missing-page") return false
    val concept = normalizeConcept(title)
    return existing.any { normalizeConcept(stripResearchPrefix(it.title)) == concept }
}

fun markExisting(findings: List<LintFinding>, existing: List<ReviewEntry>): List<LintFinding> =
    findings.map { finding ->
        val type = finding.reviewType
        if (type == null) finding
        else finding.copy(alreadyInReview = isDuplicateReview(type, finding.title, existing))
    }

/**
 * Appends checked reviews. When the file changed since it was read, reads again and dedups once.
 * A second mismatch writes nothing. Log is appended only after a non-empty review write.
 */
fun appendLintReviews(
    root: Path,
    proposals: List<ReviewProposal>,
    date: LocalDate,
    writeLog: Boolean = true,
): LintWriteResult {
    if (proposals.isEmpty()) return LintWriteResult(0, 0, "沒有新的項目。", false)
    val reviewPath = root.resolve("wiki").resolve("review.md")
    var seen = readBytes(reviewPath)
    repeat(2) { attempt ->
        val current = readBytes(reviewPath)
        if (attempt > 0 && !current.contentEquals(seen)) {
            return LintWriteResult(0, 0, "review.md 已被修改。請再按一次寫入。", false)
        }
        seen = current
        val existing = parseReviewFile(current.toString(Charsets.UTF_8))
        val fresh = proposals.filterNot { isDuplicateReview(it.type, it.title, existing) }
        val skipped = proposals.size - fresh.size
        if (fresh.isEmpty()) {
            return LintWriteResult(0, skipped, "沒有新的項目。略過重複 $skipped 筆。", false)
        }
        val again = readBytes(reviewPath)
        if (!again.contentEquals(seen)) {
            seen = again
            if (attempt == 1) {
                return LintWriteResult(0, 0, "review.md 已被修改。請再按一次寫入。", false)
            }
            return@repeat
        }
        val text = again.toString(Charsets.UTF_8)
        val newline = if (text.contains("\r\n")) "\r\n" else "\n"
        val normalized = fresh.map { proposal ->
            proposal.copy(body = proposal.body.replace("\r\n", "\n").replace("\n", newline))
        }
        val updated = appendReviews(text, date, normalized, "lint")
        atomicWrite(assertWikiWrite(root, "wiki/review.md"), updated)
        if (writeLog) {
            try {
                writeLintLog(root, date, fresh.size)
            } catch (error: Exception) {
                val reason = error.message ?: "log write failed"
                return LintWriteResult(fresh.size, skipped, "已加入 ${fresh.size} 筆，但 log 沒寫成：$reason", true)
            }
        }
        return LintWriteResult(
            appended = fresh.size,
            skipped = skipped,
            message = "已加入 ${fresh.size} 筆，略過重複 $skipped 筆。",
            wrote = true,
        )
    }
    return LintWriteResult(0, 0, "review.md 已被修改。請再按一次寫入。", false)
}

private fun writeLintLog(root: Path, date: LocalDate, count: Int) {
    val logPath = root.resolve("wiki").resolve("log.md")
    val current = if (logPath.exists()) logPath.readText() else "# Log\n"
    val updated = appendLog(current, date, "lint", "$count 筆 review", listOf("wiki/review.md"))
    atomicWrite(assertWikiWrite(root, "wiki/log.md"), updated)
}

private fun readBytes(path: Path): ByteArray = if (path.exists()) path.readBytes() else ByteArray(0)
