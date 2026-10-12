package com.neojou.mystudy.wiki.ingest

import com.neojou.mystudy.wiki.markdown.appendSection
import com.neojou.mystudy.wiki.markdown.catalogLine
import com.neojou.mystudy.wiki.markdown.catalogTarget
import com.neojou.mystudy.wiki.markdown.ensureWikilink
import com.neojou.mystudy.wiki.markdown.headingForType
import com.neojou.mystudy.wiki.markdown.normalizeKey
import com.neojou.mystudy.wiki.markdown.parseNote
import com.neojou.mystudy.wiki.markdown.renderNewPage
import com.neojou.mystudy.wiki.markdown.safeFileStem
import com.neojou.mystudy.wiki.markdown.schemaCompatibilityWarning
import com.neojou.mystudy.wiki.model.DraftProposal
import com.neojou.mystudy.wiki.model.PageProposal
import com.neojou.mystudy.wiki.model.Resolution
import com.neojou.mystudy.wiki.model.ReviewProposal
import com.neojou.mystudy.wiki.ollama.ChatClient
import com.neojou.mystudy.wiki.ollama.ChatRequest
import com.neojou.mystudy.wiki.ollama.PromptBudget
import com.neojou.mystudy.wiki.prompt.PromptException
import com.neojou.mystudy.wiki.prompt.PromptFile
import com.neojou.mystudy.wiki.prompt.loadPrompt
import com.neojou.mystudy.wiki.prompt.locateAiPrompts
import com.neojou.mystudy.wiki.prompt.render
import java.nio.file.Path
import java.time.LocalDate

/**
 * One ingest of a vault note. The pipeline does not write files.
 * [sourcePath] is the note's path relative to the vault, with `/` separators.
 */
data class IngestInput(
    val sourceName: String,
    val sourcePath: String,
    val rawText: String,
    val schema: String,
    val purpose: String?,
    val indexText: String,
    val overview: String?,
    val numCtx: Int,
    val today: LocalDate,
    val resolve: (String, List<String>) -> Resolution,
    val read: (String) -> String?,
    val findSource: (String) -> String?,
    val exists: (String) -> Boolean,
)

sealed class IngestResult {
    data class Proposal(val proposal: DraftProposal) : IngestResult()

    data class Stopped(val message: String) : IngestResult()
}

class IngestPipeline(
    private val client: ChatClient,
    private val promptDirectory: Path? = null,
) {
    /**
     * Analyze, then generate. At most two model calls, both with `json = false`.
     * [onStage] is invoked on the caller thread before each call.
     */
    fun ingest(input: IngestInput, onStage: (String) -> Unit = {}): IngestResult {
        if (PromptBudget.tooSmall(input.numCtx)) {
            return IngestResult.Stopped("Context setting is too small. Nothing was sent.")
        }
        if (input.rawText.isBlank()) {
            return IngestResult.Stopped("This note is empty. Nothing was sent.")
        }
        if (input.schema.isBlank()) {
            return IngestResult.Stopped("wiki/schema.md is missing. Nothing was written.")
        }
        val analyzePrompt = loadOrStop("ingest-analyze.md")
        val generatePrompt = loadOrStop("ingest-generate.md")
        if (analyzePrompt is PromptLoad.Bad) return IngestResult.Stopped(analyzePrompt.message)
        if (generatePrompt is PromptLoad.Bad) return IngestResult.Stopped(generatePrompt.message)
        val analyzeFile = (analyzePrompt as PromptLoad.Ok).file
        val generateFile = (generatePrompt as PromptLoad.Ok).file
        val missing = requireSections(
            analyzeFile,
            listOf("system", "language", "user", "context-schema", "context-purpose", "context-index", "context-overview"),
        ) ?: requireSections(
            generateFile,
            listOf("system", "language", "user", "existing", "ambiguous", "none"),
        )
        if (missing != null) return IngestResult.Stopped(missing)

        onStage("Analyzing…")
        val prepared = try {
            prepareAnalyze(analyzeFile, input)
        } catch (error: PromptException) {
            return IngestResult.Stopped(error.message ?: "Prompt file could not be rendered. Nothing was sent.")
        }
        if (prepared == null) {
            return IngestResult.Stopped("This note is too big for one pass. Split it. Nothing was sent.")
        }
        val analysis = try {
            client.complete(ChatRequest(prepared.system, prepared.user, json = false)).trim()
        } catch (error: Exception) {
            return IngestResult.Stopped(error.message ?: "Ollama failed")
        }
        if (analysis.isEmpty()) {
            return IngestResult.Stopped("The analysis was empty. Nothing was written.")
        }

        onStage("Generating…")
        val generated = try {
            prepareGenerate(generateFile, input, analysis)
        } catch (error: PromptException) {
            return IngestResult.Stopped(error.message ?: "Prompt file could not be rendered. Nothing was sent.")
        }
        if (generated == null) {
            return IngestResult.Stopped("The generate prompt does not fit. Nothing was written.")
        }
        val raw = try {
            client.complete(ChatRequest(generated.system, generated.user, json = false))
        } catch (error: Exception) {
            return IngestResult.Stopped(error.message ?: "Ollama failed")
        }
        val stem = sourceStemOf(input.sourceName)
        val parsed = parseIngestBlocks(raw, stem)
        val warnings = prepared.warnings + generated.warnings + parsed.warnings
        val proposal = buildIngestProposal(
            input = input,
            analysis = analysis,
            stem = stem,
            files = parsed.files,
            reviews = parsed.reviews,
            warnings = warnings,
            schemaTruncated = prepared.schemaTruncated,
        )
        return IngestResult.Proposal(proposal)
    }

    private fun loadOrStop(fileName: String): PromptLoad = try {
        PromptLoad.Ok(loadPrompt(promptDirectory ?: locateAiPrompts(), fileName))
    } catch (error: PromptException) {
        PromptLoad.Bad(error.message ?: "Prompt file ai-prompts/$fileName could not be read. Nothing was sent.")
    }
}

