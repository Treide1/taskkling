package io.taskkling.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertContains
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `add --batch`'s core contract (t-zsh6): atomic multi-create with intra-batch dep wiring
 * by local `ref` handle. The properties that matter are the ones a caller cannot check
 * themselves — that a rejected batch wrote NOTHING, and that ids come back in input order.
 */
class BatchAddTest {

    private fun rec(title: String, ref: String? = null, depends: List<String> = emptyList(), body: String? = null) =
        BatchAddArgs(AddArgs(title = title, depends = depends, body = body), ref = ref)

    // --- the motivating case: a milestone + its gate, one call (the DoD) --------------------

    @Test
    fun createsWorkTasksAndAGateDependingOnThemInOneCall() {
        val ws = tempWorkspace()
        val result = ws.addTasks(
            listOf(
                rec("Work one", ref = "w1", body = "# Brief\n\nmulti-line markdown body"),
                rec("Work two", ref = "w2", body = "second body"),
                rec("Milestone gate", ref = "gate", depends = listOf("w1", "w2")),
            ),
        )

        assertEquals(listOf("Work one", "Work two", "Milestone gate"), result.tasks.map { it.title })
        val (w1, w2, gate) = result.tasks
        // The gate's edges point at the ids minted for the EARLIER rows — the whole point.
        assertEquals(listOf(w1.id, w2.id), gate.depends)
        assertTrue(gate.depends.all { it.startsWith("t-") }, "refs must resolve to minted ids, not survive as handles")
        // Bodies round-trip through the same AddArgs path a single `add` uses.
        assertEquals("# Brief\n\nmulti-line markdown body", ws.loadTask(w1.id)!!.body)
        assertEquals("second body", ws.loadTask(w2.id)!!.body)
        // And all three are actually on disk, wired.
        assertEquals(listOf(w1.id, w2.id), ws.loadTask(gate.id)!!.depends)
    }

    @Test
    fun mintsDistinctIdsAndReturnsThemInInputOrder() {
        val ws = tempWorkspace()
        val titles = (1..12).map { "Task $it" }
        val result = ws.addTasks(titles.map { rec(it) })
        assertEquals(titles, result.tasks.map { it.title }, "results must come back in input order")
        assertEquals(12, result.tasks.map { it.id }.toSet().size, "every row needs its own id")
    }

    @Test
    fun dependsMayAlsoReferenceATaskThatAlreadyExists() {
        val ws = tempWorkspace()
        val existing = ws.addReturningId(AddArgs(title = "Pre-existing"))
        val result = ws.addTasks(listOf(rec("New", depends = listOf(existing))))
        assertEquals(listOf(existing), result.tasks.single().depends)
    }

    @Test
    fun aRefMayBeReferencedByMoreThanOneLaterRecord() {
        val ws = tempWorkspace()
        val result = ws.addTasks(
            listOf(rec("Base", ref = "base"), rec("A", depends = listOf("base")), rec("B", depends = listOf("base"))),
        )
        val baseId = result.tasks[0].id
        assertEquals(listOf(baseId), result.tasks[1].depends)
        assertEquals(listOf(baseId), result.tasks[2].depends)
    }

    // --- atomicity: a rejected batch must write NOTHING -------------------------------------

    @Test
    fun aFailingRecordRollsBackTheWholeBatch() {
        val ws = tempWorkspace()
        // Row 2 of 3 is invalid. Without validate-all-then-write, rows 0 and 1 would already
        // be on disk when row 2 throws — this asserts the store is untouched instead.
        val e = assertFailsWith<TkError> {
            ws.addTasks(
                listOf(
                    rec("Would-be first"),
                    rec("Would-be second"),
                    BatchAddArgs(AddArgs(title = "Bad priority", priority = "urgent")),
                ),
            )
        }
        assertEquals(ExitCode.VALIDATION, e.exit)
        assertContains(e.message!!, "batch record 2", message = "the error must name the offending row")
        assertContains(e.message!!, "invalid priority")
        assertEquals(emptyList(), ws.loadTasks(), "a rejected batch must leave the store EMPTY — no partial write")
    }

    @Test
    fun aFailingBatchLeavesAPopulatedStoreExactlyAsItWas() {
        val ws = tempWorkspace()
        val existing = ws.addReturningId(AddArgs(title = "Untouched"))
        assertFailsWith<TkError> {
            ws.addTasks(listOf(rec("New one"), rec("Dangling", depends = listOf("t-nope"))))
        }
        assertEquals(listOf(existing), ws.loadTasks().map { it.id }, "only the pre-existing task may remain")
    }

