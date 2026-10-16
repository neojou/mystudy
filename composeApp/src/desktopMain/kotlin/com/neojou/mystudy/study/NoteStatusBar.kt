package com.neojou.mystudy.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neojou.mystudy.ui.theme.LocalStudyTokens
import com.neojou.mystudy.wiki.markdown.readFrontmatter
import com.neojou.mystudy.wiki.settings.EditorSubMode

internal fun noteChromeLabel(editing: Boolean, subMode: EditorSubMode): String = when {
    !editing -> "閱讀"
    subMode == EditorSubMode.LivePreview -> "即時預覽 · 分割檢視"
    else -> "Source"
}

/** Closed, parsed frontmatter only. A failed fence reports zero properties. */
internal fun propertyCount(text: String): Int {
    val view = readFrontmatter(text)
    if (view.raw == null || view.unparsed) return 0
    return view.entries.size
}

@Composable
internal fun NoteStatusBar(
    status: String,
    detail: String,
    trailing: String,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalStudyTokens.current
    var expanded by remember(detail) { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 24.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            status,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = tokens.metaSize,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Text(
            detail,
            color = MaterialTheme.colorScheme.tertiary,
            fontSize = tokens.metaSize,
            maxLines = if (expanded) Int.MAX_VALUE else 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 28.dp)
                .padding(horizontal = 8.dp)
                .clickable(enabled = detail.isNotBlank()) { expanded = !expanded },
        )
        Text(
            trailing,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = tokens.metaSize,
            maxLines = 1,
        )
    }
}