private sealed class PromptLoad {
    data class Ok(val file: PromptFile) : PromptLoad()

    data class Bad(val message: String) : PromptLoad()
}

private fun requireSections(file: PromptFile, names: List<String>): String? {
    for (name in names) {
        try {
            file.require(name)
        } catch (error: PromptException) {
            return error.message
        }
    }
    return null
}

private class PreparedCall(
    val system: String,
    val user: String,
    val warnings: List<String>,
    val schemaTruncated: Boolean,
)

private fun prepareAnalyze(prompt: PromptFile, input: IngestInput): PreparedCall? {
    val budget = PromptBudget.inputCharBudget(input.numCtx)
    val schemaExcerpt = input.schema.take(PromptBudget.SCHEMA_CAP)
    val schemaTruncated = input.schema.length > schemaExcerpt.length
    val purposeFull = input.purpose?.takeIf { it.isNotBlank() }
    val purposeExcerpt = purposeFull?.take(PromptBudget.SCHEMA_CAP)
    val purposeTruncated = purposeFull != null && purposeFull.length > (purposeExcerpt?.length ?: 0)
    val overviewFull = input.overview?.takeIf { it.isNotBlank() }
    val overviewExcerpt = overviewFull?.take(PromptBudget.EXISTING_EXCERPT)
    val overviewTruncated = overviewFull != null && overviewFull.length > (overviewExcerpt?.length ?: 0)
    var indexExcerpt = if (input.indexText.length > budget) input.indexText.take(budget) else input.indexText
    var indexTruncated = indexExcerpt.length < input.indexText.length
    var includePurpose = purposeExcerpt != null
    var includeOverview = overviewExcerpt != null
    val warnings = mutableListOf<String>()

    fun assemble(): Pair<String, String> {
        val parts = mutableListOf<String>()
        parts += render(prompt.require("context-schema"), mapOf("schema" to schemaExcerpt), prompt.fileName)
        if (includePurpose && purposeExcerpt != null) {
            parts += render(prompt.require("context-purpose"), mapOf("purpose" to purposeExcerpt), prompt.fileName)
        }
        if (indexExcerpt.isNotEmpty()) {
            parts += render(prompt.require("context-index"), mapOf("index" to indexExcerpt), prompt.fileName)
        }
        if (includeOverview && overviewExcerpt != null) {
            parts += render(prompt.require("context-overview"), mapOf("overview" to overviewExcerpt), prompt.fileName)
        }
        val languageRule = render(prompt.require("language"), mapOf("lang" to IngestLanguage.NAME), prompt.fileName)
        val system = render(
            prompt.require("system"),
            mapOf("languageRule" to languageRule, "context" to parts.joinToString("\n\n")),
            prompt.fileName,
        )
        val user = render(
            prompt.require("user"),
            mapOf("sourceName" to input.sourceName, "raw" to input.rawText),
            prompt.fileName,
        )
        return system to user
    }

    var (system, user) = assemble()
    while (system.length + user.length > budget && indexExcerpt.isNotEmpty()) {
        val cut = indexExcerpt.length * 4 / 5
        indexExcerpt = if (cut < 400) "" else indexExcerpt.take(cut)
        indexTruncated = true
        val again = assemble()
        system = again.first
        user = again.second
    }
    if (system.length + user.length > budget && includeOverview) {
        includeOverview = false
        warnings += "overview.md was omitted to fit the prompt."
        val again = assemble()
        system = again.first
        user = again.second
    }
    if (system.length + user.length > budget && includePurpose) {
        includePurpose = false
        warnings += "purpose.md was omitted to fit the prompt."
        val again = assemble()
        system = again.first
        user = again.second
    }
    if (system.length + user.length > budget) return null
    if (purposeTruncated && includePurpose) warnings += "purpose.md was shortened to fit the prompt."
    if (overviewTruncated && includeOverview) warnings += "overview.md was shortened to fit the prompt."
    if (indexTruncated) warnings += "index.md was shortened to fit the prompt."
    return PreparedCall(system, user, warnings, schemaTruncated)
}

