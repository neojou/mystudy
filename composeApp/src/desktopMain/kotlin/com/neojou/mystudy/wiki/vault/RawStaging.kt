package com.neojou.mystudy.wiki.vault

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

const val RAW_SOURCE_DIR: String = "raw/sources"

data class StagedRaw(
    val relativePath: String,
    val created: Boolean,
)

/**
 * Creates a relative symbolic link to [source] under `raw/sources/`.
 * The original file is not copied, moved, or overwritten.
 * An existing link to the same real file is reused.
 */
fun linkIntoRawSource(wikiRoot: Path, source: Path): StagedRaw {
    if (Files.isSymbolicLink(source) || !source.isRegularFile()) error("Not a file: ${source.name}")
    if (!source.name.endsWith(".md", ignoreCase = true)) error("Not a markdown file: ${source.name}")
    val root = wikiRoot.toAbsolutePath().normalize()
    if (isWikiManagedPath(root, source)) {
        error("Cannot ingest a file from the wiki tree.")
    }
    Files.createDirectories(root.resolve("raw").resolve("sources"))
    val sourceReal = source.toRealPath()
    val name = source.name.ifBlank { "source.md" }
    val destination = uniqueRawSourceRelative(root, name, sourceReal)
    if (destination.second) {
        return StagedRaw(destination.first, created = false)
    }
    val target = assertNewRawFile(root, destination.first)
    val linkValue = target.parent.relativize(sourceReal)
    Files.createSymbolicLink(target, linkValue)
    return StagedRaw(destination.first, created = true)
}

/**
 * @return relative path, and true when an existing link already points at the same file.
 */
private fun uniqueRawSourceRelative(root: Path, filename: String, sourceReal: Path): Pair<String, Boolean> {
    val dot = filename.lastIndexOf('.')
    val stem = if (dot > 0) filename.substring(0, dot) else filename
    val ext = if (dot > 0) filename.substring(dot) else ""
    var candidate = "$RAW_SOURCE_DIR/$filename"
    var suffix = 2
    while (true) {
        val path = root.resolve(candidate)
        val present = Files.exists(path, LinkOption.NOFOLLOW_LINKS)
        if (!present) return candidate to false
        if (Files.isSymbolicLink(path)) {
            val real = try {
                path.toRealPath()
            } catch (_: Exception) {
                null
            }
            if (real == sourceReal) return candidate to true
        }
        candidate = "$RAW_SOURCE_DIR/$stem ($suffix)$ext"
        suffix += 1
        if (suffix > 100) error("Too many raw files named $filename")
    }
}

fun isRawSourceMarkdownLink(wikiRoot: Path, path: Path): Boolean {
    if (!Files.isSymbolicLink(path)) return false
    if (!path.name.endsWith(".md", ignoreCase = true)) return false
    val sourceDir = try {
        wikiRoot.resolve("raw").resolve("sources").toRealPath()
    } catch (_: Exception) {
        return false
    }
    val parent = try {
        path.toAbsolutePath().normalize().parent?.toRealPath()
    } catch (_: Exception) {
        null
    } ?: return false
    if (parent != sourceDir) return false
    return try {
        Files.isRegularFile(path)
    } catch (_: Exception) {
        false
    }
}
