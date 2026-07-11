package io.taskkling.core

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Coverage for `init --demo-mode`'s core halves (ADR-017): the demo layout that
 * [initWorkspace] scaffolds (`tasks_dir` inside the meta dir) and the
 * [seedDemoTasks] dataset, which must keep every reachable primary state
 * (DESIGN §2) on screen at once — the whole point of the demo workspace.
 */
class DemoSeedTest {
    private val fs = FileSystem.SYSTEM

    private fun tempDir(): Path =
        fs.canonicalize(Files.createTempDirectory("tk-demo").absolutePathString().toPath())

    // --- demo layout scaffolding ---------------------------------------------

    @Test
    fun demoLayoutKeepsTasksInsideTheMetaDir() {
        val root = tempDir()
        initWorkspace(root.toString(), demoLayout = true)

        val ws = Workspace.discover(root.toString())

        assertEquals(root / ".taskkling" / "tasks", ws.tasksDir, "demo tasks_dir resolves inside .taskkling/")
        assertTrue(fs.exists(ws.tasksDir), "demo tasks dir created")
        assertTrue(fs.exists(ws.archiveDir), "archive/ created beneath it")
        assertTrue(fs.exists(ws.trashDir), "trash/ created beneath it")
        assertFalse(fs.exists(root / "tasks"), "the default root-level tasks/ is NOT scaffolded")
        assertTrue(
            fs.read(ws.configFile) { readUtf8() }.contains("tasks_dir       = \"$DEMO_TASKS_DIR\""),
            "the written config records the demo tasks_dir",
        )
    }

    @Test
    fun demoLayoutNeverRewritesAnExistingConfig() {
        val root = tempDir()
        fs.createDirectories(root / ".taskkling")
        val handEdited = "tasks_dir = \"work\"\n"
        fs.write(root / ".taskkling" / "config.toml") { writeUtf8(handEdited) }

        initWorkspace(root.toString(), demoLayout = true)

        assertEquals(
            handEdited,
            fs.read(root / ".taskkling" / "config.toml") { readUtf8() },
            "an existing config wins; --demo-mode must not rewrite its layout",
        )
        assertFalse(fs.exists(root / ".taskkling" / "tasks"), "the demo layout is not scaffolded over it")
    }

    /**
     * A worktree-creation hook may fire `init --demo-mode` more than once: the
     * rerun must scaffold at the demo config's resolved `tasks_dir` (t-wqwt),
     * not spray the default root-level `tasks/` next to it.
     */
    @Test
    fun demoLayoutRerunScaffoldsNothingOutsideTheMetaDir() {
        val root = tempDir()
        initWorkspace(root.toString(), demoLayout = true)

        val again = initWorkspace(root.toString(), demoLayout = true)

        assertTrue(again.alreadyExisted)
        assertFalse(fs.exists(root / "tasks"), "a rerun must not scaffold the default root-level tasks/")
    }

    // --- the seeded dataset ---------------------------------------------------

    @Test
    fun seedCoversEveryReachablePrimaryState() {
        val root = tempDir()
        initWorkspace(root.toString(), demoLayout = true)
        val ws = Workspace.discover(root.toString())

        val seeded = ws.seedDemoTasks()
        val export = ws.buildExport(includeBody = false, includeArchived = false)

        assertEquals(seeded, export.tasks.size, "every seeded task is active and exported")
        // Primary states, per the UI's stateOf() precedence (status first, then computed).
        assertTrue(export.tasks.count { it.status == "done" } >= 2, "at least two done tasks (satisfied blockers)")
        assertTrue(export.tasks.any { it.status == "dropped" }, "a dropped task")
        assertTrue(export.tasks.count { it.status == "waiting" } >= 2, "waiting tasks")
        assertTrue(export.tasks.any { it.status == "waiting" && it.computed.resurfaced }, "one waiting task resurfaced")
        assertTrue(export.tasks.any { it.status == "open" && it.computed.deferred }, "a deferred task")
        assertTrue(export.tasks.count { it.computed.blocked } >= 3, "blocked tasks")
        assertTrue(export.tasks.any { it.computed.blockers.size >= 3 }, "one card blocked by several blockers")
        assertTrue(export.tasks.count { it.computed.ready } >= 3, "ready tasks")
        assertTrue(export.tasks.any { it.computed.ready && it.computed.overdue }, "one ready task overdue")
        // Secondary attributes the cards/panel render.
        assertTrue(export.tasks.any { it.priority == "high" } && export.tasks.any { it.priority == "low" }, "mixed priorities")
        assertTrue(export.tasks.any { it.waitingOn != null }, "waiting_on text present")
        // Headline counts stay consistent with the tasks they summarize.
        assertEquals(export.tasks.count { it.computed.ready }, export.counts.ready)
        assertEquals(export.tasks.count { it.computed.blocked }, export.counts.blocked)
    }

    @Test
    fun seededFilesAllLiveUnderTheMetaDir() {
        val root = tempDir()
        initWorkspace(root.toString(), demoLayout = true)
        val ws = Workspace.discover(root.toString())

        ws.seedDemoTasks()

        val files = fs.list(root / ".taskkling" / "tasks").filter { it.name.endsWith(".md") }
        assertTrue(files.isNotEmpty(), "task files written under .taskkling/tasks")
        assertFalse(fs.exists(root / "tasks"), "nothing leaks outside the meta dir")
    }
}
