package io.taskkling.cli

import io.taskkling.core.Computed
import io.taskkling.core.ExitCode
import io.taskkling.core.Priority
import io.taskkling.core.Status
import io.taskkling.core.Task
import io.taskkling.core.TkError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Edge-case coverage for the pure CLI helpers extracted into CliHelpers.kt (the `:cli`
 * test seam, t-oxaa). These are the single read+write path's argv-parsing and rendering
 * primitives — exercised here in isolation over plain values, no workspace/IO.
 */
class CliHelpersTest {

    private fun task(
        id: String,
        title: String = "title",
        thread: String? = null,
        status: Status = Status.OPEN,
        waitingOn: String? = null,
        depends: List<String> = emptyList(),
        due: String? = null,
        defer: String? = null,
        priority: Priority = Priority.NORMAL,
        created: String = "2026-01-01T00:00:00Z",
        closed: String? = null,
    ) = Task(
        id = id, title = title, thread = thread, status = status, waitingOn = waitingOn,
        depends = depends, due = due, defer = defer, priority = priority,
        created = created, closed = closed,
    )

    private fun computed(
        ready: Boolean = false,
        blocked: Boolean = false,
        deferred: Boolean = false,
        overdue: Boolean = false,
        resurfaced: Boolean = false,
        blockers: List<String> = emptyList(),
        dependents: List<String> = emptyList(),
    ) = Computed(ready, blocked, deferred, overdue, resurfaced, blockers, dependents)

    // --- flattenDepends (regression guard for t-sy9n) -----------------------------------

    @Test
    fun flattenDependsSplitsCommaSeparatedOccurrences() {
        assertEquals(listOf("a", "b", "c"), flattenDepends(listOf("a,b,c")))
    }

    @Test
    fun flattenDependsUnionsRepeatedAndCommaForms() {
        // `-d a -d b,c` == `-d a,b,c`
        assertEquals(listOf("a", "b", "c"), flattenDepends(listOf("a", "b,c")))
    }

    @Test
    fun flattenDependsDedupesPreservingFirstSeenOrder() {
        // b first appears at index 1 and must keep that slot despite later repeats.
        assertEquals(listOf("a", "b", "c"), flattenDepends(listOf("a", "b", "a", "c", "b")))
    }

    @Test
    fun flattenDependsTrimsSegmentsAndDropsEmpties() {
        // Whitespace around ids is trimmed; stray/leading/trailing commas produce empty
        // segments that are dropped rather than becoming blank ids.
        assertEquals(listOf("a", "b"), flattenDepends(listOf(" a , b ", ",", "a,")))
    }

    @Test
    fun flattenDependsOnEmptyInputIsEmpty() {
        assertEquals(emptyList(), flattenDepends(emptyList()))
    }

    // --- parseOnlyStatuses (cleanup --only, t-m0zn) --------------------------------------

    @Test
    fun parseOnlyStatusesAbsentFlagMeansNoFilter() {
        assertEquals(null, parseOnlyStatuses(emptyList()))
    }

    @Test
    fun parseOnlyStatusesAcceptsRepeatedAndCommaForms() {
        // `--only done --only dropped` == `--only done,dropped`
        assertEquals(setOf(Status.DONE, Status.DROPPED), parseOnlyStatuses(listOf("done", "dropped")))
        assertEquals(setOf(Status.DONE, Status.DROPPED), parseOnlyStatuses(listOf("done,dropped")))
        assertEquals(setOf(Status.DONE), parseOnlyStatuses(listOf("done")))
    }

    @Test
    fun parseOnlyStatusesRejectsNonClosedStatuses() {
        val err = assertFailsWith<TkError> { parseOnlyStatuses(listOf("open")) }
        assertEquals(ExitCode.USAGE, err.exit)
        assertFailsWith<TkError> { parseOnlyStatuses(listOf("done", "waiting")) }
        assertFailsWith<TkError> { parseOnlyStatuses(listOf("bogus")) }
    }

    // --- parseLeadingGlobals (PRD §10.1) ------------------------------------------------

    @Test
    fun parseLeadingGlobalsReadsRootSpaceForm() {
        val g = parseLeadingGlobals(arrayOf("--root", "/w", "list"))
        assertEquals("/w", g.root)
        assertEquals(listOf("list"), g.rest)
    }

