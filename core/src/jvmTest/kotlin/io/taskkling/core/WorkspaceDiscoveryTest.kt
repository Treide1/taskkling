package io.taskkling.core

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct coverage for [Workspace.discover] / [initWorkspace] (PRD §9) — the
 * scaffold + resolve path every command depends on, previously exercised only
 * indirectly through [tempWorkspace]. The walk-up is tested via the
 * [Workspace.findWorkspaceRoot] seam because the JVM cannot change its working
 * directory at runtime (see that function's doc).
 */
class WorkspaceDiscoveryTest {
    private val fs = FileSystem.SYSTEM

    /** A fresh, canonicalized (symlink-resolved) temp directory that exists but is not initialized. */
    private fun tempDir(): Path =
        fs.canonicalize(Files.createTempDirectory("tk-disc").absolutePathString().toPath())

    // --- walk-up discovery (the seam behind discover()'s cwd branch) --------

    @Test
    fun findWorkspaceRootWalksUpToAncestorWorkspace() {
        val root = tempDir()
        initWorkspace(root.toString())
        val nested = root / "tasks" / "deep" / "nested"
        fs.createDirectories(nested)

        val found = Workspace.findWorkspaceRoot(fs, nested)

        assertEquals(root, found, "walk-up from a nested cwd should find the ancestor .taskkling/")
    }

    @Test
    fun findWorkspaceRootIsNullOutsideAnyWorkspace() {
        // A bare temp subtree with no .taskkling anywhere up to the filesystem root.
        val bare = tempDir() / "a" / "b"
        fs.createDirectories(bare)

        assertNull(Workspace.findWorkspaceRoot(fs, bare))
    }

    // --- --root must already be initialized ---------------------------------

    @Test
    fun rootOverrideRejectsUninitializedDirectory() {
        val bare = tempDir() // exists, but no .taskkling

        val e = assertFailsWith<TkError> { Workspace.discover(bare.toString()) }

        assertEquals(ExitCode.USAGE, e.exit)
        assertTrue(
            e.message!!.contains("no taskkling workspace"),
            "message should explain the --root is not a workspace, was: ${e.message}",
        )
    }

    @Test
    fun rootOverrideOpensAnInitializedWorkspace() {
        val root = tempDir()
        initWorkspace(root.toString())

        val ws = Workspace.discover(root.toString())

        assertEquals(root, ws.root)
    }

    // --- initWorkspace idempotency ------------------------------------------

    @Test
    fun initWorkspaceReportsAlreadyExistedOnRerun() {
        val root = tempDir()

        val first = initWorkspace(root.toString())
        assertFalse(first.alreadyExisted, "first init of a fresh dir is not a re-run")

        val second = initWorkspace(root.toString())
        assertTrue(second.alreadyExisted, "second init sees the existing .taskkling/")
        assertEquals(first.root, second.root)
    }

    @Test
    fun initWorkspaceNeverClobbersAnExistingConfig() {
        val root = tempDir()
        initWorkspace(root.toString())
        val configFile = root / ".taskkling" / "config.toml"
        val handEdited = "tasks_dir = \"work\"\nid_prefix = \"zz-\"\n"
        fs.write(configFile) { writeUtf8(handEdited) }

        val again = initWorkspace(root.toString())

        assertTrue(again.alreadyExisted)
        assertEquals(
            handEdited,
            fs.read(configFile) { readUtf8() },
            "re-running init must preserve a hand-edited config.toml verbatim",
        )
    }

    // --- tasks_dir scaffolding + resolution ---------------------------------

    @Test
    fun initScaffoldsTaskDirsAtTheResolvedLocation() {
        val root = tempDir()
        initWorkspace(root.toString())

        val ws = Workspace.discover(root.toString())

        // meta scaffolding
        assertTrue(fs.exists(ws.metaDir), ".taskkling/ created")
        assertTrue(fs.exists(ws.tmpDir), ".taskkling/tmp/ created")
        assertTrue(fs.exists(ws.configFile), "config.toml written")
        // tasks/ + archive/ + trash/ at the resolved location, archive/trash nested under tasks/
        assertTrue(fs.exists(ws.tasksDir), "tasks dir created")
        assertTrue(fs.exists(ws.archiveDir), "archive/ created")
        assertTrue(fs.exists(ws.trashDir), "trash/ created")
        assertEquals(ws.tasksDir / "archive", ws.archiveDir)
        assertEquals(ws.tasksDir / "trash", ws.trashDir)
    }

    @Test
    fun customTasksDirResolvesTasksArchiveAndTrashBeneathIt() {
        val root = tempDir()
        val ws = Workspace(root, Config(tasksDir = "backlog/items"))

        assertEquals(root / "backlog" / "items", ws.tasksDir)
        assertEquals(root / "backlog" / "items" / "archive", ws.archiveDir)
        assertEquals(root / "backlog" / "items" / "trash", ws.trashDir)
    }

    /**
     * Characterization test pinning the current [initWorkspace] contract: it
     * scaffolds the DEFAULT `tasks/` layout using [Config.DEFAULT] and does NOT
     * read a pre-existing config.toml, so a custom `tasks_dir` is honored only by
     * [Workspace] path resolution (above), not by init-time scaffolding. If init
     * is ever taught to honor a configured tasks_dir, update this test.
     */
    @Test
    fun initScaffoldsDefaultLayoutRegardlessOfPreexistingCustomConfig() {
        val root = tempDir()
        fs.createDirectories(root / ".taskkling")
        fs.write(root / ".taskkling" / "config.toml") { writeUtf8("tasks_dir = \"work\"\n") }

        initWorkspace(root.toString())

        assertTrue(fs.exists(root / "tasks" / "archive"), "default tasks/ layout is scaffolded")
        assertFalse(fs.exists(root / "work"), "a config-level custom tasks_dir is NOT scaffolded by init")
        assertEquals(
            "tasks_dir = \"work\"\n",
            fs.read(root / ".taskkling" / "config.toml") { readUtf8() },
            "the pre-existing config is left untouched",
        )
    }
}
