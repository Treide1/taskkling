package io.taskkling.core

import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `doctor`'s resolution report (t-8der). The report IS the feature — it is what an
 * agent reads to answer "which binary am I running and which store am I pointed
 * at?" — so these tests assert the exact lines, not that something plausible got
 * printed.
 *
 * A whole machine (cwd, env, PATH, running binary, workspace tree) is placed in a
 * `FakeFileSystem`, which is what makes the two claims the report rests on
 * falsifiable rather than reviewable: that a nested cwd changes the *workspace*
 * half and nothing else, and that `TASKKLING_BINARY` changes the *binary* half
 * and nothing else. Those two facts are the ticket's whole point, and neither can
 * be shown by running doctor once and eyeballing it.
 */
class DoctorRunTest {

    /** A fake machine doctor can be pointed at. Each field is one of [DoctorEffects]' reads. */
    private class Machine {
        val fs = FakeFileSystem()
        var cwd: Path = "/home/dev/proj".toPath()
        var running: Path = "/home/dev/proj/.taskkling/bin/taskkling".toPath()
        val env: MutableMap<String, String> = mutableMapOf()
        var path: List<Path> = emptyList()

        fun effects(): DoctorEffects = DoctorEffects(
            fs = fs,
            version = "0.6.3",
            runningExecutable = { running },
            cwd = { cwd },
            readEnv = { env[it] },
            pathEntries = { path },
        )

        /** Put an executable-looking file at [p]. */
        fun binary(p: String): Path {
            val path = p.toPath()
            path.parent?.let { fs.createDirectories(it) }
            fs.write(path) { writeUtf8("MZ") }
            return path
        }

        /** Scaffold a workspace at [root]: config, tasks dir, and [tasks] task files. */
        fun workspace(
            root: String,
            tasksDir: String = "tasks",
            tasks: List<String> = emptyList(),
            binaryPath: String? = null,
        ) {
            val r = root.toPath()
            fs.createDirectories(r / ".taskkling")
            fs.write(r / ".taskkling" / "config.toml") {
                writeUtf8("tasks_dir = \"$tasksDir\"\n")
                if (binaryPath != null) writeUtf8("binary_path = \"$binaryPath\"\n")
            }
            fs.createDirectories(r / tasksDir)
            for (t in tasks) fs.write(r / tasksDir / "$t--some-title.md") { writeUtf8("---\n") }
        }

        fun report(args: DoctorVerbArgs = DoctorVerbArgs()): List<String> =
            doctorResolutionLines(gatherDoctorFacts(args, effects()))
    }

    /**
     * The value of one `label` row, unpadded — lets a test name the fact it cares
     * about. Labels are column-aligned, so the padded label is the exact prefix;
     * matching on the bare one would make `binary` also select `binary rule`.
     */
    private fun List<String>.row(label: String): String =
        single { it.startsWith("  " + label.padEnd(LABEL_WIDTH_FOR_TEST)) }
            .substring(2 + LABEL_WIDTH_FOR_TEST)

    private companion object {
        /** Mirrors DoctorRun's private LABEL_WIDTH; the golden test below pins the real alignment. */
        const val LABEL_WIDTH_FOR_TEST = 14
    }

    // --- the report, whole ------------------------------------------------------------------

    @Test
    fun reportsEveryFactForAPinnedBinaryInANestedDirectory() {
        // The common case this ticket is about: a checkout with its own pinned binary,
        // a store whose tasks_dir deviates from the default, and a cwd somewhere inside.
        val m = Machine()
        m.workspace("/home/dev/proj", tasksDir = ".taskkling/tasks", tasks = listOf("t-aaaa", "t-bbbb", "t-cccc"))
        m.binary("/home/dev/proj/.taskkling/bin/taskkling")
        m.cwd = "/home/dev/proj/ui/src".toPath()
        m.fs.createDirectories(m.cwd)

        assertEquals(
            listOf(
                "resolution",
                "  binary        /home/dev/proj/.taskkling/bin/taskkling",
                "  binary rule   up-tree .taskkling/bin (rule 2 of 4)",
                "  chain         TASKKLING_BINARY -> up-tree .taskkling/bin -> config binary_path -> PATH",
                "  version       0.6.3",
                "  workspace     /home/dev/proj",
                "  discovery     walked up from /home/dev/proj/ui/src",
                "  tasks_dir     /home/dev/proj/.taskkling/tasks (config: tasks_dir = \".taskkling/tasks\")",
                "  active tasks  3",
                "",
                "  TASKKLING_BINARY picks the binary; the cwd picks the workspace. They resolve independently.",
            ),
            m.report(),
        )
    }

