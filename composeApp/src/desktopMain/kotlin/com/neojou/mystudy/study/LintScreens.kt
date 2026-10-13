package com.neojou.mystudy.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.neojou.mystudy.wiki.lint.LintFinding
import com.neojou.mystudy.wiki.lint.LintRule
import com.neojou.mystudy.wiki.lint.lintRuleLabel
import com.neojou.mystudy.wiki.lint.lintSeverityLabel
import java.nio.file.Path
import kotlin.io.path.isDirectory

@Composable
fun LintPane(
    controller: StudyController,
    onMode: (StudyMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        LintControls(controller)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (controller.lintStructuralText.isNotBlank()) {
                Text(controller.lintStructuralText, style = MaterialTheme.typography.bodyMedium)
            }
            if (controller.lintSemanticText.isNotBlank()) {
                Text(controller.lintSemanticText, style = MaterialTheme.typography.bodyMedium)
            }
            controller.lintWarnings.forEach { warning ->
                Text(warning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            val findings = controller.lintFindings
            if (!controller.lintRunning && findings.isEmpty() && controller.lintStructuralText.contains("✓")) {
                Text("沒有發現問題。")
            }
            LintRule.entries.forEach { rule ->
                val rows = findings.filter { it.rule == rule }
                if (rows.isEmpty()) return@forEach
                Text(lintRuleLabel(rule), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                rows.forEach { finding ->
                    LintRow(controller, finding)
                }
            }
            if (controller.lintMessage.isNotBlank()) {
                Text(controller.lintMessage, style = MaterialTheme.typography.bodyMedium)
                if (controller.lintMessage.startsWith("已加入")) {
                    TextButton(onClick = { onMode(StudyMode.DeepResearch) }) { Text("到深度研究") }
                }
            }
        }
    }
}

@Composable
private fun LintControls(controller: StudyController) {
    var ignoreOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = controller.settings.lintSemantic,
            onCheckedChange = controller::setLintSemantic,
            enabled = !controller.lintRunning,
        )
        Text("語意健檢（Ollama，較慢）", modifier = Modifier.weight(1f))
        if (controller.lintRunning) {
            Button(onClick = controller::cancelLint) { Text("取消") }
        } else {
            Button(onClick = controller::startLint) { Text("開始健檢") }
        }
    }
    TextButton(onClick = { ignoreOpen = !ignoreOpen }, enabled = !controller.lintRunning) {
        Text(if (ignoreOpen) "忽略頁 ▾" else "忽略頁 ▸")
    }
    if (ignoreOpen) {
        OutlinedTextField(
            value = controller.lintIgnoreText,
            onValueChange = controller::updateLintIgnore,
            modifier = Modifier.fillMaxWidth(),
            enabled = !controller.lintRunning,
            label = { Text("完整路徑或 slug，逗號或換行。不比對裸檔名。") },
            minLines = 2,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = controller::selectAllLint, enabled = !controller.lintRunning && controller.lintFindings.isNotEmpty()) {
            Text("全選")
        }
        TextButton(onClick = controller::selectNoneLint, enabled = !controller.lintRunning && controller.lintChecked.isNotEmpty()) {
            Text("全不選")
        }
        Button(
            onClick = controller::writeLintReviews,
            enabled = !controller.lintRunning && controller.lintChecked.isNotEmpty(),
        ) {
            Text("寫入 review.md（${controller.lintChecked.size} 筆）")
        }
    }
}

@Composable
private fun LintRow(controller: StudyController, finding: LintFinding) {
    val writable = finding.reviewType != null && !finding.alreadyInReview
    Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Checkbox(
                    checked = finding.id in controller.lintChecked,
                    onCheckedChange = { controller.toggleLint(finding.id) },
                    enabled = writable && !controller.lintRunning,
                )
                Text(lintSeverityLabel(finding.severity), style = MaterialTheme.typography.labelMedium)
                Text(finding.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (finding.alreadyInReview) {
                    Text("已在 review.md", style = MaterialTheme.typography.labelSmall)
                } else if (finding.reviewType == null) {
                    Text("僅顯示", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (finding.detail.isNotBlank()) {
                Text(finding.detail, style = MaterialTheme.typography.bodySmall)
            }
            finding.pages.take(10).forEach { path ->
                val title = controller.lintPages.firstOrNull { it.path == path }?.title?.ifBlank { path } ?: path
                Text(
                    "[[$title]]",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { controller.openLintPage(path) },
                )
            }
            if (finding.pages.size > 10) {
                Text("等 ${finding.pages.size} 頁", style = MaterialTheme.typography.labelSmall)
            }
            if (finding.searches.isNotEmpty()) {
                Text(finding.searches.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
