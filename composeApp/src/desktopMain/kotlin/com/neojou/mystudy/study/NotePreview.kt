package com.neojou.mystudy.study

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neojou.mystudy.wiki.settings.ingestIdleHint

@Composable
fun NotePreview(controller: StudyController, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val epoch = controller.editorEpoch
    val editor = remember(epoch) { TextFieldState(controller.noteDraft) }
    LaunchedEffect(epoch, controller.noteCanEdit) {
        if (!controller.noteCanEdit) return@LaunchedEffect
        snapshotFlow { editor.text.toString() }.collect { controller.setNoteDraft(epoch, it) }
    }
    Column(
        modifier
            .fillMaxSize()
            .padding(16.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (!event.isCtrlPressed && !event.isMetaPressed) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.E -> {
                        controller.toggleNoteMode()
                        true
                    }
                    Key.S -> {
                        if (controller.noteMode == NoteViewMode.Editing && controller.noteDirty) {
                            controller.saveNote()
                        }
                        true
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = controller::requestIngest,
                enabled = !controller.busy && controller.canIngestPreview(),
            ) { Text("Ingest") }
            Text(
                text = buildString {
                    append(controller.previewTitle)
                    if (controller.noteDirty) append(" ●")
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = controller::showReading,
                enabled = controller.noteMode != NoteViewMode.Reading,
            ) {
                Text(
                    "閱讀",
                    fontWeight = if (controller.noteMode == NoteViewMode.Reading) FontWeight.Bold else FontWeight.Normal,
                )
            }
            TextButton(
                onClick = controller::showEditing,
                enabled = controller.noteCanEdit && controller.noteMode != NoteViewMode.Editing,
            ) {
                Text(
                    "編輯",
                    fontWeight = if (controller.noteMode == NoteViewMode.Editing) FontWeight.Bold else FontWeight.Normal,
                )
            }
            TextButton(
                onClick = { clipboard.setText(AnnotatedString(controller.noteDraft)) },
                enabled = !controller.noteUnreadable && !controller.noteLoading,
            ) { Text("複製全文") }
            Button(
                onClick = controller::saveNote,
                enabled = controller.noteCanEdit && controller.noteDirty && controller.notePhase !is NotePhase.Saving,
            ) { Text("存檔") }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (controller.busy) {
                CircularProgressIndicator(Modifier.size(16.dp))
            }
            Text(
                text = controller.ingestMessage.ifBlank {
                    if (controller.canIngestPreview()) {
                        ingestIdleHint(controller.settings.ingestMode)
                    } else {
                        "This file cannot be ingested."
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Text(controller.noteStatus, style = MaterialTheme.typography.bodySmall)
        controller.noteWarning?.let { warning ->
            Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        if (controller.noteMixed) {
            Text(
                "換行混用 CRLF 與 LF。存檔時會依較多的一種統一，一樣多則用 LF。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
        }
        if (controller.noteNotice.isNotBlank()) {
            Text(controller.noteNotice, style = MaterialTheme.typography.bodySmall)
        }
        if (controller.noteExternal && controller.noteDirty) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("檔案已在外部變更", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = controller::reloadFromBanner) { Text("重新載入（捨棄我的編輯）") }
            }
        }
        when {
            controller.noteLoading -> Text("讀取中…", style = MaterialTheme.typography.bodyMedium)
            controller.noteUnreadable -> Text("無法預覽此檔案類型", style = MaterialTheme.typography.bodyMedium)
            controller.noteMode == NoteViewMode.Editing && controller.noteCanEdit -> NoteEditorField(editor, Modifier.weight(1f).fillMaxWidth())
            else -> {
                val (frontmatter, body) = splitReading(controller.noteDraft)
                CopyableScroll(Modifier.weight(1f).fillMaxWidth()) {
                    if (frontmatter != null) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                            Text(
                                frontmatter,
                                modifier = Modifier.padding(8.dp).fillMaxWidth(),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    MarkdownText(body)
                }
            }
        }
    }
}

@Composable
private fun NoteEditorField(state: TextFieldState, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            BasicTextField(
                state = state,
                modifier = Modifier.fillMaxWidth(),
                lineLimits = TextFieldLineLimits.MultiLine(),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
            )
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}