private class GenerateReady(
    val system: String,
    val user: String,
    val warnings: List<String>,
)

private fun prepareGenerate(prompt: PromptFile, input: IngestInput, analysis: String): GenerateReady? {
    val budget = PromptBudget.inputCharBudget(input.numCtx)
    val schemaExcerpt = input.schema.take(PromptBudget.SCHEMA_CAP)
    val stem = sourceStemOf(input.sourceName)
    val languageRule = render(prompt.require("language"), mapOf("lang" to IngestLanguage.NAME), prompt.fileName)
    val system = render(
        prompt.require("system"),
        mapOf("languageRule" to languageRule, "schema" to schemaExcerpt, "sourceStem" to stem),
        prompt.fileName,
    )
    val worthy = parsePageWorthy(analysis)
    val warnings = mutableListOf<String>()
    if (worthy.isEmpty()) {
        warnings += "No Page-worthy titles were parsed. Existing-page excerpts were omitted."
    }
    var blocks = existingBlocks(worthy, input)
    var dropped = false

    fun userText(current: List<ExistingBlock>): String = render(
        prompt.require("user"),
        mapOf(
            "analysis" to analysis,
            "sourceName" to input.sourceName,
            "sourcePath" to input.sourcePath,
            "raw" to input.rawText,
            "existing" to renderExisting(prompt, current),
        ),
        prompt.fileName,
    )

    var user = userText(blocks)
    while (system.length + user.length > budget && blocks.isNotEmpty()) {
        blocks = blocks.dropLast(1)
        dropped = true
        user = userText(blocks)
    }
    if (system.length + user.length > budget) return null
    if (dropped) warnings += "Some existing pages were omitted to fit the prompt."
    return GenerateReady(system, user, warnings)
}

private sealed class ExistingBlock {
    data class Page(val path: String, val title: String, val body: String) : ExistingBlock()

    data class Ambiguous(val title: String, val paths: String) : ExistingBlock()
}

private fun existingBlocks(items: List<PageWorthy>, input: IngestInput): List<ExistingBlock> {
    val blocks = mutableListOf<ExistingBlock>()
    for (item in items) {
        if (blocks.size >= 8) break
        val hint = item.existingHint
        val hinted = if (hint != null && isSafeWikiPagePath(hint) && input.exists(hint)) {
            Resolution.One(hint)
        } else {
            null
        }
        when (val resolution = hinted ?: input.resolve(item.title, item.aliases)) {
            is Resolution.One -> {
                val text = input.read(resolution.path) ?: continue
                val stem = resolution.path.substringAfterLast('/').removeSuffix(".md")
                val note = parseNote(text, stem)
                blocks += ExistingBlock.Page(
                    resolution.path,
                    note.title.ifBlank { item.title },
                    note.body.take(PromptBudget.EXISTING_EXCERPT),
                )
            }
            is Resolution.Ambiguous -> blocks += ExistingBlock.Ambiguous(
                item.title,
                resolution.paths.joinToString(", "),
            )
            Resolution.None -> Unit
        }
    }
    return blocks
}

private fun renderExisting(prompt: PromptFile, blocks: List<ExistingBlock>): String {
    if (blocks.isEmpty()) return prompt.require("none")
    return blocks.joinToString("\n\n") { block ->
        when (block) {
            is ExistingBlock.Page -> render(
                prompt.require("existing"),
                mapOf("path" to block.path, "title" to block.title, "body" to block.body),
                prompt.fileName,
            )
            is ExistingBlock.Ambiguous -> render(
                prompt.require("ambiguous"),
                mapOf("title" to block.title, "paths" to block.paths),
                prompt.fileName,
            )
        }
    }
}

