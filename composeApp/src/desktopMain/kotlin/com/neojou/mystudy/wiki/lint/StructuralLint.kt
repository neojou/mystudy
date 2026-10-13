package com.neojou.mystudy.wiki.lint

import com.neojou.mystudy.wiki.model.Resolution

data class StructuralResult(
    val findings: List<LintFinding>,
    val done: Int,
    val total: Int,
    val cancelled: Boolean,
)

fun runStructuralLint(
    pages: List<LintPage>,
    resolver: (String) -> Resolution,
    config: LintConfig = LintConfig(),
    onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    isActive: () -> Boolean = { true },
): StructuralResult {
    val total = pages.size
    if (total == 0) {
        onProgress(0, 0)
        return StructuralResult(emptyList(), 0, 0, cancelled = false)
    }
    val byPath = pages.associateBy { it.path }
    val inbound = HashMap<String, Int>()
    val broken = LinkedHashMap<String, MutableList<String>>()
    val ambiguous = LinkedHashMap<String, MutableList<String>>()
    var done = 0
    var cancelled = false
    for (page in pages) {
        if (!isActive()) {
            cancelled = true
            break
        }
        if (!page.oversized) {
            for (raw in page.outlinks.distinct()) {
                if (skipTarget(raw)) continue
                when (val resolved = resolver(raw)) {
                    is Resolution.One -> {
                        if (resolved.path != page.path) {
                            inbound[resolved.path] = (inbound[resolved.path] ?: 0) + 1
                        }
                    }
                    Resolution.None -> {
                        if (!isIgnored(page.path, config.ignore)) {
                            broken.getOrPut(raw) { mutableListOf() }.add(page.path)
                        }
                    }
                    is Resolution.Ambiguous -> {
                        if (!isIgnored(page.path, config.ignore)) {
                            ambiguous.getOrPut(raw) { mutableListOf() }.add(page.path)
                        }
                    }
                }
            }
        }
        done += 1
        if (done % LINT_PROGRESS_STRIDE == 0 || done == total) onProgress(done, total)
    }
    if (cancelled) onProgress(done, total)
    val findings = mutableListOf<LintFinding>()
    for ((target, sources) in broken) {
        val listed = sources.distinct().sorted()
        findings += linkFinding(
            rule = LintRule.BrokenLink,
            severity = LintSeverity.Warning,
            title = target,
            detail = "${listed.size} 頁用 [[$target]] 連結，但 wiki 裡沒有這一頁。",
            pages = listed,
            related = listed.mapNotNull { byPath[it] },
        )
    }
    for ((target, sources) in ambiguous) {
        val listed = sources.distinct().sorted()
        findings += linkFinding(
            rule = LintRule.Ambiguous,
            severity = LintSeverity.Info,
            title = target,
            detail = "${listed.size} 頁用 [[$target]] 連結，但對到多個頁。",
            pages = listed,
            related = listed.mapNotNull { byPath[it] },
        )
    }
    if (config.tagSignal) {
        applyTagSignal(pages, resolver, config, byPath, findings)
    }
    for (page in pages) {
        if (page.oversized || isIgnored(page.path, config.ignore)) continue
        val excluded = config.excludeOrphanPrefixes.any { page.path.startsWith(it) }
        if (!excluded && (inbound[page.path] ?: 0) == 0) {
            findings += pageFinding(
                rule = LintRule.Orphan,
                title = "補連結：${page.title.ifBlank { page.stem }}",
                detail = "這一頁沒有其他頁連過來。",
                page = page,
                search = page.title.ifBlank { page.stem },
            )
        }
        if (page.outlinks.isEmpty()) {
            findings += pageFinding(
                rule = LintRule.NoOutlinks,
                title = "補外連：${page.title.ifBlank { page.stem }}",
                detail = "這一頁沒有連到其他頁。",
                page = page,
                search = page.title.ifBlank { page.stem },
            )
        }
    }
    return StructuralResult(
        findings = sortFindings(findings),
        done = done,
        total = total,
        cancelled = cancelled,
    )
}

