package io.taskkling.ui

import androidx.compose.ui.geometry.Offset
import io.taskkling.contract.TaskDto

/**
 * t-aq99, round 2 — the two-handle variant won the 2026-07-12 comparison; the
 * one-handle/drop-side variant is gone: inferring direction from cursor orientation
 * felt janky AND was semantically wrong — a card laid out further right may
 * legitimately become the BLOCKER of a further-left one when no cycle forms (layout
 * position is an effect of the edges, never a constraint on them).
 *
 * Current semantics: a chain-link handle straddles each card edge — left = "this task
 * is blocked by (drag to the blocker)", right = "this task blocks (drag to the
 * dependent)". One gesture authors BOTH directions of the relationship: dropping on
 * an unlinked card links (green feedback), dropping on an already-linked card unlinks
 * (red feedback) — which is why the glyph is a link, not a (+). Only genuinely
 * impossible targets (self, would-close-a-cycle) dim. Cards of EVERY status carry
 * handles: linking closed tasks is legal and useful for organizing.
 *
 * Env override: TASKKLING_LINK_HANDLES = "selected-hover" (leaning: handles only on
 * the selected card while hovered) | "hover" (handles on any hovered card).
 */
public enum class HandleSide { LEFT, RIGHT }

/** True = handles need the card selected AND hovered (the task's leaning); false = hover alone reveals them. */
public fun resolveHandlesNeedSelection(): Boolean =
    System.getenv("TASKKLING_LINK_HANDLES")?.lowercase() != "hover"

/** The last executed link/unlink, kept for the one-shot Ctrl+Z (inverse command, no general stack). */
public data class LinkOp(val dependent: String, val blocker: String, val wasLink: Boolean)

// --- Reachability (live validity feedback) ---------------------------------------------------

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
 * Drop-target classification for a drag from [sourceId]'s [side] handle, computed once
 * at drag start from the in-memory export (a couple of DFS walks — far below frame
 * budget): [unlink] = already linked in this direction, so the drop toggles the edge
 * OFF (red feedback); [invalid] = self or would close a cycle (inert, dimmed); every
 * other card links (green). The sets are disjoint in a DAG — an existing blocker can
 * never also be a cycle-closer.
 */
public class DragTargets(public val invalid: Set<String>, public val unlink: Set<String>)

public fun classifyLinkTargets(tasks: List<TaskDto>, sourceId: String, side: HandleSide): DragTargets {
    val source = tasks.firstOrNull { it.id == sourceId } ?: return DragTargets(setOf(sourceId), emptySet())
    return when (side) {
        // Left = choose a blocker for source (`link source --depends target`): a cycle
        // closes iff the target already (transitively) depends on source.
        HandleSide.LEFT -> DragTargets(
            invalid = dependentsClosure(tasks, sourceId) + sourceId,
            unlink = source.depends.toSet(),
        )
        // Right = source blocks target (`link target --depends source`): a cycle closes
        // iff source already (transitively) depends on the target.
        HandleSide.RIGHT -> DragTargets(
            invalid = dependsClosure(tasks, sourceId) + sourceId,
            unlink = tasks.filter { sourceId in it.depends }.map { it.id }.toSet(),
        )
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
