package com.neojou.mystudy.wiki.vault

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
const val NOTE_MAX_BYTES: Long = 1_000_000

const val NOTE_MAINTAINED_WARNING = "此檔由 Ingest、Lint 或深度研究維護"
const val NOTE_REASON_SYMLINK = "symlink 不可編輯"
const val NOTE_REASON_OUTSIDE = "不在 vault 內"
const val NOTE_REASON_DOT = "點開頭目錄不可編輯"
const val NOTE_REASON_NOT_TEXT = "非文字檔"
const val NOTE_REASON_RAW = "raw/ 不可覆寫"
const val NOTE_REASON_SCHEMA = "schema.md 不可編輯"
const val NOTE_REASON_MISSING = "檔案不存在"
const val NOTE_REASON_LARGE = "檔案過大"
const val NOTE_REASON_UTF8 = "非 UTF-8"

private val textExtensions = setOf("md", "markdown", "txt")
private val utf8Bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

sealed interface EditPermission {
    data class Editable(val warning: String? = null) : EditPermission
    data class ReadOnly(val reason: String) : EditPermission
}

enum class LineEnding { Lf, Crlf }

data class DiskSnapshot(
    val sha256: String,
    val mtimeMillis: Long,
    val size: Long,
    val lineEnding: LineEnding,
    val bom: Boolean,
    val mixed: Boolean = false,
)

sealed interface LoadedNote {
    data class Text(
        val text: String,
        val snapshot: DiskSnapshot,
        val permission: EditPermission,
    ) : LoadedNote

    data class Unreadable(val reason: String) : LoadedNote
}

sealed interface SaveOutcome {
    data object Unchanged : SaveOutcome
    data class Saved(val snapshot: DiskSnapshot) : SaveOutcome
    data class Conflict(val deleted: Boolean, val diskText: String?) : SaveOutcome
    data class Failed(val message: String) : SaveOutcome
}

/**
 * Wiki-relative path when [file] sits lexically under the wiki folder and ends in `.md`.
 * The suffix is case-sensitive. Other notes return null so they are not reindexed.
 */
fun wikiIndexRelative(root: Path, file: Path): String? {
    val pages = root.toAbsolutePath().normalize().resolve("wiki")
    val absolute = file.toAbsolutePath().normalize()
    if (!lexicallyInside(absolute, pages)) return null
    val relative = lexicalRelative(root, absolute)
    if (relative.contains("..")) return null
    if (!relative.endsWith(".md")) return null
    return relative
}

/**
 * Path rules for a manual edit. The first matching rule wins.
 * Dot-segments are checked before the extension so `.obsidian/app.json` stays a hidden-directory refusal.
 */
internal fun classifyNote(vault: Path, wikiRoot: Path?, file: Path): EditPermission {
    val absolute = file.toAbsolutePath().normalize()
    val vaultAbs = vault.toAbsolutePath().normalize()
    if (!lexicallyInside(absolute, vaultAbs)) return EditPermission.ReadOnly(NOTE_REASON_OUTSIDE)
    if (hasSymlink(vaultAbs, absolute)) return EditPermission.ReadOnly(NOTE_REASON_SYMLINK)
    if (Files.exists(absolute) && !Files.isSymbolicLink(absolute) && !isInside(absolute, vaultAbs)) {
        return EditPermission.ReadOnly(NOTE_REASON_OUTSIDE)
    }
    val relative = lexicalRelative(vaultAbs, absolute)
    if (relative.split('/').any { segment -> segment.isNotEmpty() && segment.startsWith('.') }) {
        return EditPermission.ReadOnly(NOTE_REASON_DOT)
    }
    val name = absolute.fileName?.toString().orEmpty()
    if (extensionOf(name) !in textExtensions) return EditPermission.ReadOnly(NOTE_REASON_NOT_TEXT)
    var warning: String? = null
    if (wikiRoot != null) {
        val rootAbs = wikiRoot.toAbsolutePath().normalize()
        val raw = rootAbs.resolve("raw")
        if (lexicallyInside(absolute, raw) && absolute != raw) {
            return EditPermission.ReadOnly(NOTE_REASON_RAW)
        }
        val fromRoot = lexicalRelative(rootAbs, absolute)
        if (fromRoot == "wiki/schema.md") return EditPermission.ReadOnly(NOTE_REASON_SCHEMA)
        if (fromRoot == "wiki/index.md" || fromRoot == "wiki/log.md" || fromRoot == "wiki/review.md") {
            warning = NOTE_MAINTAINED_WARNING
        }
    }
    if (!Files.exists(absolute)) return EditPermission.ReadOnly(NOTE_REASON_MISSING)
    if (!Files.isRegularFile(absolute)) return EditPermission.ReadOnly(NOTE_REASON_NOT_TEXT)
    return EditPermission.Editable(warning)
}

