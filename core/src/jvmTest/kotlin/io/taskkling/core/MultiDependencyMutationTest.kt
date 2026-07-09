package io.taskkling.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Multi-edge `link`/`unlink` semantics (PRD §10.6): both verbs take a **list** of
 * deps and apply them in a single validated mutation. Locks the batch behavior
 * that the single-element list tests in [ValidateTest] don't exercise — dedup
 * against existing edges, first-seen ordering, partial-overlap removal, and
 * atomic cycle rejection over the whole merged edge set.
 */
class MultiDependencyMutationTest {

    @Test
    fun linkAddsMultipleEdgesInOneCall() {
        val ws = tempWorkspace()
        val a = ws.addReturningId(AddArgs(title = "a"))
        val b = ws.addReturningId(AddArgs(title = "b"))
        val c = ws.addReturningId(AddArgs(title = "c"))
        ws.linkDepends(a, listOf(b, c))
        assertEquals(listOf(b, c), ws.loadTask(a)!!.depends, "both edges added, in call order")
    }

    @Test
    fun linkDedupesAgainstExistingEdge() {
        val ws = tempWorkspace()
        val a = ws.addReturningId(AddArgs(title = "a"))
        val b = ws.addReturningId(AddArgs(title = "b"))
        val c = ws.addReturningId(AddArgs(title = "c"))
        ws.linkDepends(a, listOf(b))          // a -> b already present
        ws.linkDepends(a, listOf(b, c))       // re-linking b must not duplicate it
        assertEquals(listOf(b, c), ws.loadTask(a)!!.depends, "existing b deduped, c appended")
    }

    @Test
    fun linkKeepsExistingEdgesFirstAndDropsRepeats() {
        val ws = tempWorkspace()
        val a = ws.addReturningId(AddArgs(title = "a"))
        val b = ws.addReturningId(AddArgs(title = "b"))
        val c = ws.addReturningId(AddArgs(title = "c"))
        ws.linkDepends(a, listOf(b))          // existing: [b]
        // (existing + deps).distinct(): b keeps position 0 even though re-passed last
        ws.linkDepends(a, listOf(c, b))
        assertEquals(listOf(b, c), ws.loadTask(a)!!.depends, "first-seen order: existing b stays first, c added")
    }

    @Test
    fun linkDedupesWithinTheBatch() {
        val ws = tempWorkspace()
        val a = ws.addReturningId(AddArgs(title = "a"))
        val b = ws.addReturningId(AddArgs(title = "b"))
        ws.linkDepends(a, listOf(b, b))       // a repeated dep in one call
        assertEquals(listOf(b), ws.loadTask(a)!!.depends, "duplicate within the batch collapses to one edge")
    }

    @Test
    fun unlinkRemovesMultipleEdgesInOneCall() {
        val ws = tempWorkspace()
        val a = ws.addReturningId(AddArgs(title = "a"))
        val b = ws.addReturningId(AddArgs(title = "b"))
        val c = ws.addReturningId(AddArgs(title = "c"))
        ws.linkDepends(a, listOf(b, c))
        ws.unlinkDepends(a, listOf(b, c))
        assertTrue(ws.loadTask(a)!!.depends.isEmpty(), "both edges removed in one mutation")
    }

    @Test
    fun unlinkOfAbsentIdIsANoOpAlongsideRealRemovals() {
        val ws = tempWorkspace()
        val a = ws.addReturningId(AddArgs(title = "a"))
        val b = ws.addReturningId(AddArgs(title = "b"))
        val c = ws.addReturningId(AddArgs(title = "c"))
        ws.linkDepends(a, listOf(b, c))
        // c is a real edge; b was already gone -> removing a non-present id is a no-op
        ws.unlinkDepends(a, listOf(b))
        ws.unlinkDepends(a, listOf(c, b))      // c removed; b (absent) ignored
        assertTrue(ws.loadTask(a)!!.depends.isEmpty())
    }

    @Test
    fun unlinkRemovesOnlyTheListedEdges() {
        val ws = tempWorkspace()
        val a = ws.addReturningId(AddArgs(title = "a"))
        val b = ws.addReturningId(AddArgs(title = "b"))
        val c = ws.addReturningId(AddArgs(title = "c"))
        ws.linkDepends(a, listOf(b, c))
        ws.unlinkDepends(a, listOf(c))         // b survives, c goes
        assertEquals(listOf(b), ws.loadTask(a)!!.depends)
    }

    @Test
    fun batchLinkIntroducingACycleIsRejectedAtomically() {
        val ws = tempWorkspace()
        val a = ws.addReturningId(AddArgs(title = "a"))
        val b = ws.addReturningId(AddArgs(title = "b", depends = listOf(a))) // b -> a
        val c = ws.addReturningId(AddArgs(title = "c"))
        // batch onto a = [c, b]: a -> c is harmless, but a -> b closes the loop b -> a -> b
        assertFailsWith<TkError> { ws.linkDepends(a, listOf(c, b)) }
        // whole mutation rejected before any write: no partial edge (not even the valid c) lands
        assertTrue(ws.loadTask(a)!!.depends.isEmpty(), "a validation failure leaves the batch un-applied")
    }
}
