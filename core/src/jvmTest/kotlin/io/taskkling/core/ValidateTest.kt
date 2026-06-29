package io.taskkling.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Preventive write-path validation + the validation-free delete (PRD §7.5, §9.5, §17). */
class ValidateTest {

    @Test
    fun danglingDependencyIsRejected() {
        val ws = tempWorkspace()
        assertFailsWith<TkError> { ws.addTask(AddArgs(title = "x", depends = listOf("t-zzzz"))) }
    }

    @Test
    fun selfDependencyIsRejected() {
        val ws = tempWorkspace()
        val a = ws.addReturningId(AddArgs(title = "a"))
        assertFailsWith<TkError> { ws.linkDepends(a, a) }
    }

    @Test
    fun cycleIsRejected() {
        val ws = tempWorkspace()
        val a = ws.addReturningId(AddArgs(title = "a"))
        val b = ws.addReturningId(AddArgs(title = "b", depends = listOf(a)))
        // a -> b would close the loop a -> b -> a
        assertFailsWith<TkError> { ws.linkDepends(a, b) }
    }

    @Test
    fun waitingOnRequiresWaitingStatus() {
        val ws = tempWorkspace()
        val t = Task(id = "t-xxxx", title = "x", status = Status.OPEN, waitingOn = "reason", created = "2026-01-01T00:00:00Z")
        assertFailsWith<TkError> { ws.validateInvariants(t) }
    }

    @Test
    fun deleteIsValidationFreeAndPrunesDependents() {
        val ws = tempWorkspace()
        val a = ws.addReturningId(AddArgs(title = "a"))
        val b = ws.addReturningId(AddArgs(title = "b", depends = listOf(a)))
        ws.deleteTask(a) // must not throw despite b depending on a
        assertTrue(ws.loadTask(b)!!.depends.isEmpty(), "dependent edge should be pruned")
        assertNotNull(ws.fileFor(ws.trashDir, a), "deleted node should land in trash")
    }

    @Test
    fun restoreReturnsNodeWithoutRewiringSeveredEdges() {
        val ws = tempWorkspace()
        val a = ws.addReturningId(AddArgs(title = "a"))
        val b = ws.addReturningId(AddArgs(title = "b", depends = listOf(a)))
        ws.deleteTask(a)
        ws.restoreTask(a)
        assertNotNull(ws.loadTask(a), "node should be active again")
        assertTrue(ws.loadTask(b)!!.depends.isEmpty(), "severed inbound edge must NOT be re-added")
    }

    @Test
    fun restoreDropsDanglingOwnEdges() {
        val ws = tempWorkspace()
        val a = ws.addReturningId(AddArgs(title = "a"))
        val b = ws.addReturningId(AddArgs(title = "b", depends = listOf(a)))
        ws.deleteTask(b)  // b -> a edge lives in b's trashed file
        ws.deleteTask(a)  // now a is gone from the active set
        val result = ws.restoreTask(b)
        assertEquals(listOf(a), result.droppedEdges, "edge to a (now absent) should be dropped + reported")
        assertTrue(ws.loadTask(b)!!.depends.isEmpty())
    }
}