private fun buildIngestProposal(
    input: IngestInput,
    analysis: String,
    stem: String,
    files: List<ParsedFile>,
    reviews: List<ParsedReview>,
    warnings: List<String>,
    schemaTruncated: Boolean,
): DraftProposal {
    val extraWarnings = warnings.toMutableList()
    val reserved = mutableSetOf<String>()
    val modelSource = files.firstOrNull { it.directory == "sources" }
    val others = files.filter { it.directory != "sources" }
    val fallback = files.isEmpty()
    if (fallback) {
        extraWarnings += "No FILE block. A source page was drafted locally from the analysis."
    } else if (modelSource == null) {
        extraWarnings += "Added a source page from the analysis because the model did not emit one."
    }
    val sourceRelative = chooseSourcePath(input, stem, reserved)
    val sourceParsed = modelSource?.let { parseNote(it.body, stem) }
    val sourceTitle = sourceParsed?.title?.takeIf { it.isNotBlank() } ?: stem
    val sourceBody = when {
        sourceParsed != null -> sourceParsed.body.trim().ifBlank { sourceTitle }
        else -> analysis.trim().ifBlank { sourceTitle }
    }
    val sourceExisting = input.read(sourceRelative)
    val sourcePage = if (sourceExisting == null && !input.exists(sourceRelative)) {
        PageProposal(
            relativePath = sourceRelative,
            previousText = null,
            newText = renderNewPage(
                sourceTitle,
                "source",
                sourceParsed?.tags.orEmpty(),
                sourceParsed?.aliases.orEmpty(),
                safeSourceRefs(sourceParsed?.sources.orEmpty(), input.sourcePath),
                input.today,
                sourceBody,
            ),
            conflict = false,
            selectable = true,
            note = if (modelSource == null) "Drafted locally from the analysis." else "",
            catalogHeading = headingForType("source"),
            catalogLine = catalogLine(
                catalogTarget(sourceRelative),
                oneLine(sourceBody, sourceTitle),
                "source",
                listOf(input.sourcePath),
            ),
        )
    } else {
        PageProposal(
            relativePath = sourceRelative,
            previousText = sourceExisting ?: "",
            newText = appendSection(sourceExisting ?: "", sourceBody, input.today),
            conflict = false,
            selectable = true,
            note = "Existing source page. The new section is appended.",
            catalogHeading = headingForType("source"),
            catalogLine = catalogLine(
                catalogTarget(sourceRelative),
                oneLine(sourceBody, sourceTitle),
                "source",
                listOf(input.sourcePath),
            ),
        )
    }
    reserved += sourceRelative
    val pages = mutableListOf(sourcePage)
    val link = catalogTarget(sourceRelative)
    for (file in others) {
        val filenameStem = file.relativePath.substringAfterLast('/').removeSuffix(".md")
        val parsed = parseNote(file.body, filenameStem)
        val title = parsed.title.ifBlank { filenameStem }
        val directory = when (parsed.type) {
            "entity" -> "wiki/entities"
            "concept" -> "wiki/concepts"
            else -> if (file.directory == "entities") "wiki/entities" else "wiki/concepts"
        }
        val type = if (directory == "wiki/entities") "entity" else "concept"
        val section = ensureWikilink(parsed.body.trim().ifBlank { title }, link)
        when (val resolution = input.resolve(title, parsed.aliases)) {
            is Resolution.Ambiguous -> {
                val first = resolution.paths.first()
                if (pages.any { it.relativePath == first }) {
                    extraWarnings += "Skipped duplicate page: $first"
                    continue
                }
                val previous = input.read(first)
                pages += PageProposal(
                    relativePath = first,
                    previousText = previous,
                    newText = previous ?: "",
                    conflict = true,
                    selectable = false,
                    note = "Title matches more than one page: ${resolution.paths.joinToString(", ")}. Nothing will be written for this title.",
                    catalogHeading = "",
                    catalogLine = "",
                )
            }
            is Resolution.One -> {
                if (pages.any { it.relativePath == resolution.path }) {
                    extraWarnings += "Skipped duplicate page: ${resolution.path}"
                    continue
                }
                val previous = input.read(resolution.path) ?: ""
                pages += PageProposal(
                    relativePath = resolution.path,
                    previousText = previous,
                    newText = appendSection(previous, section, input.today),
                    conflict = false,
                    selectable = true,
                    note = "Existing page. The new section is appended.",
                    catalogHeading = headingForType(type),
                    catalogLine = catalogLine(catalogTarget(resolution.path), oneLine(section, title), type, listOf(input.sourcePath)),
                )
                reserved += resolution.path
            }
            Resolution.None -> {
                val relative = allocatePath(directory, safeFileStem(title), input.exists, reserved)
                pages += PageProposal(
                    relativePath = relative,
                    previousText = null,
                    newText = renderNewPage(
                        title,
                        type,
                        parsed.tags,
                        parsed.aliases,
                        safeSourceRefs(parsed.sources, input.sourcePath),
                        input.today,
                        section,
                    ),
                    conflict = false,
                    selectable = true,
                    note = "",
                    catalogHeading = headingForType(type),
                    catalogLine = catalogLine(catalogTarget(relative), oneLine(section, title), type, listOf(input.sourcePath)),
                )
            }
        }
    }
    val reviewModels = reviews.mapIndexed { index, review ->
        ReviewProposal(
            id = "review-$index",
            type = review.type,
            title = review.title,
            body = review.body,
            options = review.options,
            searches = review.searches,
        )
    }
    val marked = pages.map { page ->
        val pageTitle = parseNote(page.newText, page.relativePath.substringAfterLast('/').removeSuffix(".md")).title
        val conflict = reviewModels.any { review ->
            review.type == "contradiction" && (
                normalizeKey(review.title) == normalizeKey(pageTitle) ||
                    (input.resolve(review.title, emptyList()) as? Resolution.One)?.path == page.relativePath
                )
        }
        if (!conflict || !page.selectable) {
            page
        } else {
            page.copy(
                conflict = true,
                note = if (page.previousText == null) {
                    "Conflict. Checking this writes a new page."
                } else {
                    "Conflict. Checking this appends a section and does not replace the page."
                },
            )
        }
    }
    return DraftProposal(
        pages = marked,
        logTitle = sourceTitle,
        operation = "ingest",
        schemaWarning = schemaCompatibilityWarning(input.schema),
        schemaTruncated = schemaTruncated,
        reviews = reviewModels,
        warnings = extraWarnings,
        sourcePath = input.sourcePath,
    )
}

