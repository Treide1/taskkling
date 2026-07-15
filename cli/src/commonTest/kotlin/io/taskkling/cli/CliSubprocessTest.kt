package io.taskkling.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // --- parse failures fail loudly, on contract (t-wezr) -----------------------------------
    //
    // A parse failure happens inside kotlinx-cli, before any verb executes, so TkCommand's
    // TkError -> exit-code mapping never engages. Left alone the parser prints its complaint
    // plus a ~20-line usage dump to STDOUT and exits 127 — off-contract (ExitCode has no 127;
    // a shell reads it as "command not found") and unreadable as an error. Main.kt's
    // `failLoudly` hook reaches into kotlinx-cli's `outputAndTerminate` to fix that; these
    // tests pin the OBSERVABLE result on the real binary, so if that suppressed access ever
    // stops working, CI fails here instead of silently reverting to 127.

    @Test
    fun unknownFlagOnAMutationVerbIsUsageExit2AndNotAHelpDump() {
        // The DoD's case 1, verbatim: `set <id> --depends <dep>` (--depends is not a set field).
        val ws = newWorkspace()
        val a = addTask(ws, "A task")
        val b = addTask(ws, "Another task")
        val r = runCli("--root", ws, "set", a, "--depends", b)

        assertEquals(2, r.exit, "unknown flag maps to ExitCode.USAGE(2), never 127; stderr=${r.stderr}")
        assertTrue(r.stderr.contains("Unknown option --depends"), "specific complaint on stderr: ${r.stderr}")
        assertFalse(r.stderr.contains("Usage:"), "no usage dump: ${r.stderr}")
        assertEquals("", r.stdout.trim(), "nothing on stdout — this is an error, not help")
        // …and it suggests the verb that does work.
        assertTrue(r.stderr.contains("taskkling link <id> -d <dep>"), "suggests link: ${r.stderr}")
        // The whole point of the ticket: the mutation must not have half-happened.
        val after = runCli("--root", ws, "get", a, "-f", "depends")
        assertEquals("", after.stdout.trim(), "the rejected mutation changed nothing")
    }

    @Test
    fun misorderedLinkArgumentsAreUsageExit2AndNotAHelpDump() {
        // The DoD's case 3, verbatim: `link <id> <dep>` — the dep is a flag, not a positional.
        val ws = newWorkspace()
        val a = addTask(ws, "A task")
        val b = addTask(ws, "Another task")
        val r = runCli("--root", ws, "link", a, b)

        assertEquals(2, r.exit, "misordered args map to ExitCode.USAGE(2), never 127; stderr=${r.stderr}")
        assertTrue(r.stderr.contains("Too many arguments"), "specific complaint on stderr: ${r.stderr}")
        assertFalse(r.stderr.contains("Usage:"), "no usage dump: ${r.stderr}")
        assertEquals("", r.stdout.trim(), "nothing on stdout")
        assertTrue(r.stderr.contains("taskkling link <id> -d <dep>"), "suggests the flag form: ${r.stderr}")
        val after = runCli("--root", ws, "get", a, "-f", "depends")
        assertEquals("", after.stdout.trim(), "the rejected mutation changed nothing")
    }

    @Test
    fun anUnknownVerbSaysSoAndIsNotReportedAsTooManyArguments() {
        // `show <id>` — the read verb folded into `get`. kotlinx-cli calls this "Too many
        // arguments!", which is actively misleading; the fix must name the real problem.
        val ws = newWorkspace()
        val id = addTask(ws, "A task")
        val r = runCli("--root", ws, "show", id)

        assertEquals(2, r.exit, "unknown verb maps to ExitCode.USAGE(2), never 127; stderr=${r.stderr}")
        assertTrue(r.stderr.contains("unknown verb 'show'"), "names the verb: ${r.stderr}")
        assertFalse(r.stderr.contains("Too many arguments"), "misleading wording replaced: ${r.stderr}")
        assertTrue(r.stderr.contains("taskkling get <id>"), "suggests get: ${r.stderr}")
        assertEquals("", r.stdout.trim(), "nothing on stdout")
    }

    @Test
    fun unknownFlagOnTheNestedConfigInitIsAlsoUsageExit2() {
        // `config init` is registered at ConfigCmd construction, so it never passes through
        // main()'s per-verb hook pass and needs its own (regression guard for that wiring).
        val r = runCli("config", "init", "--bogus")
        assertEquals(2, r.exit, "nested subcommand is on contract too; stderr=${r.stderr}")
        assertTrue(r.stderr.contains("Unknown option --bogus"), "specific complaint on stderr: ${r.stderr}")
        assertFalse(r.stderr.contains("Usage:"), "no usage dump: ${r.stderr}")
    }

    @Test
    fun helpIsNotAnErrorAndKeepsItsUsageDumpOnStdout() {
        // The hook intercepts help and errors through the SAME kotlinx-cli sink, told apart
        // only by the exit code it is handed — so help must be proven unharmed.
        val root = runCli("--help")
        assertEquals(0, root.exit, "--help is not an error; stderr=${root.stderr}")
        assertTrue(root.stdout.contains("Usage: taskkling"), "usage dump on stdout: ${root.stdout}")
        assertTrue(root.stdout.contains("Subcommands:"), "lists the verbs: ${root.stdout}")
        assertEquals("", root.stderr.trim(), "help says nothing on stderr")

        val verb = runCli("set", "--help")
        assertEquals(0, verb.exit, "per-verb --help is not an error; stderr=${verb.stderr}")
        assertTrue(verb.stdout.contains("Usage: taskkling set"), "per-verb usage on stdout: ${verb.stdout}")
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

    // --- status + external requirement are independent create/edit fields (ADR-018) --------

    @Test
    fun addStatusWaitingWithReqCreatesAWaitingTaskCarryingTheRequirement() {
        val ws = newWorkspace()
        val add = runCli("--root", ws, "add", "Gated", "--status", "waiting", "--req", "legal sign-off")
        assertEquals(0, add.exit, "stderr=${add.stderr}")
        val id = add.stdout.trim()

        val got = runCli("--root", ws, "get", id, "-f", "status", "-f", "req", "-f", "waiting_on")
        assertEquals(0, got.exit, "stderr=${got.stderr}")
        assertEquals(listOf("waiting", "legal sign-off", "legal sign-off"), got.stdout.trim().lines())
    }

    @Test
    fun addStatusDoneStampsClosedOnCreation() {
        val ws = newWorkspace()
        val add = runCli("--root", ws, "add", "Born done", "--status", "done")
        assertEquals(0, add.exit, "stderr=${add.stderr}")
        val id = add.stdout.trim()

        val got = runCli("--root", ws, "get", id, "-f", "status", "-f", "closed")
        val (status, closed) = got.stdout.trim().lines()
        assertEquals("done", status)
        assertTrue(closed.isNotEmpty(), "creating straight into done stamps a closed timestamp; got '$closed'")
    }

    @Test
    fun setStatusDoneKeepsTheExternalRequirement() {
        val ws = newWorkspace()
        val add = runCli("--root", ws, "add", "Open but gated", "--req", "a callback")
        val id = add.stdout.trim()

        val set = runCli("--root", ws, "set", id, "--status", "done")
        assertEquals(0, set.exit, "stderr=${set.stderr}")

        val got = runCli("--root", ws, "get", id, "-f", "status", "-f", "req")
        assertEquals(listOf("done", "a callback"), got.stdout.trim().lines(), "req persists across a status change")
    }

    @Test
    fun setReqOnAnOpenTaskDoesNotChangeStatus() {
        val ws = newWorkspace()
        val id = addTask(ws, "Still open")

        val set = runCli("--root", ws, "set", id, "--req", "waiting on a callback")
        assertEquals(0, set.exit, "stderr=${set.stderr}")

        val got = runCli("--root", ws, "get", id, "-f", "status", "-f", "req")
        assertEquals(listOf("open", "waiting on a callback"), got.stdout.trim().lines(), "set --req must not flip status")
    }

    @Test
    fun clearReqUnsetsTheRequirement() {
        val ws = newWorkspace()
        val add = runCli("--root", ws, "add", "Had a reason", "--req", "some reason")
        val id = add.stdout.trim()

        runCli("--root", ws, "set", id, "--clear", "req")
        val got = runCli("--root", ws, "get", id, "-f", "req")
        assertEquals("", got.stdout.trim(), "--clear req unsets the external requirement")
    }

    @Test
    fun waitOnIsADeprecatedAliasForReq() {
        val ws = newWorkspace()
        val id = addTask(ws, "Parked")

        // Legacy scripts still pass --on; it must set the same field as --req.
        val wait = runCli("--root", ws, "wait", id, "--on", "the mail")
        assertEquals(0, wait.exit, "stderr=${wait.stderr}")

        val got = runCli("--root", ws, "get", id, "-f", "status", "-f", "req")
        assertEquals(listOf("waiting", "the mail"), got.stdout.trim().lines())
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
