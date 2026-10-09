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
        relative == "wiki/index.md" || relative == "wiki/log.md" -> true
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
