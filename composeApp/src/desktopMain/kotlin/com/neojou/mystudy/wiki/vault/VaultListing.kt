package com.neojou.mystudy.wiki.vault

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

data class VaultChild(
    val path: Path,
    val name: String,
    val directory: Boolean,
)

private val skippedNames = setOf(".obsidian", ".trash", ".git", "node_modules")

/**
 * One directory level inside the vault. Hidden names and symlinks that leave the vault are omitted.
 * Symlinks are not followed.
 */
fun listVaultChildren(dir: Path, vault: Path): List<VaultChild> {
    if (!dir.isDirectory() || !isInside(dir, vault)) return emptyList()
    val children = try {
        Files.newDirectoryStream(dir).use { stream -> stream.toList() }
    } catch (_: Exception) {
        return emptyList()
    }
    return children.mapNotNull { child ->
        val name = child.name
        if (name.startsWith(".") || name in skippedNames) return@mapNotNull null
        if (Files.isSymbolicLink(child)) return@mapNotNull null
        if (!isInside(child, vault)) return@mapNotNull null
        val directory = child.isDirectory()
        if (!directory && !Files.isRegularFile(child)) return@mapNotNull null
        VaultChild(child, name, directory)
    }.sortedWith(compareByDescending<VaultChild> { it.directory }.thenBy { it.name.lowercase() })
}

const val MAX_DIRECTORY_INGEST: Int = 100

/**
 * Markdown files for a right-click Ingest.
 * Paths inside [wikiRoot] are excluded. The original files are not modified.
 */
fun ingestTargets(clicked: Path, wikiRoot: Path, vault: Path): List<Path> {
    if (!isInside(clicked, vault)) return emptyList()
    if (isInside(clicked, wikiRoot)) return emptyList()
    if (Files.isSymbolicLink(clicked)) return emptyList()
    if (Files.isDirectory(clicked)) {
        val found = mutableListOf<Path>()
        fun recurse(dir: Path) {
            for (child in listVaultChildren(dir, vault)) {
                if (isInside(child.path, wikiRoot)) continue
                if (child.directory) recurse(child.path)
                else if (child.name.endsWith(".md", ignoreCase = true)) found.add(child.path)
            }
        }
        recurse(clicked)
        return found.sortedBy { it.toString() }
    }
    if (Files.isRegularFile(clicked) && clicked.name.endsWith(".md", ignoreCase = true)) {
        return listOf(clicked)
    }
    return emptyList()
}

/**
 * Markdown files under a folder of the wiki root, as paths relative to the root.
 */
fun listMarkdownUnder(root: Path, relativeFolder: String): List<String> {
    val start = root.resolve(relativeFolder)
    if (!start.isDirectory() || !isInside(start, root)) return emptyList()
    val found = mutableListOf<String>()
    fun recurse(dir: Path) {
        val children = try {
            Files.newDirectoryStream(dir).use { stream -> stream.toList() }
        } catch (_: Exception) {
            return
        }
        for (child in children) {
            if (Files.isSymbolicLink(child)) continue
            val name = child.name
            if (name.startsWith(".")) continue
            if (!isInside(child, root)) continue
            if (child.isDirectory()) recurse(child)
            else if (name.endsWith(".md", ignoreCase = true)) {
                found += root.relativize(child).toString().replace('\\', '/')
            }
        }
    }
    recurse(start)
    return found.sorted()
}
