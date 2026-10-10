package com.neojou.mystudy.study

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.neojou.mystudy.AppTheme
import com.neojou.mystudy.AppVersion
import com.neojou.mystudy.wiki.index.NoteSummary
import java.nio.file.Path
import kotlin.io.path.isDirectory

@Composable
fun HomePane(controller: StudyController, modifier: Modifier = Modifier) {
    val preview = controller.previewPath
    if (preview == null) {
        Column(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = AppVersion.APP_NAME,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
            )
            Text(controller.status, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            Text("${controller.noteCount} notes", style = MaterialTheme.typography.bodySmall)
            if (controller.wikiRoot == null && Path.of(controller.settings.vaultPath).isDirectory()) {
                TextButton(onClick = controller::offerCreate) { Text("Create wiki root") }
            }
        }
        return
    }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = controller::ingestPreview,
                enabled = !controller.busy && controller.canIngestPreview(),
            ) { Text("Ingest") }
            Text(
                text = controller.previewTitle,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
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
                        "Ingest links this note into raw/sources/."
                    } else {
                        "This file cannot be ingested."
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            controller.previewBody,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
fun BrowsePane(controller: StudyController, modifier: Modifier = Modifier) {
    var query by remember { mutableStateOf("") }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                label = { Text("Search") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { controller.search(query) }),
            )
            Button(onClick = { controller.search(query) }, enabled = !controller.busy) { Text("Search") }
        }
        Text("${controller.noteCount} notes in the index", style = MaterialTheme.typography.bodySmall)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            grouped(controller.searchHits).forEach { (heading, notes) ->
                Text(heading, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                notes.forEach { note ->
                    Text(
                        text = "${note.title}  ·  ${note.path}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { controller.openIndexed(note.path) }
                            .padding(vertical = 4.dp),
                    )
                }
            }
        }
        if (controller.openNote.isNotBlank()) {
            Text(controller.previewTitle, style = MaterialTheme.typography.titleSmall)
            Text(
                controller.openNote,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
fun IngestPane(controller: StudyController, modifier: Modifier = Modifier) {
    val ready = controller.wikiRoot != null
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = controller::extractClaims,
                enabled = ready && !controller.busy && controller.selectedRaw != null,
            ) { Text("Extract") }
            Button(
                onClick = controller::draftClaims,
                enabled = ready && !controller.busy && controller.claims.any { it.checked },
            ) { Text("Draft") }
            if (controller.queue.size > 1) {
                TextButton(onClick = controller::skipQueued, enabled = !controller.busy) { Text("Skip") }
            }
        }
        if (!ready) {
            Text("Create a wiki root before ingest.")
        }
        if (controller.queue.isNotEmpty()) {
            Text("Queue: ${controller.queue.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
        }
        if (controller.ingestMessage.isNotBlank()) {
            Text(controller.ingestMessage, style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(0.35f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Text("raw/sources/", style = MaterialTheme.typography.titleSmall)
                controller.rawFiles.forEach { relative ->
                    val selected = relative == controller.selectedRaw
                    Text(
                        text = relative,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !controller.busy) { controller.selectRaw(relative) }
                            .padding(vertical = 4.dp),
                    )
                }
            }
            Column(Modifier.weight(0.65f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                Text(controller.selectedRaw ?: "No raw note selected", style = MaterialTheme.typography.titleSmall)
                Text(controller.rawPreview, style = MaterialTheme.typography.bodyMedium)
                controller.claims.forEachIndexed { index, choice ->
                    Row(verticalAlignment = Alignment.Top) {
                        Checkbox(
                            checked = choice.checked,
                            onCheckedChange = { controller.setClaimChecked(index, it) },
                            enabled = !controller.busy,
                        )
                        Column {
                            Text(choice.claim.suggestedTitle, fontWeight = FontWeight.Medium)
                            Text(choice.claim.statement, style = MaterialTheme.typography.bodySmall)
                            Text(choice.claim.excerpt, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AskPane(controller: StudyController, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = controller.question,
            onValueChange = { controller.question = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Question") },
            minLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = controller::ask, enabled = !controller.busy && controller.question.isNotBlank()) {
                Text("Ask")
            }
            TextButton(
                onClick = controller::fileAnswer,
                enabled = !controller.busy && controller.answer.isNotBlank(),
            ) { Text("File this answer") }
            TextButton(
                onClick = controller::showAskGraph,
                enabled = controller.askPages.isNotEmpty(),
            ) { Text("Knowledge graph") }
        }
        if (controller.askMessage.isNotBlank()) {
            Text(controller.askMessage)
        }
        if (controller.askReported) {
            Text("tokenHits=${controller.tokenHits}  graphHits=${controller.graphHits}")
        }
        CopyableScroll(Modifier.weight(1f).fillMaxWidth()) {
            if (controller.answer.isNotBlank()) {
                MarkdownText(controller.answer)
            }
        }
        if (controller.askPages.isNotEmpty()) {
            Text("Citations", style = MaterialTheme.typography.titleSmall)
            controller.askPages.forEach { page ->
                Text(
                    text = "[${page.number}] ${page.title} — ${page.path}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { controller.openAskPage(page.path) }
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
    if (controller.askGraphOpen && controller.askPages.isNotEmpty()) {
        Window(
            onCloseRequest = controller::closeAskGraph,
            title = "Knowledge graph",
            state = rememberWindowState(size = DpSize(720.dp, 560.dp)),
        ) {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    AnswerGraph(
                        pages = controller.askPages,
                        edges = controller.askEdges,
                        onOpen = controller::openAskPage,
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                    )
                }
            }
        }
    }
    val readerPath = controller.askReaderPath
    if (readerPath != null) {
        Window(
            onCloseRequest = controller::closeAskReader,
            title = controller.askReaderTitle.ifBlank { readerPath },
            state = rememberWindowState(size = DpSize(720.dp, 640.dp)),
        ) {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(readerPath, style = MaterialTheme.typography.titleSmall)
                        CopyableScroll(Modifier.weight(1f).fillMaxWidth()) {
                            Text(controller.askReaderBody, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsPane(controller: StudyController, modifier: Modifier = Modifier) {
    val current = controller.settings
    var vault by remember { mutableStateOf(current.vaultPath) }
    var url by remember { mutableStateOf(current.ollamaBaseUrl) }
    var model by remember { mutableStateOf(current.ollamaModel) }
    var ctx by remember { mutableStateOf(current.ollamaNumCtx.toString()) }
    var localError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(current) {
        vault = current.vaultPath
        url = current.ollamaBaseUrl
        model = current.ollamaModel
        ctx = current.ollamaNumCtx.toString()
    }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(vault, { vault = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Vault") })
        OutlinedTextField(url, { url = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Ollama URL") })
        OutlinedTextField(model, { model = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Model") })
        OutlinedTextField(ctx, { ctx = it }, modifier = Modifier.fillMaxWidth(), label = { Text("num_ctx") })
        Text(
            "num_ctx 65536 reserves a large context on a 24GB Mac. A failed load is shown. The app does not lower it.",
            style = MaterialTheme.typography.bodySmall,
        )
        localError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val parsed = ctx.toIntOrNull()
                if (parsed == null || parsed <= 0) {
                    localError = "num_ctx must be a positive number."
                    return@Button
                }
                localError = null
                controller.saveSettings(
                    current.copy(
                        vaultPath = vault.trim(),
                        ollamaBaseUrl = url.trim(),
                        ollamaModel = model.trim(),
                        ollamaNumCtx = parsed,
                    ),
                )
            }) { Text("Save") }
            Button(onClick = {
                val picked = pickVaultFolder() ?: return@Button
                vault = picked
            }) { Text("Choose vault folder") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = controller::redetect, enabled = !controller.busy) { Text("Redetect wiki root") }
            TextButton(onClick = controller::rebuildIndex, enabled = !controller.busy && controller.wikiRoot != null) {
                Text("Rebuild index")
            }
            TextButton(onClick = controller::checkOllama, enabled = !controller.busy) { Text("Check Ollama") }
        }
        Text(controller.status, style = MaterialTheme.typography.bodySmall)
        Text("Wiki root: ${controller.wikiRoot ?: "none"}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CopyableScroll(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scroll = rememberScrollState()
    Box(modifier) {
        SelectionContainer(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll)
                    .padding(end = 16.dp),
            ) {
                content()
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

private fun grouped(notes: List<NoteSummary>): List<Pair<String, List<NoteSummary>>> {
    val order = listOf("Sources", "Concepts", "Entities", "Queries", "Raw", "Other")
    return notes.groupBy { note ->
        when {
            note.path.startsWith("wiki/sources/") -> "Sources"
            note.path.startsWith("wiki/concepts/") -> "Concepts"
            note.path.startsWith("wiki/entities/") -> "Entities"
            note.path.startsWith("wiki/queries/") -> "Queries"
            note.path.startsWith("raw/") -> "Raw"
            else -> "Other"
        }
    }.entries.sortedBy { order.indexOf(it.key) }.map { it.key to it.value }
}