    // --- the workspace half: cwd picks the store --------------------------------------------

    @Test
    fun aNestedCwdWalksUpAndSaysSo() {
        val m = Machine()
        m.workspace("/home/dev/proj")
        m.binary("/home/dev/proj/.taskkling/bin/taskkling")

        // At the root: no walk-up.
        m.cwd = "/home/dev/proj".toPath()
        assertEquals("found in the current directory, no walk-up", m.report().row("discovery"))
        val atRoot = m.report().row("workspace")

        // Three levels down: same store, and the report says how it got there.
        m.cwd = "/home/dev/proj/a/b/c".toPath()
        m.fs.createDirectories(m.cwd)
        assertEquals("walked up from /home/dev/proj/a/b/c", m.report().row("discovery"))
        // The point of the walk-up: the resolved store did NOT move.
        assertEquals(atRoot, m.report().row("workspace"))
        assertEquals("/home/dev/proj", m.report().row("workspace"))
    }

    @Test
    fun aDeeperWorkspaceWinsOverAnAncestorOne() {
        // Discovery stops at the NEAREST .taskkling — the nested store is a different
        // store, and reporting the outer root here would be exactly the wrong answer.
        val m = Machine()
        m.workspace("/home/dev/proj", tasks = listOf("t-outer"))
        m.workspace("/home/dev/proj/sandbox", tasks = listOf("t-inner1", "t-inner2"))
        m.cwd = "/home/dev/proj/sandbox".toPath()

        assertEquals("/home/dev/proj/sandbox", m.report().row("workspace"))
        assertEquals("2", m.report().row("active tasks"))
    }

    @Test
    fun tasksDirIsReportedAbsoluteAlongsideItsConfiguredValue() {
        // The relative setting alone is what makes "which store" unanswerable: two
        // workspaces carry the identical `tasks_dir = ".taskkling/tasks"`. The absolute
        // path is the fact; the setting is quoted so the config is still traceable.
        val m = Machine()
        m.workspace("/srv/other", tasksDir = "backlog/items", tasks = listOf("t-one"))
        m.cwd = "/srv/other".toPath()

        assertEquals(
            "/srv/other/backlog/items (config: tasks_dir = \"backlog/items\")",
            m.report().row("tasks_dir"),
        )
    }

    @Test
    fun aWorkspaceWithNoTasksDirYetCountsZeroRatherThanFailing() {
        val m = Machine()
        val r = "/home/dev/fresh".toPath()
        m.fs.createDirectories(r / ".taskkling")
        m.fs.write(r / ".taskkling" / "config.toml") { writeUtf8("tasks_dir = \"tasks\"\n") }
        m.cwd = r

        assertEquals("0", m.report().row("active tasks"))
    }

    @Test
    fun outsideAnyWorkspaceItSaysSoInsteadOfFailing() {
        // Being lost is precisely when doctor gets run, so this must be a report.
        val m = Machine()
        m.cwd = "/tmp/nowhere".toPath()
        m.fs.createDirectories(m.cwd)
        val lines = m.report()

        assertEquals("none — not inside a taskkling workspace (run 'taskkling init')", lines.row("workspace"))
        // The binary half is still answered — it is half of what you came for.
        assertEquals("0.6.3", lines.row("version"))
        assertTrue(lines.none { it.startsWith("  tasks_dir") }, "no store means no tasks_dir claim: $lines")
        assertTrue(lines.none { it.startsWith("  active tasks") }, "no store means no task count: $lines")
    }

    @Test
    fun rootFlagBypassesDiscoveryEntirely() {
        val m = Machine()
        m.workspace("/home/dev/proj", tasks = listOf("t-aaaa"))
        m.workspace("/srv/elsewhere", tasks = listOf("t-x", "t-y"))
        m.cwd = "/home/dev/proj".toPath()

        val lines = m.report(DoctorVerbArgs(root = "/srv/elsewhere"))
        assertEquals("/srv/elsewhere", lines.row("workspace"))
        assertEquals("--root given, no discovery", lines.row("discovery"))
        assertEquals("2", lines.row("active tasks"))
    }

    @Test
    fun anExplicitRootThatIsNotAWorkspaceStaysAUsageError() {
        // --root is the caller asserting a store. A wrong assertion is an error (exit 2),
        // not a fact to report — unlike a failed discovery, which is.
        val m = Machine()
        m.fs.createDirectories("/srv/empty".toPath())
        m.cwd = "/srv/empty".toPath()

        val e = assertFailsWith<TkError> { m.report(DoctorVerbArgs(root = "/srv/empty")) }
        assertEquals(ExitCode.USAGE, e.exit)
        assertEquals("no taskkling workspace at --root /srv/empty", e.message)
    }

