package com.neojou.mystudy.wiki.index

import com.neojou.mystudy.wiki.vault.isInside
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Watches one wiki root. A null event means the watcher overflowed and the caller should reconcile.
 */
class WikiWatcher(
    private val root: Path,
    private val onEvent: (String?) -> Unit,
) {
    private val service = FileSystems.getDefault().newWatchService()
    private val keys = HashMap<WatchKey, Path>()
    @Volatile private var stopped = false
    private var thread: Thread? = null

    fun start() {
        registerTree(root)
        thread = Thread(::loop, "wiki-watch").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        stopped = true
        try {
            service.close()
        } catch (_: Exception) {
            // Closing the service unblocks take().
        }
        thread?.join(1500)
    }

    private fun registerTree(start: Path) {
        if (!start.isDirectory() || Files.isSymbolicLink(start)) return
        register(start)
        val children = try {
            Files.newDirectoryStream(start).use { it.toList() }
        } catch (_: Exception) {
            return
        }
        for (child in children) {
            if (Files.isSymbolicLink(child) || !child.isDirectory()) continue
            if (child.name.startsWith(".")) continue
            if (!isInside(child, root)) continue
            registerTree(child)
        }
    }

    private fun register(dir: Path) {
        val key = dir.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
        keys[key] = dir
    }

    private fun loop() {
        while (!stopped) {
            val key = try {
                service.take()
            } catch (_: Exception) {
                return
            }
            val dir = keys[key]
            if (dir == null) {
                key.reset()
                continue
            }
            for (event in key.pollEvents()) {
                if (event.kind() == OVERFLOW) {
                    onEvent(null)
                    continue
                }
                val name = event.context() as? Path ?: continue
                val child = dir.resolve(name)
                if (event.kind() == ENTRY_CREATE && Files.isDirectory(child) && !Files.isSymbolicLink(child)) {
                    registerTree(child)
                }
                val relative = try {
                    root.relativize(child).toString().replace('\\', '/')
                } catch (_: Exception) {
                    continue
                }
                if (relative.endsWith(".md") || event.kind() == ENTRY_DELETE) onEvent(relative)
            }
            if (!key.reset()) keys.remove(key)
        }
    }
}
