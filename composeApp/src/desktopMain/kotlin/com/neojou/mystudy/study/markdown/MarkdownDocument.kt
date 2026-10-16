package com.neojou.mystudy.study.markdown

import com.neojou.mystudy.study.splitReading
import com.neojou.mystudy.wiki.markdown.FrontmatterView
import com.neojou.mystudy.wiki.markdown.readFrontmatter
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

enum class ColumnAlign { Left, Center, Right }

/**
 * One markdown block. [range] is inclusive and uses offsets in the editor draft
 * (LF, no BOM). Top-level ranges tile the body. Child ranges sit inside quotes.
 */
internal sealed class MarkdownBlock {
    abstract val range: IntRange

    abstract fun withRange(range: IntRange): MarkdownBlock

    abstract fun shift(by: Int): MarkdownBlock

    data class Heading(
        val level: Int,
        val text: String,
        override val range: IntRange,
    ) : MarkdownBlock() {
        override fun withRange(range: IntRange) = copy(range = range)
        override fun shift(by: Int) = copy(range = range.shift(by))
    }

    data class Paragraph(
        val text: String,
        override val range: IntRange,
    ) : MarkdownBlock() {
        override fun withRange(range: IntRange) = copy(range = range)
        override fun shift(by: Int) = copy(range = range.shift(by))
    }

    data class Bullet(
        val depth: Int,
        val text: String,
        override val range: IntRange,
    ) : MarkdownBlock() {
        override fun withRange(range: IntRange) = copy(range = range)
        override fun shift(by: Int) = copy(range = range.shift(by))
    }

    data class Ordered(
        val depth: Int,
        val number: String,
        val text: String,
        override val range: IntRange,
    ) : MarkdownBlock() {
        override fun withRange(range: IntRange) = copy(range = range)
        override fun shift(by: Int) = copy(range = range.shift(by))
    }

    data class Task(
        val depth: Int,
        val checked: Boolean,
        val text: String,
        override val range: IntRange,
    ) : MarkdownBlock() {
        override fun withRange(range: IntRange) = copy(range = range)
        override fun shift(by: Int) = copy(range = range.shift(by))
    }

    data class Code(
        val lang: String?,
        val text: String,
        override val range: IntRange,
    ) : MarkdownBlock() {
        override fun withRange(range: IntRange) = copy(range = range)
        override fun shift(by: Int) = copy(range = range.shift(by))
    }

    data class Table(
        val header: List<String>,
        val aligns: List<ColumnAlign>,
        val rows: List<List<String>>,
        override val range: IntRange,
    ) : MarkdownBlock() {
        override fun withRange(range: IntRange) = copy(range = range)
        override fun shift(by: Int) = copy(range = range.shift(by))
    }

    data class Quote(
        val children: List<MarkdownBlock>,
        override val range: IntRange,
    ) : MarkdownBlock() {
        override fun withRange(range: IntRange) = copy(range = range)
        override fun shift(by: Int) = copy(range = range.shift(by), children = children.map { it.shift(by) })
    }

    data class Callout(
        val kind: String,
        val title: String,
        val children: List<MarkdownBlock>,
        override val range: IntRange,
    ) : MarkdownBlock() {
        override fun withRange(range: IntRange) = copy(range = range)
        override fun shift(by: Int) = copy(range = range.shift(by), children = children.map { it.shift(by) })
    }

    data class Rule(override val range: IntRange) : MarkdownBlock() {
        override fun withRange(range: IntRange) = copy(range = range)
        override fun shift(by: Int) = copy(range = range.shift(by))
    }
}

internal data class MarkdownDocument(
    val frontmatter: FrontmatterView?,
    val blocks: List<MarkdownBlock>,
    val contentStart: Int,
)

private val flavour = GFMFlavourDescriptor()

