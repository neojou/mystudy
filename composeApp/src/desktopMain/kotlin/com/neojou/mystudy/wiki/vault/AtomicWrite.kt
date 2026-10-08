package com.neojou.mystudy.wiki.vault

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Writes [text] by moving a sibling temp file into place.
 * The destination must already have been checked by [assertWikiWrite] or the scaffolder.
 */
fun atomicWrite(target: Path, text: String) {
    val parent = target.parent ?: error("Cannot write ${target.fileName}")
    Files.createDirectories(parent)
    val tmp = parent.resolve(target.fileName.toString() + ".mystudy-tmp")
    Files.writeString(
        tmp,
        text,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.TRUNCATE_EXISTING,
        StandardOpenOption.WRITE,
    )
    try {
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
