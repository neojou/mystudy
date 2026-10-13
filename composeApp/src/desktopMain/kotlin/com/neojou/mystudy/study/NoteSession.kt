package com.neojou.mystudy.study

import com.neojou.mystudy.wiki.vault.DiskSnapshot
import com.neojou.mystudy.wiki.vault.EditPermission
import com.neojou.mystudy.wiki.vault.LoadedNote
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class NoteViewMode { Reading, Editing }

sealed interface NotePhase {
    data object Clean : NotePhase
    data object Dirty : NotePhase
    data object Saving : NotePhase
    data class Failed(val message: String) : NotePhase
    data class Conflict(val deleted: Boolean) : NotePhase
}

sealed interface PendingNav {
    data class OpenFile(val path: Path) : PendingNav
    data class SwitchMode(val mode: StudyMode) : PendingNav
    data object CloseWindow : PendingNav
    data object RunIngest : PendingNav
}

enum class ExternalEffect { Ignore, Reload, Banner }

/**
 * String state for the open note. The composable owns [androidx.compose.foundation.text.input.TextFieldState].
 */
class NoteSession {
    var generation: Int = 0
    var path: Path? = null
    var permission: EditPermission = EditPermission.ReadOnly("檔案不存在")
    var mode: NoteViewMode = NoteViewMode.Reading
    var loadedText: String = ""
    var draft: String = ""
    var base: DiskSnapshot? = null
    var phase: NotePhase = NotePhase.Clean
    var externalChange: Boolean = false
    var pendingNav: PendingNav? = null
    var savedAtMillis: Long? = null

    val dirty: Boolean
        get() = permission is EditPermission.Editable && draft != loadedText

    fun open(path: Path, loaded: LoadedNote, token: Int, keepPending: Boolean = false) {
        if (token != generation) return
        val pending = pendingNav
        this.path = path
        mode = NoteViewMode.Reading
        externalChange = false
        pendingNav = if (keepPending) pending else null
        when (loaded) {
            is LoadedNote.Text -> {
                permission = loaded.permission
                loadedText = loaded.text
                draft = loaded.text
                base = loaded.snapshot
                phase = NotePhase.Clean
                savedAtMillis = loaded.snapshot.mtimeMillis
            }
            is LoadedNote.Unreadable -> {
                permission = EditPermission.ReadOnly(loaded.reason)
                loadedText = ""
                draft = ""
                base = null
                phase = NotePhase.Clean
                savedAtMillis = null
            }
        }
    }

    /** Returns true when the leave is blocked and [pendingNav] is set. */
    fun requestLeave(nav: PendingNav): Boolean {
        if (!dirty) {
            pendingNav = null
            return false
        }
        pendingNav = nav
        return true
    }

    fun cancelLeave() {
        pendingNav = null
    }

    fun discardAndProceed(): PendingNav? {
        val nav = pendingNav
        pendingNav = null
        draft = loadedText
        if (phase !is NotePhase.Conflict) phase = NotePhase.Clean
        externalChange = false
        return nav
    }

    fun markSaved(snapshot: DiskSnapshot, savedText: String) {
        base = snapshot
        loadedText = savedText
        externalChange = false
        savedAtMillis = System.currentTimeMillis()
        phase = if (draft == savedText) NotePhase.Clean else NotePhase.Dirty
    }

    fun markConflict(deleted: Boolean) {
        phase = NotePhase.Conflict(deleted)
    }

    /** Keeps [pendingNav] so a failed save does not navigate away. */
    fun markFailed(message: String) {
        phase = NotePhase.Failed(message)
    }

    fun noteExternal(hash: String, token: Int): ExternalEffect {
        if (token != generation) return ExternalEffect.Ignore
        val snap = base ?: return ExternalEffect.Ignore
        if (hash == snap.sha256) {
            externalChange = false
            return ExternalEffect.Ignore
        }
        if (dirty) {
            externalChange = true
            return ExternalEffect.Banner
        }
        return ExternalEffect.Reload
    }
}

internal fun noteStatusText(
    permission: EditPermission,
    phase: NotePhase,
    dirty: Boolean,
    external: Boolean,
    savedAtMillis: Long?,
): String {
    if (permission is EditPermission.ReadOnly) return "唯讀：${permission.reason}"
    if (phase is NotePhase.Conflict) return if (phase.deleted) "檔案已刪除" else "磁碟上的檔案已變更"
    if (phase is NotePhase.Failed) return phase.message
    if (phase is NotePhase.Saving) return "存檔中…"
    if (external && dirty) return "檔案已在外部變更"
    if (dirty) return "未儲存變更"
    if (savedAtMillis == null) return "已存"
    return "已存 ${formatNoteHm(savedAtMillis)}"
}

internal fun formatNoteHm(millis: Long): String =
    Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
