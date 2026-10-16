package com.neojou.mystudy.study

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * L0 live preview: the same [TextFieldState] on the left, a rendered copy on the right.
 * Scroll follows the editor as a fraction of its range. The right pane does not write.
 */
@Composable
internal fun LivePreviewPane(
    state: TextFieldState,
    markdown: String,
    limitWidth: Boolean,
    onWikilink: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val left = rememberScrollState()
    val right = rememberScrollState()
    LaunchedEffect(left, right) {
        snapshotFlow { left.value to left.maxValue }.collect { (value, max) ->
            val fraction = if (max <= 0) 0f else value.toFloat() / max.toFloat()
            val target = (fraction * right.maxValue).roundToInt().coerceIn(0, right.maxValue)
            if (right.value != target) right.scrollTo(target)
        }
    }
    val doc = rememberMarkdownDocument(markdown)
    Row(modifier) {
        NoteEditorField(
            state = state,
            scroll = left,
            limitWidth = limitWidth,
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        Box(Modifier.weight(1f).fillMaxHeight()) {
            SelectionContainer(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(right),
                ) {
                    MarkdownBlocks(
                        doc = doc,
                        style = MarkdownStyle.Document,
                        onWikilink = onWikilink,
                        limitWidth = limitWidth,
                        showProperties = true,
                    )
                }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(right),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
    }
}
