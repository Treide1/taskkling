package io.taskkling.core

import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `uninstall`'s orchestration ([runUninstallVerb]): tier resolution, the
 * consequence prompts, and — the safety-critical part — exactly when `--purge`
 * is allowed to touch a task graph (ADR-004 / ADR-007 / ADR-011).
 */
class UninstallRunTest {
    private val globalExe = "/opt/bin/taskkling".toPath()
    private val projectRoot = "/proj".toPath()
    private val localExe = projectRoot / ".taskkling" / "bin" / "taskkling"
    private val cacheHome = "/cache".toPath()

    /** Records every removal the runner performed, so "nothing was changed" is assertable. */
    private class Removals {
        val self: MutableList<Path> = mutableListOf()
        val other: MutableList<Path> = mutableListOf()
        val pathEntries: MutableList<String> = mutableListOf()
    }

    /** Answers [confirm] from a script, and records the prompts verbatim — they are the safety surface. */
    private class Prompter(vararg answers: Boolean) {
        val asked: MutableList<String> = mutableListOf()
        private val queue = answers.toMutableList()
        fun confirm(prompt: String): Boolean {
            asked += prompt
            return queue.removeFirstOrNull() ?: error("unexpected confirm: $prompt")
        }
    }

    private fun effects(
        fs: FakeFileSystem,
        running: Path = globalExe,
        removals: Removals = Removals(),
        prompter: Prompter = Prompter(),
        workspace: WorkspaceInfo? = null,
        tier: InstallTier = InstallTier.GLOBAL,
        pathEntryPresent: Boolean = false,
    ) = UninstallEffects(
        fs = fs,
        runningExecutable = { running },
        globalInstallDir = { globalExe.parent!! },
        userCacheDir = { cacheHome },
        requireWorkspace = { workspace ?: throw TkError(ExitCode.VALIDATION, "no workspace found") },
        findWorkspace = { workspace },
        resolveTier = { tier },
        pathHasEntry = { pathEntryPresent },
        removePathEntry = { dir -> removals.pathEntries += dir; pathEntryPresent },
        uninstallSelf = { removals.self += it },
        uninstallOther = { removals.other += it },
        confirm = prompter::confirm,
    )

    private fun fsWith(vararg files: Path) = FakeFileSystem().apply {
        files.forEach { f ->
            f.parent?.let { createDirectories(it) }
            write(f) { writeUtf8("x") }
        }
    }

    // --- Tier resolution (ADR-007) -----------------------------------------------------------------------------

    @Test
    fun the_running_binary_decides_the_tier_when_no_flag_is_given() {
        val fs = fsWith(localExe)
        val removals = Removals()

        runUninstallVerb(
            UninstallVerbArgs(yes = true),
            effects(fs, running = localExe, removals = removals, tier = InstallTier.LOCAL),
            RecordingOutput(),
        )

        assertEquals(listOf(localExe), removals.self, "the running local-bin copy is a self-removal")
        assertTrue(removals.pathEntries.isEmpty(), "a LOCAL uninstall never touches PATH")
    }

    @Test
    fun explicit_local_resolves_the_binary_under_the_workspace_meta_dir() {
        val fs = fsWith(localExe, globalExe)
        val removals = Removals()

        runUninstallVerb(
            UninstallVerbArgs(local = true, yes = true),
            effects(fs, running = globalExe, removals = removals, workspace = fakeWorkspace(projectRoot)),
            RecordingOutput(),
        )

        assertEquals(listOf(localExe), removals.other, "the local copy is not the running image — a plain delete")
        assertTrue(removals.self.isEmpty())
    }

    @Test
    fun explicit_local_without_a_workspace_fails() {
        val e = assertFailsWith<TkError> {
            runUninstallVerb(UninstallVerbArgs(local = true, yes = true), effects(fsWith(), workspace = null), RecordingOutput())
        }

        assertEquals("no workspace found", e.message)
    }

    @Test
    fun explicit_local_also_removes_the_version_stamp_and_the_wrappers() {
        val stamp = localExe.parent!! / ".version"
        val wrapper = projectRoot / "taskkling"
        val cmdWrapper = projectRoot / "taskkling.cmd"
        val fs = fsWith(localExe, stamp, wrapper, cmdWrapper, globalExe)

        runUninstallVerb(
            UninstallVerbArgs(local = true, yes = true),
            effects(fs, running = globalExe, workspace = fakeWorkspace(projectRoot)),
            RecordingOutput(),
        )

        assertTrue(!fs.exists(stamp) && !fs.exists(wrapper) && !fs.exists(cmdWrapper))
    }

