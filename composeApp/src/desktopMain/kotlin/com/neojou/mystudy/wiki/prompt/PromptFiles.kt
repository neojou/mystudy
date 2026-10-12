package com.neojou.mystudy.wiki.prompt

import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * One action file under `ai-prompts/`. Preface text and HTML comments are already removed.
 * [sections] keeps each marker body, with surrounding whitespace trimmed.
 */
class PromptFile(
    val fileName: String,
    val sections: Map<String, String>,
) {
    fun require(name: String): String {
        val text = sections[name]
            ?: throw PromptException("Prompt file ai-prompts/$fileName has no $name section. Nothing was sent.")
        if (text.isBlank()) {
            throw PromptException("Prompt file ai-prompts/$fileName has an empty $name section. Nothing was sent.")
        }
        return text
    }
}

class PromptException(message: String) : Exception(message)

private val promptFileNames = listOf("ask.md", "ingest-analyze.md", "ingest-generate.md")
private val sectionMarker = Regex("""^---\s+([a-z0-9-]+)\s+---\s*$""")
private val htmlComment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)
private val placeholder = Regex("""\{\{([A-Za-z][A-Za-z0-9_]*)\}\}""")

/**
 * Walks up from [start] for an `ai-prompts` directory that holds every action file.
 * A packaged app falls back to `compose.application.resources.dir/ai-prompts`.
 */
fun locateAiPrompts(start: Path = Path.of(System.getProperty("user.dir"))): Path {
    var dir = start.toAbsolutePath().normalize()
    while (true) {
        val candidate = dir.resolve("ai-prompts")
        if (candidate.isPromptDirectory()) return candidate
        dir = dir.parent ?: break
    }
    val resources = System.getProperty("compose.application.resources.dir")
    if (!resources.isNullOrBlank()) {
        val candidate = Path.of(resources).resolve("ai-prompts")
        if (candidate.isPromptDirectory()) return candidate
    }
    throw PromptException("Prompt directory ai-prompts was not found. Nothing was sent.")
}

/** Reads [fileName] from [directory] on this call. The preface is dropped and comments are stripped. */
fun loadPrompt(directory: Path, fileName: String): PromptFile {
    val path = directory.resolve(fileName)
    if (!path.isRegularFile()) {
        throw PromptException("Prompt file ai-prompts/$fileName is missing. Nothing was sent.")
    }
    val raw = try {
        path.readText().removePrefix("\uFEFF")
    } catch (_: Exception) {
        throw PromptException("Prompt file ai-prompts/$fileName could not be read. Nothing was sent.")
    }
    return parsePrompt(fileName, raw)
}

/**
 * Fills `{{name}}` in [template] once. Values are inserted as text and are not scanned again.
 * Every key in [values] must appear in the template, and every placeholder must have a value.
 */
fun render(template: String, values: Map<String, String>, fileName: String): String {
    val found = placeholder.findAll(template).map { it.groupValues[1] }.toList()
    val absent = values.keys.firstOrNull { key -> found.none { it == key } }
    if (absent != null) {
        throw PromptException("Prompt file ai-prompts/$fileName is missing {{$absent}}. Nothing was sent.")
    }
    val unknown = found.firstOrNull { it !in values }
    if (unknown != null) {
        throw PromptException("Prompt file ai-prompts/$fileName has unknown {{$unknown}}. Nothing was sent.")
    }
    return placeholder.replace(template) { match -> values.getValue(match.groupValues[1]) }
}

internal fun parsePrompt(fileName: String, raw: String): PromptFile {
    val lines = raw.replace("\r\n", "\n").replace('\r', '\n').lines()
    val bodies = linkedMapOf<String, StringBuilder>()
    var current: StringBuilder? = null
    for (line in lines) {
        val marker = sectionMarker.matchEntire(line)
        if (marker != null) {
            val name = marker.groupValues[1]
            if (bodies.containsKey(name)) {
                throw PromptException("Prompt file ai-prompts/$fileName has more than one $name section. Nothing was sent.")
            }
            current = StringBuilder()
            bodies[name] = current
        } else if (current != null) {
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
        }
    }
    val sections = bodies.mapValues { (_, body) -> stripComments(body.toString()).trim() }
    return PromptFile(fileName, sections)
}

private fun stripComments(text: String): String {
    val closed = htmlComment.replace(text, "")
    val unclosed = closed.indexOf("<!--")
    val cut = if (unclosed >= 0) closed.substring(0, unclosed) else closed
    return cut.lineSequence().joinToString("\n") { it.trimEnd() }
}

private fun Path.isPromptDirectory(): Boolean =
    isDirectory() && promptFileNames.all { resolve(it).isRegularFile() }