    // --- the binary half: the chain picks the tool -------------------------------------------

    @Test
    fun envVarOverridesTheLocalPinAndTheReportedRuleChanges() {
        val m = Machine()
        m.workspace("/home/dev/proj")
        m.binary("/home/dev/proj/.taskkling/bin/taskkling")
        m.cwd = "/home/dev/proj".toPath()

        // Without the override, the checkout's own pin wins.
        assertEquals("up-tree .taskkling/bin (rule 2 of 4)", m.report().row("binary rule"))

        // With it, rule 1 wins — and the report names the other binary, because that is
        // now what `taskkling` at this prompt resolves to.
        val other = m.binary("/opt/tk/taskkling")
        m.env["TASKKLING_BINARY"] = other.toString()
        m.running = other
        assertEquals("TASKKLING_BINARY (rule 1 of 4)", m.report().row("binary rule"))
        assertEquals("/opt/tk/taskkling", m.report().row("binary"))
    }

    @Test
    fun theEnvVarMovesTheBinaryAndLeavesTheWorkspaceAlone() {
        // The subtlety the report exists to state, made falsifiable: setting
        // TASKKLING_BINARY must change the binary half and NOTHING about the store.
        val m = Machine()
        m.workspace("/home/dev/proj", tasks = listOf("t-aaaa", "t-bbbb"))
        m.cwd = "/home/dev/proj".toPath()
        m.running = m.binary("/opt/tk/taskkling")
        val before = m.report()

        m.env["TASKKLING_BINARY"] = "/opt/tk/taskkling"
        val after = m.report()

        val storeRows = listOf("workspace", "discovery", "tasks_dir", "active tasks")
        for (label in storeRows) assertEquals(before.row(label), after.row(label), "$label moved with TASKKLING_BINARY")
        // …while the binary half did change: from "not resolved by the chain" to rule 1.
        assertEquals(
            "none — this binary was invoked by path, not resolved by the chain",
            before.row("binary rule"),
        )
        assertEquals("TASKKLING_BINARY (rule 1 of 4)", after.row("binary rule"))
    }

    @Test
    fun aBlankEnvVarIsNotAnOverride() {
        val m = Machine()
        m.workspace("/home/dev/proj")
        m.binary("/home/dev/proj/.taskkling/bin/taskkling")
        m.cwd = "/home/dev/proj".toPath()
        m.env["TASKKLING_BINARY"] = "   "

        assertEquals("up-tree .taskkling/bin (rule 2 of 4)", m.report().row("binary rule"))
    }

    @Test
    fun anEnvVarPointingAtNothingFallsThroughToTheNextRule() {
        val m = Machine()
        m.workspace("/home/dev/proj")
        m.binary("/home/dev/proj/.taskkling/bin/taskkling")
        m.cwd = "/home/dev/proj".toPath()
        m.env["TASKKLING_BINARY"] = "/opt/tk/deleted-yesterday"

        assertEquals("up-tree .taskkling/bin (rule 2 of 4)", m.report().row("binary rule"))
    }

    @Test
    fun configBinaryPathIsRuleThreeAndPathIsRuleFour() {
        val m = Machine()
        val configured = m.binary("/opt/pinned/taskkling")
        m.workspace("/home/dev/proj", binaryPath = configured.toString())
        m.cwd = "/home/dev/proj".toPath()
        m.running = configured
        // No TASKKLING_BINARY and no .taskkling/bin here, so config wins.
        assertEquals("config binary_path (rule 3 of 4)", m.report().row("binary rule"))

        // Blank the setting and PATH is all that is left.
        m.workspace("/home/dev/proj", binaryPath = "")
        val onPath = m.binary("/usr/local/bin/taskkling")
        m.path = listOf("/usr/local/bin".toPath())
        m.running = onPath
        assertEquals("PATH (rule 4 of 4)", m.report().row("binary rule"))
    }

