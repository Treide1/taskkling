package io.taskkling.core

import kotlinx.datetime.Instant
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [cleanup] (PRD §9.5, §10.7) is the only core operation that PERMANENTLY deletes
 * files, so it earns exhaustive coverage. Two independent halves:
 *   - the always-on sweep of closed (`done`/`dropped`) active tasks `tasks/` -> `archive/`,
 *     which must never touch `open`/`waiting` tasks; and
 *   - the `--delete-before` purge of `trash/` (and, with `--include-archive`, `archive/`)
 *     entries whose `closed` stamp is chronologically before the cutoff.
 * A final test pins the load-bearing claim the purge rests on: on canonical
 * fixed-width ISO-8601 UTC stamps, lexicographic `<` == chronological `<`.
 */
class MaintenanceCleanupTest {

    // --- local helpers (do not touch the shared tempWorkspace() fixture) -------

    /** Write [task] straight into [dir] (bypassing the mutation path) so a test
     *  can plant trash/archive entries with a controlled `closed` stamp. */
    private fun Workspace.plant(dir: Path, task: Task) {
        val fs = FileSystem.SYSTEM
        fs.createDirectories(dir)
        fs.write(dir / task.fileName()) { writeUtf8(task.toMarkdown()) }
    }

    /** Parse the task [id] out of [dir] (`archive/`/`trash/`), or null if absent. */
    private fun Workspace.taskInDir(dir: Path, id: String): Task? {
        val p = fileFor(dir, id) ?: return null
        return parseTask(p.name, FileSystem.SYSTEM.read(p) { readUtf8() })
    }

    /** A closed task with an explicit `closed` stamp, for planting in trash/archive. */
    private fun closedTask(id: String, closed: String, status: Status = Status.DONE): Task =
        Task(id = id, title = id, status = status, created = "2026-01-01T00:00:00Z", closed = closed)

    // --- sweep: closed active tasks -> archive/ --------------------------------

    @Test
    fun sweepMovesDoneAndDroppedToArchive() {
        val ws = tempWorkspace()
        val done = ws.addReturningId(AddArgs(title = "finished"))
        val dropped = ws.addReturningId(AddArgs(title = "abandoned"))
        ws.markDone(done)
        ws.markDropped(dropped)

        val result = ws.cleanup(deleteBefore = null, includeArchive = false)

        assertEquals(2, result.archived, "both closed tasks swept")
        assertEquals(0, result.purged, "no purge without --delete-before")
        // gone from the active set...
        assertNull(ws.findActiveFile(done), "done left tasks/")
        assertNull(ws.findActiveFile(dropped), "dropped left tasks/")
        // ...and present, intact, in archive/
        assertEquals(Status.DONE, ws.taskInDir(ws.archiveDir, done)?.status)
        assertEquals(Status.DROPPED, ws.taskInDir(ws.archiveDir, dropped)?.status)
    }

    @Test
    fun sweepNeverTouchesOpenOrWaiting() {
        val ws = tempWorkspace()
        val open = ws.addReturningId(AddArgs(title = "still open"))
        val waiting = ws.addReturningId(AddArgs(title = "on hold"))
        ws.waitTask(waiting, until = null, on = "someone")

        val result = ws.cleanup(deleteBefore = null, includeArchive = false)

        assertEquals(0, result.archived, "no closed task to sweep")
        assertNotNull(ws.findActiveFile(open), "open task stays active")
        assertNotNull(ws.findActiveFile(waiting), "waiting task stays active")
        assertNull(ws.fileFor(ws.archiveDir, open), "open never archived")
        assertNull(ws.fileFor(ws.archiveDir, waiting), "waiting never archived")
    }

    @Test
    fun sweepIsANoOpOnAnAllOpenBacklog() {
        val ws = tempWorkspace()
        ws.addReturningId(AddArgs(title = "a"))
        ws.addReturningId(AddArgs(title = "b"))
        val result = ws.cleanup(deleteBefore = null, includeArchive = false)
        assertEquals(0, result.archived)
        assertEquals(0, result.purged)
    }

    // --- purge: --delete-before against trash/ (and, opt-in, archive/) ----------

    @Test
    fun deleteBeforePurgesTrashEntriesBelowCutoff() {
        val ws = tempWorkspace()
        ws.plant(ws.trashDir, closedTask("t-old", "2026-06-01T00:00:00Z"))
        ws.plant(ws.trashDir, closedTask("t-new", "2026-08-01T00:00:00Z"))

        val result = ws.cleanup(deleteBefore = "2026-07-01T00:00:00Z", includeArchive = false)

        assertEquals(1, result.purged, "only the pre-cutoff trash entry purged")
        assertEquals(0, result.archived, "nothing to sweep")
        assertNull(ws.fileFor(ws.trashDir, "t-old"), "stale trash entry deleted")
        assertNotNull(ws.fileFor(ws.trashDir, "t-new"), "recent trash entry survives")
    }

