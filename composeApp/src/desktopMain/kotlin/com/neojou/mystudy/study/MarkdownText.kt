package com.neojou.mystudy.study

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neojou.mystudy.study.markdown.MarkdownBlock
import com.neojou.mystudy.study.markdown.MarkdownDocument
import com.neojou.mystudy.study.markdown.parseMarkdownDocument
import com.neojou.mystudy.ui.theme.LocalStudyTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class MarkdownStyle { Document, Compact }

/**
 * Renders a markdown answer or note.
 *
 * [MarkdownStyle.Compact] is the chat and ask layout: no reading-width cap and no properties.
 * [MarkdownStyle.Document] is the note: centered, capped, with a properties panel.
 */
@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    style: MarkdownStyle = MarkdownStyle.Compact,
    onWikilink: (String) -> Unit = {},
    limitWidth: Boolean = false,
    lazy: Boolean = false,
    readingListState: androidx.compose.foundation.lazy.LazyListState? = null,
) {
    val doc = rememberMarkdownDocument(markdown)
    if (lazy && style == MarkdownStyle.Document) {
        LazyDocument(doc, onWikilink, limitWidth, modifier, readingListState)
    } else {
        SelectionContainer(modifier) {
            Column(Modifier.fillMaxWidth()) {
                MarkdownBlocks(doc, style, onWikilink, limitWidth, showProperties = style == MarkdownStyle.Document)
            }
        }
    }
}

@Composable
internal fun MarkdownBlocks(
    doc: MarkdownDocument,
    style: MarkdownStyle,
    onWikilink: (String) -> Unit,
    limitWidth: Boolean,
    showProperties: Boolean,
) {
    val spaced = if (style == MarkdownStyle.Compact) 6.dp else 0.dp
    WidthSlot(limitWidth && style == MarkdownStyle.Document) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(spaced)) {
            if (showProperties) PropertiesPanel(doc.frontmatter, onWikilink)
            doc.blocks.forEach { block ->
                MarkdownBlockView(block, style, onWikilink)
            }
        }
    }
}