    @Test
    fun theNearestConfigDecidesTheBinaryPathRuleEvenWhenItIsSilent() {
        // CliDiscovery reads the first config.toml it meets and stops. A nested
        // workspace with no binary_path therefore SHADOWS an ancestor that has one:
        // the chain moves to PATH rather than climbing. Mirroring that is the point —
        // a report that "helpfully" kept walking would name a binary the UI never uses.
        val m = Machine()
        m.workspace("/home/dev/proj", binaryPath = m.binary("/opt/ancestor/taskkling").toString())
        m.workspace("/home/dev/proj/nested")
        m.cwd = "/home/dev/proj/nested".toPath()
        val onPath = m.binary("/usr/local/bin/taskkling")
        m.path = listOf("/usr/local/bin".toPath())
        m.running = onPath

        assertEquals("PATH (rule 4 of 4)", m.report().row("binary rule"))
    }

    // --- when the running binary is not what the chain picks ----------------------------------

    @Test
    fun aBinaryInvokedByPathIsNamedAlongsideWhatTheChainWouldPickInstead() {
        // The trap worth catching: you ran one binary by full path, but `taskkling`
        // typed at this prompt would run a different one.
        val m = Machine()
        m.workspace("/home/dev/proj")
        m.binary("/home/dev/proj/.taskkling/bin/taskkling")
        m.cwd = "/home/dev/proj".toPath()
        m.running = m.binary("/tmp/build/taskkling")

        val lines = m.report()
        assertEquals("/tmp/build/taskkling", lines.row("binary"))
        assertEquals("none — this binary was invoked by path, not resolved by the chain", lines.row("binary rule"))
        assertEquals(
            "/home/dev/proj/.taskkling/bin/taskkling (rule 2 of 4: up-tree .taskkling/bin)",
            lines.row("chain picks"),
        )
    }

    @Test
    fun whenNoRuleResolvesAnythingItSaysThatRatherThanGuessing() {
        val m = Machine()
        m.cwd = "/tmp/nowhere".toPath()
        m.fs.createDirectories(m.cwd)
        m.running = m.binary("/tmp/build/taskkling")

        assertEquals("nothing — no rule resolves a binary from here", m.report().row("chain picks"))
    }

    @Test
    fun theChainPicksRowIsAbsentWhenTheChainLeadsToTheRunningBinary() {
        // No news is no line: the row only appears when it is telling you something.
        val m = Machine()
        m.workspace("/home/dev/proj")
        m.running = m.binary("/home/dev/proj/.taskkling/bin/taskkling")
        m.cwd = "/home/dev/proj".toPath()

        assertTrue(m.report().none { it.startsWith("  chain picks") }, m.report().toString())
    }

    // --- output routing ------------------------------------------------------------------------

    @Test
    fun theReportGoesToStdoutAndTheStubCaveatToStderr() {
        val m = Machine()
        m.workspace("/home/dev/proj")
        m.cwd = "/home/dev/proj".toPath()
        val out = RecordingOutput()

        runDoctorVerb(DoctorVerbArgs(), m.effects(), out)

        assertEquals(m.report(), out.stdout)
        // The scan is still a stub, and a silent clean run would imply a checked store.
        assertEquals(listOf(SCAN_STUB_NOTE), out.stderr)
        assertTrue("post-v0.1 stub" in SCAN_STUB_NOTE, SCAN_STUB_NOTE)
    }

    @Test
    fun quietKeepsTheReportAndDropsTheCaveat() {
        // The report is the essential output — suppressing it would make `doctor -q` useless.
        val m = Machine()
        m.workspace("/home/dev/proj")
        m.cwd = "/home/dev/proj".toPath()
        val out = RecordingOutput()

        runDoctorVerb(DoctorVerbArgs(quiet = true), m.effects(), out)

        assertEquals(m.report(), out.stdout)
        assertEquals(emptyList(), out.stderr)
    }

    // --- PATH parsing --------------------------------------------------------------------------

    @Test
    fun pathVarSplitsOnThePlatformSeparatorAndDropsNoise() {
        assertEquals(';', pathListSeparator(HostOs.WINDOWS))
        assertEquals(':', pathListSeparator(HostOs.LINUX))
        assertEquals(':', pathListSeparator(HostOs.MACOS))

        assertEquals(
            listOf("/usr/bin".toPath(), "/opt/tk".toPath()),
            splitPathVar("/usr/bin::/opt/tk:", ':'),
        )
        // Windows PATH entries may be quoted; the quotes are not part of the directory.
        assertEquals(
            listOf("C:\\Program Files\\tk".toPath(), "C:\\bin".toPath()),
            splitPathVar("\"C:\\Program Files\\tk\";C:\\bin", ';'),
        )
        assertEquals(emptyList(), splitPathVar(null, ':'))
        assertEquals(emptyList(), splitPathVar("", ':'))
    }
}
