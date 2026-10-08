package com.neojou.mystudy.wiki.ingest

import com.neojou.mystudy.wiki.index.WikiIndex
import com.neojou.mystudy.wiki.markdown.appendSection
import com.neojou.mystudy.wiki.markdown.catalogLine
import com.neojou.mystudy.wiki.markdown.catalogTarget
import com.neojou.mystudy.wiki.markdown.ensureWikilink
import com.neojou.mystudy.wiki.markdown.headingForType
import com.neojou.mystudy.wiki.markdown.normalizeKey
import com.neojou.mystudy.wiki.markdown.renderNewPage
import com.neojou.mystudy.wiki.markdown.safeFileStem
import com.neojou.mystudy.wiki.markdown.schemaCompatibilityWarning
import com.neojou.mystudy.wiki.model.Claim
import com.neojou.mystudy.wiki.model.DraftProposal
import com.neojou.mystudy.wiki.model.PageProposal
import com.neojou.mystudy.wiki.model.Resolution
import com.neojou.mystudy.wiki.ollama.ChatClient
import com.neojou.mystudy.wiki.ollama.ChatRequest
import com.neojou.mystudy.wiki.ollama.PromptBudget
import com.neojou.mystudy.wiki.ollama.stripJsonFence
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Path
import java.time.LocalDate
import kotlin.io.path.readText

private val EXTRACT_SYSTEM = """
You extract claims from one source note. Use only that note.
Return JSON: {"claims":[{"statement":"","excerpt":"","suggestedType":"concept|entity|","suggestedTitle":"","aliases":[]}]}
The excerpt must be copied verbatim from the note.
Set suggestedType to concept or entity only when that idea is actually in the note. Otherwise use an empty string.
Do not invent claims. Keep titles in the note's language.
JSON only.
""".trimIndent()

private val DRAFT_SYSTEM = """
You draft wiki sections from the accepted claims and the existing page excerpts. Do not add ideas that are not in the claims.
Return JSON: {"sourceSummary":"","sourceBody":"","pages":[{"title":"","type":"concept|entity","summary":"","section":"","conflict":false,"conflictNote":"","aliases":[],"tags":[]}],"logTitle":""}
Include one pages item for each claim whose type is concept or entity. Use that claim's title.
Set conflict true when the claim contradicts an existing excerpt. Do not rewrite the existing page.
JSON only.
""".trimIndent()

sealed class ExtractResult {
    data class Ok(val claims: List<Claim>) : ExtractResult()

    data class Stopped(val message: String) : ExtractResult()
}

sealed class DraftResult {
    data class Ok(val proposal: DraftProposal) : DraftResult()

    data class Stopped(val message: String) : DraftResult()
}

class IngestPipeline(private val client: ChatClient) {
    fun extract(rawText: String, numCtx: Int): ExtractResult {
        if (PromptBudget.tooSmall(numCtx)) {
            return ExtractResult.Stopped("Context setting is too small. Nothing was sent.")
        }
        val budget = PromptBudget.inputCharBudget(numCtx)
        if (rawText.length + EXTRACT_SYSTEM.length > budget) {
            return ExtractResult.Stopped("This raw note is too big for one pass. Split it. Nothing was sent.")
        }
        val raw = try {
            client.complete(ChatRequest(EXTRACT_SYSTEM, rawText, json = true))
        } catch (error: Exception) {
            return ExtractResult.Stopped(error.message ?: "Ollama failed")
        }
        val claims = try {
            keepVerbatim(rawText, parseClaims(raw))
        } catch (error: Exception) {
            return ExtractResult.Stopped("The model did not return claims JSON.")
        }
        if (claims.isEmpty()) return ExtractResult.Stopped("No claim had a verbatim excerpt. Nothing will be written.")
        return ExtractResult.Ok(claims)
    }

