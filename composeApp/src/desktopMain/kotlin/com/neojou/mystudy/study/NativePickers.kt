package com.neojou.mystudy.study

import java.awt.FileDialog
import java.awt.Frame
import java.io.File

/**
 * macOS folder picker. [apple.awt.fileDialogForDirectories] is set only for this call.
 */
fun pickVaultFolder(): String? = pick(directory = true, title = "Choose vault folder")

private fun pick(directory: Boolean, title: String): String? {
    val key = "apple.awt.fileDialogForDirectories"
    val previous = System.getProperty(key)
    try {
        if (directory) System.setProperty(key, "true") else System.clearProperty(key)
        val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD)
        dialog.isVisible = true
        val name = dialog.file ?: return null
        val folder = dialog.directory ?: return null
        return File(folder, name).absolutePath
    } finally {
        if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
    }
}
