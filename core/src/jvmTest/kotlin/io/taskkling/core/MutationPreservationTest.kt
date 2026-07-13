package io.taskkling.core

import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The mutation verbs make quiet *preservation* promises in their docs that no
 * test guarded until now (PRD §10.5):
 *   - [waitTask] leaves an existing `defer`/`waiting_on` untouched when its
 *     `--until`/`--req` flag is omitted (each field is independently sticky);
 *   - [deleteTask] keeps an already-present `closed` stamp instead of re-minting
 *     one (`closed ?: nowUtc()`);
 *   - [restoreTask] works from `archive/` (not just `trash/`), and its
 *     `done`/`dropped` -> `open` reopen preserves `waiting_on` (independent of
 *     status, ADR-018), as does a task that was NOT closed;
 *   - [reopenTask] returns a closed task to `open`, clearing `closed` but
 *     preserving `waiting_on` (ADR-018).
 * These are the branches a naive rewrite would silently drop.
 */
class MutationPreservationTest {

    // --- local helpers (do not touch the shared tempWorkspace() fixture) -------

    /** Plant [task] straight into [dir] (bypassing the validated mutation path) so a
     *  test can seed trash/archive entries — or invariant-violating states — directly. */
    private fun Workspace.plant(dir: Path, task: Task) {
        val fs = FileSystem.SYSTEM
        fs.createDirectories(dir)
        fs.write(dir / task.fileName()) { writeUtf8(task.toMarkdown()) }
    }

    /** Parse task [id] out of [dir] (`archive/`/`trash/`), or null if absent. */
    private fun Workspace.taskInDir(dir: Path, id: String): Task? {
        val p = fileFor(dir, id) ?: return null
        return parseTask(p.name, FileSystem.SYSTEM.read(p) { readUtf8() })
    }

    // --- waitTask: defer / waiting_on are each preserved when omitted ----------

