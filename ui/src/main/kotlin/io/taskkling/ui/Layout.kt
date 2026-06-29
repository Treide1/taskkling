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
