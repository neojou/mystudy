package com.neojou.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.TimeSource

/**
 * Logging severity level used by [MyLog].
 */
enum class LogLevel(val priority: Int) {
    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3)
}

data class LogEntry(
    val elapsed: String,
    val level: LogLevel,
    val module: String,
    val message: String
)

/**
 * Thread-safe in-memory logger using Coroutines Mutex.
 *
 * Features:
 * - Asynchronous writing: [add] is non-blocking and thread-safe.
 * - Suspending reads: [getAll] and [getFiltered] allow safe access to data.
 */
object MyLog {
    private var consoleOn: Boolean = true

    /**
     * Internal scope for processing log writes asynchronously.
     * Uses SupervisorJob so a failure in one log doesn't cancel the scope.
     */
    private val logScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /**
     * Mutex to protect [entries] from concurrent modification.
     */
    private val mutex = Mutex()

    /**
     * Protected by [mutex].
     */
    private val entries: MutableList<LogEntry> = mutableListOf()

    private var globalMinLevel: LogLevel = LogLevel.DEBUG
    private val moduleMinLevels: MutableMap<String, LogLevel> = mutableMapOf()

    // 程式啟動基準點
    private val startMark = TimeSource.Monotonic.markNow()

    fun turnOnConsole() { consoleOn = true }
    fun turnOffConsole() { consoleOn = false }

    fun setGlobalMinLevel(level: LogLevel) { globalMinLevel = level }

    fun setModuleMinLevel(module: String, level: LogLevel) {
        // Map 寫入也建議保護，若設定頻繁可考慮 ConcurrentMap 或同樣用 Mutex
        // 簡單起見，這裡假設配置是在單執行緒或啟動時完成
        moduleMinLevels[module] = level
    }

    fun clearModuleMinLevel(module: String) {
        moduleMinLevels.remove(module)
    }

    private fun shouldPrint(level: LogLevel, module: String): Boolean {
        if (!consoleOn) return false
        val minLevel = moduleMinLevels[module] ?: globalMinLevel
        return level.priority >= minLevel.priority
    }

    /**
     * Adds a log entry safely.
     *
     * This function is non-suspend, so it can be called from anywhere.
     * The entry is added to the list asynchronously via a private CoroutineScope.
     */
    fun add(module: String, message: String, level: LogLevel = LogLevel.INFO) {
        // 1. 立即計算時間，確保 Timestamp 準確反映呼叫當下
        val elapsedStr = startMark.elapsedNow().toString()

        val entry = LogEntry(
            elapsed = elapsedStr,
            level = level,
            module = module,
            message = message
        )

        // 2. Console 輸出維持同步執行，方便開發除錯（不需等待協程調度）
        if (shouldPrint(level, module)) {
            println("[$elapsedStr] [${level}] [$module] $message")
        }

        // 3. 啟動協程獲取鎖並寫入清單
        logScope.launch {
            mutex.withLock {
                entries.add(entry)
            }
        }
    }

    /**
     * Returns an immutable snapshot of all collected entries.
     * Must be called from a Coroutine or suspend function.
     */
    suspend fun getAll(): List<LogEntry> {
        return mutex.withLock {
            entries.toList()
        }
    }

    /**
     * Returns filtered entries safely.
     */
    suspend fun getFiltered(module: String? = null, minLevel: LogLevel = LogLevel.DEBUG): List<LogEntry> {
        return mutex.withLock {
            entries.filter {
                (module == null || it.module == module) && it.level.priority >= minLevel.priority
            }
        }
    }

    /**
     * Clears all collected entries from memory safely.
     */
    fun clear() {
        logScope.launch {
            mutex.withLock {
                entries.clear()
            }
        }
    }
}