    // --- Cache home is GLOBAL-only safe scope (ADR-011) ---------------------------------------------------------

    @Test
    fun a_global_uninstall_removes_the_cache_home() {
        val fs = fsWith(globalExe, cacheHome / "ui" / "app" / "0.6.3" / "ui.jar")
        val out = RecordingOutput()

        runUninstallVerb(UninstallVerbArgs(yes = true), effects(fs), out)

        assertTrue(!fs.exists(cacheHome))
        assertTrue(out.stdout.contains("taskkling: removed cache home $cacheHome"))
    }

    @Test
    fun a_local_uninstall_leaves_the_cache_home_for_a_surviving_global_install() {
        val cachedJar = cacheHome / "ui" / "app" / "0.6.3" / "ui.jar"
        val fs = fsWith(localExe, cachedJar)
        val out = RecordingOutput()

        runUninstallVerb(
            UninstallVerbArgs(yes = true),
            effects(fs, running = localExe, tier = InstallTier.LOCAL),
            out,
        )

        assertTrue(fs.exists(cachedJar))
        assertTrue(out.stdout.none { it.contains("cache home") })
    }

    @Test
    fun locked_cache_leftovers_are_reported_by_path_not_raised_as_errors() {
        val locked = cacheHome / "ui" / "runtime" / "jdk21" / "bin" / "java"
        val fs = LockedFileFs(fsWith(globalExe, locked), locked)
        val out = RecordingOutput()

        runUninstallVerb(UninstallVerbArgs(yes = true), effectsOn(fs), out)

        assertTrue(out.stdout.any { it.contains("partially removed; still present (in use by a running UI?)") })
        assertTrue(out.stdout.contains("  $locked"), "the leftover is named by its path so it can be removed by hand")
    }

    // --- Prompts: the safe scope -------------------------------------------------------------------------------

    @Test
    fun yes_runs_the_safe_scope_without_asking_anything() {
        val prompter = Prompter()
        val fs = fsWith(globalExe)

        runUninstallVerb(UninstallVerbArgs(yes = true), effects(fs, prompter = prompter), RecordingOutput())

        assertTrue(prompter.asked.isEmpty())
    }

    @Test
    fun the_interactive_default_states_the_scope_then_asks() {
        val fs = fsWith(globalExe)
        val prompter = Prompter(true)
        val out = RecordingOutput()

        runUninstallVerb(
            UninstallVerbArgs(),
            effects(fs, prompter = prompter, pathEntryPresent = true, workspace = fakeWorkspace(projectRoot, taskCount = 7)),
            out,
        )

        assertEquals(
            listOf(
                "taskkling uninstall (global tier):",
                "  binary:  $globalExe",
                "  PATH:    remove '${globalExe.parent}' from your user PATH",
                "  (kept)   ${projectRoot / ".taskkling"} — 7 task(s) preserved; pass --purge to also delete them",
            ),
            out.stdout.take(4),
        )
        assertEquals(listOf("Proceed with removing the binary and PATH entry?"), prompter.asked)
    }

    @Test
    fun declining_the_first_confirm_changes_nothing() {
        val fs = fsWith(globalExe)
        val removals = Removals()
        val out = RecordingOutput()

        runUninstallVerb(UninstallVerbArgs(), effects(fs, removals = removals, prompter = Prompter(false)), out)

        assertTrue(removals.self.isEmpty() && removals.other.isEmpty() && removals.pathEntries.isEmpty())
        assertEquals("taskkling: uninstall aborted; nothing was changed", out.stdout.last())
    }

    // --- Prompts: --purge, the only path to the task graph ------------------------------------------------------

    @Test
    fun purge_with_yes_is_the_only_non_interactive_way_to_delete_data() {
        val tasksDir = projectRoot / "tasks"
        val metaDir = projectRoot / ".taskkling"
        val fs = fsWith(globalExe, tasksDir / "t-abcd--x.md", metaDir / "config.toml")
        val out = RecordingOutput()

        runUninstallVerb(
            UninstallVerbArgs(purge = true, yes = true),
            effects(fs, workspace = fakeWorkspace(projectRoot, taskCount = 1)),
            out,
        )

        assertTrue(!fs.exists(tasksDir) && !fs.exists(metaDir))
        assertEquals("taskkling: purged $metaDir + $tasksDir", out.stdout.last())
    }

