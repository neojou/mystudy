package com.neojou.mystudy.wiki.settings

import java.net.URI
import java.util.prefs.Preferences

data class StudySettings(
    val vaultPath: String,
    val wikiRootPath: String,
    val ollamaBaseUrl: String,
    val ollamaModel: String,
    val ollamaNumCtx: Int,
    val searxngUrl: String,
    val ingestMode: IngestMode = IngestMode.Analyze,
)

interface SettingsStore {
    fun get(key: String, default: String): String
    fun put(key: String, value: String)
}

/**
 * In-memory store for tests. The running app uses [PreferencesSettingsStore].
 */
class MemorySettingsStore(
    initial: Map<String, String> = emptyMap(),
) : SettingsStore {
    private val values = initial.toMutableMap()

    override fun get(key: String, default: String): String = values[key] ?: default

    override fun put(key: String, value: String) {
        values[key] = value
    }
}

/**
 * User preferences outside the vault, so Obsidian does not see an app file.
 */
class PreferencesSettingsStore : SettingsStore {
    private val prefs: Preferences = Preferences.userRoot().node("com/neojou/mystudy/wiki")

    override fun get(key: String, default: String): String = prefs.get(key, default)

    override fun put(key: String, value: String) {
        prefs.put(key, value)
        prefs.flush()
    }
}

class AppSettings(private val store: SettingsStore) {
    fun load(): StudySettings = StudySettings(
        vaultPath = store.get(KEY_VAULT, DEFAULT_VAULT).ifBlank { DEFAULT_VAULT },
        wikiRootPath = store.get(KEY_WIKI_ROOT, ""),
        ollamaBaseUrl = store.get(KEY_OLLAMA_URL, DEFAULT_OLLAMA_URL).ifBlank { DEFAULT_OLLAMA_URL },
        ollamaModel = store.get(KEY_OLLAMA_MODEL, DEFAULT_OLLAMA_MODEL).ifBlank { DEFAULT_OLLAMA_MODEL },
        ollamaNumCtx = store.get(KEY_OLLAMA_NUM_CTX, DEFAULT_NUM_CTX.toString()).toIntOrNull()
            ?: DEFAULT_NUM_CTX,
        searxngUrl = store.get(KEY_SEARXNG_URL, DEFAULT_SEARXNG_URL).ifBlank { DEFAULT_SEARXNG_URL },
        ingestMode = ingestModeFromStored(store.get(KEY_INGEST_MODE, "analyze")),
    )

    /** Stores [settings]. A blank SearXNG URL becomes the default. A non-loopback URL is rejected. */
    fun save(settings: StudySettings): StudySettings {
        val searxngUrl = normalizeSearxngUrl(settings.searxngUrl)
        store.put(KEY_VAULT, settings.vaultPath.trim())
        store.put(KEY_WIKI_ROOT, settings.wikiRootPath.trim())
        store.put(KEY_OLLAMA_URL, settings.ollamaBaseUrl.trim())
        store.put(KEY_OLLAMA_MODEL, settings.ollamaModel.trim())
        store.put(KEY_OLLAMA_NUM_CTX, settings.ollamaNumCtx.toString())
        store.put(KEY_SEARXNG_URL, searxngUrl)
        store.put(KEY_INGEST_MODE, settings.ingestMode.storedValue())
        return settings.copy(searxngUrl = searxngUrl)
    }

    companion object {
        const val DEFAULT_VAULT: String = "/Users/neojou/Knowledge/第二大腦"
        const val DEFAULT_OLLAMA_URL: String = "http://127.0.0.1:11434/v1"
        const val DEFAULT_OLLAMA_MODEL: String = "gemma4-64k"
        const val DEFAULT_NUM_CTX: Int = 65536
        const val DEFAULT_SEARXNG_URL: String = "http://127.0.0.1:8080"

        /**
         * Blank becomes [DEFAULT_SEARXNG_URL].
         * Only `http` or `https` on `127.0.0.1` or `localhost` is kept.
         */
        fun normalizeSearxngUrl(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) return DEFAULT_SEARXNG_URL
            val uri = try {
                URI(trimmed)
            } catch (_: Exception) {
                throw IllegalArgumentException("SearXNG URL is invalid.")
            }
            val host = uri.host?.lowercase() ?: throw IllegalArgumentException("SearXNG URL is invalid.")
            if (host != "127.0.0.1" && host != "localhost") {
                throw IllegalArgumentException("SearXNG host must be loopback.")
            }
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") {
                throw IllegalArgumentException("SearXNG URL is invalid.")
            }
            val path = uri.rawPath
            if (!path.isNullOrEmpty() && path != "/") throw IllegalArgumentException("SearXNG URL is invalid.")
            if (!uri.rawQuery.isNullOrEmpty() || uri.rawFragment != null) {
                throw IllegalArgumentException("SearXNG URL is invalid.")
            }
            val port = if (uri.port == -1) "" else ":${uri.port}"
            return "$scheme://$host$port"
        }

        private const val KEY_VAULT = "vaultPath"
        private const val KEY_WIKI_ROOT = "wikiRootPath"
        private const val KEY_OLLAMA_URL = "ollamaBaseUrl"
        private const val KEY_OLLAMA_MODEL = "ollamaModel"
        private const val KEY_OLLAMA_NUM_CTX = "ollamaNumCtx"
        private const val KEY_SEARXNG_URL = "searxngUrl"
        private const val KEY_INGEST_MODE = "ingestMode"
    }
}
