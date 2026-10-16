package com.neojou.mystudy.study

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neojou.mystudy.ui.theme.LocalStudyTokens
import com.neojou.mystudy.wiki.settings.EditorSubMode
import com.neojou.mystudy.wiki.settings.ingestIdleHint
import java.nio.file.Path

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotePreview(controller: StudyController, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    val tokens = LocalStudyTokens.current
    val epoch = controller.editorEpoch
    val editor = remember(epoch) { TextFieldState(controller.noteDraft) }
    LaunchedEffect(epoch, controller.noteCanEdit) {
        if (!controller.noteCanEdit) return@LaunchedEffect
        snapshotFlow { editor.text.toString() }.collect { controller.setNoteDraft(epoch, it) }
    }
    val editing = controller.noteMode == NoteViewMode.Editing && controller.noteCanEdit
    val rendered = if (editing) editor.text.toString() else controller.noteDraft
    val vault = Path.of(controller.settings.vaultPath)
    val file = controller.previewPath
    val crumbs = if (file == null) emptyList() else breadcrumbCrumbs(vault, file)
    val subMode = controller.ui.editorSubMode
    val limitWidth = controller.ui.limitLineWidth
    val ingestHint = if (controller.canIngestPreview()) {
        ingestIdleHint(controller.settings.ingestMode)
    } else {
        "This file cannot be ingested."
    }
    Column(
        modifier
            .fillMaxSize()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                if (!event.isCtrlPressed && !event.isMetaPressed) return@onPreviewKeyEvent false
                when {
                    event.key == Key.E && event.isShiftPressed -> {
                        if (editing) controller.toggleEditorSubMode()
                        true
                    }
                    event.key == Key.E -> {
                        if (controller.noteCanEdit) controller.toggleNoteMode()
                        true
                    }
                    event.key == Key.S -> {
                        if (editing && controller.noteDirty) controller.saveNote()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BreadcrumbBar(crumbs, onOpen = controller::revealInTree)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val title = buildString {
                    append(controller.previewTitle)
                    if (controller.noteDirty) append(" ●")
                }
                Text(
                    text = title,
                    fontSize = tokens.documentTitleSize,
                    lineHeight = tokens.documentTitleLine,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).semantics {
                        if (controller.noteDirty) contentDescription = "${controller.previewTitle} 未儲存變更"
                    },
                )
                ModeSegments(
                    editing = editing,
                    canEdit = controller.noteCanEdit,
                    onReading = controller::showReading,
                    onEditing = controller::showEditing,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (editing) {
                    SubModeSegments(
                        subMode = subMode,
                        onLive = { controller.setEditorSubMode(EditorSubMode.LivePreview) },
                        onSource = { controller.setEditorSubMode(EditorSubMode.Source) },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (controller.busy) {
                    CircularProgressIndicator(Modifier.size(16.dp))
                }
                if (editing) {
                    Hint(
                        "存檔（Ctrl/Cmd+S）",
                    ) {
                        FilledTonalButton(
                            onClick = controller::saveNote,
                            enabled = controller.noteDirty && controller.notePhase !is NotePhase.Saving,
                            modifier = Modifier.heightIn(min = 28.dp),
                        ) { Text("存檔") }
                    }
                }
                Hint("複製全文") {
                    IconButton(
                        onClick = { clipboard.setText(AnnotatedString(controller.noteDraft)) },
                        enabled = !controller.noteUnreadable && !controller.noteLoading,
                        modifier = Modifier.semantics { contentDescription = "複製全文" },
                    ) { CopyIcon() }
                }
                Hint(ingestHint) {
                    FilledTonalButton(
                        onClick = controller::requestIngest,
                        enabled = !controller.busy && controller.canIngestPreview(),
                        modifier = Modifier.heightIn(min = 28.dp),
                    ) { Text("Ingest") }
                }
            }
        }
        if (controller.noteNotice.isNotBlank()) {
            Text(
                controller.noteNotice,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (controller.noteExternal && controller.noteDirty) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("檔案已在外部變更", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = controller::reloadFromBanner) { Text("重新載入（捨棄我的編輯）") }
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                controller.noteLoading -> Text(
                    "讀取中…",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                controller.noteUnreadable -> Text(
                    "無法預覽此檔案類型",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
                editing && subMode == EditorSubMode.LivePreview -> LivePreviewPane(
                    state = editor,
                    markdown = rendered,
                    limitWidth = limitWidth,
                    onWikilink = controller::openWikilink,
                    modifier = Modifier.fillMaxSize(),
                )
                editing -> NoteEditorField(
                    state = editor,
                    limitWidth = limitWidth,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> MarkdownText(
                    markdown = rendered,
                    modifier = Modifier.fillMaxSize(),
                    style = MarkdownStyle.Document,
                    onWikilink = controller::openWikilink,
                    limitWidth = limitWidth,
                    lazy = true,
                )
            }
        }
        val detail = statusDetail(
            warning = controller.noteWarning,
            mixed = controller.noteMixed,
            ingest = controller.ingestMessage,
            idle = if (controller.canIngestPreview()) ingestIdleHint(controller.settings.ingestMode) else "",
        )
        val trailing = "${propertyCount(rendered)} 屬性 · ${characterCount(rendered)} 字 · ${noteChromeLabel(editing, subMode)}"
        NoteStatusBar(status = controller.noteStatus, detail = detail, trailing = trailing)
    }
}

private fun statusDetail(warning: String?, mixed: Boolean, ingest: String, idle: String): String {
    val parts = mutableListOf<String>()
    if (!warning.isNullOrBlank()) parts += warning
    if (mixed) parts += "換行混用 CRLF 與 LF。存檔時會依較多的一種統一，一樣多則用 LF。"
    val ingestLine = ingest.ifBlank { idle }
    if (ingestLine.isNotBlank()) parts += ingestLine
    return parts.joinToString("  ")
}

@Composable
internal fun NoteEditorField(
    state: TextFieldState,
    modifier: Modifier = Modifier,
    scroll: ScrollState = androidx.compose.foundation.rememberScrollState(),
    limitWidth: Boolean = false,
) {
    val tokens = LocalStudyTokens.current
    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                BasicTextField(
                    state = state,
                    modifier = Modifier
                        .then(
                            if (limitWidth) {
                                Modifier.widthIn(max = tokens.readingMaxWidth).padding(horizontal = tokens.contentPadding)
                            } else {
                                Modifier.padding(horizontal = 16.dp)
                            },
                        )
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    lineLimits = TextFieldLineLimits.MultiLine(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        lineHeight = 22.4.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeSegments(
    editing: Boolean,
    canEdit: Boolean,
    onReading: () -> Unit,
    onEditing: () -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.heightIn(min = 28.dp)) {
        Hint("閱讀（Ctrl/Cmd+E）") {
            SegmentedButton(
                selected = !editing,
                onClick = onReading,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = {},
            ) { Text("閱讀") }
        }
        Hint("編輯（Ctrl/Cmd+E）") {
            SegmentedButton(
                selected = editing,
                onClick = onEditing,
                enabled = canEdit,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = {},
            ) { Text("編輯") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubModeSegments(
    subMode: EditorSubMode,
    onLive: () -> Unit,
    onSource: () -> Unit,
) {
    SingleChoiceSegmentedButtonRow(Modifier.heightIn(min = 28.dp)) {
        Hint("即時預覽（Ctrl/Cmd+Shift+E）") {
            SegmentedButton(
                selected = subMode == EditorSubMode.LivePreview,
                onClick = onLive,
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                icon = {},
            ) { Text("即時預覽") }
        }
        Hint("Source（Ctrl/Cmd+Shift+E）") {
            SegmentedButton(
                selected = subMode == EditorSubMode.Source,
                onClick = onSource,
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                icon = {},
            ) { Text("Source") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Hint(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
    ) {
        content()
    }
}
