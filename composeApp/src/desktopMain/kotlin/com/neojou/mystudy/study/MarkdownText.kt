package com.neojou.mystudy.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Small Markdown subset for an answer. Markers such as ** are not left on screen.
 * Fenced code uses a monospace face. Body text keeps the theme font so CJK stays visible.
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in markdownBlocks(markdown)) {
            when (block) {
                is MarkdownBlock.Heading -> Text(
                    inlineMarkdown(block.text),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleLarge
                        2 -> MaterialTheme.typography.titleMedium
                        else -> MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Medium,
                )
                is MarkdownBlock.Bullet -> Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("•", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        inlineMarkdown(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                is MarkdownBlock.Code -> Text(
                    block.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
                is MarkdownBlock.Paragraph -> Text(
                    inlineMarkdown(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

internal sealed class MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock()
    data class Heading(val level: Int, val text: String) : MarkdownBlock()
    data class Bullet(val text: String) : MarkdownBlock()
    data class Code(val text: String) : MarkdownBlock()
}

internal fun markdownBlocks(markdown: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val lines = markdown.replace("\r\n", "\n").split('\n')
    var index = 0
    val paragraph = StringBuilder()
    fun flushParagraph() {
        val text = paragraph.toString().trim()
        if (text.isNotEmpty()) blocks += MarkdownBlock.Paragraph(text)
        paragraph.clear()
    }
    while (index < lines.size) {
        val line = lines[index]
        if (line.trimStart().startsWith("```")) {
            flushParagraph()
            val code = StringBuilder()
            index += 1
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                if (code.isNotEmpty()) code.append('\n')
                code.append(lines[index])
                index += 1
            }
            blocks += MarkdownBlock.Code(code.toString())
            if (index < lines.size) index += 1
            continue
        }
        val heading = headingLine.find(line)
        if (heading != null) {
            flushParagraph()
            blocks += MarkdownBlock.Heading(heading.groupValues[1].length, heading.groupValues[2])
            index += 1
            continue
        }
        val bullet = bulletLine.find(line)
        if (bullet != null) {
            flushParagraph()
            blocks += MarkdownBlock.Bullet(bullet.groupValues[1])
            index += 1
            continue
        }
        if (line.isBlank()) {
            flushParagraph()
            index += 1
            continue
        }
        if (paragraph.isNotEmpty()) paragraph.append(' ')
        paragraph.append(line.trim())
        index += 1
    }
    flushParagraph()
    return blocks
}

internal fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("**", index) -> {
                val end = text.indexOf("**", index + 2)
                if (end < 0) {
                    append(text.substring(index))
                    return@buildAnnotatedString
                }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(text.substring(index + 2, end))
                }
                index = end + 2
            }
            text.startsWith("`", index) -> {
                val end = text.indexOf('`', index + 1)
                if (end < 0) {
                    append(text.substring(index))
                    return@buildAnnotatedString
                }
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                    append(text.substring(index + 1, end))
                }
                index = end + 1
            }
            text[index] == '*' -> {
                val end = text.indexOf('*', index + 1)
                if (end < 0) {
                    append(text.substring(index))
                    return@buildAnnotatedString
                }
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(text.substring(index + 1, end))
                }
                index = end + 1
            }
            else -> {
                append(text[index])
                index += 1
            }
        }
    }
}

private val headingLine = Regex("""^(#{1,6})\s+(.*)$""")
private val bulletLine = Regex("""^\s*[-*]\s+(.*)$""")
