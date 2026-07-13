package io.taskkling.core

import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the `set`/`setFields` write path (PRD §10.4): the `--clear` arms and
 * empty-string-unsets equivalence, the two "title cannot be cleared" guards, the
 * unknown-field guard, priority-clear normalization, and the filename RE-SLUG on
 * a title change. The re-slug case is the sharpest: [updateTask] writes the new
 * path then deletes the old — an ordering bug there silently orphans or drops a
 * task, so we assert the old file is gone, the new file present, and the id stable.
 */
class SetFieldsTest {

    /** A task pre-loaded with every clearable optional field set. */
    private fun Workspace.addFullyPopulated(): String = addReturningId(
        AddArgs(
            title = "populated",
            thread = "demo",
            due = "2030-01-01",
            defer = "2029-12-01",
            priority = "high",
        ),
    )

    // --- each --clear arm ---------------------------------------------------

    @Test
    fun clearThreadUnsets() {
        val ws = tempWorkspace()
        val id = ws.addFullyPopulated()
        ws.setFields(id, SetArgs(clear = listOf("thread")))
        assertNull(ws.loadTask(id)!!.thread, "--clear thread must unset the thread")
    }

    @Test
    fun clearDueUnsets() {
        val ws = tempWorkspace()
        val id = ws.addFullyPopulated()
        ws.setFields(id, SetArgs(clear = listOf("due")))
        assertNull(ws.loadTask(id)!!.due, "--clear due must unset the due date")
    }

    @Test
    fun clearDeferUnsets() {
        val ws = tempWorkspace()
        val id = ws.addFullyPopulated()
        ws.setFields(id, SetArgs(clear = listOf("defer")))
        assertNull(ws.loadTask(id)!!.defer, "--clear defer must unset the defer date")
    }

    @Test
    fun clearPriorityResetsToNormal() {
        val ws = tempWorkspace()
        val id = ws.addFullyPopulated()
        ws.setFields(id, SetArgs(clear = listOf("priority")))
        assertEquals(Priority.NORMAL, ws.loadTask(id)!!.priority, "--clear priority resets to normal")
    }

    @Test
    fun clearMultipleFieldsInOneCall() {
        val ws = tempWorkspace()
        val id = ws.addFullyPopulated()
        ws.setFields(id, SetArgs(clear = listOf("thread", "due", "defer", "priority")))
        val t = ws.loadTask(id)!!
        assertNull(t.thread)
        assertNull(t.due)
        assertNull(t.defer)
        assertEquals(Priority.NORMAL, t.priority)
    }

    // --- empty-string-unsets is equivalent to --clear -----------------------

    @Test
    fun emptyStringUnsetsOptionalFields() {
        val ws = tempWorkspace()
        val id = ws.addFullyPopulated()
        // An explicit empty string unsets each optional field (equivalent to naming it in --clear).
        ws.setFields(id, SetArgs(thread = "", due = "", defer = "", priority = ""))
        val t = ws.loadTask(id)!!
        assertNull(t.thread, "empty --thread unsets")
        assertNull(t.due, "empty --due unsets")
        assertNull(t.defer, "empty --defer unsets")
        assertEquals(Priority.NORMAL, t.priority, "empty --priority resets to normal")
    }

    @Test
    fun blankThreadWithSurroundingWhitespaceUnsets() {
        val ws = tempWorkspace()
        val id = ws.addFullyPopulated()
        // Whitespace is trimmed first, so a whitespace-only value unsets too.
        ws.setFields(id, SetArgs(thread = "   "))
        assertNull(ws.loadTask(id)!!.thread, "whitespace-only --thread unsets after trim")
    }

    // --- error guards -------------------------------------------------------

