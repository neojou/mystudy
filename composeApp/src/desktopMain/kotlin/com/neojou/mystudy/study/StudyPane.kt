package com.neojou.mystudy.study

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.neojou.mystudy.wiki.settings.PreferencesSettingsStore

@Composable
actual fun StudyPane(
    mode: StudyMode,
    fileBrowserOpen: Boolean,
    onMode: (StudyMode) -> Unit,
    modifier: Modifier,
) {
    val scope = rememberCoroutineScope()
    val controller = remember { StudyController(PreferencesSettingsStore(), scope) }
    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    LaunchedEffect(controller.pendingMode) {
        controller.consumeMode()?.let(onMode)
    }
    Column(modifier) {
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
        Row(Modifier.weight(1f).fillMaxWidth()) {
            if (fileBrowserOpen) {
                FileTreePane(controller, Modifier.width(260.dp).fillMaxHeight())
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (mode) {
                    StudyMode.Home -> HomePane(controller, Modifier.fillMaxSize())
                    StudyMode.Browse -> BrowsePane(controller, Modifier.fillMaxSize())
                    StudyMode.Ingest -> IngestPane(controller, Modifier.fillMaxSize())
                    StudyMode.Ask -> AskPane(controller, Modifier.fillMaxSize())
                    StudyMode.Settings -> SettingsPane(controller, Modifier.fillMaxSize())
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
    if (controller.stagePaths.isNotEmpty()) {
        StageDialog(
            count = controller.stagePaths.size,
            onConfirm = controller::confirmStage,
            onDismiss = controller::dismissStage,
        )
    }
    controller.proposal?.let { proposal ->
        ConfirmWriteDialog(
            proposal = proposal,
            onWrite = controller::confirmWrites,
            onDismiss = controller::dismissProposal,
        )
    }
}
