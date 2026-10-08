package com.neojou.mystudy.wiki.index

import com.neojou.mystudy.wiki.markdown.ParsedNote
import com.neojou.mystudy.wiki.markdown.normalizeKey
import com.neojou.mystudy.wiki.markdown.parseNote
import com.neojou.mystudy.wiki.model.Resolution
import com.neojou.mystudy.wiki.vault.canonical
import com.neojou.mystudy.wiki.vault.isInside
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

data class NoteSummary(
    val path: String,
    val title: String,
    val type: String,
)

data class NoteRecord(
    val path: String,
    val title: String,
    val type: String,
    val headings: List<String>,
    val body: String,
    val tags: List<String>,
    val aliases: List<String>,
    val sources: List<String>,
    val contentHash: String,
    val frontmatterUnparsed: Boolean,
)

/**
 * Rebuildable FTS cache for one wiki root. Markdown files remain the source of truth.
 * Callers use one thread for a given instance.
 */
class WikiIndex private constructor(
    private val connection: Connection,
    val wikiRoot: Path,
) : AutoCloseable {
    private val byAlias = HashMap<String, MutableSet<String>>()
    private val byTag = HashMap<String, MutableSet<String>>()
    private var graphCache: com.neojou.mystudy.wiki.ask.WikiGraph? = null

    fun reconcileAll() {
        val found = linkedSetOf<String>()
        walkMarkdown(wikiRoot) { file, relative ->
            found += relative
            refreshFile(file, relative)
        }
        val stale = selectPaths().filter { it !in found }
        for (path in stale) deletePath(path)
        reloadMaps()
    }

    fun reindexRelative(relative: String) {
        if (relative.contains("..")) return
        val file = wikiRoot.resolve(relative)
        if (!file.isRegularFile() || !relative.endsWith(".md")) {
            deletePath(relative)
            reloadMaps()
            return
        }
        if (!isInside(file, wikiRoot)) return
        refreshFile(file, relative)
        reloadMaps()
    }

    fun noteCount(): Int = connection.createStatement().use { statement ->
        statement.executeQuery("SELECT COUNT(*) FROM notes").use { rows ->
            if (rows.next()) rows.getInt(1) else 0
        }
    }

    fun contentHash(relative: String): String? = queryString(
        "SELECT content_hash FROM notes WHERE path = ?",
        relative,
    )

    fun listSummaries(): List<NoteSummary> {
        val out = mutableListOf<NoteSummary>()
        connection.prepareStatement("SELECT path, title, type FROM notes ORDER BY path").use { statement ->
            statement.executeQuery().use { rows ->
                while (rows.next()) {
                    out += NoteSummary(rows.getString(1), rows.getString(2), rows.getString(3))
                }
            }
        }
        return out
    }

    fun load(relative: String): NoteRecord? {
        connection.prepareStatement(
            "SELECT path, title, type, headings_json, body, frontmatter_json, content_hash, frontmatter_unparsed FROM notes WHERE path = ?",
        ).use { statement ->
            statement.setString(1, relative)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                return noteFrom(rows)
            }
        }
    }

    private fun noteFrom(rows: java.sql.ResultSet): NoteRecord {
        val front = parseJsonObject(rows.getString(6))
        return NoteRecord(
            path = rows.getString(1),
            title = rows.getString(2),
            type = rows.getString(3) ?: "",
            headings = parseJsonList(rows.getString(4)),
            body = rows.getString(5) ?: "",
            tags = jsonList(front, "tags"),
            aliases = jsonList(front, "aliases"),
            sources = jsonList(front, "sources"),
            contentHash = rows.getString(7),
            frontmatterUnparsed = rows.getInt(8) == 1,
        )
    }

    fun searchFts(terms: List<String>, limit: Int): List<String> {
        val query = ftsQuery(terms) ?: return emptyList()
        val out = mutableListOf<String>()
        try {
            connection.prepareStatement(
                "SELECT path FROM notes_fts WHERE notes_fts MATCH ? ORDER BY rank LIMIT ?",
            ).use { statement ->
                statement.setString(1, query)
                statement.setInt(2, limit)
                statement.executeQuery().use { rows ->
                    while (rows.next()) out += rows.getString(1)
                }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return out
    }

    fun searchLike(term: String, limit: Int): List<String> {
        val pattern = likePattern(term)
        val out = mutableListOf<String>()
        connection.prepareStatement(
            """
            SELECT path FROM notes
            WHERE title LIKE ? ESCAPE '\' OR body LIKE ? ESCAPE '\' OR headings_json LIKE ? ESCAPE '\'
            LIMIT ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, pattern)
            statement.setString(2, pattern)
            statement.setString(3, pattern)
            statement.setInt(4, limit)
            statement.executeQuery().use { rows ->
                while (rows.next()) out += rows.getString(1)
            }
        }
        return out
    }

    fun resolve(token: String, extraAliases: List<String> = emptyList()): Resolution {
        val stem = token.trim().substringAfterLast('/').removeSuffix(".md")
        val named = resolveNamed(listOf(token.trim()) + extraAliases + stem)
        if (named !is Resolution.None) return named
        if (!token.contains("/") && !token.trim().endsWith(".md")) return Resolution.None
        val pathKey = token.trim().removePrefix("./").removeSuffix(".md")
        val hits = listOf("$pathKey.md", token.trim()).distinct().filter { noteExists(it) }
        return when (hits.size) {
            0 -> Resolution.None
            1 -> Resolution.One(hits.first())
            else -> Resolution.Ambiguous(hits)
        }
    }

    fun linksFrom(relative: String): List<String> {
        val raws = mutableListOf<String>()
        connection.prepareStatement("SELECT target_raw FROM links WHERE src_path = ?").use { statement ->
            statement.setString(1, relative)
            statement.executeQuery().use { rows ->
                while (rows.next()) raws += rows.getString(1)
            }
        }
        return raws.mapNotNull { raw ->
            when (val resolved = resolve(raw)) {
                is Resolution.One -> resolved.path
                else -> null
            }
        }
    }

    fun findSourceForRaw(rawRelative: String): String? {
        val wanted = setOf(rawRelative, rawRelative.removePrefix("raw/"))
        for (summary in listSummaries()) {
            if (summary.type != "source") continue
            val note = load(summary.path) ?: continue
            if (note.sources.any { it in wanted || it.removePrefix("raw/") in wanted }) return summary.path
        }
        return null
    }

    fun pathsForAlias(key: String): List<String> = byAlias[normalizeKey(key)].orEmpty().toList()

    fun pathsForTag(tag: String): List<String> = byTag[tag].orEmpty().toList()

    fun noteExists(relative: String): Boolean = queryString(
        "SELECT path FROM notes WHERE path = ?",
        relative,
    ) != null

    fun notesForAsk(): List<NoteRecord> {
        val out = mutableListOf<NoteRecord>()
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT path, title, type, headings_json, body, frontmatter_json, content_hash, frontmatter_unparsed
                FROM notes
                WHERE path LIKE 'wiki/%'
                  AND path NOT IN ('wiki/index.md', 'wiki/log.md', 'wiki/schema.md')
                ORDER BY path
                """.trimIndent(),
            ).use { rows ->
                while (rows.next()) out += noteFrom(rows)
            }
        }
        return out
    }

    fun linkRows(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT src_path, target_raw FROM links").use { rows ->
                while (rows.next()) out += rows.getString(1) to rows.getString(2)
            }
        }
        return out
    }

    fun askNoteCount(): Int {
        connection.createStatement().use { statement ->
            statement.executeQuery(
                """
                SELECT COUNT(*) FROM notes
                WHERE path LIKE 'wiki/%'
                  AND path NOT IN ('wiki/index.md', 'wiki/log.md', 'wiki/schema.md')
                """.trimIndent(),
            ).use { rows ->
                return if (rows.next()) rows.getInt(1) else 0
            }
        }
    }

    fun countAskTerm(term: String): Int {
        val pattern = likePattern(term)
        connection.prepareStatement(
            """
            SELECT COUNT(*) FROM notes
            WHERE path LIKE 'wiki/%'
              AND path NOT IN ('wiki/index.md', 'wiki/log.md', 'wiki/schema.md')
              AND (title LIKE ? ESCAPE '\' OR body LIKE ? ESCAPE '\' OR headings_json LIKE ? ESCAPE '\')
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, pattern)
            statement.setString(2, pattern)
            statement.setString(3, pattern)
            statement.executeQuery().use { rows ->
                return if (rows.next()) rows.getInt(1) else 0
            }
        }
    }

    fun wikiGraph(): com.neojou.mystudy.wiki.ask.WikiGraph {
        graphCache?.let { return it }
        val built = com.neojou.mystudy.wiki.ask.buildWikiGraph(this)
        graphCache = built
        return built
    }

    override fun close() {
        connection.close()
    }

    private fun resolveNamed(candidates: List<String>): Resolution {
        val found = linkedSetOf<String>()
        var seen = false
        for (candidate in candidates) {
            val hits = byAlias[normalizeKey(candidate)].orEmpty()
            if (hits.isEmpty()) continue
            seen = true
            if (hits.size > 1) return Resolution.Ambiguous(hits.toList())
            found += hits
        }
        if (!seen) return Resolution.None
        if (found.size == 1) return Resolution.One(found.first())
        return Resolution.Ambiguous(found.toList())
    }

    private fun refreshFile(file: Path, relative: String) {
        val size = Files.size(file)
        val mtime = Files.getLastModifiedTime(file).toMillis()
        val previous = selectStamp(relative)
        if (size > MAX_INDEX_BYTES) {
            val hash = hashFile(file)
            if (previous != null && previous.hash == hash) {
                updateStamp(relative, mtime, size)
                return
            }
            replaceRow(
                relative = relative,
                parsed = parseNote("", file.fileName.toString().removeSuffix(".md")),
                hash = hash,
                mtime = mtime,
                size = size,
                oversized = true,
                body = "",
            )
            return
        }
        val bytes = Files.readAllBytes(file)
        val hash = sha256(bytes)
        if (previous != null && previous.hash == hash) {
            updateStamp(relative, mtime, size)
            return
        }
        val text = decodeUtf8(bytes)
        if (text == null) {
            replaceRow(
                relative = relative,
                parsed = parseNote("", file.fileName.toString().removeSuffix(".md")),
                hash = hash,
                mtime = mtime,
                size = size,
                oversized = false,
                body = "",
                unreadable = true,
            )
            return
        }
        val parsed = parseNote(text, file.fileName.toString().removeSuffix(".md"))
        replaceRow(relative, parsed, hash, mtime, size, oversized = false, body = parsed.body)
    }

    private fun replaceRow(
        relative: String,
        parsed: ParsedNote,
        hash: String,
        mtime: Long,
        size: Long,
        oversized: Boolean,
        body: String,
        unreadable: Boolean = false,
    ) {
        connection.autoCommit = false
        try {
            deletePath(relative)
            val front = buildJsonObject {
                put("title", JsonPrimitive(parsed.title))
                put("type", JsonPrimitive(parsed.type))
                put("tags", jsonArrayOf(parsed.tags))
                put("aliases", jsonArrayOf(parsed.aliases))
                put("sources", jsonArrayOf(parsed.sources))
                put("updated", JsonPrimitive(parsed.updated ?: ""))
                put("oversized", JsonPrimitive(oversized))
            }.toString()
            connection.prepareStatement(
                """
                INSERT INTO notes(path, title, type, mtime_ms, size_bytes, content_hash, headings_json, frontmatter_json, body, frontmatter_unparsed)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, relative)
                statement.setString(2, parsed.title)
                statement.setString(3, parsed.type)
                statement.setLong(4, mtime)
                statement.setLong(5, size)
                statement.setString(6, hash)
                statement.setString(7, jsonArrayOf(parsed.headings).toString())
                statement.setString(8, front)
                statement.setString(9, if (oversized || unreadable) "" else body)
                statement.setInt(10, if (parsed.frontmatterUnparsed || unreadable) 1 else 0)
                statement.executeUpdate()
            }
            insertAliases(relative, parsed)
            insertTags(relative, parsed.tags)
            insertLinks(relative, parsed.wikilinks)
            if (!oversized && !unreadable && body.isNotEmpty()) {
                connection.prepareStatement(
                    "INSERT INTO notes_fts(path, title, headings, body) VALUES (?, ?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, relative)
                    statement.setString(2, parsed.title)
                    statement.setString(3, parsed.headings.joinToString("\n"))
                    statement.setString(4, body)
                    statement.executeUpdate()
                }
            }
            connection.commit()
        } catch (error: Exception) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
        }
    }

    private fun insertAliases(relative: String, parsed: ParsedNote) {
        val stem = relative.substringAfterLast('/').removeSuffix(".md")
        val rows = mutableListOf<Pair<String, String>>()
        if (parsed.title.isNotBlank()) rows += parsed.title to "title"
        parsed.aliases.forEach { rows += it to "alias" }
        if (stem.isNotBlank()) rows += stem to "stem"
        connection.prepareStatement("INSERT OR IGNORE INTO aliases(alias_key, path, kind) VALUES (?, ?, ?)").use { statement ->
            for ((value, kind) in rows) {
                val key = normalizeKey(value)
                if (key.isEmpty()) continue
                statement.setString(1, key)
                statement.setString(2, relative)
                statement.setString(3, kind)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertTags(relative: String, tags: List<String>) {
        connection.prepareStatement("INSERT OR IGNORE INTO tags(tag, path) VALUES (?, ?)").use { statement ->
            for (tag in tags) {
                if (tag.isBlank()) continue
                statement.setString(1, tag.trim())
                statement.setString(2, relative)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertLinks(relative: String, links: List<String>) {
        connection.prepareStatement(
            "INSERT OR IGNORE INTO links(src_path, target_raw, target_path) VALUES (?, ?, NULL)",
        ).use { statement ->
            for (link in links.distinct()) {
                statement.setString(1, relative)
                statement.setString(2, link)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun deletePath(relative: String) {
        for (sql in listOf(
            "DELETE FROM notes WHERE path = ?",
            "DELETE FROM aliases WHERE path = ?",
            "DELETE FROM tags WHERE path = ?",
            "DELETE FROM links WHERE src_path = ?",
            "DELETE FROM notes_fts WHERE path = ?",
        )) {
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, relative)
                statement.executeUpdate()
            }
        }
    }

    private fun reloadMaps() {
        graphCache = null
        byAlias.clear()
        byTag.clear()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT alias_key, path FROM aliases").use { rows ->
                while (rows.next()) {
                    byAlias.getOrPut(rows.getString(1)) { linkedSetOf() }.add(rows.getString(2))
                }
            }
        }
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT tag, path FROM tags").use { rows ->
                while (rows.next()) {
                    byTag.getOrPut(rows.getString(1)) { linkedSetOf() }.add(rows.getString(2))
                }
            }
        }
    }

    private fun selectPaths(): List<String> {
        val out = mutableListOf<String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT path FROM notes").use { rows ->
                while (rows.next()) out += rows.getString(1)
            }
        }
        return out
    }

    private data class Stamp(val hash: String, val mtime: Long, val size: Long)

    private fun selectStamp(relative: String): Stamp? {
        connection.prepareStatement("SELECT content_hash, mtime_ms, size_bytes FROM notes WHERE path = ?").use { statement ->
            statement.setString(1, relative)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return null
                return Stamp(rows.getString(1), rows.getLong(2), rows.getLong(3))
            }
        }
    }

    private fun updateStamp(relative: String, mtime: Long, size: Long) {
        connection.prepareStatement("UPDATE notes SET mtime_ms = ?, size_bytes = ? WHERE path = ?").use { statement ->
            statement.setLong(1, mtime)
            statement.setLong(2, size)
            statement.setString(3, relative)
            statement.executeUpdate()
        }
    }

    private fun queryString(sql: String, arg: String): String? {
        connection.prepareStatement(sql).use { statement ->
            statement.setString(1, arg)
            statement.executeQuery().use { rows ->
                return if (rows.next()) rows.getString(1) else null
            }
        }
    }

    companion object {
        const val MAX_INDEX_BYTES: Long = 2_000_000

        fun open(wikiRoot: Path, database: Path): WikiIndex {
            Files.createDirectories(database.parent)
            Class.forName("org.sqlite.JDBC")
            val connection = DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}")
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA busy_timeout=5000")
                SCHEMA.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { sql ->
                    statement.execute(sql)
                }
            }
            val index = WikiIndex(connection, canonical(wikiRoot))
            index.reloadMaps()
            return index
        }
    }
}

