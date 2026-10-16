package com.neojou.mystudy.study

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

internal enum class FileKind { Folder, Markdown, Image, File }

internal fun fileKind(name: String, directory: Boolean): FileKind {
    if (directory) return FileKind.Folder
    val lower = name.lowercase()
    return when {
        lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt") -> FileKind.Markdown
        lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".svg") -> FileKind.Image
        else -> FileKind.File
    }
}

@Composable
internal fun FileKindIcon(kind: FileKind, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(16.dp)) {
        val stroke = Stroke(width = 1.4.dp.toPx())
        when (kind) {
            FileKind.Folder -> {
                val path = Path().apply {
                    moveTo(1.dp.toPx(), 6.dp.toPx())
                    lineTo(1.dp.toPx(), 13.dp.toPx())
                    lineTo(15.dp.toPx(), 13.dp.toPx())
                    lineTo(15.dp.toPx(), 5.dp.toPx())
                    lineTo(7.dp.toPx(), 5.dp.toPx())
                    lineTo(5.5.dp.toPx(), 3.dp.toPx())
                    lineTo(1.dp.toPx(), 3.dp.toPx())
                    close()
                }
                drawPath(path, tint, style = stroke)
            }
            FileKind.Markdown -> {
                drawRoundRect(tint, topLeft = Offset(3.dp.toPx(), 1.dp.toPx()), size = Size(10.dp.toPx(), 14.dp.toPx()), cornerRadius = CornerRadius(1.dp.toPx()), style = stroke)
                drawLine(tint, Offset(5.dp.toPx(), 6.dp.toPx()), Offset(11.dp.toPx(), 6.dp.toPx()), strokeWidth = 1.2.dp.toPx())
                drawLine(tint, Offset(5.dp.toPx(), 9.dp.toPx()), Offset(11.dp.toPx(), 9.dp.toPx()), strokeWidth = 1.2.dp.toPx())
            }
            FileKind.Image -> {
                drawRoundRect(tint, topLeft = Offset(1.dp.toPx(), 3.dp.toPx()), size = Size(14.dp.toPx(), 10.dp.toPx()), cornerRadius = CornerRadius(1.dp.toPx()), style = stroke)
                drawCircle(tint, radius = 1.4.dp.toPx(), center = Offset(5.dp.toPx(), 6.5.dp.toPx()))
                drawLine(tint, Offset(2.dp.toPx(), 11.dp.toPx()), Offset(7.dp.toPx(), 8.dp.toPx()), strokeWidth = 1.2.dp.toPx())
                drawLine(tint, Offset(7.dp.toPx(), 8.dp.toPx()), Offset(14.dp.toPx(), 12.dp.toPx()), strokeWidth = 1.2.dp.toPx())
            }
            FileKind.File -> {
                drawRoundRect(tint, topLeft = Offset(4.dp.toPx(), 1.dp.toPx()), size = Size(8.dp.toPx(), 14.dp.toPx()), cornerRadius = CornerRadius(1.dp.toPx()), style = stroke)
            }
        }
    }
}

@Composable
internal fun CalendarIcon(modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(14.dp)) {
        val stroke = Stroke(width = 1.2.dp.toPx())
        drawRoundRect(
            tint,
            topLeft = Offset(1.dp.toPx(), 2.dp.toPx()),
            size = Size(12.dp.toPx(), 11.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx()),
            style = stroke,
        )
        drawLine(tint, Offset(1.dp.toPx(), 6.dp.toPx()), Offset(13.dp.toPx(), 6.dp.toPx()), strokeWidth = 1.dp.toPx())
        drawLine(tint, Offset(4.dp.toPx(), 1.dp.toPx()), Offset(4.dp.toPx(), 3.5.dp.toPx()), strokeWidth = 1.2.dp.toPx())
        drawLine(tint, Offset(10.dp.toPx(), 1.dp.toPx()), Offset(10.dp.toPx(), 3.5.dp.toPx()), strokeWidth = 1.2.dp.toPx())
    }
}

@Composable
internal fun CopyIcon(modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.size(18.dp)) {
        val stroke = Stroke(width = 1.4.dp.toPx())
        drawRoundRect(
            tint,
            topLeft = Offset(5.dp.toPx(), 3.dp.toPx()),
            size = Size(10.dp.toPx(), 12.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx()),
            style = stroke,
        )
        drawRoundRect(
            tint,
            topLeft = Offset(2.dp.toPx(), 6.dp.toPx()),
            size = Size(10.dp.toPx(), 12.dp.toPx()),
            cornerRadius = CornerRadius(1.dp.toPx()),
            style = stroke,
        )
    }
}

@Composable
internal fun CollapseTreeIcon(modifier: Modifier = Modifier) {
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(16.dp)) {
        val stroke = Stroke(width = 1.6.dp.toPx())
        drawLine(tint, Offset(11.dp.toPx(), 3.dp.toPx()), Offset(5.dp.toPx(), 8.dp.toPx()), strokeWidth = stroke.width)
        drawLine(tint, Offset(5.dp.toPx(), 8.dp.toPx()), Offset(11.dp.toPx(), 13.dp.toPx()), strokeWidth = stroke.width)
    }
}

@Composable
internal fun ChevronIcon(forward: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(18.dp)) {
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val top = 4.dp.toPx()
        val bottom = 14.dp.toPx()
        val mid = size.height / 2f
        val near = 5.dp.toPx()
        val far = 13.dp.toPx()
        val tip = if (forward) far else near
        val tail = if (forward) near else far
        drawLine(tint, Offset(tail, top), Offset(tip, mid), strokeWidth = stroke.width, cap = stroke.cap)
        drawLine(tint, Offset(tip, mid), Offset(tail, bottom), strokeWidth = stroke.width, cap = stroke.cap)
    }
}
