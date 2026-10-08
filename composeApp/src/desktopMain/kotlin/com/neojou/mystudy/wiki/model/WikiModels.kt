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
 * One claim extracted from a raw note. [excerpt] must appear verbatim in that note.
 */
data class Claim(
    val statement: String,
    val excerpt: String,
    val suggestedType: String,
    val suggestedTitle: String,
    val aliases: List<String>,
)

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
 * A confirmable set of page writes, plus the log line that is appended only after they succeed.
 */
data class DraftProposal(
    val pages: List<PageProposal>,
    val logTitle: String,
    val operation: String,
    val schemaWarning: String?,
    val schemaTruncated: Boolean,
)

fun defaultChecked(pages: List<PageProposal>): Set<String> =
    pages.filter { it.selectable && !it.conflict }.map { it.relativePath }.toSet()

data class ApplyResult(
    val written: List<String>,
    val failed: String?,
)