private const val SCHEMA = """
CREATE TABLE IF NOT EXISTS meta (
  key TEXT PRIMARY KEY,
  value TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS notes (
  path TEXT PRIMARY KEY,
  title TEXT NOT NULL,
  type TEXT NOT NULL DEFAULT '',
  mtime_ms INTEGER NOT NULL,
  size_bytes INTEGER NOT NULL,
  content_hash TEXT NOT NULL,
  headings_json TEXT NOT NULL,
  frontmatter_json TEXT NOT NULL,
  body TEXT NOT NULL,
  frontmatter_unparsed INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS aliases (
  alias_key TEXT NOT NULL,
  path TEXT NOT NULL,
  kind TEXT NOT NULL,
  PRIMARY KEY (alias_key, path, kind)
);
CREATE TABLE IF NOT EXISTS tags (
  tag TEXT NOT NULL,
  path TEXT NOT NULL,
  PRIMARY KEY (tag, path)
);
CREATE TABLE IF NOT EXISTS links (
  src_path TEXT NOT NULL,
  target_raw TEXT NOT NULL,
  target_path TEXT,
  PRIMARY KEY (src_path, target_raw)
);
CREATE VIRTUAL TABLE IF NOT EXISTS notes_fts USING fts5(
  path UNINDEXED,
  title,
  headings,
  body,
  tokenize = 'trigram'
);
"""

