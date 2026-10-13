package com.neojou.mystudy.wiki.settings

/**
 * How the preview Ingest button treats the current note.
 *
 * [Analyze] is the default: two model calls, then a confirm dialog, and no symbolic link.
 * [Link] writes one relative link under `raw/sources/` and stops.
 */
enum class IngestMode {
    Analyze,
    Link,
}

/** Blank and unknown stored values stay on [IngestMode.Analyze]. */
fun ingestModeFromStored(raw: String): IngestMode =
    if (raw.trim().equals("link", ignoreCase = true)) IngestMode.Link else IngestMode.Analyze

fun IngestMode.storedValue(): String = when (this) {
    IngestMode.Analyze -> "analyze"
    IngestMode.Link -> "link"
}

fun ingestModeLabel(mode: IngestMode): String = when (mode) {
    IngestMode.Analyze -> "Analyze and generate"
    IngestMode.Link -> "Symbolic link only"
}

const val INGEST_MODE_HELP: String =
    "Analyze and generate asks before writing wiki pages. Symbolic link only writes a relative link under raw/sources/ and does not call the model."

/** Shown on the preview when Ingest has not reported anything yet. */
fun ingestIdleHint(mode: IngestMode): String = when (mode) {
    IngestMode.Analyze -> "Ingest reads this note, then asks before writing wiki pages."
    IngestMode.Link -> "Ingest creates a symbolic link under raw/sources/."
}

/** Shown after Symbolic link only finishes. [relativePath] is vault-relative to the wiki root. */
fun ingestLinkMessage(relativePath: String, created: Boolean): String =
    if (created) "Linked $relativePath." else "Already linked at $relativePath."
