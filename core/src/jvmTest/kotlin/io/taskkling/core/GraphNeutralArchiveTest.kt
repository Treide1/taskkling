package io.taskkling.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The graph-neutral archive (ADR-014, readiness half): sweeping a `done` task to
 * `archive/` must not change what the graph means. Pins the live-reproduced bug
 * (a gate spuriously blocked forever by its swept dependencies) and the write-path
 * corollaries: mutations of dependents keep validating, `add`/`link` accept
 * archived targets, `restore` keeps edges into the archive, and the one cycle
 * that archiving can leave dormant is caught when the node is restored.
 */
class GraphNeutralArchiveTest {

    private fun Workspace.computedFor(id: String): Computed =
        computeAll(loadTasks()).getValue(id)

    // --- readiness across the sweep ---------------------------------------------

    @Test
    fun sweepingADoneDependencyKeepsTheDependentReady() {
        val ws = tempWorkspace()
        val dep = ws.addReturningId(AddArgs(title = "groundwork"))
        val gate = ws.addReturningId(AddArgs(title = "gate", depends = listOf(dep)))
        ws.markDone(dep)
        ws.cleanup(deleteBefore = null, includeArchive = false)

        val c = ws.computedFor(gate)
        assertTrue(c.ready, "archived done dep satisfies the edge (ADR-014)")
        assertFalse(c.blocked)
        assertTrue(c.blockers.isEmpty())
    }

    @Test
    fun exportCountsAgreeAfterTheSweep() {
        val ws = tempWorkspace()
        val dep = ws.addReturningId(AddArgs(title = "groundwork"))
        ws.addReturningId(AddArgs(title = "gate", depends = listOf(dep)))
        ws.markDone(dep)
        ws.cleanup(deleteBefore = null, includeArchive = false)

        val export = ws.buildExport(includeBody = false, includeArchived = false)
        assertEquals(1, export.counts.ready, "the gate is the one ready task")
        assertEquals(0, export.counts.blocked, "nothing is blocked by the archive")
    }

    @Test
    fun anArchivedDroppedDependencyStillBlocks() {
        val ws = tempWorkspace()
        val dep = ws.addReturningId(AddArgs(title = "abandoned"))
        val gate = ws.addReturningId(AddArgs(title = "gate", depends = listOf(dep)))
        ws.markDropped(dep)
        ws.cleanup(deleteBefore = null, includeArchive = false)

        val c = ws.computedFor(gate)
        assertTrue(c.blocked, "dropped is not done, archived or not")
        assertEquals(listOf(dep), c.blockers)
    }

    // --- write path: archived ids are valid depends targets ---------------------

    @Test
    fun mutatingATaskWhoseDepsWereSweptStillValidates() {
        val ws = tempWorkspace()
        val dep = ws.addReturningId(AddArgs(title = "groundwork"))
        val gate = ws.addReturningId(AddArgs(title = "gate", depends = listOf(dep)))
        ws.markDone(dep)
        ws.cleanup(deleteBefore = null, includeArchive = false)

        // The regression companion: closing the gate must not fail the dangling check.
        val result = ws.markDone(gate)
        assertEquals(Status.DONE, result.task.status)
    }

    @Test
    fun addAndLinkAcceptArchivedTargetsButStillRejectUnknownIds() {
        val ws = tempWorkspace()
        val dep = ws.addReturningId(AddArgs(title = "groundwork"))
        ws.markDone(dep)
        ws.cleanup(deleteBefore = null, includeArchive = false)

        val added = ws.addTask(AddArgs(title = "follow-up", depends = listOf(dep))).task
        assertEquals(listOf(dep), added.depends)
        assertTrue(ws.computedFor(added.id).ready, "archived done target satisfies immediately")

        val other = ws.addReturningId(AddArgs(title = "another"))
        assertEquals(listOf(dep), ws.linkDepends(other, listOf(dep)).task.depends)

        assertFailsWith<TkError>("a truly dangling id is still rejected") {
            ws.addTask(AddArgs(title = "bad", depends = listOf("t-nope")))
        }
    }

    // --- restore across the archive boundary -------------------------------------

    @Test
    fun restoreKeepsEdgesIntoTheArchive() {
        val ws = tempWorkspace()
        val dep = ws.addReturningId(AddArgs(title = "groundwork"))
        val gate = ws.addReturningId(AddArgs(title = "gate", depends = listOf(dep)))
        ws.markDone(dep)
        ws.cleanup(deleteBefore = null, includeArchive = false)
        ws.deleteTask(gate)

        val restored = ws.restoreTask(gate)
        assertEquals(listOf(dep), restored.task.depends, "edge into archive/ survives the round trip")
        assertTrue(restored.droppedEdges.isEmpty())
        assertTrue(ws.computedFor(gate).ready)
    }

    @Test
    fun restoreStillDropsEdgesWithNoTaskBehindThem() {
        val ws = tempWorkspace()
        val dep = ws.addReturningId(AddArgs(title = "groundwork"))
        val gate = ws.addReturningId(AddArgs(title = "gate", depends = listOf(dep)))
        ws.deleteTask(gate) // trash keeps gate's own depends [dep]...
        ws.deleteTask(dep) // ...then dep leaves the graph too (trash is not a node source)

        val restored = ws.restoreTask(gate)
        assertTrue(restored.task.depends.isEmpty())
        assertEquals(listOf(dep), restored.droppedEdges)
    }

    @Test
    fun restoreRejectsACycleClosedThroughTheArchive() {
        val ws = tempWorkspace()
        val a = ws.addReturningId(AddArgs(title = "a"))
        val b = ws.addReturningId(AddArgs(title = "b", depends = listOf(a)))
        ws.markDone(b)
        ws.cleanup(deleteBefore = null, includeArchive = false)
        // b is archived done, so a -> b is legal and dormant: a stays ready.
        ws.linkDepends(a, listOf(b))
        assertTrue(ws.computedFor(a).ready)

        // Reopening b would close a <-> b in the active set; restore must refuse...
        assertFailsWith<TkError> { ws.restoreTask(b) }
        // ...and leave the store untouched.
        assertNull(ws.findActiveFile(b), "aborted restore wrote nothing")
        assertNotNull(ws.fileFor(ws.archiveDir, b), "b is still archived")
    }
}
