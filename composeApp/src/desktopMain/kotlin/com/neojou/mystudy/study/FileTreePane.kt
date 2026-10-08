package com.neojou.mystudy.study

import androidx.compose.foundation.ContextMenuArea
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.neojou.mystudy.wiki.vault.isInside
import com.neojou.mystudy.wiki.vault.listVaultChildren
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Left rail of the vault. Wiki files are tinted. Ingest is offered only outside the wiki root.
 */
@Composable
fun FileTreePane(
    controller: StudyController,
    modifier: Modifier = Modifier,
) {
    val vault = Path.of(controller.settings.vaultPath)
    var expanded by remember(vault.toString()) { mutableStateOf(setOf(vault.toString())) }
    Column(
        modifier
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .verticalScroll(rememberScrollState())
            .padding(8.dp),
    ) {
        Text("Vault", style = MaterialTheme.typography.labelMedium)
        if (!vault.isDirectory()) {
            Text(
                "Vault folder is missing.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }
        VaultNode(
            path = vault,
            vault = vault,
            wikiRoot = controller.wikiRoot?.let(Path::of),
            depth = 0,
            expanded = expanded,
            tick = controller.treeTick,
            onToggle = { target ->
                val key = target.toString()
                expanded = if (key in expanded) expanded - key else expanded + key
            },
            onOpen = controller::previewFile,
            onIngest = controller::requestIngest,
        )
    }
}

@Composable
private fun VaultNode(
    path: Path,
    vault: Path,
    wikiRoot: Path?,
    depth: Int,
    expanded: Set<String>,
    tick: Int,
    onToggle: (Path) -> Unit,
    onOpen: (Path) -> Unit,
    onIngest: (Path) -> Unit,
) {
    val directory = path.isDirectory()
    val open = path.toString() in expanded
    val inWiki = wikiRoot != null && isInside(path, wikiRoot)
    val wikiHere = wikiRoot != null && samePath(path, wikiRoot)
    val label = buildString {
        if (directory) append(if (open) "▾ " else "▸ ")
        append(if (depth == 0) path.toString() else path.name)
        if (wikiHere) append("  · wiki")
    }
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(start = (depth * 12).dp, top = 2.dp, bottom = 2.dp)
        .clickable {
            if (directory) onToggle(path) else onOpen(path)
        }
    val color = if (inWiki) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
    val row: @Composable () -> Unit = {
        Row(rowModifier) {
            Text(
                text = label,
                color = color,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    if (!inWiki) {
        ContextMenuArea(
            items = { listOf(ContextMenuItem("Ingest") { onIngest(path) }) },
        ) {
            row()
        }
    } else {
        row()
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
                onToggle = onToggle,
                onOpen = onOpen,
                onIngest = onIngest,
            )
        }
    }
}

private fun samePath(left: Path, right: Path): Boolean = try {
    left.toRealPath() == right.toRealPath()
} catch (_: Exception) {
    left.toAbsolutePath().normalize() == right.toAbsolutePath().normalize()
}
