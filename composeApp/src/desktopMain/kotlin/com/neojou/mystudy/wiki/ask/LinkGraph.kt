package com.neojou.mystudy.wiki.ask

import com.neojou.mystudy.wiki.index.WikiIndex
import com.neojou.mystudy.wiki.model.Resolution
import kotlin.math.ln

data class GraphNode(
    val path: String,
    val title: String,
    val type: String,
    val sources: Set<String>,
    val neighbors: Set<String>,
    val lines: Map<String, String>,
)

class WikiGraph(
    val nodes: Map<String, GraphNode>,
)

data class GraphNeighbor(
    val path: String,
    val score: Double,
)

fun buildWikiGraph(index: WikiIndex): WikiGraph {
    val notes = index.notesForAsk().associateBy { it.path }
    val neighbors = HashMap<String, MutableSet<String>>()
    val lines = HashMap<String, MutableMap<String, String>>()
    notes.keys.forEach { neighbors[it] = linkedSetOf() }
    for ((source, raw) in index.linkRows()) {
        if (source !in notes) continue
        val target = when (val resolved = index.resolve(raw)) {
            is Resolution.One -> resolved.path
            else -> null
        } ?: continue
        if (target !in notes || target == source) continue
        neighbors.getValue(source).add(target)
        neighbors.getValue(target).add(source)
        lines.getOrPut(source) { linkedMapOf() }.putIfAbsent(target, wikilinkLine(notes.getValue(source).body, raw))
    }
    val nodes = notes.mapValues { (path, note) ->
        GraphNode(
            path = path,
            title = note.title,
            type = note.type,
            sources = note.sources.toSet(),
            neighbors = neighbors[path].orEmpty(),
            lines = lines[path].orEmpty(),
        )
    }
    return WikiGraph(nodes)
}

fun expandOneHop(graph: WikiGraph, seeds: List<String>): List<GraphNeighbor> {
    val seedSet = seeds.toSet()
    val best = HashMap<String, Double>()
    for (seed in seeds) {
        val from = graph.nodes[seed] ?: continue
        for (neighbor in from.neighbors) {
            if (neighbor in seedSet) continue
            val node = graph.nodes[neighbor] ?: continue
            val score = hopScore(from, node, graph)
            val previous = best[neighbor]
            if (previous == null || score > previous) best[neighbor] = score
        }
    }
    return best.entries
        .map { GraphNeighbor(it.key, it.value) }
        .sortedWith(compareByDescending<GraphNeighbor> { it.score }.thenBy { it.path })
}

fun hopScore(from: GraphNode, to: GraphNode, graph: WikiGraph): Double {
    val direct = if (to.path in from.neighbors || from.path in to.neighbors) 3.0 else 0.0
    val shared = from.sources.intersect(to.sources).size * 4.0
    val common = from.neighbors.intersect(to.neighbors)
    var adamic = 0.0
    for (name in common) {
        val degree = graph.nodes[name]?.neighbors?.size ?: 2
        adamic += 1.0 / ln(maxOf(degree, 2).toDouble())
    }
    val type = if (from.type.isNotBlank() && from.type == to.type) 1.0 else 0.0
    return direct + shared + adamic * 1.5 + type
}

fun packetEdges(graph: WikiGraph, paths: Set<String>): List<Pair<String, Pair<String, String>>> {
    val edges = mutableListOf<Pair<String, Pair<String, String>>>()
    val seen = HashSet<String>()
    for (path in paths) {
        val node = graph.nodes[path] ?: continue
        for ((target, line) in node.lines) {
            if (target !in paths) continue
            val key = listOf(path, target).sorted().joinToString("|")
            if (!seen.add(key)) continue
            edges += path to (target to line)
        }
    }
    return edges
}

private fun wikilinkLine(body: String, raw: String): String {
    val key = raw.substringBefore('#').substringAfterLast('/').removeSuffix(".md")
    return body.lineSequence().firstOrNull { line ->
        line.contains("[[") && (line.contains(raw) || (key.isNotBlank() && line.contains(key)))
    } ?: "[[$raw]]"
}