    @Test
    fun deleteBeforeUsesAStrictLessThanCutoff() {
        val ws = tempWorkspace()
        // closed exactly AT the cutoff must NOT be purged (`closed < cutoff` is strict).
        ws.plant(ws.trashDir, closedTask("t-eq", "2026-07-01T00:00:00Z"))
        // one tick earlier must be purged.
        ws.plant(ws.trashDir, closedTask("t-lt", "2026-06-30T23:59:59Z"))

        val result = ws.cleanup(deleteBefore = "2026-07-01T00:00:00Z", includeArchive = false)

        assertEquals(1, result.purged, "boundary is exclusive: only the strictly-earlier entry goes")
        assertNotNull(ws.fileFor(ws.trashDir, "t-eq"), "closed == cutoff is kept")
        assertNull(ws.fileFor(ws.trashDir, "t-lt"), "closed < cutoff is purged")
    }

    @Test
    fun deleteBeforeAcceptsADateOnlyCutoff() {
        val ws = tempWorkspace()
        // A bare date normalizes to midnight UTC, so a same-day-but-later stamp survives.
        ws.plant(ws.trashDir, closedTask("t-prev", "2026-06-30T12:00:00Z"))
        ws.plant(ws.trashDir, closedTask("t-day", "2026-07-01T12:00:00Z"))

        val result = ws.cleanup(deleteBefore = "2026-07-01", includeArchive = false)

        assertEquals(1, result.purged)
        assertNull(ws.fileFor(ws.trashDir, "t-prev"))
        assertNotNull(ws.fileFor(ws.trashDir, "t-day"), "same-day past midnight is not before the cutoff")
    }

    @Test
    fun deleteBeforeLeavesArchiveUntouchedByDefault() {
        val ws = tempWorkspace()
        ws.plant(ws.trashDir, closedTask("t-trash", "2026-01-01T00:00:00Z"))
        ws.plant(ws.archiveDir, closedTask("t-arch", "2026-01-01T00:00:00Z"))

        val result = ws.cleanup(deleteBefore = "2026-07-01T00:00:00Z", includeArchive = false)

        assertEquals(1, result.purged, "only trash is in scope without --include-archive")
        assertNull(ws.fileFor(ws.trashDir, "t-trash"), "old trash entry purged")
        assertNotNull(ws.fileFor(ws.archiveDir, "t-arch"), "archive is spared by default")
    }

    @Test
    fun includeArchiveExtendsThePurgeToArchive() {
        val ws = tempWorkspace()
        ws.plant(ws.trashDir, closedTask("t-trash", "2026-01-01T00:00:00Z"))
        ws.plant(ws.archiveDir, closedTask("t-arch", "2026-01-01T00:00:00Z"))
        ws.plant(ws.archiveDir, closedTask("t-arch-new", "2026-09-01T00:00:00Z"))

        val result = ws.cleanup(deleteBefore = "2026-07-01T00:00:00Z", includeArchive = true)

        assertEquals(2, result.purged, "both the old trash and old archive entries purged")
        assertNull(ws.fileFor(ws.trashDir, "t-trash"))
        assertNull(ws.fileFor(ws.archiveDir, "t-arch"))
        assertNotNull(ws.fileFor(ws.archiveDir, "t-arch-new"), "post-cutoff archive entry survives")
    }

    @Test
    fun purgeSparesATrashEntryWithNoClosedStamp() {
        val ws = tempWorkspace()
        // A trash entry lacking a `closed` stamp has no age, so the cutoff can't
        // reach it — the `t.closed != null` guard keeps a destructive purge safe.
        ws.plant(
            ws.trashDir,
            Task(id = "t-nostamp", title = "t-nostamp", status = Status.DONE, created = "2026-01-01T00:00:00Z"),
        )

        val result = ws.cleanup(deleteBefore = "2999-01-01T00:00:00Z", includeArchive = false)

        assertEquals(0, result.purged, "an unstamped trash entry is never purged, even under a far-future cutoff")
        assertNotNull(ws.fileFor(ws.trashDir, "t-nostamp"), "unstamped entry survives")
    }

    @Test
    fun sweepAndPurgeComposeInOneRun() {
        val ws = tempWorkspace()
        // an active closed task to sweep...
        val done = ws.addReturningId(AddArgs(title = "done now"))
        ws.markDone(done)
        // ...and a stale trash entry to purge, in the same call.
        ws.plant(ws.trashDir, closedTask("t-stale", "2026-01-01T00:00:00Z"))

        val result = ws.cleanup(deleteBefore = "2026-07-01T00:00:00Z", includeArchive = false)

        assertEquals(1, result.archived, "the freshly-done task was swept")
        assertEquals(1, result.purged, "the stale trash entry was purged")
        assertNotNull(ws.taskInDir(ws.archiveDir, done), "swept task now in archive")
        assertNull(ws.fileFor(ws.trashDir, "t-stale"), "stale trash entry gone")
    }

    // --- edge safety: archive purge must not strand active dependents (t-5z3y) --

