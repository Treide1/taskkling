package io.taskkling.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden black-box tests of the built `:cli` binary as a subprocess (t-hfwt). Each asserts the
 * real dispatch surface — exit code, stdout, stderr — that agents and the `:ui` client scrape.
 * See [runCli] / [newWorkspace] for the harness.
 */
class CliSubprocessTest {

    private val idRegex = Regex("^t-[a-z0-9]+$")

    // --- exit-code mapping: USAGE (2) vs VALIDATION (3) (PRD §10.1, Errors.kt) --------------

    @Test
    fun unknownIdIsUsageExit2() {
        val ws = newWorkspace()
        val r = runCli("--root", ws, "done", "t-nope")
        assertEquals(2, r.exit, "unknown id maps to ExitCode.USAGE(2); stderr=${r.stderr}")
        assertTrue(r.stderr.contains("unknown id"), "diagnostic on stderr: ${r.stderr}")
        assertEquals("", r.stdout.trim(), "no stdout on a usage error")
    }

    @Test
    fun invalidPriorityIsValidationExit3() {
        val ws = newWorkspace()
        val id = addTask(ws, "A task")
        val r = runCli("--root", ws, "set", id, "-p", "bogus")
        assertEquals(3, r.exit, "invalid priority maps to ExitCode.VALIDATION(3); stderr=${r.stderr}")
        assertTrue(r.stderr.contains("invalid priority"), "diagnostic on stderr: ${r.stderr}")
    }

    // --- guard errors (the CLI-layer TkError(USAGE, …) throws) ------------------------------

    @Test
    fun setWithNoFieldsIsAGuardedUsageError() {
        val ws = newWorkspace()
        val id = addTask(ws, "A task")
        val r = runCli("--root", ws, "set", id)
        assertEquals(2, r.exit)
        assertTrue(
            r.stderr.contains("set needs at least one field"),
            "guard message on stderr: ${r.stderr}",
        )
    }

    @Test
    fun linkWithNoDependsIsAGuardedUsageError() {
        val ws = newWorkspace()
        val id = addTask(ws, "A task")
        val r = runCli("--root", ws, "link", id)
        assertEquals(2, r.exit)
        assertTrue(
            r.stderr.contains("link needs at least one --depends id"),
            "guard message on stderr: ${r.stderr}",
        )
    }

    @Test
    fun unlinkWithNoDependsIsAGuardedUsageError() {
        val ws = newWorkspace()
        val id = addTask(ws, "A task")
        val r = runCli("--root", ws, "unlink", id)
        assertEquals(2, r.exit)
        assertTrue(
            r.stderr.contains("unlink needs at least one --depends id"),
            "guard message on stderr: ${r.stderr}",
        )
    }

    // --- emit / idIsEssential: add prints the id even under -q (t-b4bk) ---------------------

    @Test
    fun addPrintsTheMintedIdEvenUnderQuiet() {
        val ws = newWorkspace()
        val r = runCli("--root", ws, "-q", "add", "Quiet add")
        assertEquals(0, r.exit, "stderr=${r.stderr}")
        val id = r.stdout.trim()
        assertTrue(idRegex.matches(id), "add emits the fresh id even with -q (idIsEssential); got '$id'")
    }

    @Test
    fun quietSuppressesTheEchoedIdOnANonEssentialMutation() {
        // `done` is a plain mutation: -q must swallow the id echo (contrast with add above).
        val ws = newWorkspace()
        val id = addTask(ws, "A task")
        val r = runCli("--root", ws, "-q", "done", id)
        assertEquals(0, r.exit, "stderr=${r.stderr}")
        assertEquals("", r.stdout.trim(), "quiet suppresses the id on a non-essential mutation")
    }

    // --- config init: nested-subcommand dispatch (the handledBySubcommand trick) ------------

    @Test
    fun configInitDispatchesToTheNestedSubcommand() {
        // The nested `init` runs and prints the user config path to stdout; the parent stays
        // silent (handledBySubcommand). It's a user-level, write-if-absent, idempotent op.
        val r = runCli("config", "init")
        assertEquals(0, r.exit, "stderr=${r.stderr}")
        assertTrue(r.stdout.trim().endsWith("config.toml"), "prints the config path: '${r.stdout}'")
    }

    @Test
    fun bareConfigWithoutASubcommandIsAUsageError() {
        val r = runCli("config")
        assertEquals(2, r.exit)
        assertTrue(r.stderr.contains("needs a subcommand"), "parent reports the usage error: ${r.stderr}")
    }