internal fun parseMarkdownDocument(source: String): MarkdownDocument {
    val (frontmatterText, body) = splitReading(source)
    val bodyStart = source.length - body.length
    val frontmatter = if (frontmatterText == null) null else readFrontmatter(source)
    if (body.isEmpty()) {
        return MarkdownDocument(frontmatter, emptyList(), bodyStart)
    }
    val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(body)
    val natural = tree.children.flatMap { convert(body, it, depth = 0) }
    val filled = if (natural.isEmpty()) {
        listOf(MarkdownBlock.Paragraph(body.trim(), inclusive(0, body.length)))
    } else {
        gapFill(natural, 0, body.length)
    }
    return MarkdownDocument(
        frontmatter = frontmatter,
        blocks = filled.map { it.shift(bodyStart) },
        contentStart = bodyStart,
    )
}

/** Top-level blocks of [source], for callers that already lived on the old helper. */
internal fun markdownBlocks(source: String): List<MarkdownBlock> = parseMarkdownDocument(source).blocks

internal fun spliceBlock(source: String, range: IntRange, replacement: String): String {
    val start = range.first.coerceIn(0, source.length)
    val end = (range.last + 1).coerceIn(start, source.length)
    return source.substring(0, start) + replacement + source.substring(end)
}

internal fun markdownTreeDebug(source: String): String = buildString {
    val body = splitReading(source).second
    val tree = MarkdownParser(flavour).buildMarkdownTreeFromString(body)
    append(dump(body, tree, 0))
}

private fun dump(source: String, node: ASTNode, depth: Int): String = buildString {
    append("  ".repeat(depth))
    append(node.type.name)
    append(" [")
    append(node.startOffset)
    append(',')
    append(node.endOffset)
    append("]\n")
    node.children.forEach { append(dump(source, it, depth + 1)) }
}

private fun convert(source: String, node: ASTNode, depth: Int): List<MarkdownBlock> {
    val name = node.type.name
    if (name == "WHITE_SPACE" || name == "EOL") return emptyList()
    val range = inclusive(node.startOffset, node.endOffset)
    return when {
        name.startsWith("ATX_") -> {
            val level = name.removePrefix("ATX_").toIntOrNull()?.coerceIn(1, 6) ?: 1
            listOf(MarkdownBlock.Heading(level, headingText(source, node), range))
        }
        name == "SETEXT_1" -> listOf(MarkdownBlock.Heading(1, setextText(source, node), range))
        name == "SETEXT_2" -> listOf(MarkdownBlock.Heading(2, setextText(source, node), range))
        name == "PARAGRAPH" -> listOf(MarkdownBlock.Paragraph(slice(source, node).trim(), range))
        name == "UNORDERED_LIST" -> flattenList(source, node, depth, ordered = false)
        name == "ORDERED_LIST" -> flattenList(source, node, depth, ordered = true)
        name == "CODE_FENCE" || name == "CODE_BLOCK" -> listOf(codeBlock(source, node, range))
        name == "BLOCK_QUOTE" || name == "ALERT" -> listOf(quoteOrCallout(source, node, range, depth))
        name == "HORIZONTAL_RULE" -> listOf(MarkdownBlock.Rule(range))
        name == "TABLE" -> listOf(tableBlock(source, node, range))
        else -> {
            val children = node.children.flatMap { convert(source, it, depth) }
            when {
                children.isNotEmpty() -> children
                slice(source, node).isBlank() -> emptyList()
                else -> listOf(MarkdownBlock.Paragraph(slice(source, node).trim(), range))
            }
        }
    }
}

private fun flattenList(source: String, node: ASTNode, depth: Int, ordered: Boolean): List<MarkdownBlock> {
    val items = node.children.filter { it.type.name == "LIST_ITEM" }
    if (items.isEmpty()) return emptyList()
    return items.flatMap { item -> listItem(source, item, depth, ordered) }
}

