package com.neojou.mystudy.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neojou.mystudy.ui.theme.LocalStudyTokens
import com.neojou.mystudy.wiki.markdown.FrontmatterEntry
import com.neojou.mystudy.wiki.markdown.FrontmatterView

private val dateValue = Regex("""^\d{4}-\d{2}-\d{2}(?:[T ]\d{2}:\d{2}(?::\d{2})?)?$""")

@Composable
internal fun PropertiesPanel(
    view: FrontmatterView?,
    onWikilink: (String) -> Unit = {},
) {
    if (view == null || view.raw == null) return
    val tokens = LocalStudyTokens.current
    var open by remember(view.raw) { mutableStateOf(true) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(tokens.cardRadius),
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            TextButton(onClick = { open = !open }) {
                val count = if (view.unparsed) 0 else view.entries.size
                Text(if (open) "屬性 ($count)" else "屬性 ($count)  ▸")
            }
            if (!open) return@Column
            if (view.unparsed) {
                Text("YAML 無法解析", color = MaterialTheme.colorScheme.tertiary, fontSize = tokens.metaSize)
                Text(
                    view.raw,
                    modifier = Modifier.padding(bottom = 8.dp).fillMaxWidth(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            view.entries.forEach { entry -> PropertyRow(entry, onWikilink) }
        }
    }
}

@Composable
private fun PropertyRow(entry: FrontmatterEntry, onWikilink: (String) -> Unit) {
    val tokens = LocalStudyTokens.current
    Row(
        Modifier.fillMaxWidth().padding(bottom = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            entry.key,
            modifier = Modifier.width(120.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = tokens.metaSize,
            lineHeight = 16.sp,
        )
        when (entry) {
            is FrontmatterEntry.ListValue -> ChipRow(entry.key, entry.values)
            is FrontmatterEntry.Scalar -> ScalarValue(entry.value, onWikilink)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(key: String, values: List<String>) {
    val tokens = LocalStudyTokens.current
    val hashed = key.equals("tags", ignoreCase = true)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        values.forEach { value ->
            val chipColor = if (hashed) tokens.link.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceContainerLow
            val textColor = if (hashed) tokens.link else MaterialTheme.colorScheme.onSurface
            Surface(color = chipColor, shape = RoundedCornerShape(50)) {
                Text(
                    text = if (hashed) "#$value" else value,
                    color = textColor,
                    fontSize = tokens.metaSize,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun ScalarValue(value: String, onWikilink: (String) -> Unit) {
    val tokens = LocalStudyTokens.current
    when {
        value.equals("true", ignoreCase = true) || value.equals("false", ignoreCase = true) -> {
            Checkbox(checked = value.equals("true", ignoreCase = true), onCheckedChange = null)
        }
        dateValue.matches(value) -> {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CalendarIcon(Modifier.width(14.dp))
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        }
        else -> Text(inlineMarkdown(value, tokens.link, onWikilink), style = MaterialTheme.typography.bodyMedium)
    }
}
