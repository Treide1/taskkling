package io.taskkling.ui

import kotlinx.serialization.SerializationException
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises the UI's only read path: [CliClient] shelling out to the `taskkling` binary and
 * parsing its `export` JSON (PRD §6.3, §12), plus its failure handling.
 *
 * The shell-out is NOT injectable — [CliClient] builds a `ProcessBuilder` from the binary path
 * with no seam — so rather than testing an extracted parse function in isolation, we point the
 * client at a *real fake binary* (a tiny throwaway script) that emits a chosen stdout/stderr and
 * exit code. That exercises the whole path end-to-end: `ProcessBuilder`, stream capture, the
 * exit-code → [CliException] branch, and the kotlinx JSON decode. The fake is written per-OS
 * (a `.cmd` on Windows, a shebang `sh` script elsewhere); `ProcessBuilder` launches both
 * directly. The JVM test job runs on Linux (CI) and Windows (dev host), both covered.
 */
class CliClientTest {

    /** A compact but complete real export payload (all keys, encodeDefaults-style). Single-line
     *  so it survives a Windows `.cmd` `echo`. Mirrors the CLI's export for a done root + child. */
    private val exportJson =
        """{"generatedAt":"2026-01-02T00:00:00Z","counts":{"ready":1,"blocked":0,"waiting":0,""" +
            """"done":1},"tasks":[{"id":"t-aaaa","title":"root","thread":"demo","status":"done",""" +
            """"waitingOn":null,"depends":[],"due":null,"defer":null,"priority":"normal",""" +
            """"created":"2026-01-01T00:00:00Z","closed":"2026-01-02T00:00:00Z","computed":""" +
            """{"ready":false,"blocked":false,"deferred":false,"overdue":false,"resurfaced":""" +
            """false,"blockers":[],"dependents":["t-bbbb"]},"body":null},{"id":"t-bbbb",""" +
            """"title":"child","thread":"demo","status":"open","waitingOn":null,"depends":""" +
            """["t-aaaa"],"due":null,"defer":null,"priority":"normal","created":""" +
            """"2026-01-01T00:00:00Z","closed":null,"computed":{"ready":true,"blocked":false,""" +
            """"deferred":false,"overdue":false,"resurfaced":false,"blockers":[],"dependents":""" +
            """[]},"body":null}]}"""

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /**
     * Writes a throwaway executable that prints [stdout] to stdout, [stderr] to stderr, and exits
     * with [exit]. Returns its absolute path — a stand-in for the real `taskkling` binary. Any
     * args passed by [CliClient] (`export`, `--export-on-success`, …) are ignored by the fake.
     */
    private fun fakeBinary(stdout: String = "", stderr: String = "", exit: Int = 0): String {
        val dir = Files.createTempDirectory("cliclient-test").toFile().apply { deleteOnExit() }
        val script: File
        if (isWindows) {
            script = File(dir, "fake.cmd")
            val sb = StringBuilder("@echo off\r\n")
            if (stdout.isNotEmpty()) sb.append("echo ").append(stdout).append("\r\n")
            if (stderr.isNotEmpty()) sb.append("echo ").append(stderr).append(" 1>&2\r\n")
            sb.append("exit /b ").append(exit).append("\r\n")
            script.writeText(sb.toString())
        } else {
            script = File(dir, "fake.sh")
            val sb = StringBuilder("#!/bin/sh\n")
            if (stdout.isNotEmpty()) sb.append("printf '%s\\n' '").append(stdout).append("'\n")
            if (stderr.isNotEmpty()) sb.append("printf '%s\\n' '").append(stderr).append("' 1>&2\n")
            sb.append("exit ").append(exit).append("\n")
            script.writeText(sb.toString())
            script.setExecutable(true)
        }
        script.deleteOnExit()
        return script.absolutePath
    }

    private fun client(stdout: String = "", stderr: String = "", exit: Int = 0) =
        CliClient(fakeBinary(stdout, stderr, exit))

    // --- Happy path: a real export payload parses ------------------------------------------

    @Test
    fun exportParsesARealExportPayload() {
        val export = client(stdout = exportJson).export()

        assertEquals(1, export.counts.done)
        assertEquals(1, export.counts.ready)
        assertEquals(2, export.tasks.size)

        val root = export.tasks.first { it.id == "t-aaaa" }
        val child = export.tasks.first { it.id == "t-bbbb" }
        assertEquals("done", root.status)
        assertEquals(listOf("t-bbbb"), root.computed.dependents)
        assertEquals(listOf("t-aaaa"), child.depends)
        assertTrue(child.computed.ready)
    }

