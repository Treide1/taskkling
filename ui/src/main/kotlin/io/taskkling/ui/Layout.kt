package io.taskkling.ui

import io.taskkling.contract.TaskDto

/** A node's grid slot in the layered layout: its [layer] (column) and [indexInLayer] (row). */
public data class NodePos(val id: String, val layer: Int, val indexInLayer: Int)

/** A directed edge `from` an upstream dependency `to` the dependent node. */
public data class Edge(val from: String, val to: String)

/** The computed layout: per-id slots, the edge set, and the grid extents for sizing. */
public data class GraphLayout(
    val positions: Map<String, NodePos>,
    val edges: List<Edge>,
    val layerCount: Int,
    val maxLayerSize: Int,
)

/**
 * Hand-rolled Sugiyama-style **layered** layout (PRD §13). Layer assignment is by
 * longest dependency chain — `layer(n) = 0` for a root (no `depends`), else
 * `1 + max(layer(d))` over its present dependencies — so edges flow left→right
 * from prerequisites to dependents. Within a layer, nodes are ordered by id
 * (stable; crossing-minimisation is a later refinement). Dangling `depends`
 * (target absent) are ignored, and a visiting-set guards against cycles a
 * hand-edit might introduce, so the read path never loops.
 */
public fun layout(tasks: List<TaskDto>): GraphLayout {
    val byId = tasks.associateBy { it.id }
    val layerOf = HashMap<String, Int>()
    val visiting = HashSet<String>()

    fun layerOf(id: String): Int {
        layerOf[id]?.let { return it }
        if (!visiting.add(id)) return 0 // cycle guard
        val deps = byId[id]?.depends.orEmpty().filter { it in byId }
        val l = if (deps.isEmpty()) 0 else 1 + deps.maxOf { layerOf(it) }
        visiting.remove(id)
        layerOf[id] = l
        return l
    }
    tasks.forEach { layerOf(it.id) }

    val byLayer = tasks.map { it.id }.groupBy { layerOf.getValue(it) }
    val positions = HashMap<String, NodePos>()
    var maxLayerSize = 0
    for (layer in byLayer.keys.sorted()) {
        val ids = byLayer.getValue(layer).sorted()
        maxLayerSize = maxOf(maxLayerSize, ids.size)
        ids.forEachIndexed { i, id -> positions[id] = NodePos(id, layer, i) }
    }

    val edges = tasks.flatMap { t ->
        t.depends.filter { it in byId }.map { dep -> Edge(from = dep, to = t.id) }
    }

    return GraphLayout(
        positions = positions,
        edges = edges,
        layerCount = (byLayer.keys.maxOrNull() ?: -1) + 1,
        maxLayerSize = maxLayerSize,
    )
}

/**
 * Per-column vertical stacking (DESIGN §10): given card [heights] top-to-bottom within a
 * single column, returns each card's top edge in the same coordinate as [start] — the
 * first card at [start], each subsequent card a constant [gap] below the previous card's
 * measured bottom. Columns stack independently by measured height; cross-column row
 * alignment is deliberately not attempted (column k's card i need not align with column
 * j's card i). Pure math (heights + gap → tops) so the stacking rule is documented and
 * unit-testable in isolation.
 */
public fun stackTops(heights: List<Int>, gap: Int, start: Int): List<Int> {
    val tops = ArrayList<Int>(heights.size)
    var y = start
    for (h in heights) {
        tops.add(y)
        y += h + gap
    }
    return tops
}

/**
 * Clamp a target scroll offset to a Compose `ScrollState`'s reachable range `0..maxScroll`.
 * [maxScroll] is `0` when the content fits its viewport (nothing to scroll), so every target
 * collapses to `0`; otherwise an offset past either end is pulled to the nearest reachable
 * edge. Idempotent — `coerceIn` of an already-in-range value is a no-op. Pure (offset + bound →
 * offset) so the pan bound is unit-testable without a live `ScrollState`; the same bound is what
 * `ScrollState` enforces on the wheel/drag pan path.
 */
public fun clampScroll(offset: Int, maxScroll: Int): Int = offset.coerceIn(0, maxScroll)

/**
 * Pan-to-card target offset (DESIGN §9): the scroll position that places a card whose measured
 * centre sits at [center] content-px in the middle of a [viewport]-px window, clamped to the
 * scroll range via [clampScroll]. Centring means scrolling to `center - viewport / 2`; the clamp
 * means a card near a content edge lands as close to centred as the bounds allow rather than
 * over-scrolling past `0` or [maxScroll]. The same function serves both axes (x and y). Pure
 * (centre + viewport + bound → offset) so the centring rule is unit-testable without a
 * `ScrollState`.
 */
public fun centerScrollOffset(center: Float, viewport: Int, maxScroll: Int): Int =
    clampScroll((center - viewport / 2f).toInt(), maxScroll)
