package io.taskkling.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Black-box coverage of `add --batch -` against the REAL binary (t-zsh6). The unit tests
 * cover decode and the core batch rules; only these prove the thing the DoD actually asks
 * for — that one invocation, fed JSON on a real pipe, files a whole milestone and hands
 * back its ids. See [runCli] / [newWorkspace] for the harness.
 */
class CliBatchAddSubprocessTest {

    private val idRegex = Regex("^t-[a-z0-9]+$")

    private fun idsOf(r: CliResult): List<String> = r.stdout.trim().lines().filter { it.isNotBlank() }

    /** `get <id> -f <field>` — the field value, for asserting what actually landed on disk. */
    private fun field(ws: String, id: String, name: String): String {
        val r = runCli("--root", ws, "get", id, "-f", name)
        assertEquals(0, r.exit, "get -f $name failed: ${r.stderr}")
        return r.stdout.trim()
    }

    // --- the DoD: a milestone + its gate, in ONE invocation ---------------------------------

    @Test
    fun oneInvocationFilesAMilestoneWithBodiesAndAGateWiredToIt() {
        val ws = newWorkspace()
        val payload = """
            [
              {"ref":"w1","title":"Port the parser","thread":"dx","priority":"high",
               "body":"# Port the parser\n\n- [ ] step one\n- [ ] step two"},
              {"ref":"w2","title":"Port the writer","thread":"dx",
               "body":"# Port the writer\n\nMulti-line body, no heredoc gymnastics."},
              {"ref":"gate","title":"v0.9.0 gate","thread":"dx","depends":["w1","w2"],
               "body":"Close once both legs land."}
            ]
        """.trimIndent()

        val r = runCli("--root", ws, "add", "--batch", "-", stdin = payload)

        assertEquals(0, r.exit, "batch add failed: ${r.stderr}")
        val ids = idsOf(r)
        assertEquals(3, ids.size, "one minted id per record, one per line: ${r.stdout}")
        assertTrue(ids.all { idRegex.matches(it) }, "every line must be a bare id: $ids")
        assertEquals(3, ids.toSet().size, "ids must be distinct")

        // Ids come back in INPUT order, so the caller can zip them against their records.
        val (w1, w2, gate) = ids
        assertEquals("Port the parser", field(ws, w1, "title"))
        assertEquals("Port the writer", field(ws, w2, "title"))
        assertEquals("v0.9.0 gate", field(ws, gate, "title"))

        // The gate's edges resolved from local handles to the real minted ids.
        assertEquals("$w1,$w2", field(ws, gate, "depends"))
        assertEquals("high", field(ws, w1, "priority"))
        assertEquals("dx", field(ws, w1, "thread"))

        // The gate is blocked by the work, the work is ready — the graph is real.
        assertEquals("true", field(ws, gate, "blocked"))
        assertEquals("true", field(ws, w1, "ready"))

        // Multi-line markdown bodies survive the pipe intact (the reason the format is JSON).
        val body = runCli("--root", ws, "get", w1, "-b")
        assertEquals("# Port the parser\n\n- [ ] step one\n- [ ] step two", body.stdout.trim())
    }

    @Test
    fun batchIdsPrintEvenUnderQuietBecauseTheIdsAreTheResult() {
        val ws = newWorkspace()
        val r = runCli("--root", ws, "-q", "add", "--batch", "-", stdin = """[{"title":"A"},{"title":"B"}]""")
        assertEquals(0, r.exit, "stderr=${r.stderr}")
        assertEquals(2, idsOf(r).size, "minted ids are essential output: ${r.stdout}")
    }

    @Test
    fun exportOnSuccessEmitsTheWholePostBatchExportInsteadOfIds() {
        val ws = newWorkspace()
        val r = runCli(
            "--root", ws, "add", "--batch", "-", "--export-on-success",
            stdin = """[{"ref":"a","title":"A"},{"title":"B","depends":["a"]}]""",
        )
        assertEquals(0, r.exit, "stderr=${r.stderr}")
        val tasks = Json.parseToJsonElement(r.stdout).jsonObject["tasks"]!!.jsonArray
        assertEquals(2, tasks.size, "the export must contain the whole batch")
    }

    // --- BOM: what PowerShell 5.1 actually pipes ---------------------------------------------

    @Test
    fun aBomPrefixedPayloadIsAcceptedAndBodiesStayBomClean() {
        val ws = newWorkspace()
        // Exactly what `... | taskkling add --batch -` delivers on PS 5.1. Without the strip
        // at the stdin boundary the JSON parse fails outright (exit 2) and nothing is created.
        val r = runCli(
            "--root", ws, "add", "--batch", "-",
            stdin = "﻿" + """[{"title":"Piped from PowerShell","body":"body text"}]""",
        )
        assertEquals(0, r.exit, "a BOM-prefixed payload must parse: ${r.stderr}")
        val id = idsOf(r).single()
        assertEquals("Piped from PowerShell", field(ws, id, "title"))
        val body = runCli("--root", ws, "get", id, "-b")
        assertEquals("body text", body.stdout.trim())
        assertTrue(!body.stdout.contains('﻿'), "no FEFF may survive into the body")
    }