    @Test
    fun mutateParsesTheRefreshedExport() {
        // mutate() appends --export-on-success and decodes the returned export via the same seam.
        val export = client(stdout = exportJson).mutate(listOf("done", "t-bbbb"))
        assertEquals(2, export.tasks.size)
        assertEquals(1, export.counts.done)
    }

    // --- Failure: non-zero exit surfaces as CliException ------------------------------------

    @Test
    fun nonZeroExitThrowsCliExceptionCarryingCodeAndStderr() {
        val ex = assertFailsWith<CliException> {
            client(stderr = "task t-zzzz not found", exit = 2).export()
        }
        assertEquals(2, ex.code)
        assertTrue(
            ex.message!!.contains("task t-zzzz not found"),
            "CliException should carry the CLI's stderr, was: ${ex.message}",
        )
    }

    @Test
    fun nonZeroExitWithBlankStderrFallsBackToAGenericMessage() {
        // Nothing on either stream: the exit code is genuinely all there is to report.
        val ex = assertFailsWith<CliException> {
            client(exit = 5).export()
        }
        assertEquals(5, ex.code)
        assertTrue(ex.message!!.contains("5"), "fallback message should name the exit code")
    }

    // --- t-eeze: a blank-stderr failure must still explain itself ----------------------------
    //
    // kotlinx-cli rejects bad arguments by printing the reason (plus a usage dump) to STDOUT
    // and exiting 127 with stderr EMPTY. The old fallback discarded stdout, so a UI running
    // against an older pinned binary could only say "taskkling exited 127". These pin the
    // message-building rules; the multi-line cases go straight at the pure function, since a
    // cross-platform fake binary can't emit multi-line stdout without shell-quoting games.

    @Test
    fun blankStderrFailureQuotesTheReasonFromStdout() {
        val msg = cliFailureMessage(127, stdout = "Unknown option --status", stderr = "")
        assertTrue(msg.contains("Unknown option --status"), "should carry the CLI's reason, was: $msg")
        assertTrue(msg.contains("127"), "should still name the exit code, was: $msg")
    }

    @Test
    fun blankStderrFailureStopsAtTheUsageDump() {
        // The diagnosis comes FIRST and the usage block after it — quoting the whole dump
        // would bury the one line that matters (and `--help` already prints it on demand).
        val msg = cliFailureMessage(
            127,
            stdout = """
                Unknown option --status
                Usage: taskkling add options_list
                Arguments:
                    title -> Task title { String }
            """.trimIndent(),
            stderr = "",
        )
        assertTrue(msg.contains("Unknown option --status"), "should keep the reason, was: $msg")
        assertTrue(!msg.contains("Usage:"), "should drop the usage boilerplate, was: $msg")
        assertTrue(!msg.contains("Task title"), "should drop the usage boilerplate, was: $msg")
    }

    @Test
    fun stderrWinsOverStdoutWhenBothArePresent() {
        // The CLI's own errors go to stderr; stdout is only the fallback channel.
        val msg = cliFailureMessage(2, stdout = "noise on stdout", stderr = "taskkling: unknown id 't-zzzz'")
        assertTrue(msg.contains("unknown id 't-zzzz'"), "was: $msg")
        assertTrue(!msg.contains("noise on stdout"), "stderr should win outright, was: $msg")
    }

    @Test
    fun aVerboseStdoutIsTruncatedRatherThanDumpedWhole() {
        // No usage marker to stop at: the excerpt's own line/char caps must bound it, so a
        // chatty binary can't push a wall of text into a toast.
        val msg = cliFailureMessage(1, stdout = (1..50).joinToString("\n") { "line $it padded out" }, stderr = "")
        assertTrue(msg.contains("line 1 padded out"), "should keep the head, was: $msg")
        assertTrue(!msg.contains("line 40"), "should not run to the end, was: $msg")
        assertTrue(msg.length < 400, "should stay bounded, was ${msg.length} chars")
    }

    @Test
    fun blankStderrFailureWithBlankStdoutStillNamesTheExitCode() {
        assertEquals("taskkling exited 3", cliFailureMessage(3, stdout = "   \n\n", stderr = ""))
    }

