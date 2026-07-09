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
        val ex = assertFailsWith<CliException> {
            client(exit = 5).export()
        }
        assertEquals(5, ex.code)
        assertTrue(ex.message!!.contains("5"), "fallback message should name the exit code")
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
}
