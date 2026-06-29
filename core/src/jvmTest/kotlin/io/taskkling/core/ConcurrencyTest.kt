package io.taskkling.core

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The write protocol's core safety property (PRD §7.1 step 3): every mutation
 * re-reads the file **fresh** under the lock and applies only its minimal field
 * change, so a concurrent edit to a *different* field is never clobbered. We
 * simulate the interleaving by editing one field directly on disk, then running
 * a mutation that touches another field, and assert both survive.
 */
class ConcurrencyTest {

    @Test
    fun differentFieldEditsBothSurvive() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "orig"))

        // A concurrent writer set `due` on disk after our caller last read the node.
        val current = ws.loadTask(id)!!
        ws.overwriteOnDisk(current.copy(due = "2030-01-01T00:00:00Z"))

        // Our mutation changes a different field; updateTask reads fresh, so it
        // must preserve the externally-set due.
        ws.setFields(id, SetArgs(priority = "high"))

        val merged = ws.loadTask(id)!!
        assertEquals("2030-01-01T00:00:00Z", merged.due, "externally-set due must survive")
        assertEquals(Priority.HIGH, merged.priority, "our priority edit must apply")
    }

    @Test
    fun sequentialLockedMutationsAllApply() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "seq"))
        ws.setFields(id, SetArgs(priority = "high"))
        ws.setFields(id, SetArgs(thread = "demo"))
        ws.appendBody(id, "note")
        val t = ws.loadTask(id)!!
        assertEquals(Priority.HIGH, t.priority)
        assertEquals("demo", t.thread)
        assertEquals("note", t.body)
    }
}