@Composable
private fun LazyDocument(
    doc: MarkdownDocument,
    onWikilink: (String) -> Unit,
    limitWidth: Boolean,
    modifier: Modifier,
    readingListState: androidx.compose.foundation.lazy.LazyListState?,
) {
    val list = readingListState ?: rememberLazyListState()
    Box(modifier) {
        SelectionContainer(Modifier.fillMaxSize()) {
            LazyColumn(
                state = list,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                item(key = "properties") {
                    WidthSlot(limitWidth) { PropertiesPanel(doc.frontmatter, onWikilink) }
                }
                items(doc.blocks.size, key = { index -> "${doc.blocks[index].range.first}-$index" }) { index ->
                    WidthSlot(limitWidth) {
                        MarkdownBlockView(doc.blocks[index], MarkdownStyle.Document, onWikilink)
                    }
                }
            }
        }
        androidx.compose.foundation.VerticalScrollbar(
            adapter = rememberScrollbarAdapter(list),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
internal fun WidthSlot(limit: Boolean, content: @Composable () -> Unit) {
    val tokens = LocalStudyTokens.current
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .then(if (limit) Modifier.widthIn(max = tokens.readingMaxWidth) else Modifier)
                .fillMaxWidth()
                .padding(horizontal = if (limit) tokens.contentPadding else 0.dp),
        ) {
            content()
        }
    }
}

@Composable
internal fun rememberMarkdownDocument(markdown: String): MarkdownDocument {
    if (markdown.length <= 32_000) {
        return remember(markdown) { parseMarkdownDocument(markdown) }
    }
    var doc by remember(markdown) { mutableStateOf(parseMarkdownDocument("")) }
    LaunchedEffect(markdown) {
        doc = withContext(Dispatchers.Default) { parseMarkdownDocument(markdown) }
    }
    return doc
}

@Composable
internal fun MarkdownBlockView(
    block: MarkdownBlock,
    style: MarkdownStyle,
    onWikilink: (String) -> Unit,
) {
    val tokens = LocalStudyTokens.current
    val link = tokens.link
    val pad = blockPadding(block, style)
    when (block) {
        is MarkdownBlock.Heading -> Column(pad) {
            Text(inlineMarkdown(block.text, link, onWikilink), style = headingStyle(block.level, style))
            if (style == MarkdownStyle.Document && block.level == 2) {
                Box(
                    Modifier
                        .padding(top = 4.dp)
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
        }
        is MarkdownBlock.Paragraph -> {
            if (block.text.isBlank()) return
            Text(
                inlineMarkdown(block.text, link, onWikilink),
                modifier = pad,
                style = bodyStyle(style),
            )
        }
        is MarkdownBlock.Bullet -> ListRow(block.depth, "•", inlineMarkdown(block.text, link, onWikilink), style, pad)
        is MarkdownBlock.Ordered -> ListRow(block.depth, block.number, inlineMarkdown(block.text, link, onWikilink), style, pad)
        is MarkdownBlock.Task -> Row(pad.padding(start = (block.depth * 16).dp), verticalAlignment = Alignment.Top) {
            Checkbox(checked = block.checked, onCheckedChange = null)
            Text(inlineMarkdown(block.text, link, onWikilink), style = bodyStyle(style), modifier = Modifier.weight(1f))
        }
        is MarkdownBlock.Code -> Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = MaterialTheme.shapes.small,
            modifier = pad.fillMaxWidth(),
        ) {
            val scroll = rememberScrollState()
            Text(
                block.text,
                modifier = Modifier.horizontalScroll(scroll).padding(12.dp),
                style = bodyStyle(style).copy(fontFamily = FontFamily.Monospace, fontSize = tokens.codeSize, lineHeight = 20.sp),
            )
        }
        is MarkdownBlock.Table -> Box(pad) { MarkdownTable(block, link, onWikilink) }
        is MarkdownBlock.Quote -> Row(pad.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(tokens.link.copy(alpha = 0.4f)))
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                block.children.forEach { MarkdownBlockView(it, style, onWikilink) }
            }
        }
        is MarkdownBlock.Callout -> {
            val accent = calloutAccent(block.kind)
            Surface(
                color = accent.copy(alpha = 0.12f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(tokens.chipRadius),
                modifier = pad.fillMaxWidth(),
            ) {
                Row(Modifier.height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().background(accent))
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).weight(1f)) {
                        val title = block.title.ifBlank { block.kind }
                        Text(title, style = bodyStyle(style), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                        block.children.forEach { MarkdownBlockView(it, style, onWikilink) }
                    }
                }
            }
        }
        is MarkdownBlock.Rule -> Box(
            pad.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun ListRow(
    depth: Int,
    marker: String,
    text: AnnotatedString,
    style: MarkdownStyle,
    modifier: Modifier,
) {
    Row(modifier.padding(start = (depth * 16).dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(marker, style = bodyStyle(style))
        Text(text, style = bodyStyle(style), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun headingStyle(level: Int, style: MarkdownStyle): TextStyle {
    val tokens = LocalStudyTokens.current
    val color = tokens.headingColor(level)
    val family = MaterialTheme.typography.bodyLarge.fontFamily
    if (style == MarkdownStyle.Compact) {
        val base = when (level) {
            1 -> MaterialTheme.typography.titleLarge
            2 -> MaterialTheme.typography.titleMedium
            else -> MaterialTheme.typography.titleSmall
        }
        return base.copy(color = color, fontWeight = FontWeight.Medium)
    }
    val (size, line) = when (level) {
        1 -> 24.sp to 32.sp
        2 -> 20.sp to 28.sp
        3 -> 17.sp to 26.sp
        else -> 15.sp to 24.sp
    }
    return TextStyle(color = color, fontSize = size, lineHeight = line, fontFamily = family, fontWeight = FontWeight.Medium)
}

@Composable
private fun bodyStyle(style: MarkdownStyle): TextStyle {
    val tokens = LocalStudyTokens.current
    val family = MaterialTheme.typography.bodyLarge.fontFamily
    return if (style == MarkdownStyle.Document) {
        TextStyle(
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = tokens.bodySize,
            lineHeight = tokens.bodyLine,
            fontFamily = family,
        )
    } else {
        MaterialTheme.typography.bodyMedium.copy(fontFamily = family)
    }
}

private fun blockPadding(block: MarkdownBlock, style: MarkdownStyle): Modifier {
    if (style == MarkdownStyle.Compact) return Modifier
    return when (block) {
        is MarkdownBlock.Heading -> when (block.level) {
            1 -> Modifier.padding(top = 28.dp, bottom = 12.dp)
            2 -> Modifier.padding(top = 24.dp, bottom = 10.dp)
            3 -> Modifier.padding(top = 20.dp, bottom = 8.dp)
            else -> Modifier.padding(top = 16.dp, bottom = 6.dp)
        }
        is MarkdownBlock.Rule -> Modifier.padding(vertical = 16.dp)
        else -> Modifier.padding(bottom = 10.dp)
    }
}

@Composable
private fun calloutAccent(kind: String): Color {
    val tokens = LocalStudyTokens.current
    return when (kind.lowercase()) {
        "tip", "success", "hint", "example" -> tokens.tipCallout
        "warning", "caution", "attention" -> tokens.warningCallout
        "danger", "error", "bug", "failure" -> tokens.dangerCallout
        else -> tokens.noteCallout
    }
}

internal fun inlineMarkdown(
    text: String,
    linkColor: Color = Color.Unspecified,
    onWikilink: (String) -> Unit = {},
): AnnotatedString = buildAnnotatedString {
    val linkStyle = TextLinkStyles(
        style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline),
    )
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
            text.startsWith("[[", index) -> {
                val end = text.indexOf("]]", index + 2)
                if (end < 0) {
                    append(text[index])
                    index += 1
                    continue
                }
                val inner = text.substring(index + 2, end)
                val target = inner.substringBefore('|').substringBefore('#').trim()
                val label = inner.substringAfter('|', inner).substringBefore('#').trim().ifBlank { target }
                if (target.isEmpty()) {
                    append(text.substring(index, end + 2))
                } else {
                    withLink(
                        LinkAnnotation.Clickable(
                            tag = target,
                            styles = TextLinkStyles(SpanStyle(color = linkColor)),
                            linkInteractionListener = { onWikilink(target) },
                        ),
                    ) { append(label) }
                }
                index = end + 2
            }
            text.startsWith("![", index) -> {
                val close = text.indexOf(']', index + 2)
                if (close < 0) {
                    append(text[index])
                    index += 1
                    continue
                }
                val alt = text.substring(index + 2, close).ifBlank { "圖片" }
                val after = if (close + 1 < text.length && text[close + 1] == '(') {
                    val paren = text.indexOf(')', close + 2)
                    if (paren < 0) close else paren
                } else {
                    close
                }
                append(alt)
                index = after + 1
            }
            text.startsWith("[", index) -> {
                val labelEnd = text.indexOf(']', index + 1)
                if (labelEnd < 0 || labelEnd + 1 >= text.length || text[labelEnd + 1] != '(') {
                    append(text[index])
                    index += 1
                    continue
                }
                val urlEnd = text.indexOf(')', labelEnd + 2)
                if (urlEnd < 0) {
                    append(text[index])
                    index += 1
                    continue
                }
                val label = text.substring(index + 1, labelEnd)
                val url = text.substring(labelEnd + 2, urlEnd).trim()
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    withLink(LinkAnnotation.Url(url, linkStyle)) { append(label) }
                } else {
                    append(label)
                }
                index = urlEnd + 1
            }
            text.startsWith("https://", index) || text.startsWith("http://", index) -> {
                var end = index
                while (end < text.length && !text[end].isWhitespace()) end += 1
                val raw = text.substring(index, end)
                val url = raw.trimEnd('.', ',', ';', ')')
                withLink(LinkAnnotation.Url(url, linkStyle)) { append(url) }
                append(raw.removePrefix(url))
                index = end
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

internal fun characterCount(text: String): Int = text.codePointCount(0, text.length)