    @Test
    fun titleCannotBeClearedViaEmptyString() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "keep me"))
        val err = assertFailsWith<TkError> { ws.setFields(id, SetArgs(title = "")) }
        assertEquals(ExitCode.USAGE, err.exit)
        assertEquals("keep me", ws.loadTask(id)!!.title, "failed clear must not have mutated the title")
    }

    @Test
    fun titleCannotBeClearedViaClearList() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "keep me"))
        val err = assertFailsWith<TkError> { ws.setFields(id, SetArgs(clear = listOf("title"))) }
        assertEquals(ExitCode.USAGE, err.exit)
        assertEquals("keep me", ws.loadTask(id)!!.title, "failed clear must not have mutated the title")
    }

    @Test
    fun unknownClearFieldIsUsageError() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "t"))
        val err = assertFailsWith<TkError> { ws.setFields(id, SetArgs(clear = listOf("bogus"))) }
        assertEquals(ExitCode.USAGE, err.exit)
        assertTrue("bogus" in (err.message ?: ""), "message should name the offending field")
    }

    @Test
    fun statusCannotBeCleared() {
        // There is no null status; --clear status is a usage error (ADR-018).
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "t"))
        val err = assertFailsWith<TkError> { ws.setFields(id, SetArgs(clear = listOf("status"))) }
        assertEquals(ExitCode.USAGE, err.exit)
        assertTrue("status" in (err.message ?: ""), "message should mention status")
        assertEquals(Status.OPEN, ws.loadTask(id)!!.status, "failed clear must not have mutated the status")
    }

    // --- status + external requirement as independent set fields (ADR-018) --

    @Test
    fun setStatusDoneStampsClosedAndKeepsRequirement() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "gated", req = "legal sign-off"))
        ws.setFields(id, SetArgs(status = "done"))
        val t = ws.loadTask(id)!!
        assertEquals(Status.DONE, t.status)
        assertNotNull(t.closed, "entering done stamps closed")
        assertEquals("legal sign-off", t.waitingOn, "the external requirement persists across the status change")
    }

    @Test
    fun setRequirementOnOpenTaskSucceedsWithoutChangingStatus() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "still open"))
        ws.setFields(id, SetArgs(req = "waiting on a callback"))
        val t = ws.loadTask(id)!!
        assertEquals(Status.OPEN, t.status, "setting req does not flip status (ADR-018)")
        assertEquals("waiting on a callback", t.waitingOn)
    }

    @Test
    fun clearRequirementUnsetsIt() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "t", req = "some reason"))
        ws.setFields(id, SetArgs(clear = listOf("req")))
        assertNull(ws.loadTask(id)!!.waitingOn, "--clear req unsets the external requirement")
    }

    @Test
    fun invalidPriorityValueIsValidationError() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "t"))
        val err = assertFailsWith<TkError> { ws.setFields(id, SetArgs(priority = "urgent")) }
        assertEquals(ExitCode.VALIDATION, err.exit)
    }

    // --- filename RE-SLUG on a title change ---------------------------------

    @Test
    fun titleChangeReSlugsFileKeepingIdStable() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "old title"))

        val oldName = ws.loadTaskFileName(id)
        assertEquals("$id--old-title.md", oldName)

        ws.setFields(id, SetArgs(title = "brand new title"))

        val fs = FileSystem.SYSTEM
        // The old slug file must be gone — an ordering bug would orphan it.
        assertFalse(fs.exists(ws.tasksDir / oldName), "old-slug file must be deleted after re-slug")
        // The new slug file must be present under the same id.
        val newName = "$id--brand-new-title.md"
        assertTrue(fs.exists(ws.tasksDir / newName), "new-slug file must exist after re-slug")

        // Exactly one active file for this id; the id is unchanged; the task still loads.
        val activeForId = fs.list(ws.tasksDir).filter { idOfFileName(it.name) == id }
        assertEquals(1, activeForId.size, "exactly one active file for the id after re-slug")
        val reloaded = ws.loadTask(id)!!
        assertEquals(id, reloaded.id, "id must be stable across a title re-slug")
        assertEquals("brand new title", reloaded.title)
    }

    @Test
    fun titleEditWithSameSlugDoesNotDropTheFile() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "same slug"))
        val name = ws.loadTaskFileName(id)

        // A cosmetic title tweak that slugifies to the identical name: newPath == path,
        // so updateTask must NOT delete the file it just wrote.
        ws.setFields(id, SetArgs(title = "Same Slug"))

        val fs = FileSystem.SYSTEM
        assertTrue(fs.exists(ws.tasksDir / name), "file must survive a no-op-slug title edit")
        assertEquals("Same Slug", ws.loadTask(id)!!.title)
    }

    /** The on-disk file name backing active task [id]. */
    private fun Workspace.loadTaskFileName(id: String): String =
        findActiveFile(id)!!.name
}
