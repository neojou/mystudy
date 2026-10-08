package com.neojou.mystudy.wiki.vault

import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile

fun canonical(path: Path): Path = try {
    path.toRealPath()
} catch (_: IOException) {
    path.toAbsolutePath().normalize()
}

/**
 * True when [child] is [parent] or a file inside it. Comparison uses real paths.
 */
fun isInside(child: Path, parent: Path): Boolean {
    val childPath = canonical(child)
    val parentPath = canonical(parent)
    return childPath == parentPath || childPath.startsWith(parentPath)
}

fun isWikiRoot(dir: Path): Boolean {
    if (!dir.isDirectory()) return false
    val raw = dir.resolve("raw")
    val index = dir.resolve("wiki").resolve("index.md")
    return raw.isDirectory() && index.isRegularFile()
}

fun relativeToRoot(root: Path, child: Path): String? {
    val rootPath = canonical(root)
    val childPath = canonical(child)
    if (childPath != rootPath && !childPath.startsWith(rootPath)) return null
    if (childPath == rootPath) return ""
    return rootPath.relativize(childPath).toString().replace('\\', '/')
}

fun resolveInside(root: Path, relative: String): Path? {
    if (relative.isBlank() || relative.startsWith("/") || relative.startsWith("\\")) return null
    if (relative.split('/', '\\').any { it == ".." || it == "." }) return null
    val rootPath = canonical(root)
    val target = rootPath.resolve(relative).normalize()
    if (target != rootPath && !target.startsWith(rootPath)) return null
    if (target.exists() && !isInside(target, rootPath)) return null
    return target
}
