package com.neojou.mystudy.study

/**
 * Splits a leading closed `---` block from editor text that already uses LF.
 * The rest is rendered as Markdown. Unclosed markers stay in the body.
 */
fun splitReading(text: String): Pair<String?, String> {
    if (!text.startsWith("---\n")) return null to text
    val rest = text.substring(4)
    val end = rest.indexOf("\n---\n")
    if (end >= 0) return rest.substring(0, end) to rest.substring(end + 5).removePrefix("\n")
    if (rest.endsWith("\n---")) return rest.removeSuffix("\n---") to ""
    return null to text
}
