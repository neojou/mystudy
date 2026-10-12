package com.neojou.mystudy.wiki.model

/**
 * Result of matching a title, alias, filename stem, or path against the index.
 */
sealed class Resolution {
    data object None : Resolution()

    data class One(val path: String) : Resolution()

    data class Ambiguous(val paths: List<String>) : Resolution()
}

/**
 * One wiki page the user may confirm. The app built [newText]; the model does not write files.
 *
 * [selectable] is false when the title matches more than one page. Those rows cannot be written.
 * [conflict] rows start unchecked. Checking one appends [newText] and does not replace the old body.
 */
data class PageProposal(
    val relativePath: String,
    val previousText: String?,
    val newText: String,
    val conflict: Boolean,
    val selectable: Boolean,
    val note: String,
    val catalogHeading: String,
    val catalogLine: String,
)

/**
 * One REVIEW block the user may append to `wiki/review.md` after confirming.
 */
data class ReviewProposal(
    val id: String,
    val type: String,
    val title: String,
    val body: String,
    val options: List<String>,
    val searches: List<String>,
)

/**
 * A confirmable set of page writes, plus the log line that is appended only after they succeed.
 * [reviews] is empty for Ask and Chat filing. [sourcePath] is the vault-relative note for ingest.
 */
data class DraftProposal(
    val pages: List<PageProposal>,
    val logTitle: String,
    val operation: String,
    val schemaWarning: String?,
    val schemaTruncated: Boolean,
    val reviews: List<ReviewProposal> = emptyList(),
    val warnings: List<String> = emptyList(),
    val sourcePath: String = "",
)

fun defaultChecked(pages: List<PageProposal>): Set<String> =
    pages.filter { it.selectable && !it.conflict }.map { it.relativePath }.toSet()

fun defaultCheckedReviews(reviews: List<ReviewProposal>): Set<String> =
    reviews.map { it.id }.toSet()

data class ApplyResult(
    val written: List<String>,
    val failed: String?,
)
