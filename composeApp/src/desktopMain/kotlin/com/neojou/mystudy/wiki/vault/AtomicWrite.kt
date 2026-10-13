package com.neojou.mystudy.wiki.vault

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Writes [text] as UTF-8 with no BOM, creating missing parent directories.
 * The destination must already have been checked by [assertWikiWrite] or the scaffolder.
 * Manual note edits use [atomicWriteBytes] instead, which does not create parents.
 */
fun atomicWrite(target: Path, text: String) {
    val parent = target.parent ?: error("Cannot write ${target.fileName}")
    Files.createDirectories(parent)
    atomicWriteBytes(target, text.toByteArray(StandardCharsets.UTF_8))
}

/**
 * Replaces [target] by moving a sibling `name.mystudy-tmp` file into place.
 * Does not create parent directories. The caller has already checked the path.
 */
fun atomicWriteBytes(target: Path, bytes: ByteArray) {
    val parent = target.parent ?: error("Cannot write ${target.fileName}")
    val tmp = parent.resolve(target.fileName.toString() + ".mystudy-tmp")
    try {
        Files.write(
            tmp,
            bytes,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        Files.move(
            tmp,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    } catch (error: Exception) {
        Files.deleteIfExists(tmp)
        throw error
    }
}