    @Test
    fun waitPreservesBothDeferAndWaitingOnWhenNeitherFlagIsGiven() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "on hold"))
        // Seed both fields through the verb itself, then re-wait with no flags.
        ws.waitTask(id, until = "2026-08-01", req = "alice")

        val again = ws.waitTask(id, until = null, req = null).task

        assertEquals(Status.WAITING, again.status)
        assertEquals("2026-08-01T00:00:00Z", again.defer, "defer survives a flag-less re-wait")
        assertEquals("alice", again.waitingOn, "waiting_on survives a flag-less re-wait")
    }

    @Test
    fun waitUpdatesOnlyUntilLeavingWaitingOnIntact() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "on hold"))
        ws.waitTask(id, until = "2026-08-01", req = "alice")

        val moved = ws.waitTask(id, until = "2026-09-15", req = null).task

        assertEquals("2026-09-15T00:00:00Z", moved.defer, "--until replaces the defer")
        assertEquals("alice", moved.waitingOn, "waiting_on is left untouched when --req is omitted")
    }

    @Test
    fun waitUpdatesOnlyWaitingOnLeavingDeferIntact() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "on hold"))
        ws.waitTask(id, until = "2026-08-01", req = "alice")

        val moved = ws.waitTask(id, until = null, req = "bob").task

        assertEquals("bob", moved.waitingOn, "--req replaces waiting_on")
        assertEquals("2026-08-01T00:00:00Z", moved.defer, "defer is left untouched when --until is omitted")
    }

    // --- deleteTask: an existing closed stamp is preserved, else freshly minted -

    @Test
    fun deletePreservesAnAlreadyPresentClosedStamp() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "already closed"))
        // Plant a controlled closed stamp directly (markDone would mint a fresh one).
        val stamp = "2026-03-04T05:06:07Z"
        ws.overwriteOnDisk(ws.loadTask(id)!!.copy(status = Status.DONE, closed = stamp))

        val trashed = ws.deleteTask(id).task

        assertEquals(stamp, trashed.closed, "delete must not overwrite an existing closed stamp")
        assertEquals(stamp, ws.taskInDir(ws.trashDir, id)?.closed, "and the on-disk trash file keeps it too")
    }

    @Test
    fun deleteStampsClosedWhenTheTaskHasNone() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "still open"))
        assertNull(ws.loadTask(id)!!.closed, "precondition: an open task carries no closed stamp")

        val trashed = ws.deleteTask(id).task

        assertNotNull(trashed.closed, "delete stamps a fresh closed on an unclosed task")
    }

    // --- restoreTask: from archive/, and the reopen vs. keep-status branches ----

    @Test
    fun restoreFromArchiveReopensADoneTaskAndClearsClosed() {
        val ws = tempWorkspace()
        // Only the trash path is exercised elsewhere; seed the archive leg here.
        ws.plant(
            ws.archiveDir,
            Task(id = "t-arch", title = "swept", status = Status.DONE, created = "2026-01-01T00:00:00Z", closed = "2026-02-02T00:00:00Z"),
        )

        val result = ws.restoreTask("t-arch")

        assertEquals(Status.OPEN, result.task.status, "a done archived task returns as open")
        assertNull(result.task.closed, "restore clears the closed stamp")
        assertNotNull(ws.findActiveFile("t-arch"), "task is back in the active set")
        assertNull(ws.fileFor(ws.archiveDir, "t-arch"), "and gone from archive/")
    }

    @Test
    fun restoreReopenPreservesWaitingOn() {
        val ws = tempWorkspace()
        // A dropped task carrying waiting_on: the reopen flips status to open but the
        // external requirement is independent of status (ADR-018) and rides through.
        ws.plant(
            ws.archiveDir,
            Task(
                id = "t-wo",
                title = "had a waiter",
                status = Status.DROPPED,
                waitingOn = "someone",
                created = "2026-01-01T00:00:00Z",
                closed = "2026-02-02T00:00:00Z",
            ),
        )

        val restored = ws.restoreTask("t-wo").task

        assertEquals(Status.OPEN, restored.status, "dropped reopens to open")
        assertEquals("someone", restored.waitingOn, "reopen preserves waiting_on (ADR-018)")
    }

    @Test
    fun restoreOfANonClosedTaskKeepsItsStatusAndWaitingOn() {
        val ws = tempWorkspace()
        // A waiting (not done/dropped) task must NOT be reopened by restore:
        // its status and waiting_on ride through unchanged, only closed is cleared.
        ws.plant(
            ws.trashDir,
            Task(
                id = "t-wait",
                title = "still waiting",
                status = Status.WAITING,
                waitingOn = "review",
                created = "2026-01-01T00:00:00Z",
                closed = "2026-02-02T00:00:00Z",
            ),
        )

        val restored = ws.restoreTask("t-wait").task

        assertEquals(Status.WAITING, restored.status, "a non-closed task is restored as-is, not reopened")
        assertEquals("review", restored.waitingOn, "its waiting_on is preserved")
        assertNull(restored.closed, "restore always clears closed")
    }

    // --- reopenTask verb: closed -> open, clearing closed, preserving waiting_on -

    @Test
    fun reopenReturnsADoneTaskToOpenClearingClosedButKeepingWaitingOn() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "finished"))
        // Plant a done task that also carries waiting_on to prove reopen clears the
        // closed stamp but leaves the (status-independent, ADR-018) requirement alone.
        ws.overwriteOnDisk(
            ws.loadTask(id)!!.copy(status = Status.DONE, closed = "2026-02-02T00:00:00Z", waitingOn = "ghost"),
        )

        val reopened = ws.reopenTask(id).task

        assertEquals(Status.OPEN, reopened.status)
        assertNull(reopened.closed, "reopen clears the closed stamp")
        assertEquals("ghost", reopened.waitingOn, "reopen preserves waiting_on (ADR-018)")
    }

    @Test
    fun reopenReturnsADroppedTaskToOpen() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "abandoned"))
        ws.markDropped(id)
        assertNotNull(ws.loadTask(id)!!.closed, "precondition: drop stamped a closed")

        val reopened = ws.reopenTask(id).task

        assertEquals(Status.OPEN, reopened.status, "a dropped task reopens to open")
        assertNull(reopened.closed, "reopen clears the closed stamp")
    }
}
