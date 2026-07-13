package io.taskkling.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `add` treats status and the external requirement (`req`) as first-class,
 * independent create fields (ADR-018): a task may be born in any status and may
 * carry a requirement regardless of that status. Creating straight into a closed
 * state stamps `closed` under the same rule as a later transition.
 */
class AddStatusRequirementTest {

    @Test
    fun addDefaultsToOpenWithNoRequirementOrClosedStamp() {
        val ws = tempWorkspace()
        val t = ws.addTask(AddArgs(title = "plain")).task
        assertEquals(Status.OPEN, t.status)
        assertNull(t.waitingOn)
        assertNull(t.closed)
    }

    @Test
    fun addStatusWaitingWithReqCarriesBothIndependently() {
        val ws = tempWorkspace()
        val t = ws.addTask(AddArgs(title = "gated", status = "waiting", req = "legal sign-off")).task
        assertEquals(Status.WAITING, t.status)
        assertEquals("legal sign-off", t.waitingOn)
        assertNull(t.closed, "a waiting task is not closed")
    }

    @Test
    fun addOpenTaskMayStillCarryARequirement() {
        // The whole point of ADR-018: a requirement is not tied to the waiting status.
        val ws = tempWorkspace()
        val t = ws.addTask(AddArgs(title = "open but blocked", req = "a callback")).task
        assertEquals(Status.OPEN, t.status)
        assertEquals("a callback", t.waitingOn)
    }

    @Test
    fun addStatusDoneStampsClosedOnCreation() {
        val ws = tempWorkspace()
        val t = ws.addTask(AddArgs(title = "born done", status = "done")).task
        assertEquals(Status.DONE, t.status)
        assertNotNull(t.closed, "creating into done stamps closed")
    }

    @Test
    fun addStatusDroppedStampsClosedOnCreation() {
        val ws = tempWorkspace()
        val t = ws.addTask(AddArgs(title = "born dropped", status = "dropped")).task
        assertEquals(Status.DROPPED, t.status)
        assertNotNull(t.closed, "creating into dropped stamps closed")
    }

    @Test
    fun requirementSurvivesTheDoneReopenDropCycle() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "sticky req", req = "the note"))

        ws.markDone(id)
        assertEquals("the note", ws.loadTask(id)!!.waitingOn, "req survives done")

        ws.reopenTask(id)
        assertEquals("the note", ws.loadTask(id)!!.waitingOn, "req survives reopen")

        ws.markDropped(id)
        assertEquals("the note", ws.loadTask(id)!!.waitingOn, "req survives drop")
    }
}
