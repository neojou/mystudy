package com.neojou.mystudy.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.rememberWindowState
import com.neojou.mystudy.AppTheme
import com.neojou.mystudy.AppWindow

@Composable
fun ChatPane(controller: StudyController, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        CopyableScroll(Modifier.weight(1f).fillMaxWidth()) {
            controller.chatHistory.forEach { bubble ->
                ChatTurnView(bubble.question, bubble.steps, bubble.answer)
            }
            if (controller.liveQuestion.isNotBlank()) {
                ChatTurnView(controller.liveQuestion, controller.liveSteps, controller.liveAnswer)
            }
        }
        if (controller.chatMessage.isNotBlank()) {
            Text(controller.chatMessage)
        }
        OutlinedTextField(
            value = controller.chatInput,
            onValueChange = { controller.chatInput = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Question") },
            minLines = 2,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = controller::chat,
                enabled = !controller.busy && controller.chatInput.isNotBlank(),
            ) { Text("Send") }
            TextButton(onClick = controller::cancelChat, enabled = controller.busy) { Text("Stop") }
            TextButton(onClick = controller::newChat) { Text("New chat") }
            TextButton(
                onClick = controller::fileChatAnswer,
                enabled = !controller.busy && controller.chatHistory.lastOrNull()?.answer?.isNotBlank() == true,
            ) { Text("File this answer") }
            TextButton(
                onClick = controller::showChatGraph,
                enabled = controller.chatPages.isNotEmpty(),
            ) { Text("Knowledge graph") }
        }
        if (controller.chatShown.isNotEmpty()) {
            Text("References", style = MaterialTheme.typography.titleSmall)
            controller.chatShown.forEach { page ->
                Text(
                    text = "[${page.number}] ${page.title} — ${page.path}",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { controller.openChatPage(page.path) }
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
    if (controller.chatGraphOpen && controller.chatPages.isNotEmpty()) {
        AppWindow(
            onCloseRequest = controller::closeChatGraph,
            title = "Knowledge graph",
            state = rememberWindowState(size = DpSize(720.dp, 560.dp)),
            kind = "Chat",
        ) {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    AnswerGraph(
                        pages = controller.chatPages,
                        edges = controller.chatEdges,
                        onOpen = controller::openChatPage,
                        modifier = Modifier.fillMaxSize().padding(16.dp),
                    )
                }
            }
        }
    }
    val readerPath = controller.chatReaderPath
    if (readerPath != null) {
        AppWindow(
            onCloseRequest = controller::closeChatReader,
            title = controller.chatReaderTitle.ifBlank { readerPath },
            state = rememberWindowState(size = DpSize(720.dp, 640.dp)),
            kind = "Chat",
        ) {
            AppTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxSize().padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(readerPath, style = MaterialTheme.typography.titleSmall)
                        CopyableScroll(Modifier.weight(1f).fillMaxWidth()) {
                            Text(controller.chatReaderBody, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatTurnView(question: String, steps: List<String>, answer: String) {
    var open by remember(question, steps) { mutableStateOf(true) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(question, style = MaterialTheme.typography.titleSmall)
        if (steps.isNotEmpty()) {
            TextButton(onClick = { open = !open }) { Text(if (open) "Hide steps" else "Show steps") }
            if (open) {
                steps.forEach { step ->
                    Text(step, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (answer.isNotBlank()) MarkdownText(answer)
    }
}