    fun draft(
        index: WikiIndex,
        rawRelative: String,
        claims: List<Claim>,
        numCtx: Int,
        today: LocalDate,
    ): DraftResult {
        if (claims.isEmpty()) return DraftResult.Stopped("Select at least one claim.")
        if (PromptBudget.tooSmall(numCtx)) return DraftResult.Stopped("Context setting is too small. Nothing was sent.")
        val schemaPath = index.wikiRoot.resolve("wiki").resolve("schema.md")
        val schema = try {
            schemaPath.readText()
        } catch (_: Exception) {
            return DraftResult.Stopped("wiki/schema.md is missing. Nothing was written.")
        }
        val schemaExcerpt = schema.take(PromptBudget.SCHEMA_CAP)
        val truncated = schema.length > schemaExcerpt.length
        var existing = existingExcerpts(index, claims)
        var user = buildDraftUser(schemaExcerpt, claims, existing)
        val budget = PromptBudget.inputCharBudget(numCtx)
        while (DRAFT_SYSTEM.length + user.length > budget && existing.isNotEmpty()) {
            existing = existing.dropLast(1)
            user = buildDraftUser(schemaExcerpt, claims, existing)
        }
        if (DRAFT_SYSTEM.length + user.length > budget) {
            return DraftResult.Stopped("The draft prompt does not fit. Nothing was sent.")
        }
        val raw = try {
            client.complete(ChatRequest(DRAFT_SYSTEM, user, json = true))
        } catch (error: Exception) {
            return DraftResult.Stopped(error.message ?: "Ollama failed")
        }
        val model = try {
            Json.parseToJsonElement(stripJsonFence(raw)).jsonObject
        } catch (_: Exception) {
            return DraftResult.Stopped("The model did not return draft JSON. Nothing was written.")
        }
        val stem = rawRelative.substringAfterLast('/').removeSuffix(".md")
        val stemHits = index.pathsForAlias(stem)
        val link = if (stemHits.size <= 1) stem else "raw/${rawRelative.removeSuffix(".md")}|$stem"
        val proposal = buildIngestProposal(
            rawRelative = rawRelative,
            claims = claims,
            model = model,
            schema = schema,
            schemaTruncated = truncated,
            today = today,
            read = { relative -> readFile(index.wikiRoot, relative) },
            resolve = { title, aliases -> index.resolve(title, aliases) },
            findSource = { index.findSourceForRaw(it) },
            exists = { relative ->
                index.noteExists(relative) || index.wikiRoot.resolve(relative).toFile().isFile
            },
            rawLink = link,
        )
        return DraftResult.Ok(proposal)
    }
}

fun keepVerbatim(raw: String, claims: List<Claim>): List<Claim> {
    val normalized = raw.replace("\r\n", "\n")
    return claims.filter { claim ->
        val excerpt = claim.excerpt.replace("\r\n", "\n").trim()
        excerpt.isNotEmpty() && normalized.contains(excerpt)
    }
}

fun parseClaims(jsonText: String): List<Claim> {
    val root = Json.parseToJsonElement(stripJsonFence(jsonText)).jsonObject
    return root["claims"]?.jsonArray.orEmpty().mapNotNull { element ->
        val obj = element.jsonObject
        val title = obj.string("suggestedTitle")
        if (title.isBlank()) null
        else Claim(
            statement = obj.string("statement"),
            excerpt = obj.string("excerpt"),
            suggestedType = obj.string("suggestedType").lowercase(),
            suggestedTitle = title,
            aliases = obj.stringList("aliases"),
        )
    }
}

