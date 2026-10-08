package com.neojou.mystudy.study

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/**
 * Folder button drawn with the theme color so the top bar does not need an icon font.
 */
@Composable
fun FileBrowserIcon(modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.size(20.dp)) {
        val tab = Size(size.width * 0.42f, size.height * 0.18f)
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, size.height * 0.08f),
            size = tab,
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(0f, size.height * 0.22f),
            size = Size(size.width, size.height * 0.68f),
            cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
        )
    }
}
