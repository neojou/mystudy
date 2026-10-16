package com.neojou.mystudy.study

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neojou.mystudy.ui.theme.LocalStudyTokens
import com.neojou.mystudy.wiki.vault.ingestTargets
import com.neojou.mystudy.wiki.vault.listVaultChildren
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * File browser for the vault.
 *
 * The section title stays "Vault". Each row, including the vault root, shows only
 * the last path segment. The absolute path is a hover tooltip. The tree has no context menu.
 */
@Composable
fun FileTreePane(
    controller: StudyController,
    onCollapse: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vault = Path.of(controller.settings.vaultPath)
    var expanded by remember(vault.toString()) { mutableStateOf(setOf(vault.toString())) }
    var reveal by remember { mutableStateOf<Path?>(null) }
    LaunchedEffect(controller.treeReveal) {
        val target = controller.consumeTreeReveal() ?: return@LaunchedEffect
        reveal = target
        expanded = expanded + ancestorKeys(vault, target)
    }
    Column(
        modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Vault", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            IconButton(
                onClick = onCollapse,
                modifier = Modifier.semantics { contentDescription = "收合檔案樹" },
            ) { CollapseTreeIcon() }
        }
        if (!vault.isDirectory()) {
            Text(
                "Vault folder is missing.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
            )
            return@Column
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 8.dp),
        ) {
            VaultNode(
                path = vault,
                vault = vault,
                wikiRoot = controller.wikiRoot?.let(Path::of),
                depth = 0,
                expanded = expanded,
                tick = controller.treeTick,
                selected = controller.previewPath,
                reveal = reveal,
                onToggle = { target ->
                    val key = target.toString()
                    expanded = if (key in expanded || absoluteKey(target) in expanded) {
                        expanded - key - absoluteKey(target)
                    } else {
                        expanded + key
                    }
                },
                onOpen = controller::requestOpen,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultNode(
    path: Path,
    vault: Path,
    wikiRoot: Path?,
    depth: Int,
    expanded: Set<String>,
    tick: Int,
    selected: Path?,
    reveal: Path?,
    onToggle: (Path) -> Unit,
    onOpen: (Path) -> Unit,
) {
    val tokens = LocalStudyTokens.current
    val directory = path.isDirectory()
    val open = path.toString() in expanded || absoluteKey(path) in expanded
    val wikiHere = wikiRoot != null && samePath(path, wikiRoot.resolve("wiki"))
    val inRaw = wikiRoot != null && underRaw(wikiRoot, path)
    val chosen = selected != null && samePath(path, selected)
    val revealed = reveal != null && samePath(path, reveal)
    val label = buildString {
        append(vaultTreeLabel(path))
        if (wikiHere) append("  · wiki")
    }
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(revealed, reveal?.toString()) {
        if (revealed) requester.bringIntoView()
    }
    val textColor = when {
        inRaw && !chosen -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text(path.toAbsolutePath().normalize().toString()) } },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(tokens.treeRowHeight)
                .bringIntoViewRequester(requester)
                .background(if (chosen) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                .clickable { if (directory) onToggle(path) else onOpen(path) },
        ) {
            val guide = MaterialTheme.colorScheme.outlineVariant
            for (level in 0 until depth) {
                Box(
                    Modifier
                        .padding(start = (level * 16 + 8).dp)
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(guide),
                )
            }
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(start = (depth * 16).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .width(2.dp)
                        .fillMaxHeight()
                        .background(if (chosen) MaterialTheme.colorScheme.primary else Color.Transparent),
                )
                Text(
                    text = if (directory) if (open) "▾" else "▸" else "",
                    color = textColor,
                    fontSize = tokens.treeSize,
                    modifier = Modifier.width(16.dp),
                )
                FileKindIcon(fileKind(path.name, directory), textColor)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label,
                    color = textColor,
                    fontSize = tokens.treeSize,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    val children = remember(path.toString(), tick, directory) {
        if (directory) listVaultChildren(path, vault) else emptyList()
    }
    if (directory && open) {
        children.forEach { child ->
            VaultNode(
                path = child.path,
                vault = vault,
                wikiRoot = wikiRoot,
                depth = depth + 1,
                expanded = expanded,
                tick = tick,
                selected = selected,
                reveal = reveal,
                onToggle = onToggle,
                onOpen = onOpen,
            )
        }
    }
}

private fun ancestorKeys(vault: Path, target: Path): Set<String> {
    val keys = mutableSetOf(vault.toString(), absoluteKey(vault))
    var cursor = target.parent
    val stop = vault.toAbsolutePath().normalize()
    var guard = 0
    while (cursor != null && guard++ < 64) {
        keys += cursor.toString()
        keys += absoluteKey(cursor)
        val abs = cursor.toAbsolutePath().normalize()
        if (abs == stop || !abs.startsWith(stop)) break
        cursor = cursor.parent
    }
    return keys
}

private fun absoluteKey(path: Path): String = path.toAbsolutePath().normalize().toString()

private fun underRaw(wikiRoot: Path, path: Path): Boolean {
    val raw = wikiRoot.resolve("raw").toAbsolutePath().normalize()
    val abs = path.toAbsolutePath().normalize()
    return abs == raw || abs.startsWith(raw)
}

/** Last path segment shown in the file browser. A nameless root falls back to the whole path. */
internal fun vaultTreeLabel(path: Path): String = path.name.ifEmpty { path.toString() }

/** True when the open preview file can be ingested (markdown outside `wiki/` and `raw/`). */
internal fun previewOffersIngest(path: Path, wikiRoot: Path?, vault: Path): Boolean {
    if (path.isDirectory()) return false
    if (wikiRoot == null) return path.name.endsWith(".md", ignoreCase = true)
    return ingestTargets(path, wikiRoot, vault).isNotEmpty()
}

private fun samePath(left: Path, right: Path): Boolean = try {
    left.toRealPath() == right.toRealPath()
} catch (_: Exception) {
    left.toAbsolutePath().normalize() == right.toAbsolutePath().normalize()
}
