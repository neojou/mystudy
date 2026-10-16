package com.neojou.mystudy.wiki.settings

/**
 * Editor chrome stored in Preferences, separate from [StudySettings].
 * Reading versus editing is not stored. Opening a note still starts in reading.
 */
enum class EditorSubMode {
    LivePreview,
    Source,
}

data class UiPreferences(
    val sidebarWidthDp: Int = SIDEBAR_DEFAULT_DP,
    val editorSubMode: EditorSubMode = EditorSubMode.LivePreview,
    val limitLineWidth: Boolean = true,
)

const val SIDEBAR_DEFAULT_DP: Int = 260
const val SIDEBAR_MIN_DP: Int = 180
const val SIDEBAR_MAX_DP: Int = 560
const val SIDEBAR_COLLAPSE_DP: Float = 120f

fun EditorSubMode.stored(): String = when (this) {
    EditorSubMode.LivePreview -> "live"
    EditorSubMode.Source -> "source"
}

/** Unknown and blank values stay on live preview. */
fun editorSubModeFromStored(raw: String): EditorSubMode =
    if (raw.trim().equals("source", ignoreCase = true)) EditorSubMode.Source else EditorSubMode.LivePreview

/** Non-numeric values use the default. Numbers are clamped into the stored range. */
fun parseSidebarWidth(raw: String): Int {
    val parsed = raw.trim().toIntOrNull() ?: return SIDEBAR_DEFAULT_DP
    return parsed.coerceIn(SIDEBAR_MIN_DP, SIDEBAR_MAX_DP)
}

fun limitLineWidthFromStored(raw: String): Boolean = raw.trim() != "false"

/**
 * Layout clamp for the current window.
 * When 45% of the window is at least 180, the range is 180..min(560, 45%).
 * A narrower window uses one width: min(180, window-280), and never below 120.
 */
fun sidebarBounds(windowWidthDp: Float): Pair<Float, Float> {
    val cap = minOf(SIDEBAR_MAX_DP.toFloat(), windowWidthDp * 0.45f)
    if (cap >= SIDEBAR_MIN_DP) return SIDEBAR_MIN_DP.toFloat() to cap
    val tight = minOf(SIDEBAR_MIN_DP.toFloat(), windowWidthDp - 280f).coerceAtLeast(SIDEBAR_COLLAPSE_DP)
    return tight to tight
}

fun clampSidebarWidth(widthDp: Float, windowWidthDp: Float): Float {
    val (minDp, maxDp) = sidebarBounds(windowWidthDp)
    return widthDp.coerceIn(minDp, maxDp)
}

sealed interface SidebarDragEnd {
    data class Persist(val dp: Int) : SidebarDragEnd
    data object Collapse : SidebarDragEnd
}

/**
 * A drag that ends below 120 collapses and does not keep that width.
 * Anything wider is clamped and stored as a whole number of dp.
 */
fun finishSidebarDrag(widthDp: Float, windowWidthDp: Float): SidebarDragEnd {
    if (widthDp < SIDEBAR_COLLAPSE_DP) return SidebarDragEnd.Collapse
    return SidebarDragEnd.Persist(clampSidebarWidth(widthDp, windowWidthDp).toInt())
}

/**
 * Pointer movement only changes [widthDp]. Call [finish] once when the gesture ends.
 */
class SidebarWidthDrag(
    startDp: Float,
    private val windowWidthDp: Float,
) {
    var widthDp: Float = startDp
        private set

    fun moveBy(deltaDp: Float) {
        widthDp = (widthDp + deltaDp).coerceAtLeast(0f)
    }

    fun finish(): SidebarDragEnd = finishSidebarDrag(widthDp, windowWidthDp)
}
