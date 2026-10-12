package com.neojou.mystudy.study

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neojou.mystudy.wiki.research.ResearchPhase
import com.neojou.mystudy.wiki.research.SearxngStatus
import com.neojou.mystudy.wiki.research.formatSearxngStatus
import java.nio.file.Path
import kotlin.io.path.isDirectory

@Composable
fun DeepResearchPane(
    controller: StudyController,
    onMode: (StudyMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) {
        controller.onDeepResearchOpened()
    }
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ResearchStatusBar(controller, onMode)
        if (controller.wikiRoot == null) {
            Text(controller.status, style = MaterialTheme.typography.bodyMedium)
            Text(
                "No wiki root was found. Creating one adds llm-wiki/ inside the vault, with raw/sources/ and wiki/index.md, plus log.md, schema.md, and the sources, concepts, entities, and queries folders. Existing notes stay where they are.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (Path.of(controller.settings.vaultPath).isDirectory()) {
                TextButton(onClick = controller::offerCreate) { Text("Create wiki root") }
            }
            return@Column
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ReviewList(controller, Modifier.weight(1f).fillMaxHeight())
            ResearchRunPanel(controller, Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun ResearchStatusBar(controller: StudyController, onMode: (StudyMode) -> Unit) {
    val ok = controller.searxngStatus is SearxngStatus.Ok
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(10.dp)
                .background(if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error, CircleShape),
        )
        Text(controller.settings.searxngUrl, style = MaterialTheme.typography.bodySmall)
        Text(
            formatSearxngStatus(controller.searxngStatus),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = controller::testSearxng) { Text("重新測試") }
        TextButton(onClick = { onMode(StudyMode.Settings) }) { Text("前往設定") }
        TextButton(onClick = controller::reloadReviews) { Text("重新整理") }
    }
}

@Composable
private fun ReviewList(controller: StudyController, modifier: Modifier) {
    val reviews = controller.reviews
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (reviews.isEmpty()) {
            Text("目前沒有待研究項目（Ingest 時勾選 REVIEW 會加入）")
            return@Column
        }
        val reason = controller.researchBlockText()
        reviews.forEach { entry ->
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(entry.type, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
                    Text(entry.title, style = MaterialTheme.typography.titleSmall)
                    if (entry.description.isNotBlank()) {
                        Text(
                            entry.description.take(160),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (entry.searchQueries.isNotEmpty()) {
                        Text(entry.searchQueries.joinToString("  "), style = MaterialTheme.typography.labelSmall)
                    }
                    if (entry.source.isNotBlank()) {
                        Text(entry.source, style = MaterialTheme.typography.labelSmall)
                    }
                    Button(
                        onClick = { controller.startResearch(entry) },
                        enabled = controller.canResearchNow(),
                    ) { Text("深度研究") }
                    if (reason != null) {
                        Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun ResearchRunPanel(controller: StudyController, modifier: Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(phaseLabel(controller.researchPhase), style = MaterialTheme.typography.titleSmall)
        controller.researchHits.forEachIndexed { index, hit ->
            Text(
                "[${index + 1}] ${hit.title} — ${hit.url} — ${hit.snippet}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (controller.researchText.isNotBlank()) {
            Text(controller.researchText, style = MaterialTheme.typography.bodyMedium)
        }
        if (controller.researchMessage.isNotBlank() && controller.researchPhase != ResearchPhase.Done) {
            Text(controller.researchMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (controller.researchPhase == ResearchPhase.Searching || controller.researchPhase == ResearchPhase.Synthesizing) {
            TextButton(onClick = controller::cancelResearch) { Text("取消") }
        }
        if (controller.researchPhase == ResearchPhase.Failed) {
            TextButton(onClick = controller::retryResearch) { Text("重試") }
        }
        if (controller.researchPhase == ResearchPhase.Done && controller.researchPage != null) {
            TextButton(onClick = controller::openResearchedPage) {
                Text(controller.researchPage ?: "")
            }
        }
    }
}

private fun phaseLabel(phase: ResearchPhase): String = when (phase) {
    ResearchPhase.Idle -> "待命"
    ResearchPhase.Searching -> "搜尋中"
    ResearchPhase.Synthesizing -> "綜整中"
    ResearchPhase.Saving -> "寫入中"
    ResearchPhase.Done -> "完成"
    ResearchPhase.Failed -> "失敗"
}