    // --- atomicity, observed through the real store ------------------------------------------

    @Test
    fun aRejectedBatchCreatesNothingAtAll() {
        val ws = newWorkspace()
        val r = runCli(
            "--root", ws, "add", "--batch", "-",
            stdin = """[{"title":"First"},{"title":"Second"},{"title":"Third","priority":"urgent"}]""",
        )
        assertEquals(3, r.exit, "a well-formed but invalid batch is VALIDATION(3): ${r.stderr}")
        assertTrue(r.stderr.contains("batch record 2"), "name the offending row: ${r.stderr}")
        assertTrue(r.stderr.contains("invalid priority"), "name the offending field: ${r.stderr}")
        assertEquals("", r.stdout.trim(), "no ids on a failed batch")
        // The load-bearing assertion: rows 0 and 1 were valid and would have been written by
        // any write-as-you-go implementation.
        val list = runCli("--root", ws, "list", "--id-only")
        assertEquals("", list.stdout.trim(), "a rejected batch must leave the store EMPTY")
    }

    @Test
    fun aForwardRefIsRejectedWithNoUsageDump() {
        val ws = newWorkspace()
        val r = runCli(
            "--root", ws, "add", "--batch", "-",
            stdin = """[{"title":"Gate","depends":["w1"]},{"ref":"w1","title":"Work"}]""",
        )
        assertEquals(3, r.exit, "stderr=${r.stderr}")
        assertTrue(r.stderr.contains("batch record 0"), "name the row: ${r.stderr}")
        assertTrue(r.stderr.contains("w1"), "name the ref: ${r.stderr}")
        // The t-wezr contract: a specific message, never a usage dump.
        assertTrue(!r.stderr.contains("Usage: "), "no usage dump on an error: ${r.stderr}")
        assertEquals("", runCli("--root", ws, "list", "--id-only").stdout.trim())
    }

    @Test
    fun malformedJsonIsAUsageErrorWithNoUsageDump() {
        val ws = newWorkspace()
        val r = runCli("--root", ws, "add", "--batch", "-", stdin = """[{"title":"A"""")
        assertEquals(2, r.exit, "an unparseable payload is USAGE(2): ${r.stderr}")
        assertTrue(r.stderr.contains("not valid JSON"), "name the problem: ${r.stderr}")
        assertTrue(!r.stderr.contains("Usage: "), "no usage dump on an error: ${r.stderr}")
    }

    // --- guards on the add surface -------------------------------------------------------

    @Test
    fun addWithNeitherTitleNorBatchNamesBothOptions() {
        val ws = newWorkspace()
        val r = runCli("--root", ws, "add")
        assertEquals(2, r.exit, "stderr=${r.stderr}")
        assertTrue(r.stderr.contains("--batch"), "the message must name the alternative: ${r.stderr}")
        assertTrue(!r.stderr.contains("Usage: "), "no usage dump on an error: ${r.stderr}")
    }

    @Test
    fun batchCombinedWithASingleAddFlagIsRefusedRatherThanIgnored() {
        val ws = newWorkspace()
        // Silently dropping `-b body` would destroy input the caller believed they supplied.
        val r = runCli("--root", ws, "add", "--batch", "-", "-b", "orphaned body", stdin = """[{"title":"A"}]""")
        assertEquals(2, r.exit, "stderr=${r.stderr}")
        assertTrue(r.stderr.contains("--body"), "name the conflicting flag: ${r.stderr}")
        assertEquals("", runCli("--root", ws, "list", "--id-only").stdout.trim(), "the guard must fire before any write")
    }

    @Test
    fun batchCombinedWithATitleIsRefused() {
        val ws = newWorkspace()
        val r = runCli("--root", ws, "add", "A title", "--batch", "-", stdin = """[{"title":"A"}]""")
        assertEquals(2, r.exit, "stderr=${r.stderr}")
        assertTrue(r.stderr.contains("<title>"), "name the conflict: ${r.stderr}")
    }

    @Test
    fun anEmptyBatchArrayIsRejected() {
        val ws = newWorkspace()
        val r = runCli("--root", ws, "add", "--batch", "-", stdin = "[]")
        assertEquals(3, r.exit, "stderr=${r.stderr}")
        assertTrue(r.stderr.contains("no records"), "stderr=${r.stderr}")
    }
}
