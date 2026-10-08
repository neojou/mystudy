package com.neojou.mystudy.study

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.neojou.mystudy.wiki.ask.PacketEdge
import com.neojou.mystudy.wiki.ask.PacketPage
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

/**
 * The pages retrieved for one answer, laid out on a circle.
 * This is not a graph of the vault.
 */
@Composable
fun AnswerGraph(
    pages: List<PacketPage>,
    edges: List<PacketEdge>,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (pages.isEmpty()) return
    val shown = pages.take(8)
    var selected by remember(shown, edges) { mutableStateOf<String?>(null) }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelMedium
    val labelColor = MaterialTheme.colorScheme.onSurface
    val scheme = MaterialTheme.colorScheme
    val nodeRadius = 28.dp
    val edgeSlop = 10.dp
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .pointerInput(shown, edges) {
                    val radius = nodeRadius.toPx()
                    val slop = edgeSlop.toPx()
                    detectTapGestures { offset ->
                        val points = circleLayout(shown, Size(size.width.toFloat(), size.height.toFloat()), radius)
                        when (val hit = hitGraph(points, edges, offset, radius + 24f, slop)) {
                            is GraphHit.Node -> onOpen(hit.path)
                            is GraphHit.Edge -> selected = hit.line
                            GraphHit.Miss -> Unit
                        }
                    }
                },
        ) {
            val radius = 28.dp.toPx()
            val points = circleLayout(shown, size, radius)
            val byPath = points.associateBy { it.page.path }
            val selectedLine = selected
            for (edge in edges) {
                val from = byPath[edge.from]?.center ?: continue
                val to = byPath[edge.to]?.center ?: continue
                val chosen = edge.line == selectedLine
                drawLine(
                    color = if (chosen) scheme.primary else scheme.outline,
                    start = from,
                    end = to,
                    strokeWidth = if (chosen) 4f else 2f,
                )
            }
            for (point in points) {
                drawCircle(typeColor(point.page.type, scheme), radius, point.center)
                drawCircle(scheme.outline, radius, point.center, style = Stroke(width = 2f))
                val layout = measurer.measure(
                    text = AnnotatedString(point.page.title),
                    style = labelStyle.merge(TextStyle(color = labelColor, textAlign = TextAlign.Center)),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                    constraints = Constraints(maxWidth = (radius * 3.2f).toInt().coerceAtLeast(1)),
                )
                drawText(
                    layout,
                    topLeft = Offset(
                        point.center.x - layout.size.width / 2f,
                        point.center.y + radius + 4f,
                    ),
                )
            }
        }
        selected?.let { line ->
            Text(line, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

internal data class GraphPoint(val page: PacketPage, val center: Offset)

internal sealed class GraphHit {
    data class Node(val path: String) : GraphHit()
    data class Edge(val line: String) : GraphHit()
    data object Miss : GraphHit()
}

internal fun circleLayout(pages: List<PacketPage>, size: Size, nodeRadius: Float): List<GraphPoint> {
    if (pages.isEmpty() || size.width <= 0f || size.height <= 0f) return emptyList()
    val cx = size.width / 2f
    val cy = size.height / 2f
    if (pages.size == 1) return listOf(GraphPoint(pages[0], Offset(cx, cy)))
    val orbit = (minOf(cx, cy) - nodeRadius - 48f).coerceAtLeast(nodeRadius + 8f)
    return pages.mapIndexed { index, page ->
        val angle = -PI / 2.0 + (2.0 * PI * index / pages.size)
        GraphPoint(
            page,
            Offset(
                cx + (orbit * cos(angle)).toFloat(),
                cy + (orbit * sin(angle)).toFloat(),
            ),
        )
    }
}

internal fun hitGraph(
    points: List<GraphPoint>,
    edges: List<PacketEdge>,
    tap: Offset,
    nodeRadius: Float,
    edgeSlop: Float,
): GraphHit {
    val node = points.minByOrNull { (it.center - tap).getDistance() }
    if (node != null && (node.center - tap).getDistance() <= nodeRadius) {
        return GraphHit.Node(node.page.path)
    }
    var best: PacketEdge? = null
    var bestDistance = Float.MAX_VALUE
    val byPath = points.associateBy { it.page.path }
    for (edge in edges) {
        val start = byPath[edge.from]?.center ?: continue
        val end = byPath[edge.to]?.center ?: continue
        val distance = distanceToSegment(tap, start, end)
        if (distance <= edgeSlop && distance < bestDistance) {
            best = edge
            bestDistance = distance
        }
    }
    return if (best != null) GraphHit.Edge(best.line) else GraphHit.Miss
}

internal fun distanceToSegment(point: Offset, start: Offset, end: Offset): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) return (point - start).getDistance()
    val t = (((point.x - start.x) * dx + (point.y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    val projection = Offset(start.x + dx * t, start.y + dy * t)
    return (point - projection).getDistance()
}

private fun typeColor(type: String, scheme: androidx.compose.material3.ColorScheme): Color = when (type) {
    "concept" -> scheme.primary
    "entity" -> scheme.tertiary
    "source" -> scheme.secondary
    "query" -> scheme.primaryContainer
    else -> scheme.outline
}
