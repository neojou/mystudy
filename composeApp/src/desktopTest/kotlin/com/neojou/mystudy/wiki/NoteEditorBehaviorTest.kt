package com.neojou.mystudy.wiki

import com.neojou.mystudy.study.ExternalEffect
import com.neojou.mystudy.study.NotePhase
import com.neojou.mystudy.study.NoteSession
import com.neojou.mystudy.study.NavOrigin
import com.neojou.mystudy.study.NoteViewMode
import com.neojou.mystudy.study.PendingNav
import com.neojou.mystudy.study.StudyMode
import com.neojou.mystudy.study.splitReading
import com.neojou.mystudy.wiki.index.WikiIndex
import com.neojou.mystudy.wiki.settings.AppSettings
import com.neojou.mystudy.wiki.settings.MemorySettingsStore
import com.neojou.mystudy.wiki.vault.DiskSnapshot
import com.neojou.mystudy.wiki.vault.EditPermission
import com.neojou.mystudy.wiki.vault.LineEnding
import com.neojou.mystudy.wiki.vault.LoadedNote
import com.neojou.mystudy.wiki.vault.NOTE_MAINTAINED_WARNING
import com.neojou.mystudy.wiki.vault.NOTE_REASON_DOT
import com.neojou.mystudy.wiki.vault.NOTE_REASON_LARGE
import com.neojou.mystudy.wiki.vault.NOTE_REASON_MISSING
import com.neojou.mystudy.wiki.vault.NOTE_REASON_NOT_TEXT
import com.neojou.mystudy.wiki.vault.NOTE_REASON_OUTSIDE
import com.neojou.mystudy.wiki.vault.NOTE_REASON_RAW
import com.neojou.mystudy.wiki.vault.NOTE_REASON_SCHEMA
import com.neojou.mystudy.wiki.vault.NOTE_REASON_SYMLINK
import com.neojou.mystudy.wiki.vault.NOTE_REASON_UTF8
import com.neojou.mystudy.wiki.vault.SaveOutcome
import com.neojou.mystudy.wiki.vault.assertNoteEdit
import com.neojou.mystudy.wiki.vault.assertWikiWrite
import com.neojou.mystudy.wiki.vault.loadNote
import com.neojou.mystudy.wiki.vault.noteEditPermission
import com.neojou.mystudy.wiki.vault.saveNote
import com.neojou.mystudy.wiki.vault.scaffoldWiki
import com.neojou.mystudy.wiki.vault.wikiIndexRelative
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteEditorBehaviorTest {
    @Test
    fun noteEditPermissionAllowsVaultNotesAndBlocksTheRest() {
        val vault = tempDir()
        val root = scaffoldWiki(vault)
        val concept = root.resolve("wiki/concepts/a.md")
        concept.writeText("# A\n")
        val zettel = vault.resolve("Zettelkasten/b.md")
        Files.createDirectories(zettel.parent)
        zettel.writeText("box\n")
        val upper = root.resolve("wiki/concepts/A.MD")
        upper.writeText("upper\n")
        val nested = root.resolve("wiki/concepts/nested/a.md")
        Files.createDirectories(nested.parent)
        nested.writeText("nested\n")
        val rawFile = root.resolve("raw/sources/plain.md")
        rawFile.writeText("raw\n")
        val outsideTarget = tempDir().resolve("secret.md")
        outsideTarget.writeText("secret\n")
        val rawLink = root.resolve("raw/sources/x.md")
        Files.createSymbolicLink(rawLink, outsideTarget)
        val link = vault.resolve("linked.md")
        Files.createSymbolicLink(link, outsideTarget)
        val hidden = vault.resolve(".obsidian/app.json")
        Files.createDirectories(hidden.parent)
        hidden.writeText("{}")
        val png = vault.resolve("picture.png")
        png.writeBytes(byteArrayOf(1, 2, 3))
        val outside = tempDir().resolve("nope.md")
        outside.writeText("nope\n")
        val escaped = vault.resolve("../escaped.md").normalize()

        assertIs<EditPermission.Editable>(noteEditPermission(vault, root, concept))
        assertIs<EditPermission.Editable>(noteEditPermission(vault, root, zettel))
        assertIs<EditPermission.Editable>(noteEditPermission(vault, root, upper))
        assertIs<EditPermission.Editable>(noteEditPermission(vault, root, nested))
        assertNull(wikiIndexRelative(root, upper))
        assertEquals("wiki/concepts/nested/a.md", wikiIndexRelative(root, nested))
        assertFailsWith<IllegalStateException> { assertWikiWrite(root, "wiki/concepts/nested/a.md") }
        assertEquals(concept.toAbsolutePath().normalize(), assertNoteEdit(vault, root, concept))

        val index = noteEditPermission(vault, root, root.resolve("wiki/index.md"))
        assertIs<EditPermission.Editable>(index)
        assertEquals(NOTE_MAINTAINED_WARNING, index.warning)
        assertEquals(NOTE_REASON_RAW, readOnly(noteEditPermission(vault, root, rawFile)))
        assertEquals(NOTE_REASON_SYMLINK, readOnly(noteEditPermission(vault, root, rawLink)))
        assertEquals(NOTE_REASON_SYMLINK, readOnly(noteEditPermission(vault, root, link)))
        assertEquals(NOTE_REASON_SCHEMA, readOnly(noteEditPermission(vault, root, root.resolve("wiki/schema.md"))))
        assertEquals(NOTE_REASON_DOT, readOnly(noteEditPermission(vault, root, hidden)))
        assertEquals(NOTE_REASON_NOT_TEXT, readOnly(noteEditPermission(vault, root, png)))
        assertEquals(NOTE_REASON_OUTSIDE, readOnly(noteEditPermission(vault, root, outside)))
        assertEquals(NOTE_REASON_OUTSIDE, readOnly(noteEditPermission(vault, root, escaped)))
        assertEquals(NOTE_REASON_MISSING, readOnly(noteEditPermission(vault, root, vault.resolve("missing.md"))))

        for (blocked in listOf(rawFile, rawLink, root.resolve("wiki/schema.md"), hidden, png, outside, escaped)) {
            val error = assertFailsWith<IllegalStateException> { assertNoteEdit(vault, root, blocked) }
            assertTrue(error.message!!.contains("Blocked path:"))
        }
        val before = outsideTarget.readBytes()
        val failed = saveNote(
            vault, root, link, "changed", "secret\n",
            DiskSnapshot("none", 0, 0, LineEnding.Lf, false),
        )
        assertIs<SaveOutcome.Failed>(failed)
        assertTrue(before.contentEquals(outsideTarget.readBytes()))

        val created = vault.resolve("Zettelkasten/brand-new.md")
        assertFalse(Files.exists(created))
        assertEquals(created.toAbsolutePath().normalize(), assertNoteEdit(vault, root, created, mustExist = false))
        assertFalse(Files.exists(created))
        val orphan = vault.resolve("no-parent/new.md")
        assertFailsWith<IllegalStateException> { assertNoteEdit(vault, root, orphan, mustExist = false) }
        assertFalse(Files.exists(orphan.parent))
    }

    @Test
    fun unchangedSaveDoesNotTouchBytesOrMtime() {
        val (vault, root, file) = noteFile("same\n")
        val before = file.readBytes()
        val mtime = Files.getLastModifiedTime(file).toMillis()
        val loaded = loadNote(vault, root, file) as LoadedNote.Text
        val outcome = saveNote(vault, root, file, loaded.text, loaded.text, loaded.snapshot)
        assertIs<SaveOutcome.Unchanged>(outcome)
        assertTrue(before.contentEquals(file.readBytes()))
        assertEquals(mtime, Files.getLastModifiedTime(file).toMillis())
        assertFalse(Files.exists(file.resolveSibling("note.md.mystudy-tmp")))
    }

    @Test
    fun changedSaveWritesBytesAndRemovesTheTempFile() {
        val (vault, root, file) = noteFile("old\n")
        val loaded = loadNote(vault, root, file) as LoadedNote.Text
        val outcome = saveNote(vault, root, file, "new\n", loaded.text, loaded.snapshot)
        val saved = assertIs<SaveOutcome.Saved>(outcome)
        assertTrue("new\n".toByteArray().contentEquals(file.readBytes()))
        assertEquals(saved.snapshot.sha256, sha(file.readBytes()))
        assertFalse(Files.exists(file.resolveSibling("note.md.mystudy-tmp")))
    }

    @Test
    fun saveKeepsBomAndCrlfAndDoesNotAddATrailingNewline() {
        val vault = tempDir()
        val root = scaffoldWiki(vault)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val crlf = root.resolve("wiki/concepts/crlf.md")
        crlf.writeBytes(bom + "a\r\nb".toByteArray())
        val loaded = loadNote(vault, root, crlf) as LoadedNote.Text
        assertEquals("a\nb", loaded.text)
        assertTrue(loaded.snapshot.bom)
        assertEquals(LineEnding.Crlf, loaded.snapshot.lineEnding)
        assertFalse(loaded.snapshot.mixed)
        val saved = saveNote(vault, root, crlf, "a\nc", loaded.text, loaded.snapshot)
        assertIs<SaveOutcome.Saved>(saved)
        assertTrue((bom + "a\r\nc".toByteArray()).contentEquals(crlf.readBytes()))

        val bare = root.resolve("wiki/concepts/bare.md")
        bare.writeBytes("no-nl".toByteArray())
        val bareLoaded = loadNote(vault, root, bare) as LoadedNote.Text
        val bareSaved = saveNote(vault, root, bare, "no-nl-x", bareLoaded.text, bareLoaded.snapshot)
        assertIs<SaveOutcome.Saved>(bareSaved)
        assertTrue("no-nl-x".toByteArray().contentEquals(bare.readBytes()))

        val mixed = root.resolve("wiki/concepts/mixed.md")
        val mixedBytes = "a\r\nb\nc".toByteArray()
        mixed.writeBytes(mixedBytes)
        val mixedLoaded = loadNote(vault, root, mixed) as LoadedNote.Text
        assertTrue(mixedLoaded.snapshot.mixed)
        assertEquals(LineEnding.Lf, mixedLoaded.snapshot.lineEnding)
        val untouched = saveNote(vault, root, mixed, mixedLoaded.text, mixedLoaded.text, mixedLoaded.snapshot)
        assertIs<SaveOutcome.Unchanged>(untouched)
        assertTrue(mixedBytes.contentEquals(mixed.readBytes()))
        val unified = saveNote(vault, root, mixed, "a\nb\nz", mixedLoaded.text, mixedLoaded.snapshot)
        assertIs<SaveOutcome.Saved>(unified)
        assertTrue("a\nb\nz".toByteArray().contentEquals(mixed.readBytes()))

        val majority = root.resolve("wiki/concepts/majority.md")
        majority.writeBytes("a\r\nb\r\nc\nd".toByteArray())
        val majorityLoaded = loadNote(vault, root, majority) as LoadedNote.Text
        assertEquals(LineEnding.Crlf, majorityLoaded.snapshot.lineEnding)
        val majoritySaved = saveNote(
            vault, root, majority, "a\nb\nc\nz", majorityLoaded.text, majorityLoaded.snapshot,
        )
        assertIs<SaveOutcome.Saved>(majoritySaved)
        assertTrue("a\r\nb\r\nc\r\nz".toByteArray().contentEquals(majority.readBytes()))
    }

    @Test
    fun externalEditConflictsUntilForce() {
        val (vault, root, file) = noteFile("mine\n")
        val loaded = loadNote(vault, root, file) as LoadedNote.Text
        file.writeText("theirs\n")
        val blocked = saveNote(vault, root, file, "ours\n", loaded.text, loaded.snapshot)
        val conflict = assertIs<SaveOutcome.Conflict>(blocked)
        assertFalse(conflict.deleted)
        assertEquals("theirs\n", conflict.diskText)
        assertEquals("theirs\n", file.readText())
        val forced = saveNote(vault, root, file, "ours\n", loaded.text, loaded.snapshot, force = true)
        assertIs<SaveOutcome.Saved>(forced)
        assertEquals("ours\n", file.readText())
    }

    @Test
    fun deletedFileConflictsAndRecreateDoesNotMakeParents() {
        val (vault, root, file) = noteFile("hello\n")
        val loaded = loadNote(vault, root, file) as LoadedNote.Text
        Files.delete(file)
        val conflict = saveNote(vault, root, file, "hello!\n", loaded.text, loaded.snapshot)
        val deleted = assertIs<SaveOutcome.Conflict>(conflict)
        assertTrue(deleted.deleted)
        assertFalse(Files.exists(file))
        val recreated = saveNote(
            vault, root, file, "hello!\n", loaded.text, loaded.snapshot, recreate = true,
        )
        assertIs<SaveOutcome.Saved>(recreated)
        assertEquals("hello!\n", file.readText())

        val ghost = vault.resolve("missing-parent/new.md")
        val refused = saveNote(
            vault, root, ghost, "z", "y", loaded.snapshot, recreate = true,
        )
        assertIs<SaveOutcome.Failed>(refused)
        assertFalse(Files.exists(ghost.parent))
    }

    @Test
    fun externalChangeReloadsWhenCleanAndBannersWhenDirty() {
        val session = sessionOf("hello")
        assertEquals(NoteViewMode.Reading, session.mode)
        assertEquals(ExternalEffect.Reload, session.noteExternal("other", session.generation))
        assertEquals("hello", session.draft)
        assertFalse(session.externalChange)
        session.draft = "edited"
        assertEquals(ExternalEffect.Banner, session.noteExternal("other", session.generation))
        assertTrue(session.externalChange)
        assertEquals("edited", session.draft)
    }

    @Test
    fun ownSaveHashIsNotAnExternalChange() {
        val session = sessionOf("hello")
        session.draft = "hello"
        session.externalChange = true
        val saved = session.base!!.copy(sha256 = "after")
        session.markSaved(saved, "hello")
        assertEquals(ExternalEffect.Ignore, session.noteExternal("after", session.generation))
        assertFalse(session.externalChange)
        assertTrue(session.phase is NotePhase.Clean)
    }

    @Test
    fun wikiMarkdownSaveIsReindexedAndOtherNotesAreNot() {
        val vault = tempDir()
        val root = scaffoldWiki(vault)
        val page = root.resolve("wiki/concepts/a.md")
        val other = root.resolve("wiki/concepts/b.md")
        page.writeText("# A\n\nsee\n")
        other.writeText("# B\n")
        val logBefore = root.resolve("wiki/log.md").readBytes()
        val zettel = vault.resolve("Zettelkasten/b.md")
        Files.createDirectories(zettel.parent)
        zettel.writeText("box\n")
        val text = root.resolve("wiki/concepts/a.txt")
        text.writeText("text\n")
        WikiIndex.open(root, tempDir().resolve("notes.sqlite")).use { index ->
            index.reconcileAll()
            val before = index.noteCount()
            val loaded = loadNote(vault, root, page) as LoadedNote.Text
            val outcome = saveNote(vault, root, page, "# A\n\nsee [[B]]\n", loaded.text, loaded.snapshot)
            assertIs<SaveOutcome.Saved>(outcome)
            val relative = wikiIndexRelative(root, page)
            assertEquals("wiki/concepts/a.md", relative)
            index.reindexRelative(relative!!)
            assertTrue(index.load("wiki/concepts/a.md")!!.body.contains("[[B]]"))
            assertEquals(listOf("wiki/concepts/b.md"), index.linksFrom("wiki/concepts/a.md"))

            val zettelLoaded = loadNote(vault, root, zettel) as LoadedNote.Text
            assertNull(wikiIndexRelative(root, zettel))
            assertIs<SaveOutcome.Saved>(
                saveNote(vault, root, zettel, "boxed\n", zettelLoaded.text, zettelLoaded.snapshot),
            )
            val textLoaded = loadNote(vault, root, text) as LoadedNote.Text
            assertNull(wikiIndexRelative(root, text))
            assertIs<SaveOutcome.Saved>(
                saveNote(vault, root, text, "changed\n", textLoaded.text, textLoaded.snapshot),
            )
            assertEquals(before, index.noteCount())
            assertNull(index.load("Zettelkasten/b.md"))
            assertEquals("changed\n", text.readText())
        }
        assertTrue(logBefore.contentEquals(root.resolve("wiki/log.md").readBytes()))
    }

    @Test
    fun oversizedAndNonUtf8NotesAreReadOnlyAndNotSaved() {
        val vault = tempDir()
        val root = scaffoldWiki(vault)
        val big = root.resolve("wiki/concepts/big.md")
        val original = ByteArray(1_000_001) { 'a'.code.toByte() }
        big.writeBytes(original)
        assertEquals(NOTE_REASON_LARGE, readOnly(noteEditPermission(vault, root, big)))
        assertIs<LoadedNote.Unreadable>(loadNote(vault, root, big))
        assertFailsWith<IllegalStateException> { assertNoteEdit(vault, root, big) }
        val bigSave = saveNote(
            vault, root, big, "short\n", "other",
            DiskSnapshot("none", 0, 0, LineEnding.Lf, false),
        )
        assertIs<SaveOutcome.Failed>(bigSave)
        assertTrue(original.contentEquals(big.readBytes()))

        val binary = root.resolve("wiki/concepts/binary.md")
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        binary.writeBytes(bytes)
        assertEquals(NOTE_REASON_UTF8, readOnly(noteEditPermission(vault, root, binary)))
        val unreadable = loadNote(vault, root, binary)
        assertIs<LoadedNote.Unreadable>(unreadable)
        assertEquals(NOTE_REASON_UTF8, unreadable.reason)
        val refused = saveNote(
            vault, root, binary, "text\n", "",
            DiskSnapshot("none", 0, 0, LineEnding.Lf, false),
        )
        assertIs<SaveOutcome.Failed>(refused)
        assertTrue(bytes.contentEquals(binary.readBytes()))
    }

    @Test
    fun dirtyLeaveNeedsConfirmationAndFailureDoesNotNavigate() {
        val session = sessionOf("hello")
        assertFalse(session.dirty)
        assertFalse(session.requestLeave(PendingNav.SwitchMode(StudyMode.Browse)))
        assertNull(session.pendingNav)
        session.draft = "edited"
        assertTrue(session.requestLeave(PendingNav.OpenFile(Path.of("other.md"))))
        val open = assertIs<PendingNav.OpenFile>(session.pendingNav)
        assertEquals(NavOrigin.Link, open.origin)
        session.markFailed("disk full")
        assertIs<NotePhase.Failed>(session.phase)
        assertIs<PendingNav.OpenFile>(session.pendingNav)
        assertTrue(session.dirty)
        val nav = session.discardAndProceed()
        assertIs<PendingNav.OpenFile>(nav)
        assertNull(session.pendingNav)
        assertEquals("hello", session.draft)
        assertFalse(session.dirty)
    }

    @Test
    fun noteAutosaveDefaultsOffAndRoundTripsOutsideTheVault() {
        val fresh = AppSettings(MemorySettingsStore())
        assertFalse(fresh.load().noteAutosave)
        val store = MemorySettingsStore()
        val settings = AppSettings(store)
        settings.saveLintIgnore("/tmp/wiki", "wiki/old.md")
        val saved = settings.save(settings.load().copy(noteAutosave = true))
        assertTrue(saved.noteAutosave)
        assertTrue(AppSettings(store).load().noteAutosave)
        assertEquals("wiki/old.md", settings.lintIgnore("/tmp/wiki"))
        val cleared = settings.save(saved.copy(noteAutosave = false))
        assertFalse(cleared.noteAutosave)
        assertFalse(AppSettings(store).load().noteAutosave)
    }

    @Test
    fun readingSplitsAClosedFrontmatterBlock() {
        assertEquals("title: A" to "body", splitReading("---\ntitle: A\n---\n\nbody"))
        assertEquals("title: A" to "", splitReading("---\ntitle: A\n---"))
        val open = "---\ntitle: A\nstill open"
        assertEquals(null to open, splitReading(open))
        assertEquals(null to "body", splitReading("body"))
    }

    private fun sessionOf(text: String): NoteSession {
        val session = NoteSession()
        session.generation = 1
        session.open(
            Path.of("note.md"),
            LoadedNote.Text(
                text = text,
                snapshot = DiskSnapshot("base", 1, text.length.toLong(), LineEnding.Lf, false),
                permission = EditPermission.Editable(),
            ),
            token = 1,
        )
        return session
    }

    private fun noteFile(text: String): Triple<Path, Path, Path> {
        val vault = tempDir()
        val root = scaffoldWiki(vault)
        val file = root.resolve("wiki/concepts/note.md")
        file.writeText(text)
        return Triple(vault, root, file)
    }

    private fun readOnly(permission: EditPermission): String =
        assertIs<EditPermission.ReadOnly>(permission).reason

    private fun sha(bytes: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun tempDir(): Path = Files.createTempDirectory("mystudy-note")
}
