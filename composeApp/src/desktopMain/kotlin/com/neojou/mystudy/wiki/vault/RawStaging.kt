package com.neojou.mystudy.wiki.vault

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readBytes

data class StagedRaw(
    val relativePath: String,
    val copied: Boolean,
)

/**
 * Copies [source] into `raw/` without modifying [source] and without overwriting an existing raw file.
 * Identical bytes are reused. Different bytes with the same name get a numeric suffix.
 */
fun stageIntoRaw(wikiRoot: Path, source: Path): StagedRaw {
    if (!source.isRegularFile()) error("Not a file: ${source.name}")
    val root = canonical(wikiRoot)
    if (isInside(source, root.resolve("raw"))) {
        val relative = relativeToRoot(root, source) ?: error("Raw file is outside the wiki root.")
        return StagedRaw(relative, copied = false)
    }
    val bytes = source.readBytes()
    val name = source.name.ifBlank { "source.md" }
    val destination = uniqueRawRelative(root, name, bytes)
    if (destination.second) {
        return StagedRaw(destination.first, copied = false)
    }
    val target = assertNewRawFile(root, destination.first)
    val tmp = target.resolveSibling(target.name + ".mystudy-tmp")
    Files.write(tmp, bytes)
    try {
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE)
    } catch (error: Exception) {
        Files.deleteIfExists(tmp)
        throw error
    }
    return StagedRaw(destination.first, copied = true)
}

/**
 * @return relative path, and true when an existing raw file already has the same bytes.
 */
private fun uniqueRawRelative(root: Path, filename: String, bytes: ByteArray): Pair<String, Boolean> {
    val dot = filename.lastIndexOf('.')
    val stem = if (dot > 0) filename.substring(0, dot) else filename
    val ext = if (dot > 0) filename.substring(dot) else ""
    var candidate = "raw/$filename"
    var suffix = 2
    while (true) {
        val path = root.resolve(candidate)
        if (!path.exists()) return candidate to false
        if (path.isRegularFile() && path.readBytes().contentEquals(bytes)) return candidate to true
        candidate = "raw/$stem ($suffix)$ext"
        suffix += 1
        if (suffix > 100) error("Too many raw files named $filename")
    }
}
