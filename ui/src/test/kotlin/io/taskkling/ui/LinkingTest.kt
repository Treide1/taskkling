package io.taskkling.ui

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The pure reachability + edge-geometry math behind canvas link authoring (t-aq99). These
 * decide what a drag is *allowed* to do — a wrong closure offers a cycle-closing drop as
 * legal (green) and only the CLI's rejection stops it — so they're worth pinning directly.
 *
 * The fixture is a diamond plus an isolated node:
 *
 *     t-d ──→ t-b ──→ t-a        (arrows point blocker → dependent)
 *      └────→ t-c ──→ t-a         t-e stands alone
 */
class LinkingTest {
    private val tasks = listOf(
        task("t-a", depends = listOf("t-b", "t-c")),
        task("t-b", depends = listOf("t-d")),
        task("t-c", depends = listOf("t-d")),
        task("t-d"),
        task("t-e"),
    )

    // --- closures --------------------------------------------------------------------------

    @Test
    fun `dependsClosure walks the whole blocker chain, not just direct blockers`() {
        assertEquals(setOf("t-b", "t-c", "t-d"), dependsClosure(tasks, "t-a"))
    }

    @Test
    fun `dependentsClosure walks the whole dependent chain`() {
        assertEquals(setOf("t-b", "t-c", "t-a"), dependentsClosure(tasks, "t-d"))
    }

    @Test
    fun `a leaf's closures are empty, both directions`() {
        assertEquals(emptySet(), dependsClosure(tasks, "t-d"))
        assertEquals(emptySet(), dependentsClosure(tasks, "t-a"))
        assertEquals(emptySet(), dependsClosure(tasks, "t-e"))
        assertEquals(emptySet(), dependentsClosure(tasks, "t-e"))
    }

    @Test
    fun `an unknown id has no closure rather than throwing`() {
        assertEquals(emptySet(), dependsClosure(tasks, "t-zzzz"))
        assertEquals(emptySet(), dependentsClosure(tasks, "t-zzzz"))
    }

    @Test
    fun `a dangling depends id is still reported as a blocker`() {
        // Archived blockers stay in `depends` (t-nt8t); the closure must not silently drop them.
        assertEquals(setOf("t-gone"), dependsClosure(listOf(task("t-x", depends = listOf("t-gone"))), "t-x"))
    }

    /** The closure walk must terminate even on data the DAG invariant says can't exist. */
    @Test
    fun `a cyclic export terminates instead of looping forever`() {
        val cyclic = listOf(task("t-x", depends = listOf("t-y")), task("t-y", depends = listOf("t-x")))
        assertEquals(setOf("t-y"), dependsClosure(cyclic, "t-x"))
        assertEquals(setOf("t-y"), dependentsClosure(cyclic, "t-x"))
    }

    // --- drop-target classification --------------------------------------------------------

    @Test
    fun `dragging LEFT from a card offers its blockers as unlink and its dependents as cycles`() {
        // Left = "pick a blocker for t-b": t-d is already one (drop unlinks); t-a depends on
        // t-b, so blocking t-b on t-a would close a cycle.
        val targets = classifyLinkTargets(tasks, "t-b", HandleSide.LEFT)
        assertEquals(setOf("t-d"), targets.unlink)
        assertEquals(setOf("t-a", "t-b"), targets.invalid)
        // Everything unclassified links: t-c and t-e are legal new blockers for t-b.
        assertLinks(targets, "t-c", "t-e")
    }

    @Test
    fun `dragging RIGHT from a card offers its dependents as unlink and its blockers as cycles`() {
        // Right = "t-b blocks the target": t-a already is one (drop unlinks); t-d blocks t-b,
        // so making t-d depend on t-b would close a cycle.
        val targets = classifyLinkTargets(tasks, "t-b", HandleSide.RIGHT)
        assertEquals(setOf("t-a"), targets.unlink)
        assertEquals(setOf("t-d", "t-b"), targets.invalid)
        assertLinks(targets, "t-c", "t-e")
    }