    @Test
    fun parseLeadingGlobalsReadsRootEqualsForm() {
        val g = parseLeadingGlobals(arrayOf("--root=/w", "list"))
        assertEquals("/w", g.root)
        assertEquals(listOf("list"), g.rest)
    }

    @Test
    fun parseLeadingGlobalsFoldsQuietAndNoColorBeforeVerb() {
        val g = parseLeadingGlobals(arrayOf("--quiet", "--no-color", "list"))
        assertTrue(g.quiet)
        assertTrue(g.noColor)
        assertEquals(listOf("list"), g.rest)
        // -q is the short alias for --quiet.
        assertTrue(parseLeadingGlobals(arrayOf("-q", "list")).quiet)
    }

    @Test
    fun parseLeadingGlobalsStopsAtTheVerb() {
        // Once the subcommand token is seen, later flags belong to the verb, not the globals:
        // --root here is the verb's own option, so the leading root stays null and every token
        // after `list` passes through untouched.
        val g = parseLeadingGlobals(arrayOf("list", "--root", "x"))
        assertEquals(null, g.root)
        assertEquals(listOf("list", "--root", "x"), g.rest)
    }

    @Test
    fun parseLeadingGlobalsDoesNotMisparseALiteralRootLaterInTheLine() {
        // A `--root` appearing after the verb (e.g. inside body text) must NOT be captured as
        // the global root — the leading scan already stopped at the `add` verb.
        val g = parseLeadingGlobals(arrayOf("add", "title", "--root", "not-a-flag"))
        assertEquals(null, g.root)
        assertEquals(listOf("add", "title", "--root", "not-a-flag"), g.rest)
    }

    @Test
    fun parseLeadingGlobalsPassesUnknownLeadingFlagThrough() {
        // An unrecognised leading flag is not consumed — it flows into rest so kotlinx-cli
        // reports it, rather than being silently swallowed.
        val g = parseLeadingGlobals(arrayOf("--frobnicate", "list"))
        assertEquals(listOf("--frobnicate", "list"), g.rest)
        assertFalse(g.quiet)
        assertEquals(null, g.root)
    }

    @Test
    fun parseLeadingGlobalsOnEmptyArgvYieldsEmptyRest() {
        val g = parseLeadingGlobals(emptyArray())
        assertEquals(emptyList(), g.rest)
        assertEquals(null, g.root)
        assertFalse(g.quiet)
        assertFalse(g.noColor)
    }

    // --- bodyArg: the `-` = read-stdin convention (t-gmb9) ------------------------------

    @Test
    fun bodyArgReturnsLiteralTextUnchanged() {
        // Any value other than the bare `-` sentinel is passed through verbatim; the stdin
        // reader must never fire.
        var read = false
        val out = bodyArg("hello world") { read = true; "STDIN" }
        assertEquals("hello world", out)
        assertFalse(read, "stdin must not be read for literal body text")
    }

    @Test
    fun bodyArgDashReadsFromStdin() {
        val out = bodyArg("-") { "piped body\nsecond line" }
        assertEquals("piped body\nsecond line", out)
    }

    @Test
    fun bodyArgTreatsOnlyTheBareDashAsTheSentinel() {
        // `--` or `-x` are ordinary text, not the stdin sentinel.
        assertEquals("--", bodyArg("--") { "STDIN" })
        assertEquals("-x", bodyArg("-x") { "STDIN" })
    }

    // --- bodyArg: BOM hygiene at the stdin boundary (t-zsh6) ----------------------------

    @Test
    fun bodyArgStripsTheBomPowerShellPrependsToPipedStdin() {
        // PS 5.1 prepends U+FEFF to every piped payload. Un-stripped it lands in the body —
        // and for a `--batch` payload it makes the JSON parse fail outright.
        assertEquals("piped body", bodyArg("-") { "﻿piped body" })
    }

    @Test
    fun bodyArgStripsOnlyOneLeadingBomAndKeepsInteriorFeff() {
        // A FEFF anywhere but the front is content (zero-width joiner in real text).
        assertEquals("zero﻿width", bodyArg("-") { "﻿zero﻿width" })
    }

    @Test
    fun bodyArgLeavesALiteralArgumentsBomAlone() {
        // Only the pipe inserts a BOM; a BOM in an argv value was typed, so it is content.
        assertEquals("﻿literal", bodyArg("﻿literal") { "STDIN" })
    }