private fun chooseSourcePath(input: IngestInput, stem: String, reserved: MutableSet<String>): String {
    val found = input.findSource(input.sourcePath)
    if (found != null && isSafeWikiPagePath(found)) {
        reserved += found
        return found
    }
    val canonical = "wiki/sources/$stem.md"
    val text = input.read(canonical)
    val present = text != null || input.exists(canonical)
    if (!present) {
        reserved += canonical
        return canonical
    }
    val parsed = parseNote(text ?: "", stem)
    val cites = parsed.sources.any { it.replace('\\', '/') == input.sourcePath }
    val sameStem = parsed.sources.isEmpty() &&
        (safeFileStem(parsed.title) == stem || parsed.title == stem) &&
        (parsed.type.isEmpty() || parsed.type == "source")
    if (cites || sameStem) {
        reserved += canonical
        return canonical
    }
    return allocatePath("wiki/sources", stem, input.exists, reserved)
}

private fun safeSourceRefs(sources: List<String>, required: String): List<String> {
    val cleaned = sources.map { it.trim().replace('\\', '/') }.filter { ref ->
        ref.isNotEmpty() && !ref.startsWith("/") && !ref.contains("..") && !ref.contains(':')
    }
    return (cleaned + required).distinct()
}

private fun oneLine(body: String, fallback: String): String {
    val line = body.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
        ?: fallback
    return line.replace(Regex("\\s+"), " ").take(200)
}

fun sourceStemOf(sourceName: String): String {
    val base = sourceName.substringAfterLast('/').substringAfterLast('\\')
    val stem = when {
        base.endsWith(".md", ignoreCase = true) -> base.dropLast(3)
        else -> base
    }
    return safeFileStem(stem.ifBlank { base })
}

fun vaultRelative(vault: Path, source: Path): String {
    val relative = vault.toAbsolutePath().normalize().relativize(source.toAbsolutePath().normalize())
    val text = relative.toString().replace('\\', '/')
    if (text.isBlank() || text == ".." || text.startsWith("../") || text.startsWith("/")) {
        error("Source is outside the vault.")
    }
    return text
}

private fun allocatePath(
    directory: String,
    stem: String,
    exists: (String) -> Boolean,
    reserved: MutableSet<String>,
): String {
    var suffix = 1
    while (suffix < 50) {
        val name = if (suffix == 1) "$directory/$stem.md" else "$directory/$stem ($suffix).md"
        if (!exists(name) && name !in reserved) {
            reserved += name
            return name
        }
        suffix += 1
    }
    error("Too many pages named $stem")
}
