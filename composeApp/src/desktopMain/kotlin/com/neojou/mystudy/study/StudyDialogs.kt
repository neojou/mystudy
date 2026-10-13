package com.neojou.mystudy.study

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.neojou.mystudy.wiki.markdown.unifiedDiff
import com.neojou.mystudy.wiki.model.DraftProposal
import com.neojou.mystudy.wiki.model.defaultChecked
import com.neojou.mystudy.wiki.model.defaultCheckedReviews

@Composable
fun CreateWikiDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create wiki root") },
        text = {
            Text(
                "No wiki root was found. Creating one adds llm-wiki/ inside the vault, with raw/sources/ and wiki/index.md, plus log.md, schema.md, and the sources, concepts, entities, and queries folders. Existing notes stay where they are.",
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun RootChoiceDialog(
    choices: List<String>,
    onChoose: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.widthIn(min = 360.dp, max = 640.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Several wiki roots found. Choose one.", style = MaterialTheme.typography.titleMedium)
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    choices.forEach { path ->
                        TextButton(onClick = { onChoose(path) }) { Text(path) }
                    }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    }
}

@Composable
fun ConfirmWriteDialog(
    proposal: DraftProposal,
    onWrite: (Set<String>, Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var checked by remember(proposal) { mutableStateOf(defaultChecked(proposal.pages)) }
    var checkedReviews by remember(proposal) { mutableStateOf(defaultCheckedReviews(proposal.reviews)) }
    val pagesOn = proposal.pages.any { it.selectable && it.relativePath in checked }
    val reviewsOn = proposal.reviews.any { it.id in checkedReviews }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier
                    .widthIn(min = 420.dp, max = 760.dp)
                    .heightIn(max = 640.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Confirm write", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Checked pages also update wiki/index.md and append wiki/log.md.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (proposal.reviews.isNotEmpty()) {
                    Text(
                        "Checked reviews append to wiki/review.md.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                proposal.schemaWarning?.let { warning ->
                    Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (proposal.schemaTruncated) {
                    Text(
                        "schema.md was longer than the prompt budget. The write still follows the local rules.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                proposal.warnings.forEach { warning ->
                    Text(warning, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (proposal.pages.isNotEmpty()) {
                        Text("Pages", style = MaterialTheme.typography.titleSmall)
                    }
                    proposal.pages.forEach { page ->
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(
                                checked = page.relativePath in checked,
                                onCheckedChange = { on ->
                                    checked = if (on) checked + page.relativePath else checked - page.relativePath
                                },
                                enabled = page.selectable,
                            )
                            Column(Modifier.padding(bottom = 12.dp)) {
                                Text(page.relativePath, style = MaterialTheme.typography.titleSmall)
                                if (page.note.isNotBlank()) {
                                    Text(page.note, style = MaterialTheme.typography.bodySmall)
                                } else if (!page.selectable) {
                                    Text("This title matches more than one page. It will not be written.")
                                } else if (page.conflict) {
                                    Text("Conflict. Checking this appends a section and does not replace the page.")
                                }
                                Text(
                                    unifiedDiff(page.previousText, page.newText),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    if (proposal.reviews.isNotEmpty()) {
                        Text("Reviews", style = MaterialTheme.typography.titleSmall)
                    }
                    proposal.reviews.forEach { review ->
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(
                                checked = review.id in checkedReviews,
                                onCheckedChange = { on ->
                                    checkedReviews = if (on) checkedReviews + review.id else checkedReviews - review.id
                                },
                            )
                            Column(Modifier.padding(bottom = 12.dp)) {
                                Text("${review.type} | ${review.title}", style = MaterialTheme.typography.titleSmall)
                                if (review.options.isNotEmpty()) {
                                    Text(
                                        "OPTIONS: ${review.options.joinToString(" | ")}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                                review.searches.forEach { search ->
                                    Text("SEARCH: $search", style = MaterialTheme.typography.bodySmall)
                                }
                                val preview = reviewBodyPreview(review.body)
                                if (preview.isNotBlank()) {
                                    Text(preview, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onWrite(checked, checkedReviews) },
                        enabled = pagesOn || reviewsOn,
                    ) { Text("Write") }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
fun NoteUnsavedDialog(
    saving: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = { if (!saving) onCancel() }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.widthIn(min = 360.dp, max = 520.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("尚未存檔", style = MaterialTheme.typography.titleMedium)
                Text("這則筆記有未儲存的變更。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSave, enabled = !saving) { Text("存檔") }
                    Button(onClick = onDiscard, enabled = !saving) { Text("捨棄") }
                    TextButton(onClick = onCancel, enabled = !saving) { Text("取消") }
                }
            }
        }
    }
}

@Composable
fun NoteConflictDialog(
    deleted: Boolean,
    onReload: () -> Unit,
    onOverwrite: () -> Unit,
    onRecreate: () -> Unit,
    onCancel: () -> Unit,
) {
    Dialog(onDismissRequest = onCancel) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(
                Modifier.widthIn(min = 360.dp, max = 560.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (deleted) {
                    Text("檔案已刪除", style = MaterialTheme.typography.titleMedium)
                    Text("磁碟上的檔案已經不在。可以在原路徑重新建立並寫入。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRecreate) { Text("重新建立並寫入") }
                        TextButton(onClick = onCancel) { Text("取消") }
                    }
                } else {
                    Text("磁碟上的檔案已變更", style = MaterialTheme.typography.titleMedium)
                    Text("開啟之後，檔案被另外寫過。沒有自動合併。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onReload) { Text("重新載入磁碟版本") }
                        Button(onClick = onOverwrite) { Text("用我的版本覆寫") }
                        TextButton(onClick = onCancel) { Text("取消") }
                    }
                }
            }
        }
    }
}

private fun reviewBodyPreview(body: String): String = body.lineSequence()
    .filter { line ->
        val trimmed = line.trim()
        !trimmed.startsWith("OPTIONS:") && !trimmed.startsWith("SEARCH:")
    }
    .take(12)
    .joinToString("\n")
    .trim()