    // --- `-b -` reads the body from stdin (t-gmb9) -----------------------------------------

    @Test
    fun bodyDashReadsMultiLineBodyFromStdin() {
        val ws = newWorkspace()
        val body = "line one\nline two"
        val add = runCli("--root", ws, "add", "Body task", "-b", "-", stdin = body)
        assertEquals(0, add.exit, "stderr=${add.stderr}")
        val id = add.stdout.trim()
        assertTrue(idRegex.matches(id), "got id '$id'")

        val got = runCli("--root", ws, "get", id, "--body")
        assertEquals(0, got.exit, "stderr=${got.stderr}")
        assertEquals(body, got.stdout.trim(), "the piped multi-line body round-trips verbatim")
    }

    // --- export JSON contract shape (PRD §12) — what the UI consumes ------------------------

    @Test
    fun exportEmitsTheJsonContractShape() {
        val ws = newWorkspace()
        val id = addTask(ws, "Exported task")
        val r = runCli("--root", ws, "export")
        assertEquals(0, r.exit, "stderr=${r.stderr}")

        val root = Json.parseToJsonElement(r.stdout).jsonObject
        assertTrue("generatedAt" in root, "top-level generatedAt present")
        val counts = root["counts"]!!.jsonObject
        // The single open task is ready → counts.ready == 1.
        assertEquals(1, counts["ready"]!!.jsonPrimitive.content.toInt())

        val tasks = root["tasks"]!!.jsonArray
        assertEquals(1, tasks.size)
        val task = tasks[0].jsonObject
        assertEquals(id, task["id"]!!.jsonPrimitive.content)
        assertEquals("open", task["status"]!!.jsonPrimitive.content)
        // The computed sub-object is the export's value-add over raw frontmatter.
        val computed = task["computed"]!!.jsonObject
        assertTrue(computed["ready"]!!.jsonPrimitive.boolean, "a lone open task is ready")
    }

    // --- `--version` fast path (offline, non-interactive) ----------------------------------

    @Test
    fun versionPrintsAndExitsCleanly() {
        val r = runCli("--version")
        assertEquals(0, r.exit, "stderr=${r.stderr}")
        assertTrue(r.stdout.trim().startsWith("taskkling "), "version banner: '${r.stdout}'")
        // Non-interactive stdout: the update notifier is TTY-gated, so nothing extra is emitted.
        assertEquals("", r.stderr.trim(), "no diagnostics on the version fast path")
    }

    // --- `ui` verb: the pre-network guard rail (t-k7ee; success path is manual QA) ----------

    @Test
    fun uiOutsideAWorkspaceFailsLocallyBeforeAnyNetwork() {
        // Workspace discovery runs FIRST in the launch path (mirrors update's cheap-local-first
        // ordering), so a bad --root fails identically on every platform, network or not.
        val notAWorkspace = newScratchDir()
        val r = runCli("--root", notAWorkspace, "ui")
        assertEquals(2, r.exit, "a bad --root maps to ExitCode.USAGE(2), same as every verb; stderr=${r.stderr}")
        assertTrue(r.stderr.contains("workspace"), "diagnostic names the workspace problem: ${r.stderr}")
    }

    @Test
    fun uiRefusesHeadlessLinuxNamingFetchOnly() {
        // Linux-only by design: the display rule (DISPLAY/WAYLAND_DISPLAY both unset) is the one
        // reliable headless marker (ADR-010), and CI's linux leg genuinely has no display. On a
        // developer box WITH a display this leg is skipped rather than faked — runCli cannot
        // scrub the child's environment.
        if (!hostIsLinux) return
        if (!displayEnvIsEmpty()) return
        val ws = newWorkspace()
        val r = runCli("--root", ws, "ui")
        assertEquals(3, r.exit, "headless refusal maps to ExitCode.VALIDATION(3); stderr=${r.stderr}")
        assertTrue(r.stderr.contains("--fetch-only"), "refusal names the operation that works headless: ${r.stderr}")
        assertTrue(r.stderr.contains("display", ignoreCase = true), "refusal names the missing display: ${r.stderr}")
    }

    // --- helpers ---------------------------------------------------------------------------

    /** Add a task and return its minted id (fails the test if add didn't succeed). */
    private fun addTask(ws: String, title: String): String {
        val r = runCli("--root", ws, "add", title)
        assertEquals(0, r.exit, "add failed: ${r.stderr}")
        return r.stdout.trim().also { assertTrue(idRegex.matches(it), "unexpected id '$it'") }
    }
}