fun buildIngestProposal(
    rawRelative: String,
    claims: List<Claim>,
    model: JsonObject,
    schema: String,
    schemaTruncated: Boolean,
    today: LocalDate,
    read: (String) -> String?,
    resolve: (String, List<String>) -> Resolution,
    findSource: (String) -> String?,
    exists: (String) -> Boolean,
    rawLink: String,
): DraftProposal {
    val sourceStem = safeFileStem(rawRelative.substringAfterLast('/').removeSuffix(".md"))
    val sourceSummary = model.string("sourceSummary").ifBlank { claims.first().statement }
    val sourceBody = ensureWikilink(model.string("sourceBody").ifBlank { claims.joinToString("\n\n") { it.statement } }, rawLink)
    val reserved = mutableSetOf<String>()
    val sourceRelative = findSource(rawRelative) ?: allocatePath("wiki/sources", sourceStem, exists, reserved)
    reserved += sourceRelative
    val sourceExisting = read(sourceRelative)
    val sourcePage = if (sourceExisting == null) {
        PageProposal(
            relativePath = sourceRelative,
            previousText = null,
            newText = renderNewPage(sourceStem, "source", emptyList(), emptyList(), listOf(rawRelative.removePrefix("raw/")), today, sourceBody),
            conflict = false,
            selectable = true,
            note = "",
            catalogHeading = headingForType("source"),
            catalogLine = catalogLine(catalogTarget(sourceRelative), sourceSummary, "source", listOf(rawRelative)),
        )
    } else {
        PageProposal(
            relativePath = sourceRelative,
            previousText = sourceExisting,
            newText = appendSection(sourceExisting, sourceBody, today),
            conflict = false,
            selectable = true,
            note = "Existing source page. The new section is appended.",
            catalogHeading = headingForType("source"),
            catalogLine = catalogLine(catalogTarget(sourceRelative), sourceSummary, "source", listOf(rawRelative)),
        )
    }
    val modelPages = model["pages"]?.jsonArray.orEmpty().map { it.jsonObject }
    val pages = mutableListOf(sourcePage)
    val seen = linkedSetOf<String>()
    for (claim in claims) {
        val type = when (claim.suggestedType) {
            "concept", "entity" -> claim.suggestedType
            else -> continue
        }
        val key = normalizeKey(claim.suggestedTitle)
        if (!seen.add(key)) continue
        val modelPage = modelPages.firstOrNull { normalizeKey(it.string("title")) == key }
        val conflict = modelPage?.boolean("conflict") == true
        val conflictNote = modelPage?.string("conflictNote").orEmpty()
        val sectionText = modelPage?.string("section").orEmpty().ifBlank {
            claim.statement + "\n\n> " + claim.excerpt
        }
        val linked = ensureWikilink(sectionText, catalogTarget(sourceRelative))
        val resolution = resolve(claim.suggestedTitle, claim.aliases)
        val summary = modelPage?.string("summary").orEmpty().ifBlank { claim.statement }
        val aliases = (claim.aliases + (modelPage?.stringList("aliases").orEmpty())).distinct()
        val tags = modelPage?.stringList("tags").orEmpty()
        when (resolution) {
            is Resolution.Ambiguous -> {
                val first = resolution.paths.first()
                val previous = read(first)
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
                val previous = read(resolution.path) ?: ""
                pages += PageProposal(
                    relativePath = resolution.path,
                    previousText = previous,
                    newText = appendSection(previous, linked, today),
                    conflict = conflict,
                    selectable = true,
                    note = if (conflict) conflictNote.ifBlank { "Flagged as a conflict. Checking this appends a section and does not replace the page." } else "Existing page. The new section is appended.",
                    catalogHeading = headingForType(type),
                    catalogLine = catalogLine(catalogTarget(resolution.path), summary, type, listOf(rawRelative)),
                )
            }
            Resolution.None -> {
                val directory = if (type == "entity") "wiki/entities" else "wiki/concepts"
                val relative = allocatePath(directory, safeFileStem(claim.suggestedTitle), exists, reserved)
                pages += PageProposal(
                    relativePath = relative,
                    previousText = null,
                    newText = renderNewPage(
                        claim.suggestedTitle,
                        type,
                        tags,
                        aliases,
                        listOf(rawRelative.removePrefix("raw/")),
                        today,
                        linked,
                    ),
                    conflict = conflict,
                    selectable = true,
                    note = if (conflict) conflictNote.ifBlank { "Flagged as a conflict. Leave it unchecked to skip this page." } else "",
                    catalogHeading = headingForType(type),
                    catalogLine = catalogLine(catalogTarget(relative), summary, type, listOf(rawRelative)),
                )
            }
        }
    }
    return DraftProposal(
        pages = pages,
        logTitle = model.string("logTitle").ifBlank { sourceStem },
        operation = "ingest",
        schemaWarning = schemaCompatibilityWarning(schema),
        schemaTruncated = schemaTruncated,
    )
}

private fun existingExcerpts(index: WikiIndex, claims: List<Claim>): List<String> {
    val blocks = mutableListOf<String>()
    for (claim in claims) {
        if (blocks.size >= 8) break
        if (claim.suggestedType != "concept" && claim.suggestedType != "entity") continue
        when (val resolution = index.resolve(claim.suggestedTitle, claim.aliases)) {
            is Resolution.One -> {
                val note = index.load(resolution.path) ?: continue
                val text = note.body.take(PromptBudget.EXISTING_EXCERPT)
                blocks += "## ${note.path}\ntitle: ${note.title}\n$text"
            }
            is Resolution.Ambiguous -> blocks += "Ambiguous title ${claim.suggestedTitle}: ${resolution.paths.joinToString(", ")}"
            Resolution.None -> Unit
        }
    }
    return blocks
}

private fun buildDraftUser(schema: String, claims: List<Claim>, existing: List<String>): String = buildString {
    append("## schema.md\n")
    append(schema)
    append("\n\n## claims\n")
    for (claim in claims) {
        append("- title: ")
        append(claim.suggestedTitle)
        append(" type: ")
        append(claim.suggestedType)
        append("\n  ")
        append(claim.statement.replace("\n", " "))
        append("\n")
    }
    append("\n## existing pages\n")
    if (existing.isEmpty()) append("(none)\n")
    existing.forEach { append(it).append("\n\n") }
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

private fun readFile(root: Path, relative: String): String? = try {
    root.resolve(relative).readText()
} catch (_: Exception) {
    null
}

private fun JsonObject.string(key: String): String = try {
    this[key]?.jsonPrimitive?.content ?: ""
} catch (_: Exception) {
    ""
}

private fun JsonObject.boolean(key: String): Boolean = try {
    this[key]?.jsonPrimitive?.content == "true"
} catch (_: Exception) {
    false
}

private fun JsonObject.stringList(key: String): List<String> = try {
    this[key]?.jsonArray?.mapNotNull { element ->
        try {
            element.jsonPrimitive.content
        } catch (_: Exception) {
            null
        }
    } ?: emptyList()
} catch (_: Exception) {
    emptyList()
}
