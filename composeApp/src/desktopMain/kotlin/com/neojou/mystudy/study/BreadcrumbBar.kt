package com.neojou.mystudy.study

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neojou.mystudy.ui.theme.LocalStudyTokens
import java.nio.file.Path
import kotlin.io.path.name

internal data class BreadcrumbCrumb(val label: String, val path: Path?)

internal fun breadcrumbCrumbs(vault: Path, file: Path): List<BreadcrumbCrumb> {
    val root = BreadcrumbCrumb(vaultTreeLabel(vault), vault)
    val relative = try {
        vault.toAbsolutePath().normalize().relativize(file.toAbsolutePath().normalize())
    } catch (_: Exception) {
        return listOf(root)
    }
    if (relative.startsWith("..")) return listOf(root)
    val parent = relative.parent ?: return listOf(root)
    val crumbs = mutableListOf(root)
    var cursor = vault
    parent.forEach { part ->
        cursor = cursor.resolve(part)
        crumbs += BreadcrumbCrumb(part.name.ifEmpty { part.toString() }, cursor)
    }
    if (crumbs.size <= 4) return crumbs
    return listOf(crumbs.first(), BreadcrumbCrumb("…", null)) + crumbs.takeLast(2)
}

@Composable
internal fun BreadcrumbBar(
    crumbs: List<BreadcrumbCrumb>,
    onOpen: (Path) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (crumbs.isEmpty()) return
    val tokens = LocalStudyTokens.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text(
                    " › ",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = tokens.metaSize,
                )
            }
            val target = crumb.path
            Text(
                crumb.label,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = tokens.metaSize,
                lineHeight = 16.sp,
                modifier = Modifier
                    .padding(vertical = 2.dp)
                    .then(
                        if (target != null && crumb.label != "…") {
                            Modifier.clickable { onOpen(target) }
                        } else {
                            Modifier
                        },
                    ),
            )
        }
    }
}
