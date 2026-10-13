package com.neojou.mystudy.study

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Main-window title and close/focus hooks. Child Ask and Chat windows do not read [dirty].
 */
object EditorChrome {
    var dirty by mutableStateOf(false)

    private var closeHandler: ((() -> Unit) -> Unit)? = null
    private var focusHandler: (() -> Unit)? = null

    fun bind(onClose: (() -> Unit) -> Unit, onFocus: () -> Unit) {
        closeHandler = onClose
        focusHandler = onFocus
    }

    fun unbind() {
        closeHandler = null
        focusHandler = null
        dirty = false
    }

    fun onWindowFocus() {
        focusHandler?.invoke()
    }

    fun requestClose(exit: () -> Unit) {
        val handler = closeHandler
        if (handler == null) exit() else handler(exit)
    }
}
