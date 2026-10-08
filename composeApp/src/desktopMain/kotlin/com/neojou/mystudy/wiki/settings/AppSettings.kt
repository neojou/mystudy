package com.neojou.mystudy.wiki.settings

import java.util.prefs.Preferences

data class StudySettings(
    val vaultPath: String,
    val wikiRootPath: String,
    val ollamaBaseUrl: String,
    val ollamaModel: String,
    val ollamaNumCtx: Int,
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
    )

    fun save(settings: StudySettings) {
        store.put(KEY_VAULT, settings.vaultPath.trim())
        store.put(KEY_WIKI_ROOT, settings.wikiRootPath.trim())
        store.put(KEY_OLLAMA_URL, settings.ollamaBaseUrl.trim())
        store.put(KEY_OLLAMA_MODEL, settings.ollamaModel.trim())
        store.put(KEY_OLLAMA_NUM_CTX, settings.ollamaNumCtx.toString())
    }

    companion object {
        const val DEFAULT_VAULT: String = "/Users/neojou/Knowledge/第二大腦"
        const val DEFAULT_OLLAMA_URL: String = "http://127.0.0.1:11434/v1"
        const val DEFAULT_OLLAMA_MODEL: String = "gemma4-64k"
        const val DEFAULT_NUM_CTX: Int = 65536

        private const val KEY_VAULT = "vaultPath"
        private const val KEY_WIKI_ROOT = "wikiRootPath"
        private const val KEY_OLLAMA_URL = "ollamaBaseUrl"
        private const val KEY_OLLAMA_MODEL = "ollamaModel"
        private const val KEY_OLLAMA_NUM_CTX = "ollamaNumCtx"
    }
}
