package com.neojou.mystudy

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.neojou.tools.LogLevel
import com.neojou.tools.MyLog
import com.neojou.tools.SystemSettings

/**
 * Open and close state for the one About dialog.
 *
 * The in-app About item and the macOS application-menu About item share one instance.
 */
class AboutRequest {
    var visible by mutableStateOf(false)
        private set

    fun show() {
        visible = true
    }

    fun dismiss() {
        visible = false
    }
}


/**
 * Log tag used by [App] for app-level logging.
 */
private const val TAG = "APP"

/**
 * UI state for application bootstrap/initialization.
 */
private sealed class AppInitState {
    /** Initialization is running. */
    data object Loading : AppInitState()

    /** Initialization completed successfully and the app can render its main UI. */
    data object Ready : AppInitState()

    /**
     * Initialization failed.
     *
     * @property error The root cause of the initialization failure.
     */
    data class Error(val error: Throwable) : AppInitState()
}

/**
 * Root composable of the application.
 *
 * Responsibilities:
 * - Bootstraps app-wide initialization (via [SystemSettings.initOnce]) when this composable
 *   enters the Composition.
 * - Emits an app-level log when initialization succeeds or fails.
 * - Renders a lightweight loading UI while initialization is running, or an error UI on failure.
 *
 * Side effects:
 * Initialization is performed inside [LaunchedEffect] to tie the coroutine to the lifecycle of this
 * composable, as recommended for Compose side effects. [LaunchedEffect] starts when [App] enters
 * the Composition and is cancelled when it leaves it. [web:99]
 *
 * Important:
 * [SystemSettings.initOnce] must be idempotent because [App] may re-enter the Composition later
 * (for example, after navigation changes). [web:145]
 *
 * @since 1.0
 * @see LaunchedEffect
 */
@Composable
fun App(about: AboutRequest = remember { AboutRequest() }) {
    var initState: AppInitState by remember { mutableStateOf(AppInitState.Loading) }

    LaunchedEffect(Unit) {
        runCatching { SystemSettings.initOnce() }
            .onSuccess {
                MyLog.add(TAG, "System Ready", LogLevel.DEBUG)
                initState = AppInitState.Ready
            }
            .onFailure { e ->
                MyLog.add(TAG, "System init failed: ${e.message ?: "unknown"}", LogLevel.ERROR)
                initState = AppInitState.Error(e)
            }
    }

    AppTheme {
        when (val state = initState) {
            AppInitState.Loading -> Text("Loading...")
            AppInitState.Ready -> HomeScreen(about)
            is AppInitState.Error -> Text("Init failed: ${state.error.message ?: "unknown"}")
        }
        if (about.visible) {
            AboutDialog(onDismiss = about::dismiss)
        }
    }
}