    @Test
    fun exit127WithReasonOnStdoutSurfacesThroughARealSubprocess() {
        // The whole path end-to-end, reproducing the dogfood finding: exit 127, reason on
        // stdout, stderr silent — the shape an older pinned binary hits a new flag with.
        val ex = assertFailsWith<CliException> {
            client(stdout = "Unknown option --status", exit = 127).export()
        }
        assertEquals(127, ex.code)
        assertTrue(
            ex.message!!.contains("Unknown option --status"),
            "the CLI's stdout reason should reach the UI, was: ${ex.message}",
        )
    }

    // --- t-eeze: the version probe behind the skew hint --------------------------------------

    @Test
    fun parseVersionReadsTheVersionLine() {
        assertEquals("0.6.3", parseVersionOutput("taskkling 0.6.3\n"))
        assertEquals("0.6.3", parseVersionOutput("taskkling 0.6.3\r\n")) // native stdout is text-mode on Windows
    }

    @Test
    fun parseVersionSkipsAnyNotifierChatterAroundIt() {
        // `--version`'s update notifier is TTY-gated so a subprocess shouldn't see it, but the
        // parse shouldn't depend on that gate holding.
        assertEquals("0.6.3", parseVersionOutput("A new version is available: 0.7.0\ntaskkling 0.6.3\n"))
    }

    @Test
    fun parseVersionReturnsNullWhenThereIsNoVersionLine() {
        // "don't know" — the caller must stay silent rather than guess at skew.
        assertEquals(null, parseVersionOutput("Unknown option --version\nUsage: taskkling options_list\n"))
        assertEquals(null, parseVersionOutput(""))
    }

    @Test
    fun versionReadsItFromTheBinary() {
        assertEquals("0.6.2", client(stdout = "taskkling 0.6.2").version())
    }

    @Test
    fun versionIsNullWhenTheProbeFailsRatherThanThrowing() {
        // An ancient binary that rejects --version must not break launch.
        assertEquals(null, client(stdout = "Unknown option --version", exit = 127).version())
    }

    // --- Failure: malformed stdout surfaces as a parse error, not a CliException ------------

    @Test
    fun malformedStdoutThrowsASerializationError() {
        // Exit 0 but garbage on stdout: the failure is a decode error, distinct from CliException.
        assertFailsWith<SerializationException> {
            client(stdout = "this is not json").export()
        }
    }

    @Test
    fun truncatedJsonThrowsASerializationError() {
        // A half-written payload (e.g. a CLI killed mid-flush) must not parse silently.
        assertFailsWith<SerializationException> {
            client(stdout = """{"generatedAt":"T","counts":{"ready":1}""").export()
        }
    }

    // --- Body normalization: `get --body` stdout → clean editor text --------------------------
    //
    // The native binary's stdout is text-mode on Windows, so `println` turns every `\n` into
    // `\r\n`. Read back as raw bytes, a trailing `\r` used to survive and render as a stray
    // trailing space in the panel's body well; internal lines carried `\r` too. normalizeBodyText
    // must fold all CR variants to `\n` and leave no trailing newline, so the loaded text equals
    // the CLI's own fully-trimmed stored body.

    @Test
    fun bodyStripsTheTrailingCarriageReturnFromWindowsCrlfOutput() {
        // `println("abc")` on the native Windows binary → bytes `abc\r\n`.
        assertEquals("abc", normalizeBodyText("abc\r\n"))
    }

    @Test
    fun bodyFoldsInternalCrlfLineEndingsToLf() {
        // A two-line body round-trips as `foo\r\nbar\r\n`; the editor wants plain `foo\nbar`.
        assertEquals("foo\nbar", normalizeBodyText("foo\r\nbar\r\n"))
    }

    @Test
    fun bodyDropsTrailingBlankLinesAndCarriageReturns() {
        assertEquals("a\n\nb", normalizeBodyText("a\r\n\r\nb\r\n\r\n"))
    }

    @Test
    fun bodyStripsALoneTrailingCarriageReturn() {
        assertEquals("abc", normalizeBodyText("abc\r"))
    }

    @Test
    fun bodyLeavesCleanLfTextUntouched() {
        assertEquals("foo\nbar", normalizeBodyText("foo\nbar\n"))
        assertEquals("no-newline", normalizeBodyText("no-newline"))
        assertEquals("", normalizeBodyText(""))
    }
}