    // --- decodeBatch: the `add --batch -` wire schema (t-zsh6) ---------------------------

    @Test
    fun decodeBatchReadsTheFullRecordSurfaceMirroringAddsFlags() {
        val batch = decodeBatch(
            """
            [{"ref":"w1","title":"Work","thread":"dx","status":"waiting","req":"sign-off",
              "depends":["t-aaaa"],"due":"2026-07-31","defer":"2026-07-01","priority":"high",
              "body":"# Heading\n\nmulti-line"}]
            """.trimIndent(),
        )
        val r = batch.single()
        assertEquals("w1", r.ref)
        assertEquals("Work", r.args.title)
        assertEquals("dx", r.args.thread)
        assertEquals("waiting", r.args.status)
        assertEquals("sign-off", r.args.req)
        assertEquals(listOf("t-aaaa"), r.args.depends)
        assertEquals("2026-07-31", r.args.due)
        assertEquals("2026-07-01", r.args.defer)
        assertEquals("high", r.args.priority)
        // The reason the format is JSON at all: a body with real newlines.
        assertEquals("# Heading\n\nmulti-line", r.args.body)
    }

    @Test
    fun decodeBatchDefaultsEveryFieldButTitle() {
        val r = decodeBatch("""[{"title":"Bare"}]""").single()
        assertEquals("Bare", r.args.title)
        assertEquals(null, r.ref)
        assertEquals(null, r.args.thread)
        assertEquals(emptyList(), r.args.depends)
        assertEquals(null, r.args.body)
    }

    @Test
    fun decodeBatchPreservesRecordOrder() {
        val batch = decodeBatch("""[{"title":"A"},{"title":"B"},{"title":"C"}]""")
        assertEquals(listOf("A", "B", "C"), batch.map { it.args.title })
    }

    @Test
    fun decodeBatchGivesDependsTheSameCommaGrammarAsTheDFlag() {
        // `-d a,b` == `-d a -d b`, so ["a,b"] must mean ["a","b"] too (flattenDepends).
        val r = decodeBatch("""[{"title":"A","depends":["t-a,t-b"," t-c "]}]""").single()
        assertEquals(listOf("t-a", "t-b", "t-c"), r.args.depends)
    }

    @Test
    fun decodeBatchRejectsMalformedJsonAsAUsageError() {
        val e = assertFailsWith<TkError> { decodeBatch("[{\"title\":\"A\"") }
        assertEquals(ExitCode.USAGE, e.exit, "an unparseable payload is a malformed argument")
        assertTrue(e.message!!.contains("not valid JSON"), "message must name the problem: ${e.message}")
    }

    @Test
    fun decodeBatchRejectsAnUnknownKeyRatherThanDroppingIt() {
        // A typo'd `titel` would otherwise create a task silently missing the field the
        // caller believed they set — the batch equivalent of a typo'd flag.
        val e = assertFailsWith<TkError> { decodeBatch("""[{"title":"A","titel":"oops"}]""") }
        assertEquals(ExitCode.USAGE, e.exit)
        assertTrue(e.message!!.contains("titel"), "message must name the unknown key: ${e.message}")
    }

    @Test
    fun decodeBatchRejectsARecordWithNoTitle() {
        val e = assertFailsWith<TkError> { decodeBatch("""[{"thread":"dx"}]""") }
        assertEquals(ExitCode.USAGE, e.exit)
        assertTrue(e.message!!.contains("title"), "message must name the missing field: ${e.message}")
    }

    @Test
    fun decodeBatchRejectsAJsonObjectThatIsNotAnArray() {
        val e = assertFailsWith<TkError> { decodeBatch("""{"title":"A"}""") }
        assertEquals(ExitCode.USAGE, e.exit)
    }

    // --- buildAttrs: folded attrs column (PRD §10.2) ------------------------------------

    @Test
    fun buildAttrsIsEmptyWhenNoRelationalOrTimeFields() {
        assertEquals("", buildAttrs(task("t-1")))
    }

    @Test
    fun buildAttrsFoldsFieldsInFixedOrderJoinedByTwoSpaces() {
        val attrs = buildAttrs(
            task(
                "t-1",
                depends = listOf("a", "b"),
                due = "2026-07-31",
                defer = "2026-07-01",
                waitingOn = "review",
            ),
        )
        assertEquals("depends:a,b  due:2026-07-31  defer:2026-07-01  waiting:review", attrs)
    }