    @Test
    fun anEmptyTitleAnywhereRejectsTheBatch() {
        val ws = tempWorkspace()
        val e = assertFailsWith<TkError> { ws.addTasks(listOf(rec("Fine"), rec("   "))) }
        assertEquals(ExitCode.VALIDATION, e.exit)
        assertContains(e.message!!, "batch record 1")
        assertContains(e.message!!, "title must not be empty")
        assertEquals(emptyList(), ws.loadTasks())
    }

    @Test
    fun anEmptyBatchIsRejected() {
        val ws = tempWorkspace()
        val e = assertFailsWith<TkError> { ws.addTasks(emptyList()) }
        assertEquals(ExitCode.VALIDATION, e.exit)
        assertContains(e.message!!, "no records")
    }

    // --- ref resolution failures ------------------------------------------------------------

    @Test
    fun aForwardReferenceIsRejectedAndNamesTheRowAndRef() {
        val ws = tempWorkspace()
        // The gate is declared BEFORE the work it depends on. Backward-refs-only is what makes
        // an intra-batch cycle structurally impossible, so this must be refused, not resolved.
        val e = assertFailsWith<TkError> {
            ws.addTasks(listOf(rec("Gate", depends = listOf("w1")), rec("Work", ref = "w1")))
        }
        assertEquals(ExitCode.VALIDATION, e.exit)
        assertContains(e.message!!, "batch record 0")
        assertContains(e.message!!, "'w1'")
        assertContains(e.message!!, "LATER", message = "a forward ref must be distinguished from a typo")
        assertEquals(emptyList(), ws.loadTasks())
    }

    @Test
    fun anUnknownRefIsRejectedSeparatelyFromAForwardRef() {
        val ws = tempWorkspace()
        val e = assertFailsWith<TkError> { ws.addTasks(listOf(rec("A", depends = listOf("typo")))) }
        assertEquals(ExitCode.VALIDATION, e.exit)
        assertContains(e.message!!, "batch record 0")
        assertContains(e.message!!, "unknown id or ref 'typo'")
    }

    @Test
    fun aDuplicateRefIsRejected() {
        val ws = tempWorkspace()
        // Otherwise the second silently shadows the first and later edges wire to the wrong task.
        val e = assertFailsWith<TkError> {
            ws.addTasks(listOf(rec("A", ref = "dup"), rec("B", ref = "dup")))
        }
        assertEquals(ExitCode.VALIDATION, e.exit)
        assertContains(e.message!!, "batch record 1")
        assertContains(e.message!!, "duplicate ref 'dup'")
        assertEquals(emptyList(), ws.loadTasks())
    }

    @Test
    fun aSelfReferenceIsRejected() {
        val ws = tempWorkspace()
        val e = assertFailsWith<TkError> { ws.addTasks(listOf(rec("Loop", ref = "me", depends = listOf("me")))) }
        assertEquals(ExitCode.VALIDATION, e.exit)
        assertContains(e.message!!, "own ref 'me'")
        assertEquals(emptyList(), ws.loadTasks())
    }

    // --- the batch shares the single-add path --------------------------------------------

    @Test
    fun batchBodiesAreStrippedOfALeadingBomLikeSingleAdd() {
        val ws = tempWorkspace()
        val result = ws.addTasks(listOf(rec("Bom body", body = "﻿piped from PowerShell")))
        assertEquals("piped from PowerShell", ws.loadTask(result.tasks.single().id)!!.body)
    }

    @Test
    fun batchRecordsCarryTheFullAddArgsSurface() {
        val ws = tempWorkspace()
        val result = ws.addTasks(
            listOf(
                BatchAddArgs(
                    AddArgs(
                        title = "Everything",
                        thread = "dx",
                        status = "waiting",
                        req = "legal sign-off",
                        due = "2026-07-31",
                        defer = "2026-07-01",
                        priority = "high",
                        body = "body",
                    ),
                ),
            ),
        )
        val t = ws.loadTask(result.tasks.single().id)!!
        assertEquals("dx", t.thread)
        assertEquals(Status.WAITING, t.status)
        assertEquals("legal sign-off", t.waitingOn)
        assertEquals(Priority.HIGH, t.priority)
        assertTrue(t.due!!.startsWith("2026-07-31"), "due normalizes through the same path as add: ${t.due}")
        assertTrue(t.defer!!.startsWith("2026-07-01"), "defer normalizes through the same path as add: ${t.defer}")
    }

    @Test
    fun exportAfterReflectsTheWholeBatch() {
        val ws = tempWorkspace()
        val result = ws.addTasks(listOf(rec("A", ref = "a"), rec("B", depends = listOf("a"))), exportAfter = true)
        val export = result.export!!
        assertEquals(2, export.tasks.size, "the export must be computed after ALL rows are written")
    }
}
