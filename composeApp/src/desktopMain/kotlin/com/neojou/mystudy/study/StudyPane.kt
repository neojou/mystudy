package com.neojou.mystudy.study

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.neojou.mystudy.wiki.settings.PreferencesSettingsStore
import com.neojou.mystudy.wiki.settings.SIDEBAR_DEFAULT_DP
import com.neojou.mystudy.wiki.settings.SIDEBAR_MAX_DP
import com.neojou.mystudy.wiki.settings.SIDEBAR_MIN_DP
import com.neojou.mystudy.wiki.settings.SidebarDragEnd
import com.neojou.mystudy.wiki.settings.clampSidebarWidth
import com.neojou.mystudy.wiki.settings.finishSidebarDrag

@Composable
actual fun StudyPane(
    mode: StudyMode,
    requestedMode: StudyMode?,
    fileBrowserOpen: Boolean,
    onFileBrowserOpen: (Boolean) -> Unit,
    onMode: (StudyMode) -> Unit,
    onRequestHandled: () -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val controller = remember { StudyController(PreferencesSettingsStore(), scope) }
    DisposableEffect(controller) {
        EditorChrome.bind(
            onClose = controller::requestClose,
            onFocus = controller::onWindowFocus,
        )
        onDispose {
            EditorChrome.unbind()
            controller.close()
        }
    }
    LaunchedEffect(controller.pendingMode) {
        controller.consumeMode()?.let(onMode)
    }
    LaunchedEffect(requestedMode) {
        val target = requestedMode ?: return@LaunchedEffect
        onRequestHandled()
        if (target == mode) return@LaunchedEffect
        if (controller.requestMode(target)) onMode(target)
    }
    LaunchedEffect(controller.approvedMode) {
        val next = controller.consumeApprovedMode() ?: return@LaunchedEffect
        onMode(next)
    }
    LaunchedEffect(controller.noteClipboard) {
        val text = controller.consumeNoteClipboard() ?: return@LaunchedEffect
        clipboard.setText(AnnotatedString(text))
    }
    Column(
        modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            if (event.isShiftPressed) return@onPreviewKeyEvent false
            if (!event.isCtrlPressed && !event.isMetaPressed) return@onPreviewKeyEvent false
            if (event.key != Key.Backslash) return@onPreviewKeyEvent false
            onFileBrowserOpen(!fileBrowserOpen)
            true
        },
    ) {
        controller.error?.let { message ->
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = controller::clearError) { Text("Dismiss") }
            }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val windowDp = maxWidth.value
            var liveWidth by remember { mutableStateOf<Float?>(null) }
            val visual = liveWidth ?: clampSidebarWidth(controller.ui.sidebarWidthDp.toFloat(), windowDp)
            Row(Modifier.fillMaxSize()) {
            if (fileBrowserOpen) {
                FileTreePane(
                    controller,
                    onCollapse = { onFileBrowserOpen(false) },
                    modifier = Modifier.width(visual.dp).fillMaxHeight(),
                )
                SidebarHandle(
                    onDragStart = { liveWidth = visual },
                    onDrag = { delta -> liveWidth = ((liveWidth ?: visual) + delta).coerceAtLeast(0f) },
                    onDragEnd = {
                        val width = liveWidth
                        liveWidth = null
                        if (width == null) return@SidebarHandle
                        when (val end = finishSidebarDrag(width, windowDp)) {
                            is SidebarDragEnd.Persist -> controller.persistSidebarWidth(end.dp)
                            SidebarDragEnd.Collapse -> onFileBrowserOpen(false)
                        }
                    },
                    onNudge = { delta ->
                        val next = clampSidebarWidth(visual + delta, windowDp)
                            .toInt()
                            .coerceIn(SIDEBAR_MIN_DP, SIDEBAR_MAX_DP)
                        controller.persistSidebarWidth(next)
                    },
                    onReset = { controller.persistSidebarWidth(SIDEBAR_DEFAULT_DP) },
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (mode) {
                    StudyMode.Home -> HomePane(controller, Modifier.fillMaxSize())
                    StudyMode.Browse -> BrowsePane(controller, Modifier.fillMaxSize())
                    StudyMode.Chat -> ChatPane(controller, Modifier.fillMaxSize())
                    StudyMode.Ask -> AskPane(controller, Modifier.fillMaxSize())
                    StudyMode.Lint -> LintPane(controller, onMode, Modifier.fillMaxSize())
                    StudyMode.DeepResearch -> DeepResearchPane(controller, onMode, Modifier.fillMaxSize())
                    StudyMode.Settings -> SettingsPane(controller, Modifier.fillMaxSize())
                }
            }
            }
        }
    }
    if (controller.showCreate) {
        CreateWikiDialog(onConfirm = controller::createWiki, onDismiss = controller::dismissCreate)
    }
    if (controller.rootChoices.isNotEmpty()) {
        RootChoiceDialog(
            choices = controller.rootChoices,
            onChoose = controller::chooseRoot,
            onDismiss = controller::dismissChoices,
        )
    }
    controller.proposal?.let { proposal ->
        ConfirmWriteDialog(
            proposal = proposal,
            onWrite = controller::confirmWrites,
            onDismiss = controller::dismissProposal,
        )
    }
    val notePhase = controller.notePhase
    if (controller.notePending != null && notePhase !is NotePhase.Conflict) {
        NoteUnsavedDialog(
            saving = notePhase is NotePhase.Saving,
            onSave = controller::confirmLeaveSave,
            onDiscard = controller::confirmLeaveDiscard,
            onCancel = controller::cancelLeave,
        )
    }
    if (notePhase is NotePhase.Conflict) {
        NoteConflictDialog(
            deleted = notePhase.deleted,
            onReload = controller::reloadFromConflict,
            onOverwrite = controller::overwriteNote,
            onRecreate = controller::recreateNote,
            onCancel = controller::cancelConflict,
        )
    }
}