private fun applyTagSignal(
    pages: List<LintPage>,
    resolver: (String) -> Resolution,
    config: LintConfig,
    byPath: Map<String, LintPage>,
    findings: MutableList<LintFinding>,
) {
    data class Group(val paths: MutableSet<String>, val spellings: MutableMap<String, Int>)
    val groups = LinkedHashMap<String, Group>()
    for (page in pages) {
        if (page.oversized || isIgnored(page.path, config.ignore)) continue
        for (tag in page.tags) {
            val concept = normalizeConcept(tag)
            if (concept.isEmpty()) continue
            val group = groups.getOrPut(concept) { Group(linkedSetOf(), linkedMapOf()) }
            group.paths += page.path
            val spelling = tag.trim()
            group.spellings[spelling] = (group.spellings[spelling] ?: 0) + 1
        }
    }
    val names = existingNames(pages)
    for (concept in groups.keys.sorted()) {
        val group = groups.getValue(concept)
        if (concept in names) continue
        if (group.spellings.keys.any { resolver(it) !is Resolution.None }) continue
        val display = group.spellings.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .first()
            .key
        val tagPaths = group.paths.sorted()
        val existing = findings.indexOfFirst { finding ->
            finding.rule == LintRule.BrokenLink && normalizeConcept(finding.title) == concept
        }
        if (existing >= 0) {
            val current = findings[existing]
            val union = (current.pages + tagPaths).distinct().sorted()
            findings[existing] = current.copy(
                pages = union,
                searches = searchesForConcept(current.title, union.mapNotNull { byPath[it] }),
            )
            continue
        }
        if (group.paths.size < config.missingTermMinPages) continue
        val related = tagPaths.mapNotNull { byPath[it] }
        findings += makeFinding(
            rule = LintRule.MissingPage,
            severity = LintSeverity.Warning,
            title = display,
            detail = "${tagPaths.size} 頁的 tags 有「$display」，但 wiki 裡沒有這一頁。",
            pages = tagPaths,
            searches = searchesForConcept(display, related),
        )
    }
}

private fun linkFinding(
    rule: LintRule,
    severity: LintSeverity,
    title: String,
    detail: String,
    pages: List<String>,
    related: List<LintPage>,
): LintFinding = makeFinding(
    rule = rule,
    severity = severity,
    title = title,
    detail = detail,
    pages = pages,
    searches = if (rule == LintRule.Ambiguous) emptyList() else searchesForConcept(title, related),
)

private fun pageFinding(
    rule: LintRule,
    title: String,
    detail: String,
    page: LintPage,
    search: String,
): LintFinding = makeFinding(
    rule = rule,
    severity = LintSeverity.Info,
    title = title,
    detail = detail,
    pages = listOf(page.path),
    searches = listOf(search).filter { it.isNotBlank() }.take(LINT_MAX_SEARCHES),
)

private fun makeFinding(
    rule: LintRule,
    severity: LintSeverity,
    title: String,
    detail: String,
    pages: List<String>,
    searches: List<String>,
): LintFinding {
    val path = pages.firstOrNull().orEmpty()
    return LintFinding(
        id = "${rule.name}\u0000$title\u0000$path",
        rule = rule,
        severity = severity,
        title = title,
        detail = detail,
        pages = pages,
        searches = searches,
        reviewType = reviewTypeFor(rule),
        defaultChecked = defaultCheckedFor(rule),
    )
}

fun searchesForConcept(concept: String, related: List<LintPage>): List<String> {
    val trimmed = concept.trim()
    if (trimmed.isEmpty()) return emptyList()
    val ranked = related
        .filter { it.title.isNotBlank() && normalizeConcept(it.title) != normalizeConcept(trimmed) }
        .groupBy { it.title }
        .map { (title, group) -> Triple(group.size, group.minOf { it.path }, title) }
        .sortedWith(compareByDescending<Triple<Int, String, String>> { it.first }.thenBy { it.second })
    val out = LinkedHashSet<String>()
    fun add(query: String) {
        val text = query.trim()
        if (text.isEmpty() || out.size >= LINT_MAX_SEARCHES) return
        if (out.any { normalizeConcept(it) == normalizeConcept(text) }) return
        out += text
    }
    add(trimmed)
    for (item in ranked) add("$trimmed ${item.third}")
    return out.toList()
}

fun sortFindings(findings: List<LintFinding>): List<LintFinding> =
    findings.sortedWith(
        compareBy<LintFinding> { it.rule.ordinal }
            .thenBy { it.pages.firstOrNull().orEmpty() }
            .thenBy { it.title },
    )

private fun skipTarget(raw: String): Boolean =
    raw.isBlank() ||
        raw.startsWith("http://", ignoreCase = true) ||
        raw.startsWith("https://", ignoreCase = true)