    @Test
    fun purgeRetainsAnArchivedDepStillReferencedByAnActiveTask() {
        val ws = tempWorkspace()
        // The exact repro from t-5z3y: dep -> archived done; gate depends on it.
        val dep = ws.addReturningId(AddArgs(title = "groundwork"))
        val gate = ws.addReturningId(AddArgs(title = "gate", depends = listOf(dep)))
        ws.markDone(dep)
        ws.cleanup(deleteBefore = null, includeArchive = false) // sweep dep -> archive/

        // A far-future cutoff would purge the archived dep — but the gate still needs it.
        val result = ws.cleanup(deleteBefore = "2999-01-01T00:00:00Z", includeArchive = true)

        assertEquals(0, result.purged, "the still-referenced archive entry is not purged")
        assertEquals(1, result.retained, "it is retained and reported")
        assertNotNull(ws.fileFor(ws.archiveDir, dep), "archived dep survives the purge")
        // And the point of the fix: the gate is not stranded — it computes ready and closes.
        val c = ws.computeAll(ws.loadTasks()).getValue(gate)
        assertTrue(c.ready, "gate keeps its satisfied archived edge")
        assertEquals(Status.DONE, ws.markDone(gate).task.status, "closing the gate still validates")
    }

    @Test
    fun purgePurgesAnArchivedTaskOnceNoActiveTaskDependsOnIt() {
        val ws = tempWorkspace()
        val dep = ws.addReturningId(AddArgs(title = "groundwork"))
        val gate = ws.addReturningId(AddArgs(title = "gate", depends = listOf(dep)))
        ws.markDone(dep)
        ws.cleanup(deleteBefore = null, includeArchive = false)
        ws.deleteTask(gate) // the only dependent leaves the active set

        val result = ws.cleanup(deleteBefore = "2999-01-01T00:00:00Z", includeArchive = true)

        // Both the now-unreferenced archived dep and the trashed gate are old enough to go.
        assertEquals(2, result.purged, "with no active dependent, the archived entry is purgeable")
        assertEquals(0, result.retained)
        assertNull(ws.fileFor(ws.archiveDir, dep), "unreferenced archive entry purged")
    }

    @Test
    fun purgeRetainsOnlyTheReferencedArchiveEntriesInAMixedRun() {
        val ws = tempWorkspace()
        val kept = ws.addReturningId(AddArgs(title = "still needed"))
        ws.addReturningId(AddArgs(title = "gate", depends = listOf(kept)))
        val loose = ws.addReturningId(AddArgs(title = "nobody needs me"))
        ws.markDone(kept)
        ws.markDone(loose)
        ws.cleanup(deleteBefore = null, includeArchive = false) // both -> archive/

        val result = ws.cleanup(deleteBefore = "2999-01-01T00:00:00Z", includeArchive = true)

        assertEquals(1, result.purged, "the unreferenced archived task goes")
        assertEquals(1, result.retained, "the referenced archived task stays")
        assertNotNull(ws.fileFor(ws.archiveDir, kept), "referenced entry retained")
        assertNull(ws.fileFor(ws.archiveDir, loose), "unreferenced entry purged")
    }

    @Test
    fun trashPurgeIsNeverGuardedByActiveDepends() {
        val ws = tempWorkspace()
        // A trash id can never be a valid edge target (delete cascade-prunes), but
        // plant a same-named active edge anyway to prove the guard is archive-only.
        val dep = ws.addReturningId(AddArgs(title = "dep"))
        ws.addReturningId(AddArgs(title = "gate", depends = listOf(dep)))
        ws.plant(ws.trashDir, closedTask(dep, "2026-01-01T00:00:00Z"))

        val result = ws.cleanup(deleteBefore = "2999-01-01T00:00:00Z", includeArchive = true)

        assertEquals(1, result.purged, "the trash entry purges despite the id being referenced")
        assertEquals(0, result.retained, "trash is out of the edge guard's scope")
        assertNull(ws.fileFor(ws.trashDir, dep))
    }

    // --- the load-bearing invariant: lexicographic == chronological -------------

    @Test
    fun lexicographicCompareEqualsChronologicalOnIsoUtcStamps() {
        // Canonical, fixed-width ISO-8601 UTC stamps as taskkling mints them
        // (`nowUtc`, seconds precision, always `Z`). Includes single-digit->double-digit
        // rollovers (month 9->10, day 9->10, hour 9->10) — the classic place a naive
        // string sort would diverge from time if the fields weren't zero-padded.
        val stamps = listOf(
            "2025-12-31T23:59:59Z",
            "2026-01-01T00:00:00Z",
            "2026-06-09T09:09:09Z",
            "2026-06-09T10:00:00Z",
            "2026-06-10T00:00:00Z",
            "2026-09-30T23:59:59Z",
            "2026-10-01T00:00:00Z",
            "2027-01-01T00:00:00Z",
        )
        for (a in stamps) {
            for (b in stamps) {
                val lexical = a < b
                val chronological = Instant.parse(a) < Instant.parse(b)
                assertEquals(
                    chronological, lexical,
                    "string compare of '$a' < '$b' must agree with instant compare",
                )
            }
        }

        // Sorting by string must equal sorting by instant.
        val shuffled = stamps.shuffled()
        assertEquals(
            shuffled.sortedBy { Instant.parse(it) },
            shuffled.sorted(),
            "lexicographic sort reproduces the chronological order",
        )
    }
}
