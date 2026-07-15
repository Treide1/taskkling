package io.taskkling.ui

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the store's orchestration — the part of the UI that was previously only
 * reachable by composing a window (t-82d4). No Compose UI is instantiated: [AppStore] holds
 * snapshot state but composes nothing, and [FakeClient] stands in for the subprocess.
 *
 * Every coroutine runs on the test scheduler (the store's scope AND its injected `io`), so
 * `advanceUntilIdle()` — not a timeout — is what "the CLI call finished" means here, and the
 * busy flag can be observed mid-flight deterministically.
 */
class AppStoreTest {

    /**
     * A store wired to [client], with both its scope and its IO hop on the test scheduler, so
     * `advanceUntilIdle()` drives it. The scope is the TestScope itself, NOT `backgroundScope`:
     * background coroutines only run while the test body is suspended, and these tests assert
     * on state between synchronous calls.
     */
    private fun TestScope.storeOf(client: FakeClient): AppStore =
        AppStore(client, this, StandardTestDispatcher(testScheduler))

    private fun seeded() = FakeClient(listOf(task("t-a", depends = listOf("t-b")), task("t-b")))

    /** A loaded store: initial export done, nothing in flight. */
    private fun TestScope.loaded(client: FakeClient = seeded()): AppStore =
        storeOf(client).also {
            it.start()
            advanceUntilIdle()
        }

    private fun lastToast(store: AppStore): String = store.toasts.items.last().text

    @Test
    fun `start loads the export`() = runTest {
        val store = loaded()
        assertEquals(listOf("t-a", "t-b"), store.export?.tasks?.map { it.id })
        assertNull(store.error)
    }

    @Test
    fun `start surfaces a failure verbatim on the error line`() = runTest {
        val client = seeded().apply { failOn = { "no workspace found" } }
        val store = storeOf(client)
        store.start()
        advanceUntilIdle()
        assertNull(store.export)
        assertEquals("no workspace found", store.error)
    }

    // --- refresh reconciliation ------------------------------------------------------------

    @Test
    fun `refresh drops selection, pin, link mode and edge that the new export lost`() = runTest {
        val client = seeded()
        val store = loaded(client)
        store.select("t-a")
        store.togglePin("t-a")
        store.toggleLinkMode("t-a")
        store.selectEdge(Edge(from = "t-b", to = "t-a"))

        store.mutate(listOf("delete", "t-a"))
        advanceUntilIdle()

        assertNull(store.selectedId)
        assertNull(store.pinnedId)
        assertNull(store.linkModeId)
        assertNull(store.selectedEdge)
    }

    @Test
    fun `refresh keeps state the new export still carries`() = runTest {
        val store = loaded()
        store.togglePin("t-b") // pinning selects too, so pin first and let select win
        store.select("t-a")
        store.selectEdge(Edge(from = "t-b", to = "t-a"))

        store.mutate(listOf("done", "t-b"))
        advanceUntilIdle()

        assertEquals("t-a", store.selectedId)
        assertEquals("t-b", store.pinnedId)
        assertEquals(Edge("t-b", "t-a"), store.selectedEdge)
    }

    @Test
    fun `refresh drops a selected edge whose link was removed, task intact`() = runTest {
        val store = loaded()
        store.selectEdge(Edge(from = "t-b", to = "t-a"))

        store.performEdgeOp(dependent = "t-a", blocker = "t-b", link = false)
        advanceUntilIdle()

        assertNull(store.selectedEdge)
        assertNotNullId(store, "t-a")
    }

    private fun assertNotNullId(store: AppStore, id: String) =
        assertTrue(store.export?.tasks?.any { it.id == id } == true, "$id should still be in the export")

    // --- the t-nt8t selection guard --------------------------------------------------------

    @Test
    fun `select refuses an id the export does not carry and keeps the current selection`() = runTest {
        val store = loaded()
        store.select("t-a")

        assertFalse(store.select("t-archived"))
        assertEquals("t-a", store.selectedId)
    }

    // --- performEdgeOp no-op pre-checks (the CLI would exit 0 silently) ---------------------

    @Test
    fun `re-linking an existing edge toasts and never shells out`() = runTest {
        val client = seeded()
        val store = loaded(client)
        client.calls.clear()

        store.performEdgeOp(dependent = "t-a", blocker = "t-b", link = true)
        advanceUntilIdle()

        assertEquals("already linked: t-b → t-a", lastToast(store))
        assertEquals(emptyList(), client.calls)
    }

    @Test
    fun `unlinking an absent edge toasts and never shells out`() = runTest {
        val client = seeded()
        val store = loaded(client)
        client.calls.clear()

        store.performEdgeOp(dependent = "t-b", blocker = "t-a", link = false)
        advanceUntilIdle()

        assertEquals("not linked: t-a → t-b", lastToast(store))
        assertEquals(emptyList(), client.calls)
    }

    @Test
    fun `an edge op while busy toasts and never shells out`() = runTest {
        val client = seeded()
        val store = loaded(client)
        store.mutate(listOf("done", "t-b")) // in flight: busy is set synchronously
        client.calls.clear()

        store.performEdgeOp(dependent = "t-b", blocker = "t-a", link = true)

        assertEquals("busy — try again in a moment", lastToast(store))
        assertEquals(emptyList(), client.calls)
    }

    @Test
    fun `a failed edge op toasts the CLI's reason and leaves nothing to undo`() = runTest {
        val client = seeded().apply { failOn = { if (it.first() == "link") "would create a cycle" else null } }
        val store = loaded(client)

        store.performEdgeOp(dependent = "t-b", blocker = "t-a", link = true)
        advanceUntilIdle()
        assertEquals("link failed: would create a cycle", lastToast(store))

        store.undoLast()
        assertEquals("nothing to undo", lastToast(store))
    }

    // --- the one-shot undo (t-aq99) --------------------------------------------------------

    @Test
    fun `a successful link arms undo, and undoLast inverts it exactly once`() = runTest {
        val client = seeded()
        val store = loaded(client)

        store.performEdgeOp(dependent = "t-b", blocker = "t-a", link = true)
        advanceUntilIdle()
        assertEquals("linked t-a → t-b (Ctrl+Z to undo)", lastToast(store))
        assertEquals(listOf("t-a"), store.export?.tasks?.first { it.id == "t-b" }?.depends)

        store.undoLast()
        advanceUntilIdle()
        assertEquals("undid link: t-a → t-b", lastToast(store))
        assertEquals(emptyList(), store.export?.tasks?.first { it.id == "t-b" }?.depends)

        // One-shot: the undo consumed the memory rather than arming its own inverse.
        store.undoLast()
        assertEquals("nothing to undo", lastToast(store))
    }

    // --- createTask (t-rjna) ---------------------------------------------------------------

    @Test
    fun `createTask closes the dialog and selects the minted id`() = runTest {
        val store = loaded()
        store.openCreate()

        store.createTask(listOf("add", "--title", "fresh"))
        advanceUntilIdle()

        assertEquals("t-new0", store.selectedId)
        assertFalse(store.showCreate)
        assertFalse(store.creating)
        assertNull(store.createError)
    }

    /**
     * t-ctbc: the minted card has no measured rect yet, so the store selects it and publishes
     * the id; centring it is the composable's half of the job. Losing this hand-off is how the
     * pan silently degrades back to a bare selection.
     */
    @Test
    fun `createTask publishes the minted id for the caller to centre`() = runTest {
        val store = loaded()

        store.createTask(listOf("add", "--title", "fresh"))
        advanceUntilIdle()
        assertEquals("t-new0", store.newlyCreatedId)

        store.pannedToNew()
        assertNull(store.newlyCreatedId)
    }

    @Test
    fun `a failed create publishes no id to centre`() = runTest {
        val client = seeded().apply { failOn = { if (it.first() == "add") "title must not be blank" else null } }
        val store = loaded(client)

        store.createTask(listOf("add", "--title", ""))
        advanceUntilIdle()

        assertNull(store.newlyCreatedId)
    }

    // --- version skew (t-eeze) -------------------------------------------------------------

    @Test
    fun `a binary on a different version than the UI warns once`() = runTest {
        val client = seeded().apply { reportedVersion = "0.0.1-ancient" }
        val store = loaded(client)

        store.checkVersionSkew()
        advanceUntilIdle()

        assertEquals(ToastKind.ERROR, store.toasts.items.last().kind)
        assertTrue("0.0.1-ancient" in lastToast(store), "the toast should name the binary's version")
        assertTrue(BUILD_VERSION in lastToast(store), "the toast should name the UI's version")
    }

    @Test
    fun `a matching binary stays silent`() = runTest {
        val store = loaded() // FakeClient reports BUILD_VERSION by default
        store.checkVersionSkew()
        advanceUntilIdle()

        assertTrue(store.toasts.items.isEmpty(), "no skew, no toast")
    }

    @Test
    fun `an unreadable version says nothing rather than guessing`() = runTest {
        val client = seeded().apply { reportedVersion = null }
        val store = loaded(client)

        store.checkVersionSkew()
        advanceUntilIdle()

        assertTrue(store.toasts.items.isEmpty(), "null means 'couldn't tell' — stay quiet")
    }

    @Test
    fun `a create failure keeps the dialog open with its own error, sparing the panel's`() = runTest {
        val client = seeded().apply { failOn = { if (it.first() == "add") "title must not be blank" else null } }
        val store = loaded(client)
        store.openCreate()

        store.createTask(listOf("add", "--title", ""))
        advanceUntilIdle()

        assertTrue(store.showCreate, "the dialog must stay open for a retry")
        assertEquals("add: title must not be blank", store.createError)
        assertNull(store.error, "a create failure must never reach the panel error line")
        assertFalse(store.creating, "the dialog must re-enable after a failure")
        assertFalse(store.busy)
    }

    @Test
    fun `dismissing the create dialog mid-submit is refused`() = runTest {
        val store = loaded()
        store.openCreate()
        store.createTask(listOf("add", "--title", "fresh"))

        store.dismissCreate()

        assertTrue(store.showCreate)
    }

    // --- mutateAll (prune) -----------------------------------------------------------------

    @Test
    fun `mutateAll runs every mutation in order`() = runTest {
        val client = seeded()
        val store = loaded(client)
        client.calls.clear()

        store.mutateAll(listOf(listOf("delete", "t-a"), listOf("delete", "t-b")))
        advanceUntilIdle()

        assertEquals(listOf(listOf("delete", "t-a"), listOf("delete", "t-b")), client.calls)
        assertEquals(emptyList(), store.export?.tasks?.map { it.id })
        assertNull(store.error)
    }

    @Test
    fun `mutateAll stops at the first failure, refreshes from the last success, then errors`() = runTest {
        val client = seeded().apply { failOn = { if (it == listOf("delete", "t-b")) "task is locked" else null } }
        val store = loaded(client)
        client.calls.clear()

        store.mutateAll(listOf(listOf("delete", "t-a"), listOf("delete", "t-b"), listOf("delete", "t-c")))
        advanceUntilIdle()

        // t-c is never attempted — the loop breaks on the first failure.
        assertEquals(listOf(listOf("delete", "t-a"), listOf("delete", "t-b")), client.calls)
        // The graph shows t-a's successful delete...
        assertEquals(listOf("t-b"), store.export?.tasks?.map { it.id })
        // ...and the error survives that refresh, which clears the error line.
        assertEquals("delete: task is locked", store.error)
        assertFalse(store.busy)
    }

    // --- the busy gate (t-t36o) ------------------------------------------------------------

    @Test
    fun `a second mutation is dropped while one is in flight`() = runTest {
        val client = seeded()
        val store = loaded(client)
        client.calls.clear()

        store.mutate(listOf("done", "t-b"))
        assertTrue(store.busy)
        store.mutate(listOf("done", "t-a"))
        advanceUntilIdle()

        assertEquals(listOf(listOf("done", "t-b")), client.calls)
        assertFalse(store.busy)
    }

    @Test
    fun `reload is dropped while a mutation is in flight`() = runTest {
        val client = seeded()
        val store = loaded(client)
        client.calls.clear()

        store.mutate(listOf("done", "t-b"))
        store.reload()
        advanceUntilIdle()

        assertEquals(listOf(listOf("done", "t-b")), client.calls)
    }

    /** The whole point of keeping saveBody outside the gate: a focus-loss autosave can't be dropped. */
    @Test
    fun `saveBody goes through while a mutation is in flight`() = runTest {
        val client = seeded()
        val store = loaded(client)

        store.mutate(listOf("done", "t-b"))
        assertTrue(store.busy)
        store.saveBody("t-a", "notes typed just before focus left")
        advanceUntilIdle()

        assertEquals(listOf("t-a" to "notes typed just before focus left"), client.writes)
    }

    @Test
    fun `a save failure surfaces on the error line`() = runTest {
        val client = seeded().apply { failOn = { if (it.first() == "write") "disk full" else null } }
        val store = loaded(client)

        store.saveBody("t-a", "text")
        advanceUntilIdle()

        assertEquals("write: disk full", store.error)
    }

    @Test
    fun `loadBody returns the stored body`() = runTest {
        val client = seeded().apply { bodies["t-a"] = "the notes" }
        val store = loaded(client)

        assertEquals("the notes", store.loadBody("t-a"))
    }

    /**
     * A body read that fails must NOT propagate: the panel still renders its well, and the
     * error surfaces on save instead. The seeded body is what this would return if the
     * failure were ignored, so "" is only reachable by swallowing the throw.
     */
    @Test
    fun `loadBody returns empty rather than failing the panel`() = runTest {
        val client = seeded().apply {
            bodies["t-a"] = "the notes"
            failOn = { if (it.first() == "get") "unknown id" else null }
        }
        val store = loaded(client)

        assertEquals("", store.loadBody("t-a"))
    }

    @Test
    fun `a failed mutation names the verb on the error line`() = runTest {
        val client = seeded().apply { failOn = { if (it.first() == "done") "unknown id" else null } }
        val store = loaded(client)

        store.mutate(listOf("done", "t-zzzz"))
        advanceUntilIdle()

        assertEquals("done: unknown id", store.error)
        assertFalse(store.busy)
    }
}