fun indexDatabasePath(wikiRoot: Path): Path {
    val home = Path.of(System.getProperty("user.home"), "Library", "Application Support", "mystudy", "index")
    Files.createDirectories(home)
    val id = sha256(canonical(wikiRoot).toString().toByteArray(StandardCharsets.UTF_8))
    return home.resolve("$id.sqlite")
}

private fun walkMarkdown(root: Path, visitor: (Path, String) -> Unit) {
    fun recurse(dir: Path) {
        val children = try {
            Files.newDirectoryStream(dir).use { it.toList() }
        } catch (_: Exception) {
            return
        }
        for (child in children) {
            if (Files.isSymbolicLink(child)) continue
            val name = child.name
            if (name.startsWith(".")) continue
            if (!isInside(child, root)) continue
            when {
                child.isDirectory() -> recurse(child)
                child.isRegularFile() && name.endsWith(".md") -> {
                    val relative = root.relativize(child).toString().replace('\\', '/')
                    visitor(child, relative)
                }
            }
        }
    }
    if (root.isDirectory()) recurse(root)
}

private fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

private fun hashFile(file: Path): String {
    val digest = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(file).use { input ->
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun decodeUtf8(bytes: ByteArray): String? {
    val decoder: CharsetDecoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

private fun jsonArrayOf(values: List<String>): JsonArray = buildJsonArray {
    values.forEach { add(JsonPrimitive(it)) }
}

private fun parseJsonList(json: String): List<String> = try {
    Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content }
} catch (_: Exception) {
    emptyList()
}

private fun parseJsonObject(json: String) = try {
    Json.parseToJsonElement(json).jsonObject
} catch (_: Exception) {
    buildJsonObject { }
}

private fun jsonList(objectJson: kotlinx.serialization.json.JsonObject, key: String): List<String> =
    objectJson[key]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

internal fun ftsQuery(terms: List<String>): String? {
    val usable = terms.map { it.replace(Regex("""["*^:()]"""), "").trim() }.filter { it.length >= 3 }.distinct()
    if (usable.isEmpty()) return null
    return usable.joinToString(" OR ") { "\"${it.replace("\"", "\"\"")}\"" }
}

private fun likePattern(term: String): String {
    val escaped = term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    return "%$escaped%"
}