    @Test
    fun yes_alone_never_deletes_the_workspace() {
        val tasksDir = projectRoot / "tasks"
        val fs = fsWith(globalExe, tasksDir / "t-abcd--x.md")

        runUninstallVerb(
            UninstallVerbArgs(yes = true),
            effects(fs, workspace = fakeWorkspace(projectRoot, taskCount = 1)),
            RecordingOutput(),
        )

        assertTrue(fs.exists(tasksDir / "t-abcd--x.md"), "-y is the SAFE scope; data survives it")
    }

    @Test
    fun interactive_purge_asks_a_separate_second_confirm() {
        val fs = fsWith(globalExe)
        val prompter = Prompter(true, true)

        runUninstallVerb(
            UninstallVerbArgs(purge = true),
            effects(fs, prompter = prompter, workspace = fakeWorkspace(projectRoot, taskCount = 3)),
            RecordingOutput(),
        )

        assertEquals(2, prompter.asked.size)
        assertEquals(
            "This PERMANENTLY deletes 3 task(s) at ${projectRoot / ".taskkling"} + ${projectRoot / "tasks"} and cannot be undone. Continue?",
            prompter.asked[1],
        )
    }

    @Test
    fun declining_the_purge_confirm_preserves_the_workspace_and_the_binary() {
        val tasksDir = projectRoot / "tasks"
        val fs = fsWith(globalExe, tasksDir / "t-abcd--x.md")
        val removals = Removals()
        val out = RecordingOutput()

        runUninstallVerb(
            UninstallVerbArgs(purge = true),
            effects(fs, removals = removals, prompter = Prompter(true, false), workspace = fakeWorkspace(projectRoot, taskCount = 1)),
            out,
        )

        assertTrue(fs.exists(tasksDir / "t-abcd--x.md"))
        assertTrue(removals.self.isEmpty(), "aborting the purge aborts the whole uninstall")
        assertEquals("taskkling: uninstall aborted; workspace preserved", out.stdout.last())
    }

    @Test
    fun a_purge_plan_that_does_not_cover_tasks_never_claims_task_deletion() {
        // tasks_dir escapes the workspace: purgePlan refuses to touch it (t-qoyn), so no prompt may promise it.
        val fs = fsWith(globalExe)
        val prompter = Prompter(true, true)
        val out = RecordingOutput()
        val workspace = fakeWorkspace(
            projectRoot,
            tasksDirSetting = "../outside",
            taskCount = 5,
            purge = PurgePlan(listOf(projectRoot / ".taskkling"), coversTasks = false),
        )

        runUninstallVerb(UninstallVerbArgs(purge = true), effects(fs, prompter = prompter, workspace = workspace), out)

        val summary = out.stdout.first { it.startsWith("  PURGE:") }
        assertEquals(
            "  PURGE:   ${projectRoot / ".taskkling"} — PERMANENTLY DELETES config and caches " +
                "(tasks_dir '../outside' does not resolve to a directory inside the workspace; tasks are NOT touched)",
            summary,
        )
        assertTrue(out.stdout.none { it.contains("DELETES 5 task") })
        assertEquals(
            "This PERMANENTLY deletes the workspace config and caches at ${projectRoot / ".taskkling"} and cannot be undone. Continue?",
            prompter.asked[1],
        )
    }

    // --- Reporting ---------------------------------------------------------------------------------------------

    @Test
    fun a_windows_self_removal_that_left_a_locked_old_sibling_says_so() {
        val fs = fsWith(globalExe, "$globalExe.old".toPath())
        val out = RecordingOutput()

        runUninstallVerb(UninstallVerbArgs(yes = true), effects(fs), out)

        assertTrue(out.stdout.contains("taskkling: off PATH now; the locked file will clear on your next reboot"))
    }

    @Test
    fun quiet_suppresses_the_summary_but_not_the_removal() {
        val fs = fsWith(globalExe)
        val removals = Removals()
        val out = RecordingOutput()

        runUninstallVerb(UninstallVerbArgs(yes = true, quiet = true), effects(fs, removals = removals), out)

        assertTrue(out.stdout.isEmpty())
        assertEquals(listOf(globalExe), removals.self)
    }

    // --- Helpers for the forwarding-filesystem case -------------------------------------------------------------

    private fun effectsOn(fs: okio.FileSystem) = UninstallEffects(
        fs = fs,
        runningExecutable = { globalExe },
        globalInstallDir = { globalExe.parent!! },
        userCacheDir = { cacheHome },
        requireWorkspace = { throw TkError(ExitCode.VALIDATION, "no workspace found") },
        findWorkspace = { null },
        resolveTier = { InstallTier.GLOBAL },
        pathHasEntry = { false },
        removePathEntry = { false },
        uninstallSelf = { },
        uninstallOther = { },
        confirm = { true },
    )
}