    @Test
    fun `the source card is always an invalid target — no self-links`() {
        for (side in HandleSide.entries) {
            assertTrue("t-e" in classifyLinkTargets(tasks, "t-e", side).invalid, "self must be invalid on $side")
        }
    }

    @Test
    fun `invalid and unlink never overlap — an existing blocker is not a cycle-closer`() {
        for (id in tasks.map { it.id }) {
            for (side in HandleSide.entries) {
                val t = classifyLinkTargets(tasks, id, side)
                assertEquals(emptySet(), t.invalid intersect t.unlink, "$id/$side classified a target twice")
            }
        }
    }

    @Test
    fun `an unknown source offers nothing but itself`() {
        val targets = classifyLinkTargets(tasks, "t-zzzz", HandleSide.LEFT)
        assertEquals(setOf("t-zzzz"), targets.invalid)
        assertEquals(emptySet(), targets.unlink)
    }

    private fun assertLinks(targets: DragTargets, vararg ids: String) {
        for (id in ids) {
            assertTrue(id !in targets.invalid && id !in targets.unlink, "$id should be a plain link target")
        }
    }

    // --- edge geometry ---------------------------------------------------------------------

    private val curve = edgeCurve(
        a = CardRect(left = 0f, top = 0f, width = 100f, height = 50f),
        b = CardRect(left = 300f, top = 0f, width = 100f, height = 50f),
        arrowPx = 10f,
        minDxPx = 20f,
    )

    @Test
    fun `an edge curve spans the blocker's right edge to the dependent's arrowhead base`() {
        assertEquals(Offset(100f, 25f), curve.p0) // right-centre of a
        assertEquals(Offset(290f, 25f), curve.p3) // left of b, less the arrowhead
        // The control points pull horizontally out of each card by half the gap (95f).
        assertEquals(Offset(195f, 25f), curve.p1)
        assertEquals(Offset(195f, 25f), curve.p2)
    }

    @Test
    fun `control points keep a minimum horizontal pull on a back-edge`() {
        // b sits LEFT of a: the natural dx goes negative, so minDxPx floors it and the curve
        // still bows outward rather than collapsing into a spike.
        val back = edgeCurve(
            a = CardRect(left = 300f, top = 0f, width = 100f, height = 50f),
            b = CardRect(left = 0f, top = 0f, width = 100f, height = 50f),
            arrowPx = 10f,
            minDxPx = 20f,
        )
        assertEquals(420f, back.p1.x) // p0.x + minDx
        assertEquals(-30f, back.p2.x) // p3.x - minDx
    }

    @Test
    fun `the cubic starts at p0 and ends at p3`() {
        assertEquals(curve.p0, cubicPoint(curve, 0f))
        assertEquals(curve.p3, cubicPoint(curve, 1f))
    }

    @Test
    fun `a straight-across edge stays on its own row at the midpoint`() {
        // Both cards share centerY=25, so every sample sits on that line.
        assertEquals(25f, cubicPoint(curve, 0.5f).y)
    }

    @Test
    fun `distanceToCurve is zero on the curve and grows with the offset`() {
        val onCurve = cubicPoint(curve, 0.5f)
        assertTrue(distanceToCurve(onCurve, curve) < 0.01f, "a point on the curve should measure ~0")
        assertTrue(distanceToCurve(onCurve + Offset(0f, 40f), curve) > 39f)
    }

    @Test
    fun `distanceToCurve measures to the nearest point, not an endpoint`() {
        // A point hovering directly above the curve's middle is ~30px away, even though both
        // endpoints are far off — this is what makes a click near an edge's middle select it.
        val p = cubicPoint(curve, 0.5f) + Offset(0f, 30f)
        assertTrue(distanceToCurve(p, curve) in 29f..31f)
    }
}