private fun listItem(source: String, item: ASTNode, depth: Int, ordered: Boolean): List<MarkdownBlock> {
    val range = inclusive(item.startOffset, item.endOffset)
    val nested = item.children.flatMap { child ->
        when (child.type.name) {
            "UNORDERED_LIST" -> flattenList(source, child, depth + 1, ordered = false)
            "ORDERED_LIST" -> flattenList(source, child, depth + 1, ordered = true)
            else -> emptyList()
        }
    }
    val paragraph = item.children.firstOrNull { it.type.name == "PARAGRAPH" }
    val raw = paragraph?.let { slice(source, it).trim() }
        ?: slice(source, item).trim().substringAfter(' ').trim()
    val checkbox = item.children.firstOrNull { it.type.name == "CHECK_BOX" }
    val taskMatch = taskLine.find(raw)
    val checked = when {
        checkbox != null -> slice(source, checkbox).contains('x', ignoreCase = true)
        taskMatch != null -> taskMatch.groupValues[1].equals("x", ignoreCase = true)
        else -> false
    }
    val text = when {
        taskMatch != null -> taskMatch.groupValues[2]
        checkbox != null -> raw
        else -> raw
    }
    val own: MarkdownBlock = when {
        checkbox != null || taskMatch != null -> MarkdownBlock.Task(depth, checked, text, range)
        ordered -> MarkdownBlock.Ordered(depth, listNumber(source, item), text, range)
        else -> MarkdownBlock.Bullet(depth, text, range)
    }
    return listOf(own) + nested
}

private fun listNumber(source: String, item: ASTNode): String {
    val token = item.children.firstOrNull { it.type.name == "LIST_NUMBER" }
    val raw = token?.let { slice(source, it).trim() }.orEmpty()
    return raw.ifBlank { "1." }
}

private fun codeBlock(source: String, node: ASTNode, range: IntRange): MarkdownBlock.Code {
    if (node.type.name == "CODE_BLOCK") {
        val text = slice(source, node).trimEnd('\n').lines().joinToString("\n") { line ->
            line.removePrefix("    ").removePrefix("\t")
        }
        return MarkdownBlock.Code(null, text, range)
    }
    val raw = slice(source, node)
    val lines = raw.removeSuffix("\n").split('\n')
    val opener = lines.firstOrNull().orEmpty().trim()
    val marker = when {
        opener.startsWith("```") -> "```"
        opener.startsWith("~~~") -> "~~~"
        else -> "```"
    }
    val lang = opener.removePrefix(marker).trim().ifBlank { null }
    val closing = lines.lastIndex > 0 && lines.last().trim().startsWith(marker)
    val end = if (closing) lines.size - 1 else lines.size
    val text = if (lines.size <= 1) "" else lines.subList(1, end).joinToString("\n")
    return MarkdownBlock.Code(lang, text, range)
}

private val calloutLine = Regex("""^\[!([A-Za-z]+)][-+]?\s*(.*)$""")
private val taskLine = Regex("""^\[([ xX])](?:\s+(.*))?$""")