internal fun assertClassifiedNote(
    vault: Path,
    wikiRoot: Path?,
    file: Path,
    mustExist: Boolean = true,
): Path {
    val absolute = file.toAbsolutePath().normalize()
    if (!mustExist && !Files.exists(absolute) && !Files.isSymbolicLink(absolute)) {
        val structural = classifyNote(vault, wikiRoot, absolute)
        if (structural is EditPermission.ReadOnly && structural.reason != NOTE_REASON_MISSING) {
            error("Blocked path: ${pathLabel(vault, absolute)}")
        }
        val parent = absolute.parent ?: error("Blocked path: ${pathLabel(vault, absolute)}")
        if (hasSymlink(vault.toAbsolutePath().normalize(), parent)) {
            error("Blocked path: ${pathLabel(vault, absolute)}")
        }
        if (!Files.isDirectory(parent) || !lexicallyInside(parent, vault) || !isInside(parent, vault)) {
            error("Blocked path: ${pathLabel(vault, absolute)}")
        }
        return absolute
    }
    val permission = noteEditPermission(vault, wikiRoot, absolute)
    if (permission is EditPermission.ReadOnly) error("Blocked path: ${pathLabel(vault, absolute)}")
    return absolute
}

fun loadNote(
    vault: Path,
    wikiRoot: Path?,
    file: Path,
    maxBytes: Long = NOTE_MAX_BYTES,
): LoadedNote {
    val structural = classifyNote(vault, wikiRoot, file)
    if (structural is EditPermission.ReadOnly && structural.reason in skipReadReasons) {
        return LoadedNote.Unreadable(structural.reason)
    }
    val absolute = file.toAbsolutePath().normalize()
    if (!Files.isRegularFile(absolute)) {
        val reason = (structural as? EditPermission.ReadOnly)?.reason ?: NOTE_REASON_NOT_TEXT
        return LoadedNote.Unreadable(reason)
    }
    val size = Files.size(absolute)
    if (size > maxBytes) {
        val reason = (structural as? EditPermission.ReadOnly)?.reason ?: NOTE_REASON_LARGE
        return LoadedNote.Unreadable(reason)
    }
    val bytes = Files.readAllBytes(absolute)
    if (decodeNoteUtf8(bytes) == null) {
        val reason = (structural as? EditPermission.ReadOnly)?.reason ?: NOTE_REASON_UTF8
        return LoadedNote.Unreadable(reason)
    }
    val permission = when (structural) {
        is EditPermission.ReadOnly -> structural
        is EditPermission.Editable -> structural
    }
    val mtime = Files.getLastModifiedTime(absolute).toMillis()
    return LoadedNote.Text(
        text = editorTextOf(bytes),
        snapshot = snapshotOf(bytes, mtime),
        permission = permission,
    )
}

fun saveNote(
    vault: Path,
    wikiRoot: Path?,
    file: Path,
    editorText: String,
    loadedText: String,
    base: DiskSnapshot,
    force: Boolean = false,
    recreate: Boolean = false,
    maxBytes: Long = NOTE_MAX_BYTES,
): SaveOutcome {
    if (editorText == loadedText) return SaveOutcome.Unchanged
    val absolute = file.toAbsolutePath().normalize()
    val structural = classifyNote(vault, wikiRoot, absolute)
    if (structural is EditPermission.ReadOnly && structural.reason != NOTE_REASON_MISSING) {
        return SaveOutcome.Failed(structural.reason)
    }
    if (Files.isSymbolicLink(absolute)) return SaveOutcome.Failed(NOTE_REASON_SYMLINK)
    if (!Files.exists(absolute)) {
        if (!recreate) return SaveOutcome.Conflict(deleted = true, diskText = null)
        try {
            assertNoteEdit(vault, wikiRoot, absolute, mustExist = false)
        } catch (error: IllegalStateException) {
            return SaveOutcome.Failed(error.message ?: "Blocked path")
        }
        return writeEncoded(absolute, editorText, base, maxBytes)
    }
    val size = Files.size(absolute)
    if (size > maxBytes) return SaveOutcome.Failed(NOTE_REASON_LARGE)
    val mtime = Files.getLastModifiedTime(absolute).toMillis()
    val sameStamp = mtime == base.mtimeMillis && size == base.size
    if (!sameStamp) {
        val disk = Files.readAllBytes(absolute)
        if (decodeNoteUtf8(disk) == null) return SaveOutcome.Failed(NOTE_REASON_UTF8)
        if (noteSha256(disk) != base.sha256 && !force) {
            return SaveOutcome.Conflict(deleted = false, diskText = editorTextOf(disk))
        }
    }
    return writeEncoded(absolute, editorText, base, maxBytes)
}

