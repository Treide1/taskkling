package io.taskkling.core

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** Computed attributes / ready-set semantics (PRD §8.2, §11, §17). */
class ComputeTest {

    private val past = "2000-01-01T00:00:00Z"
    private val future = "2999-01-01T00:00:00Z"

    private fun task(
        id: String,
        status: Status = Status.OPEN,
        depends: List<String> = emptyList(),
        due: String? = null,
        defer: String? = null,
    ) = Task(id = id, title = id, status = status, depends = depends, due = due, defer = defer, created = past)

    @Test
    fun openNodeWithNoDepsIsReady() {
        val c = computeAll(listOf(task("t-1"))).getValue("t-1")
        assertTrue(c.ready)
        assertFalse(c.blocked)
    }

    @Test
    fun unsatisfiedDependencyBlocks() {
        val tasks = listOf(task("t-a"), task("t-b", depends = listOf("t-a")))
        val c = computeAll(tasks).getValue("t-b")
        assertFalse(c.ready)
        assertTrue(c.blocked)
        assertEquals(listOf("t-a"), c.blockers)
    }

    @Test
    fun satisfiedDependencyUnblocks() {
        val tasks = listOf(task("t-a", status = Status.DONE), task("t-b", depends = listOf("t-a")))
        val c = computeAll(tasks).getValue("t-b")
        assertTrue(c.ready)
        assertTrue(c.blockers.isEmpty())
    }

    @Test
    fun danglingDependencyCountsAsBlocker() {
        val c = computeAll(listOf(task("t-b", depends = listOf("t-missing")))).getValue("t-b")
        assertFalse(c.ready)
        assertEquals(listOf("t-missing"), c.blockers)
    }

    @Test
    fun alsoDoneSatisfiesADependencyMissingFromTheSet() {
        // The graph-neutral archive supplement (ADR-014): an id absent from the
        // set but known done elsewhere is not a blocker.
        val c = computeAll(listOf(task("t-b", depends = listOf("t-arch"))), alsoDone = setOf("t-arch"))
            .getValue("t-b")
        assertTrue(c.ready)
        assertFalse(c.blocked)
        assertTrue(c.blockers.isEmpty())
    }

    @Test
    fun futureDeferSuppressesReadiness() {
        val c = computeAll(listOf(task("t-d", defer = future))).getValue("t-d")
        assertFalse(c.ready)
        assertTrue(c.deferred)
    }

    @Test
    fun pastDeferDoesNotSuppress() {
        val c = computeAll(listOf(task("t-d", defer = past))).getValue("t-d")
        assertTrue(c.ready)
        assertFalse(c.deferred)
    }

    @Test
    fun dueInPastIsOverdueUnlessClosed() {
        val open = computeAll(listOf(task("t-o", due = past))).getValue("t-o")
        assertTrue(open.overdue)
        val done = computeAll(listOf(task("t-c", status = Status.DONE, due = past))).getValue("t-c")
        assertFalse(done.overdue)
    }

    @Test
    fun dueNeverGatesReadiness() {
        val c = computeAll(listOf(task("t-o", due = past))).getValue("t-o")
        assertTrue(c.ready) // overdue but still actionable
    }

    @Test
    fun waitingWithElapsedDeferResurfaces() {
        val c = computeAll(listOf(task("t-w", status = Status.WAITING, defer = past))).getValue("t-w")
        assertTrue(c.resurfaced)
    }

    // --- Time-boundary edges, pinned with an injected clock (t-7ce4).
    // `now` is fixed so the boundary comparisons (defer `>`, due `<`, resurface
    // `<=`) are exercised at exactly `now` rather than at a live wall clock.

    private val now = Instant.parse("2026-07-09T12:00:00Z")

    @Test
    fun deferExactlyAtNowDoesNotSuppressReadiness() {
        // deferActive uses strict `>`, so defer == now is NOT active.
        val at = computeAll(listOf(task("t-d", defer = now.toString())), now = now).getValue("t-d")
        assertFalse(at.deferred)
        assertTrue(at.ready)
        // One second later is still in the future → deferred.
        val after = computeAll(listOf(task("t-d", defer = (now + 1.seconds).toString())), now = now).getValue("t-d")
        assertTrue(after.deferred)
        assertFalse(after.ready)
    }

    @Test
    fun dueExactlyAtNowIsNotOverdue() {
        // overdue uses strict `<`, so due == now is NOT yet overdue.
        val at = computeAll(listOf(task("t-o", due = now.toString())), now = now).getValue("t-o")
        assertFalse(at.overdue)
        // One second earlier has already passed → overdue.
        val before = computeAll(listOf(task("t-o", due = (now - 1.seconds).toString())), now = now).getValue("t-o")
        assertTrue(before.overdue)
    }

    @Test
    fun waitingDeferExactlyAtNowResurfaces() {
        // resurface uses `<=`, so a waiting task whose defer == now resurfaces.
        val at = computeAll(
            listOf(task("t-w", status = Status.WAITING, defer = now.toString())),
            now = now,
        ).getValue("t-w")
        assertTrue(at.resurfaced)
        // A defer one second in the future has not elapsed yet → no resurface.
        val after = computeAll(
            listOf(task("t-w", status = Status.WAITING, defer = (now + 1.seconds).toString())),
            now = now,
        ).getValue("t-w")
        assertFalse(after.resurfaced)
    }

    @Test
    fun dependentsAreTheInverseEdge() {
        val tasks = listOf(task("t-a"), task("t-b", depends = listOf("t-a")), task("t-c", depends = listOf("t-a")))
        val c = computeAll(tasks).getValue("t-a")
        assertEquals(listOf("t-b", "t-c"), c.dependents)
    }
}
