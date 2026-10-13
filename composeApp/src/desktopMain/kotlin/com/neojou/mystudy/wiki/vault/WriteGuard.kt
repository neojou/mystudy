package com.neojou.mystudy.wiki.vault

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

private val pageDirs = setOf("sources", "concepts", "entities", "queries")

/**
 * Returns the file to write, or throws when the path leaves the allow-list.
 * `raw/` cannot be overwritten. `wiki/schema.md` cannot be written here.
 */
fun assertWikiWrite(root: Path, relative: String): Path {
    val target = resolveInside(root, relative) ?: error("Blocked path: $relative")
    val allowed = when {
        relative == "wiki/index.md" || relative == "wiki/log.md" || relative == "wiki/review.md" -> true
        else -> isPageFile(relative)
    }
    if (!allowed || relative == "wiki/schema.md") error("Blocked path: $relative")
    val parent = target.parent
    if (parent != null && parent.exists() && !isInside(parent, root)) error("Blocked path: $relative")
    return target
}

/**
 * A new symbolic link under `raw/sources/`. The destination must not already exist.
 */
fun assertNewRawFile(root: Path, relative: String): Path {
    if (!relative.startsWith("raw/sources/") || relative.endsWith("/")) error("Blocked raw path: $relative")
    val rest = relative.removePrefix("raw/sources/")
    if (rest.isBlank() || rest.contains('/') || rest.contains('\\')) error("Blocked raw path: $relative")
    val target = resolveInside(root, relative) ?: error("Blocked raw path: $relative")
    val sourceDir = canonical(root).resolve("raw").resolve("sources")
    val parent = target.parent ?: error("Blocked raw path: $relative")
    if (!isInside(parent, sourceDir) && canonical(parent) != sourceDir) error("Blocked raw path: $relative")
    if (!parent.isDirectory()) error("Raw folder is missing: $relative")
    if (target.exists() || Files.isSymbolicLink(target)) error("Refusing to overwrite raw file: $relative")
    return target
}

/**
 * Manual edit of an existing vault note. This is separate from [assertWikiWrite].
 * A read-only path throws. [mustExist] is false only when recreating a deleted note in place.
 */
fun noteEditPermission(vault: Path, wikiRoot: Path?, file: Path): EditPermission {
    val structural = classifyNote(vault, wikiRoot, file)
    if (structural is EditPermission.ReadOnly) return structural
    val absolute = file.toAbsolutePath().normalize()
    val size = Files.size(absolute)
    if (size > NOTE_MAX_BYTES) return EditPermission.ReadOnly(NOTE_REASON_LARGE)
    val bytes = Files.readAllBytes(absolute)
    if (decodeNoteUtf8(bytes) == null) return EditPermission.ReadOnly(NOTE_REASON_UTF8)
    return structural
}

fun assertNoteEdit(vault: Path, wikiRoot: Path?, file: Path, mustExist: Boolean = true): Path =
    assertClassifiedNote(vault, wikiRoot, file, mustExist)

private fun isPageFile(relative: String): Boolean {
    val parts = relative.split('/')
    if (parts.size != 3) return false
    if (parts[0] != "wiki") return false
    if (parts[1] !in pageDirs) return false
    val name = parts[2]
    if (!name.endsWith(".md")) return false
    if (name == ".md" || name.contains("..")) return false
    return true
}