internal fun editorTextOf(bytes: ByteArray): String {
    val decoded = decodeNoteUtf8(bytes) ?: return ""
    val logical = if (hasUtf8Bom(bytes) && decoded.startsWith('\uFEFF')) decoded.substring(1) else decoded
    return logical.replace("\r\n", "\n").replace("\r", "\n")
}

internal fun snapshotOf(bytes: ByteArray, mtimeMillis: Long): DiskSnapshot {
    val decoded = decodeNoteUtf8(bytes) ?: error("not utf-8")
    val logical = if (hasUtf8Bom(bytes) && decoded.startsWith('\uFEFF')) decoded.substring(1) else decoded
    val (ending, mixed) = lineEndingOf(logical)
    return DiskSnapshot(
        sha256 = noteSha256(bytes),
        mtimeMillis = mtimeMillis,
        size = bytes.size.toLong(),
        lineEnding = ending,
        bom = hasUtf8Bom(bytes),
        mixed = mixed,
    )
}

internal fun lineEndingOf(text: String): Pair<LineEnding, Boolean> {
    var crlf = 0
    var lf = 0
    var index = 0
    while (index < text.length) {
        val char = text[index]
        if (char == '\r' && index + 1 < text.length && text[index + 1] == '\n') {
            crlf++
            index += 2
        } else if (char == '\n' || char == '\r') {
            lf++
            index++
        } else {
            index++
        }
    }
    val mixed = crlf > 0 && lf > 0
    val ending = when {
        !mixed && crlf > 0 -> LineEnding.Crlf
        mixed && crlf > lf -> LineEnding.Crlf
        else -> LineEnding.Lf
    }
    return ending to mixed
}

internal fun encodeEditorText(text: String, ending: LineEnding, bom: Boolean): ByteArray {
    val body = if (ending == LineEnding.Crlf) text.replace("\n", "\r\n") else text
    val utf8 = body.toByteArray(StandardCharsets.UTF_8)
    if (!bom) return utf8
    return utf8Bom + utf8
}

internal fun noteSha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

internal fun noteFileSha256(path: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

internal fun decodeNoteUtf8(bytes: ByteArray): String? {
    val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

private fun writeEncoded(
    file: Path,
    editorText: String,
    base: DiskSnapshot,
    maxBytes: Long,
): SaveOutcome {
    val bytes = encodeEditorText(editorText, base.lineEnding, base.bom)
    if (bytes.size.toLong() > maxBytes) return SaveOutcome.Failed(NOTE_REASON_LARGE)
    try {
        atomicWriteBytes(file, bytes)
    } catch (error: Exception) {
        return SaveOutcome.Failed(error.message ?: "存檔失敗")
    }
    val mtime = Files.getLastModifiedTime(file).toMillis()
    val size = Files.size(file)
    return SaveOutcome.Saved(
        DiskSnapshot(
            sha256 = noteSha256(bytes),
            mtimeMillis = mtime,
            size = size,
            lineEnding = base.lineEnding,
            bom = base.bom,
            mixed = false,
        ),
    )
}

private val skipReadReasons = setOf(
    NOTE_REASON_NOT_TEXT,
    NOTE_REASON_MISSING,
    NOTE_REASON_OUTSIDE,
)

private fun hasUtf8Bom(bytes: ByteArray): Boolean =
    bytes.size >= 3 && bytes[0] == utf8Bom[0] && bytes[1] == utf8Bom[1] && bytes[2] == utf8Bom[2]

private fun extensionOf(name: String): String {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.lastIndex) return ""
    return name.substring(dot + 1).lowercase()
}

private fun hasSymlink(vaultAbs: Path, file: Path): Boolean {
    var cursor: Path? = file.toAbsolutePath().normalize()
    while (cursor != null && cursor != vaultAbs) {
        if (Files.isSymbolicLink(cursor)) return true
        cursor = cursor.parent
    }
    return false
}

private fun pathLabel(vault: Path, file: Path): String {
    val absolute = file.toAbsolutePath().normalize()
    val root = vault.toAbsolutePath().normalize()
    return if (lexicallyInside(absolute, root)) lexicalRelative(root, absolute) else absolute.toString()
}