private fun quoteOrCallout(source: String, node: ASTNode, range: IntRange, depth: Int): MarkdownBlock {
    val firstLine = slice(source, node).lineSequence()
        .map { it.trim().removePrefix(">").trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
    val match = calloutLine.find(firstLine)
    val children = node.children.flatMap { convert(source, it, depth) }
        .filterNot { it is MarkdownBlock.Quote && it.children.isEmpty() }
    if (match == null) return MarkdownBlock.Quote(children, range)
    val body = children.mapNotNull { block ->
        if (block !is MarkdownBlock.Paragraph) return@mapNotNull block
        val lines = block.text.lines()
        if (lines.isEmpty() || calloutLine.find(lines.first().trim()) == null) return@mapNotNull block
        val rest = lines.drop(1).joinToString("\n") { it.trim().removePrefix(">").trim() }.trim()
        if (rest.isEmpty()) null else block.copy(text = rest)
    }
    return MarkdownBlock.Callout(
        kind = match.groupValues[1],
        title = match.groupValues[2],
        children = body,
        range = range,
    )
}

private fun tableBlock(source: String, node: ASTNode, range: IntRange): MarkdownBlock.Table {
    val headerNode = node.children.firstOrNull { it.type.name == "HEADER" }
    val rowNodes = node.children.filter { it.type.name == "ROW" }
    var header = headerNode?.let { cellsOf(source, it) }.orEmpty()
    var rows = rowNodes.map { cellsOf(source, it) }
    if (header.isEmpty() && rows.isNotEmpty()) {
        header = rows.first()
        rows = rows.drop(1)
    }
    val width = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
    val paddedHeader = pad(header, width)
    val paddedRows = rows.map { pad(it, width) }
    return MarkdownBlock.Table(
        header = paddedHeader,
        aligns = columnAligns(slice(source, node), width),
        rows = paddedRows,
        range = range,
    )
}

private fun cellsOf(source: String, node: ASTNode): List<String> {
    val direct = node.children.filter { it.type.name == "CELL" }
    if (direct.isNotEmpty()) return direct.map { cellText(source, it) }
    val row = node.children.firstOrNull { it.type.name == "ROW" } ?: return emptyList()
    return row.children.filter { it.type.name == "CELL" }.map { cellText(source, it) }
}

private fun cellText(source: String, node: ASTNode): String =
    slice(source, node).trim().replace("\\|", "|")

private fun pad(cells: List<String>, width: Int): List<String> =
    if (cells.size >= width) cells else cells + List(width - cells.size) { "" }

private fun columnAligns(tableSource: String, count: Int): List<ColumnAlign> {
    if (count == 0) return emptyList()
    val separator = tableSource.lineSequence().map { it.trim() }.firstOrNull { line ->
        val body = line.trim('|').trim()
        body.isNotEmpty() && body.all { it == '-' || it == '|' || it == ':' || it == ' ' }
    } ?: return List(count) { ColumnAlign.Left }
    val parts = splitPipes(separator).map { it.trim() }
    return List(count) { index ->
        val cell = parts.getOrNull(index).orEmpty()
        when {
            cell.startsWith(":") && cell.endsWith(":") -> ColumnAlign.Center
            cell.endsWith(":") -> ColumnAlign.Right
            else -> ColumnAlign.Left
        }
    }
}

private fun splitPipes(line: String): List<String> {
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var index = 0
    val text = line.trim().removePrefix("|").removeSuffix("|")
    while (index < text.length) {
        val char = text[index]
        if (char == '\\' && index + 1 < text.length) {
            current.append(text[index + 1])
            index += 2
            continue
        }
        if (char == '|') {
            cells += current.toString()
            current.clear()
            index += 1
            continue
        }
        current.append(char)
        index += 1
    }
    cells += current.toString()
    return cells
}

private fun headingText(source: String, node: ASTNode): String {
    val content = node.children.firstOrNull { it.type.name == "ATX_CONTENT" }
    if (content != null) return slice(source, content).trim()
    return slice(source, node).trim().trimStart('#').trim()
}

private fun setextText(source: String, node: ASTNode): String =
    slice(source, node).lineSequence().firstOrNull { it.isNotBlank() && !it.all { char -> char == '=' || char == '-' } }
        .orEmpty()
        .trim()

private fun slice(source: String, node: ASTNode): String {
    val start = node.startOffset.coerceIn(0, source.length)
    val end = node.endOffset.coerceIn(start, source.length)
    return source.substring(start, end)
}

private fun inclusive(nodeStart: Int, nodeEndExclusive: Int): IntRange {
    if (nodeEndExclusive <= nodeStart) return nodeStart until nodeStart
    return nodeStart until nodeEndExclusive
}

private fun gapFill(blocks: List<MarkdownBlock>, start: Int, endExclusive: Int): List<MarkdownBlock> {
    if (endExclusive <= start) return emptyList()
    if (blocks.isEmpty()) return listOf(MarkdownBlock.Paragraph("", inclusive(start, endExclusive)))
    val out = ArrayList<MarkdownBlock>(blocks.size)
    var cursor = start
    for ((index, block) in blocks.withIndex()) {
        val nodeEnd = block.range.last + 1
        val startAt = if (cursor <= block.range.first) cursor else block.range.first
        val endAt = if (index == blocks.lastIndex) endExclusive else maxOf(nodeEnd, startAt + 1)
        val safeEnd = maxOf(endAt, startAt + 1).coerceAtMost(endExclusive).let { candidate ->
            if (candidate <= startAt) startAt + 1 else candidate
        }
        out += block.withRange(inclusive(startAt, safeEnd))
        cursor = safeEnd
    }
    if (cursor < endExclusive && out.isNotEmpty()) {
        val last = out.last()
        out[out.lastIndex] = last.withRange(inclusive(last.range.first, endExclusive))
    }
    return out
}

private fun IntRange.shift(by: Int): IntRange = (first + by)..(last + by)
