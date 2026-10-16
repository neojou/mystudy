package com.neojou.mystudy.study

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.neojou.mystudy.study.markdown.ColumnAlign
import com.neojou.mystudy.study.markdown.MarkdownBlock

@Composable
internal fun MarkdownTable(
    table: MarkdownBlock.Table,
    link: Color,
    onWikilink: (String) -> Unit,
) {
    val scroll = rememberScrollState()
    val outline = MaterialTheme.colorScheme.outlineVariant
    val headerColor = MaterialTheme.colorScheme.surfaceContainer
    val shape = RoundedCornerShape(6.dp)
    Box(Modifier.horizontalScroll(scroll).clip(shape).border(1.dp, outline, shape)) {
        SubcomposeLayout { constraints ->
            val columns = table.header.size
            val loose = Constraints(maxWidth = Constraints.Infinity)
            val intrinsic = Array(table.rows.size + 1) { IntArray(columns) }
            fun probe(key: String, text: String, header: Boolean, align: ColumnAlign) =
                subcompose(key) {
                    CellLabel(text, header, align, link, onWikilink)
                }.first().measure(loose)
            table.header.forEachIndexed { index, cell ->
                intrinsic[0][index] = probe("p-h-$index", cell, true, alignAt(table, index)).width
            }
            table.rows.forEachIndexed { row, cells ->
                cells.forEachIndexed { index, cell ->
                    if (index < columns) {
                        intrinsic[row + 1][index] = probe("p-$row-$index", cell, false, alignAt(table, index)).width
                    }
                }
            }
            val widths = IntArray(columns) { index ->
                (0..table.rows.size).maxOf { intrinsic[it][index] }
            }
            val headerPlaces = table.header.mapIndexed { index, cell ->
                subcompose("h-$index") {
                    CellBox(cell, true, alignAt(table, index), headerColor, outline, link, onWikilink)
                }.first().measure(Constraints.fixedWidth(widths[index]))
            }
            val rowPlaces = table.rows.mapIndexed { row, cells ->
                List(columns) { index ->
                    subcompose("c-$row-$index") {
                        CellBox(
                            cells.getOrElse(index) { "" },
                            false,
                            alignAt(table, index),
                            Color.Transparent,
                            outline,
                            link,
                            onWikilink,
                        )
                    }.first().measure(Constraints.fixedWidth(widths[index]))
                }
            }
            val headerHeight = headerPlaces.maxOfOrNull { it.height } ?: 0
            val rowHeights = rowPlaces.map { row -> row.maxOfOrNull { it.height } ?: 0 }
            val totalWidth = widths.sum().coerceAtLeast(0)
            val totalHeight = headerHeight + rowHeights.sum()
            layout(totalWidth, totalHeight) {
                var x = 0
                headerPlaces.forEachIndexed { index, placeable ->
                    placeable.place(x, 0)
                    x += widths[index]
                }
                var y = headerHeight
                rowPlaces.forEachIndexed { rowIndex, row ->
                    x = 0
                    row.forEachIndexed { index, placeable ->
                        placeable.place(x, y)
                        x += widths[index]
                    }
                    y += rowHeights[rowIndex]
                }
            }
        }
    }
}

private fun alignAt(table: MarkdownBlock.Table, index: Int): ColumnAlign =
    table.aligns.getOrElse(index) { ColumnAlign.Left }

@Composable
private fun CellBox(
    text: String,
    header: Boolean,
    align: ColumnAlign,
    fill: Color,
    line: Color,
    link: Color,
    onWikilink: (String) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .background(fill)
            .border(1.dp, line)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        CellLabel(text, header, align, link, onWikilink, Modifier.fillMaxWidth())
    }
}

@Composable
private fun CellLabel(
    text: String,
    header: Boolean,
    align: ColumnAlign,
    link: Color,
    onWikilink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        inlineMarkdown(text, link, onWikilink),
        modifier = modifier,
        fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
        textAlign = when (align) {
            ColumnAlign.Left -> TextAlign.Start
            ColumnAlign.Center -> TextAlign.Center
            ColumnAlign.Right -> TextAlign.End
        },
        style = MaterialTheme.typography.bodyMedium,
    )
}