    @Test
    fun buildAttrsOmitsAbsentFields() {
        // Only `due` is set — no depends/defer/waiting segments appear.
        assertEquals("due:2026-07-31", buildAttrs(task("t-1", due = "2026-07-31")))
    }

    // --- formatTable: column alignment + attrs column (PRD §10.2) -----------------------

    @Test
    fun formatTableEmptyRowsYieldEmptyString() {
        assertEquals("", formatTable(emptyList()))
    }

    @Test
    fun formatTableAlignsColumnsToTheWidestCell() {
        // idW=3 (bbb), titleW=3 (one), threadW=2 (th vs the "-" placeholder), statusW=4 (open).
        // Columns are padEnd'd to those widths and separated by two spaces.
        val out = formatTable(
            listOf(
                task(id = "a", title = "one", thread = "th"),
                task(id = "bbb", title = "t", thread = null),
            ),
        )
        assertEquals(
            "a    one  th  open\n" +
                "bbb  t    -   open",
            out,
        )
    }

    @Test
    fun formatTableEmptyThreadRendersAsDashPlaceholder() {
        val out = formatTable(listOf(task(id = "x", title = "y", thread = null)))
        assertTrue(out.contains("-"), "a null thread is shown as '-'")
    }

    @Test
    fun formatTableAppendsTheFoldedAttrsColumnOnlyWhenPresent() {
        val out = formatTable(
            listOf(
                task(id = "a1", title = "has", depends = listOf("d1")),
                task(id = "a2", title = "none"),
            ),
        )
        val lines = out.split("\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].endsWith("depends:d1"), "row with a dependency carries the attrs column")
        assertTrue(lines[1].trimEnd().endsWith("open"), "row with no attrs ends at the status column")
    }

    @Test
    fun formatTableTruncatesOverlongTitlesWithAnEllipsis() {
        val long = "a".repeat(60)
        val out = formatTable(listOf(task(id = "t", title = long)))
        assertTrue(out.contains("a".repeat(49) + "…"), "title truncated to 49 chars + ellipsis")
        assertFalse(out.contains("a".repeat(51)), "the full over-length title is not emitted")
    }

    // --- fieldMap: ordered stored + computed projection (PRD §8.1/§8.2) -----------------

    @Test
    fun fieldMapProjectsStoredAndComputedInStableOrder() {
        val t = task(
            id = "t-1",
            title = "Do the thing",
            thread = "v0.5.2-tests",
            status = Status.WAITING,
            waitingOn = "review",
            depends = listOf("t-a", "t-b"),
            due = "2026-07-31T00:00:00Z",
            defer = "2026-07-10T00:00:00Z",
            priority = Priority.HIGH,
            created = "2026-01-01T00:00:00Z",
            closed = null,
        )
        val c = computed(
            ready = false, blocked = true, deferred = true,
            blockers = listOf("t-a"), dependents = listOf("t-z"),
        )
        val map = fieldMap(t, c)

        // Order is contractual (agents scrape `get -i` line-by-line): stored fields first,
        // then computed.
        assertEquals(
            listOf(
                "id", "title", "thread", "status", "req", "waiting_on", "depends", "due", "defer",
                "priority", "created", "closed", "ready", "blocked", "deferred", "overdue",
                "resurfaced", "blockers", "dependents",
            ),
            map.keys.toList(),
        )
        assertEquals("t-1", map["id"])
        assertEquals("waiting", map["status"])
        assertEquals("review", map["req"], "req aliases the external requirement (ADR-018)")
        assertEquals("review", map["waiting_on"])
        assertEquals("t-a,t-b", map["depends"])
        assertEquals("high", map["priority"])
        assertEquals("true", map["blocked"])
        assertEquals("t-a", map["blockers"])
        assertEquals("t-z", map["dependents"])
    }

    @Test
    fun fieldMapRendersAbsentOptionalsAsEmptyStrings() {
        val map = fieldMap(task(id = "t-1", title = "bare"), computed())
        assertEquals("", map["thread"])
        assertEquals("", map["req"])
        assertEquals("", map["waiting_on"])
        assertEquals("", map["due"])
        assertEquals("", map["defer"])
        assertEquals("", map["closed"])
        assertEquals("", map["depends"])
        assertEquals("normal", map["priority"])
    }

    // --- parse-failure rendering (t-wezr) ------------------------------------------------
    //
    // The blobs below are kotlinx-cli's REAL output shape: the specific complaint, then
    // `makeUsage()` — which always opens "Usage: <command> options_list". Captured from the
    // shipped binary so these tests break if that shape ever moves.

    private val setUsageDump = """
        Usage: taskkling set options_list
        Arguments:
            id -> Task id { String }
        Options:
            --title -> Set title { String }
            --help, -h -> Usage info
    """.trimIndent()

    @Test
    fun stripUsageDumpKeepsOnlyTheSpecificComplaint() {
        assertEquals(
            "Unknown option --depends",
            stripUsageDump("Unknown option --depends\n$setUsageDump"),
        )
    }

    @Test
    fun stripUsageDumpReturnsAMessageWithoutADumpWhole() {
        // Defensive: never truncate an unrecognised shape to nothing.
        assertEquals("something unexpected", stripUsageDump("something unexpected"))
    }

    @Test
    fun offendingArgumentPlucksTheStrayToken() {
        assertEquals("show", offendingArgument("Too many arguments! Couldn't process argument show!"))
        assertEquals("t-a1b2", offendingArgument("Too many arguments! Couldn't process argument t-a1b2!"))
    }

    @Test
    fun offendingArgumentIsNullForOtherMessages() {
        assertEquals(null, offendingArgument("Unknown option --depends"))
        assertEquals(null, offendingArgument("No value for id"))
    }

    @Test
    fun setDependsIsPointedAtTheLinkVerb() {
        // The DoD's named case: --depends is not a `set` field, and the fix must say so
        // AND name the verb that does work.
        val lines = parseErrorLines("set", "Unknown option --depends\n$setUsageDump")
        assertEquals("Unknown option --depends", lines[0])
        assertTrue(lines[1].contains("taskkling link <id> -d <dep>"), "names the right verb: $lines")
        assertTrue(lines.none { it.contains("Usage:") }, "no usage dump survives: $lines")
    }

    @Test
    fun shortDependsOnAVerbThatLacksItIsAlsoPointedAtLink() {
        val lines = parseErrorLines("done", "Unknown option -d\n$setUsageDump")
        assertTrue(lines[1].contains("taskkling link <id> -d <dep>"), "$lines")
    }

    @Test
    fun misorderedLinkArgsSaysTheDependencyIsAFlag() {
        val lines = parseErrorLines("link", "Too many arguments! Couldn't process argument t-a1b2!\n$setUsageDump")
        assertEquals("Too many arguments! Couldn't process argument t-a1b2!", lines[0])
        assertTrue(lines[1].contains("taskkling link <id> -d <dep>"), "$lines")
    }

    @Test
    fun misorderedUnlinkArgsNamesUnlinkNotLink() {
        val lines = parseErrorLines("unlink", "Too many arguments! Couldn't process argument t-a1b2!\n$setUsageDump")
        assertTrue(lines[1].contains("taskkling unlink <id> -d <dep>"), "$lines")
    }

    @Test
    fun aStrayTokenOnTheRootParserIsRephrasedAsAnUnknownVerb() {
        // The root parser takes no positionals, so kotlinx-cli's "Too many arguments!" is an
        // actively misleading way to say "no such verb" — it must not be passed through.
        val lines = parseErrorLines(null, "Too many arguments! Couldn't process argument bogus!\n$setUsageDump")
        assertEquals("unknown verb 'bogus'", lines[0])
        assertTrue(lines.none { it.contains("Too many arguments") }, "kotlinx-cli's wording is replaced: $lines")
        assertTrue(lines[1].contains("--help"), "points at the verb list: $lines")
    }

    @Test
    fun theShowVerbIsPointedAtGet() {
        val lines = parseErrorLines(null, "Too many arguments! Couldn't process argument show!\n$setUsageDump")
        assertEquals("unknown verb 'show'", lines[0])
        assertEquals("did you mean: taskkling get <id>", lines[1])
    }

    @Test
    fun anUnknownOptionWithNoObviousFixGetsNoInventedSuggestion() {
        // The table is fixed, not fuzzy: silence beats a guess.
        val lines = parseErrorLines("set", "Unknown option --nonsense\n$setUsageDump")
        assertEquals(listOf("Unknown option --nonsense"), lines)
    }
}
