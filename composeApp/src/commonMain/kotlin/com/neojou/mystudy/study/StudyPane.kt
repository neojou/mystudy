package com.neojou.mystudy.study

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Desktop study surface. The JVM actual owns the vault tree, the index, and Ollama.
 */
@Composable
expect fun StudyPane(
    mode: StudyMode,
    fileBrowserOpen: Boolean,
    onMode: (StudyMode) -> Unit,
    modifier: Modifier,
)
