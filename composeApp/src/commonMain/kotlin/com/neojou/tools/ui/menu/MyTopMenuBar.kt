package com.neojou.tools.ui.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Reusable top menu bar driven only by [items].
 *
 * Host apps configure structure and actions via [MyTopMenuItem]; this composable
 * does not embed product-specific menu names and needs no source edits for reuse.
 *
 * Supports:
 * - Top-level actions (leaf items)
 * - One dropdown level under a root with [MyTopMenuItem.children]
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyTopMenuBar(
    items: List<MyTopMenuItem>,
    barModifier: Modifier = Modifier,
) {
    TopAppBar(
        modifier = barModifier,
        title = {
            Row {
                items.forEachIndexed { index, item ->
                    val itemModifier = if (index == 0) {
                        Modifier
                    } else {
                        Modifier.padding(start = 4.dp)
                    }
                    if (item.children.isNotEmpty()) {
                        DropdownRootItem(
                            item = item,
                            modifier = itemModifier,
                        )
                    } else {
                        ActionRootItem(
                            item = item,
                            modifier = itemModifier,
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun ActionRootItem(
    item: MyTopMenuItem,
    modifier: Modifier = Modifier,
) {
    TextButton(
        onClick = { item.onClick?.invoke() },
        enabled = item.enabled,
        modifier = modifier,
    ) {
        Text(item.label)
    }
}

@Composable
private fun DropdownRootItem(
    item: MyTopMenuItem,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(item.id) { mutableStateOf(false) }

    Box(modifier = modifier) {
        TextButton(
            onClick = { if (item.enabled) expanded = true },
            enabled = item.enabled,
        ) {
            Text(item.label)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            item.children.forEach { child ->
                DropdownMenuItem(
                    text = { Text(child.label) },
                    enabled = child.enabled,
                    onClick = {
                        expanded = false
                        child.onClick?.invoke()
                    },
                )
            }
        }
    }
}
