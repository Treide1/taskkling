package io.taskkling.ui

import androidx.compose.ui.geometry.Offset
import io.taskkling.contract.TaskDto

/**
 * t-aq99: drag-to-link/unlink dependency edges on the canvas. Everything in
 * this file is design work; the two interaction variants under comparison
 * live behind [LinkDirection] so both branches carry identical code and
 * differ only in [DEFAULT_LINK_DIRECTION] (env vars override at launch, so either
 * branch can impersonate the other without a rebuild).
 *
 *  - TWO_HANDLES: a (+) handle on each card edge. Left handle = "this task is blocked
 *    by — drag to the blocker"; right handle = "this task blocks — drag to the
 *    dependent". Link validity (self/cycle/duplicate) is computed at drag-START from
 *    the export already in memory, and invalid targets dim for the whole drag.
 *  - ONE_HANDLE: a single (+) handle on the right edge; direction is inferred from
 *    where the drop lands relative to the source (drop right = source blocks target,
 *    drop left = target blocks source). No live validity — errors surface at drop as
 *    the CLI's own failure toast. Re-dragging an existing edge's pair toggles it OFF
 *    (unlink), demoing option (c) of the unlink question.
 *
 * Env overrides:
 *  - TASKKLING_LINK_MODE = "a" | "two-handles" | "b" | "one-handle"
 *  - TASKKLING_LINK_HANDLES = "selected-hover" (leaning: handles only on the selected
 *    card while hovered) | "hover" (handles on any hovered card)
 */
public enum class LinkDirection { TWO_HANDLES, ONE_HANDLE }

/** Which card edge a link drag started from. */
public enum class HandleSide { LEFT, RIGHT }

/** Branch default — the only line the two branches differ in. */
public val DEFAULT_LINK_DIRECTION: LinkDirection = LinkDirection.TWO_HANDLES

public fun resolveLinkDirection(): LinkDirection =
    when (System.getenv("TASKKLING_LINK_MODE")?.lowercase()) {
        "a", "two-handles" -> LinkDirection.TWO_HANDLES
        "b", "one-handle" -> LinkDirection.ONE_HANDLE
        else -> DEFAULT_LINK_DIRECTION
    }

/** True = handles need the card selected AND hovered (the task's leaning); false = hover alone reveals them. */
public fun resolveHandlesNeedSelection(): Boolean =
    System.getenv("TASKKLING_LINK_HANDLES")?.lowercase() != "hover"

/** The last executed link/unlink, kept for the one-shot Ctrl+Z (inverse command, no general stack). */
public data class LinkOp(val dependent: String, val blocker: String, val wasLink: Boolean)

// --- Reachability (live cycle feedback, TWO_HANDLES) -----------------------------------------

/** All ids [rootId] transitively depends on (its blocker closure), excluding the root. */
public fun dependsClosure(tasks: List<TaskDto>, rootId: String): Set<String> =
    closure(rootId) { id -> tasks.firstOrNull { it.id == id }?.depends.orEmpty() }

/** All ids that transitively depend on [rootId] (its dependent closure), excluding the root. */
public fun dependentsClosure(tasks: List<TaskDto>, rootId: String): Set<String> =
    closure(rootId) { id -> tasks.filter { id in it.depends }.map { it.id } }

private fun closure(rootId: String, next: (String) -> List<String>): Set<String> {
    val seen = HashSet<String>()
    val stack = ArrayDeque(next(rootId))
    while (stack.isNotEmpty()) {
        val id = stack.removeLast()
        if (seen.add(id)) stack.addAll(next(id))
    }
    seen.remove(rootId)
    return seen
}

/**
 * The targets a drag from [sourceId]'s [side] handle must NOT drop on: the source
 * itself, everything already linked in that direction, and everything whose linking
 * would close a cycle. Computed once at drag start — the whole active set is already
 * in memory, so this is a couple of DFS walks over ~N nodes, far below frame budget.
 */
public fun invalidLinkTargets(tasks: List<TaskDto>, sourceId: String, side: HandleSide): Set<String> {
    val source = tasks.firstOrNull { it.id == sourceId } ?: return setOf(sourceId)
    return when (side) {
        // Left = choose a blocker for source (`link source --depends target`): a cycle
        // closes iff the target already (transitively) depends on source.
        HandleSide.LEFT -> dependentsClosure(tasks, sourceId) + source.depends + sourceId
        // Right = source blocks target (`link target --depends source`): a cycle closes
        // iff source already (transitively) depends on the target.
        HandleSide.RIGHT ->
            dependsClosure(tasks, sourceId) + tasks.filter { sourceId in it.depends }.map { it.id } + sourceId
    }
}

// --- Edge geometry (hit-testing for click-to-select-an-edge) ---------------------------------

/** The four control points of one rendered S-curve edge, in canvas px (mirrors [drawSCurveEdge]'s math). */
public class EdgeCurve(public val p0: Offset, public val p1: Offset, public val p2: Offset, public val p3: Offset)

/** The curve an `a → b` edge renders as: right-centre of the blocker to the arrowhead base at the dependent. */
internal fun edgeCurve(a: CardRect, b: CardRect, arrowPx: Float, minDxPx: Float): EdgeCurve {
    val x1 = a.right
    val y1 = a.centerY
    val endX = b.left - arrowPx
    val y2 = b.centerY
    val dx = maxOf(minDxPx, (endX - x1) * 0.5f)
    return EdgeCurve(Offset(x1, y1), Offset(x1 + dx, y1), Offset(endX - dx, y2), Offset(endX, y2))
}

/** De Casteljau evaluation of the cubic at [t]. */
public fun cubicPoint(c: EdgeCurve, t: Float): Offset {
    fun lerp(a: Offset, b: Offset): Offset = a + (b - a) * t
    val ab = lerp(c.p0, c.p1)
    val bc = lerp(c.p1, c.p2)
    val cd = lerp(c.p2, c.p3)
    return lerp(lerp(ab, bc), lerp(bc, cd))
}

/** Minimum distance from [p] to the sampled curve — 24 samples is plenty at edge scale. */
public fun distanceToCurve(p: Offset, c: EdgeCurve, samples: Int = 24): Float {
    var best = Float.MAX_VALUE
    for (i in 0..samples) {
        val d = (cubicPoint(c, i / samples.toFloat()) - p).getDistance()
        if (d < best) best = d
    }
    return best
}
