package com.neojou.mystudy.wiki.vault

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

sealed class Discovery {
    data object VaultMissing : Discovery()

    data object None : Discovery()

    data class One(val root: Path) : Discovery()

    data class Many(val roots: List<Path>) : Discovery()
}

private val skippedNames = setOf(".obsidian", ".trash", ".git", "node_modules")

/**
 * Finds wiki roots inside [vault] only. A root has both `raw/` and `wiki/index.md`.
 * This never creates directories and never walks outside the vault.
 *
 * When [honorSaved] is true and [savedRoot] is still a wiki root inside the vault, that root is used.
 */
fun discoverWikiRoot(vault: Path, savedRoot: Path?, honorSaved: Boolean): Discovery {
    if (!vault.isDirectory()) return Discovery.VaultMissing
    if (honorSaved && savedRoot != null && savedRoot.isDirectory() && isInside(savedRoot, vault) && isWikiRoot(savedRoot)) {
        return Discovery.One(canonical(savedRoot))
    }
    val preferred = listOf(vault.resolve("llm-wiki"), vault)
        .filter { it.isDirectory() && isWikiRoot(it) }
        .map { canonical(it) }
    val nested = walkRoots(canonical(vault))
    val hits = (preferred + nested).distinct()
    return when (hits.size) {
        0 -> Discovery.None
        1 -> Discovery.One(hits.first())
        else -> Discovery.Many(hits)
    }
}

private fun walkRoots(vault: Path): List<Path> {
    val hits = mutableListOf<Path>()
    fun recurse(dir: Path, depth: Int) {
        if (depth > 4) return
        val children = try {
            Files.newDirectoryStream(dir).use { stream -> stream.toList() }
        } catch (_: Exception) {
            return
        }
        for (child in children) {
            if (Files.isSymbolicLink(child)) continue
            if (!child.isDirectory()) continue
            val name = child.name
            if (name.startsWith(".") || name in skippedNames) continue
            if (!isInside(child, vault)) continue
            if (isWikiRoot(child)) hits.add(canonical(child))
            recurse(child, depth + 1)
        }
    }
    recurse(vault, 0)
    return hits
}
