package com.neojou.mystudy.wiki.vault

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

data class CappedText(
    val text: String,
    val truncated: Boolean,
)

/**
 * Reads at most [maxChars] characters. A longer file is reported as truncated and is not fully loaded.
 */
fun readCapped(path: Path, maxChars: Int): CappedText {
    val limit = maxChars.coerceAtLeast(0)
    BufferedReader(InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8)).use { reader ->
        val buffer = CharArray(limit + 1)
        var count = 0
        while (count < buffer.size) {
            val read = reader.read(buffer, count, buffer.size - count)
            if (read < 0) break
            count += read
        }
        val truncated = count > limit
        val shown = if (truncated) limit else count
        return CappedText(String(buffer, 0, shown), truncated)
    }
}
