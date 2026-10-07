package com.neojou.tools

/**
 * Central place for app-wide (process-wide) system settings initialization.
 *
 * Keep the initialization code here safe for `commonMain` usage (no platform-specific APIs),
 * and make it idempotent by calling [initOnce] from app startup code.
 */
object SystemSettings {
    /**
     * Tracks whether [initOnce] has already run in this process.
     */
    private var initialized = false

    /**
     * Initializes global settings exactly once per process.
     *
     * If this function is called multiple times, only the first invocation performs work;
     * subsequent invocations return immediately.
     *
     * Current responsibilities:
     * - Configure [MyLog] default/global minimum log level.
     * - Reserve a single place for future global settings (should remain `commonMain`-compatible).
     *
     * @see MyLog.setGlobalMinLevel
     */
    fun initOnce() {
        if (initialized) return
        initialized = true

        // MyLog 初始設定
        MyLog.setGlobalMinLevel(LogLevel.DEBUG)
        //MyLog.setModuleMinLevel("AI", LogLevel.DEBUG)

        // 其他未來的全域設定也可以放這裡（需保持 commonMain 可用）
    }
}
