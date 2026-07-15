package io.taskkling.core

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * `doctor`'s resolution report (t-8der): which binary is running, which rule of
 * the resolution chain picks it, which workspace it is pointed at, and what that
 * store actually holds.
 *
 * It exists because those are the questions agents cannot answer without
 * archaeology, and answering them wrong is expensive in a specific way: a real
 * id against the wrong store answers "unknown id", which reads as a typo rather
 * than as a wrong workspace. The subtlety the report states every time, because
 * nobody writes it down: **`TASKKLING_BINARY` picks the BINARY, the cwd picks
 * the WORKSPACE, and they resolve independently** — borrowing another checkout's
 * binary never moves the store, and `cd`-ing never changes the tool.
 *
 * Facts only; no verdicts. In particular this deliberately does NOT say whether
 * a workspace "looks like" a demo sandbox. `init --demo-mode` writes
 * `tasks_dir = ".taskkling/tasks"` (ADR-017), but so does this project's own
 * canonical backlog, so any such check would flag the single most important
 * store there is as a fixture — worse than the confusion it would end. The
 * missing marker is tracked separately (t-qyo9). The workspace ROOT is the
 * honest discriminator, and it is the second thing in the report.
 *
 * Scope: `doctor` runs in the shell and reports the SHELL's resolution. It
 * cannot report what the UI's process resolved (t-hn1g) — different process,
 * different environment. What it can do is state the chain's outcome from here,
 * which is the same chain the UI walks.
 *
 * The wording is the product: it is what an agent reads to orient, so it lives
 * here against [CliOutput] and is asserted in `DoctorRunTest`, not eyeballed.
 */

/** `doctor`'s flags, as :cli parsed them. (`--fix` belongs to the scan, which is still a stub.) */
public data class DoctorVerbArgs(
    val root: String? = null,
    val quiet: Boolean = false,
)

/**
 * `doctor`'s impure surface (see [UpdateEffects] for the shape's rationale).
 * Everything the report states is read through here, so a test can place a whole
 * machine — env, cwd, PATH, a running binary, a workspace tree — in a
 * `FakeFileSystem` and assert the exact lines.
 */
public class DoctorEffects(
    public val fs: FileSystem,
    /** This binary's version — the one the report prints (`Taskkling.VERSION` in production). */
    public val version: String,
    public val runningExecutable: () -> Path,
    public val cwd: () -> Path,
    public val readEnv: (String) -> String?,
    /** `PATH`, already split into directories ([systemPathEntries]). */
    public val pathEntries: () -> List<Path>,
)

/** The production bundle: the real process, the real filesystem, the real environment. */
public fun productionDoctorEffects(): DoctorEffects = DoctorEffects(
    fs = FileSystem.SYSTEM,
    version = Taskkling.VERSION,
    runningExecutable = ::runningExecutablePath,
    // The same cwd Workspace.discover() walks up from, resolved the same way.
    cwd = { FileSystem.SYSTEM.canonicalize(".".toPath()) },
    readEnv = ::readEnvVar,
    pathEntries = ::systemPathEntries,
)

/**
 * One rule of the binary-resolution chain, in the order it is tried
 * (`CliDiscovery`, ui/CliClient.kt:7-16). [ordinal] + 1 is the "rule N of 4" the
 * report prints.
 */
public enum class BinaryRule(public val label: String) {
    ENV("TASKKLING_BINARY"),
    LOCAL_BIN("up-tree .taskkling/bin"),
    CONFIG("config binary_path"),
    PATH("PATH"),
}

/** How the workspace root was arrived at — the report's "did discovery walk up?" answer. */
public enum class RootDiscovery { ROOT_FLAG, CWD, WALKED_UP }

/** The store `doctor` resolved: where it is, how it was found, and what it holds. */
public data class DoctorWorkspaceFacts(
    val root: String,
    val discovery: RootDiscovery,
    /** Where discovery started — only meaningful (and only printed) when it had to walk up. */
    val cwd: String,
    /** `tasks_dir` verbatim from `config.toml` — quoted next to the absolute path it resolves to. */
    val tasksDirSetting: String,
    /** [tasksDirSetting] resolved against [root]: the directory the tool will actually read and write. */
    val tasksDir: String,
    val activeTasks: Int,
)

/**
 * Everything the report states, gathered once ([gatherDoctorFacts]) and rendered
 * once ([doctorResolutionLines]). Split so the wording is testable without a
 * filesystem and the resolution is testable without parsing text.
 *
 * [chainBinary]/[chainRule] describe what the CHAIN resolves from this cwd+env;
 * [runningBinary] is what the OS says is actually executing. Usually the same
 * file — but not necessarily, which is the whole reason both are recorded:
 * invoking a binary by explicit path bypasses the chain entirely, and then
 * `taskkling` typed at the prompt would run something else.
 */
public data class DoctorFacts(
    val runningBinary: String,
    val version: String,
    val chainRule: BinaryRule?,
    val chainBinary: String?,
    /** Whether [chainBinary] IS [runningBinary] (same file, canonicalized) — i.e. whether "which rule won" has an answer. */
    val chainSelectsRunning: Boolean,
    /** null = not inside a workspace at all, which is itself the answer worth printing. */
    val workspace: DoctorWorkspaceFacts?,
)

// --- rendering (the product) -----------------------------------------------------------------

/** Wide enough for the longest label ("active tasks"), so every value starts in the same column. */
private const val LABEL_WIDTH = 14

private fun row(label: String, value: String): String = "  " + label.padEnd(LABEL_WIDTH) + value

/** The chain, spelled out once per report so the "rule N of 4" above it is self-describing. */
private val CHAIN_LEGEND: String = BinaryRule.entries.joinToString(" -> ") { it.label }

/**
 * The independence note. This is the line the ticket exists for: every other row
 * is a fact you could dig out, this is the one nobody knows to look for.
 */
internal const val INDEPENDENCE_NOTE: String =
    "  TASKKLING_BINARY picks the binary; the cwd picks the workspace. They resolve independently."

/** Printed after the report: `doctor`'s advertised integrity scan is still unimplemented (PRD §19). */
internal const val SCAN_STUB_NOTE: String =
    "taskkling: doctor's integrity scan is still a post-v0.1 stub (PRD §19) — the report above is all it checks, " +
        "and --fix has nothing to apply yet"

/** The report, as the lines to print. Pure: every fact is already in [facts]. */
public fun doctorResolutionLines(facts: DoctorFacts): List<String> = buildList {
    add("resolution")
    add(row("binary", facts.runningBinary))
    add(row("binary rule", binaryRuleValue(facts)))
    add(row("chain", CHAIN_LEGEND))
    // Only when the chain does NOT lead here: naming the binary it leads to instead
    // is the actionable half (that is what `taskkling` at the prompt, and the UI,
    // would run). When it does lead here the answer is already two rows up.
    if (!facts.chainSelectsRunning) add(row("chain picks", chainPicksValue(facts)))
    add(row("version", facts.version))
    addAll(workspaceRows(facts.workspace))
    add("")
    add(INDEPENDENCE_NOTE)
}

/** Which rule selected the running binary — or the honest admission that none did. */
private fun binaryRuleValue(facts: DoctorFacts): String = when {
    facts.chainSelectsRunning -> "${facts.chainRule!!.label} (rule ${facts.chainRule.ordinal + 1} of 4)"
    // Not a defect: running a binary by its full path is normal and is exactly how
    // an agent gets here. It just means the chain is not what put it there.
    else -> "none — this binary was invoked by path, not resolved by the chain"
}

private fun chainPicksValue(facts: DoctorFacts): String = when (facts.chainBinary) {
    null -> "nothing — no rule resolves a binary from here"
    else -> "${facts.chainBinary} (rule ${facts.chainRule!!.ordinal + 1} of 4: ${facts.chainRule.label})"
}

private fun workspaceRows(ws: DoctorWorkspaceFacts?): List<String> {
    if (ws == null) {
        // The single most useful thing doctor can say when it is said, so it says it
        // in the same slot the root would occupy rather than as an error.
        return listOf(row("workspace", "none — not inside a taskkling workspace (run 'taskkling init')"))
    }
    return listOf(
        row("workspace", ws.root),
        row("discovery", discoveryValue(ws)),
        row("tasks_dir", "${ws.tasksDir} (config: tasks_dir = \"${ws.tasksDirSetting}\")"),
        row("active tasks", ws.activeTasks.toString()),
    )
}

private fun discoveryValue(ws: DoctorWorkspaceFacts): String = when (ws.discovery) {
    RootDiscovery.ROOT_FLAG -> "--root given, no discovery"
    RootDiscovery.CWD -> "found in the current directory, no walk-up"
    RootDiscovery.WALKED_UP -> "walked up from ${ws.cwd}"
}

// --- gathering --------------------------------------------------------------------------------

/** Run `doctor`: report first, then say what it did not check. */
public fun runDoctorVerb(args: DoctorVerbArgs, fx: DoctorEffects, out: CliOutput) {
    doctorResolutionLines(gatherDoctorFacts(args, fx)).forEach(out::out)
    // The report is the essential output and always prints; the stub caveat is
    // diagnostics, so --quiet drops it (and it goes to stderr either way, keeping
    // stdout scrapeable).
    if (!args.quiet) out.err(SCAN_STUB_NOTE)
}

/** Read the machine: the running binary, the chain from here, and the workspace in scope. */
public fun gatherDoctorFacts(args: DoctorVerbArgs, fx: DoctorEffects): DoctorFacts {
    val cwd = fx.cwd()
    val running = fx.runningExecutable()
    val chain = resolveBinaryChain(fx.fs, cwd, fx.readEnv, fx.pathEntries())
    return DoctorFacts(
        runningBinary = running.toString(),
        version = fx.version,
        chainRule = chain?.first,
        chainBinary = chain?.second?.toString(),
        chainSelectsRunning = chain != null && sameFile(fx.fs, chain.second, running),
        workspace = gatherWorkspaceFacts(args, fx, cwd),
    )
}

/**
 * Resolve the workspace the way every other verb does, but against [DoctorEffects.fs]
 * and without [Workspace.discover]'s one behavior doctor must not inherit: a failed
 * discovery is a REPORT here, not an error. Being lost is precisely when doctor is
 * run, so "not inside a workspace" returns null and gets printed.
 *
 * An explicit `--root` is different — it is the caller ASSERTING a workspace, so a
 * wrong one stays a usage error (exit 2), with [Workspace.discover]'s own wording.
 */
private fun gatherWorkspaceFacts(args: DoctorVerbArgs, fx: DoctorEffects, cwd: Path): DoctorWorkspaceFacts? {
    val fs = fx.fs
    val root: Path
    val discovery: RootDiscovery
    if (args.root != null) {
        root = fs.canonicalize(args.root.toPath())
        if (!fs.exists(root / ".taskkling")) throw TkError(ExitCode.USAGE, "no taskkling workspace at --root $root")
        discovery = RootDiscovery.ROOT_FLAG
    } else {
        root = Workspace.findWorkspaceRoot(fs, cwd) ?: return null
        discovery = if (root == cwd) RootDiscovery.CWD else RootDiscovery.WALKED_UP
    }
    val config = Config.load(fs, root / ".taskkling" / "config.toml")
    // `root / config.tasksDir` is Workspace.tasksDir's own expression: resolving it
    // here means the absolute path printed IS the one the tool will read and write,
    // not a re-derivation that could drift from it.
    val tasksDir = root / config.tasksDir
    return DoctorWorkspaceFacts(
        root = root.toString(),
        discovery = discovery,
        cwd = cwd.toString(),
        tasksDirSetting = config.tasksDir,
        tasksDir = tasksDir.toString(),
        activeTasks = activeTaskCount(fs, tasksDir),
    )
}

/** [Workspace.activeIds]`.size`, counted against [fs] (that method is `FileSystem.SYSTEM`-bound). */
private fun activeTaskCount(fs: FileSystem, tasksDir: Path): Int {
    if (!fs.exists(tasksDir)) return 0
    return fs.list(tasksDir).filter { it.name.endsWith(".md") }.map { idOfFileName(it.name) }.toSet().size
}

// --- the binary-resolution chain --------------------------------------------------------------

private val EXE_NAMES = listOf("taskkling", "taskkling.exe")

/**
 * A candidate binary exists. `CliDiscovery` also requires the executable bit
 * (`File.canExecute`); okio exposes no such probe, and adding a read-side
 * expect/actual across four targets to cover it is not worth it — on Windows,
 * where the confusion this report addresses actually happens, there is no
 * exec bit and `canExecute` collapses to exactly this. The residual divergence
 * is POSIX-only and narrow: a non-executable file named `taskkling` on PATH,
 * which doctor would name and the UI would skip.
 */
private fun binaryExists(fs: FileSystem, p: Path): Boolean = fs.metadataOrNull(p)?.isRegularFile == true

/** [p] and [other] are the same file. Canonicalization is best-effort (as in [installLocalBin]'s guard). */
private fun sameFile(fs: FileSystem, p: Path, other: Path): Boolean {
    fun canon(x: Path): Path = try { fs.canonicalize(x) } catch (_: Exception) { x.normalized() }
    return canon(p) == canon(other)
}

private fun ancestors(start: Path): Sequence<Path> = generateSequence(start) { it.parent }

/**
 * Replay the chain from [cwd] + [readEnv]: the first rule that resolves a binary
 * wins, mirroring `CliDiscovery.locate` (ui/CliClient.kt) — TASKKLING_BINARY,
 * up-tree `.taskkling/bin`, config `binary_path`, PATH. Null when no rule
 * resolves anything, which happens whenever doctor was invoked by full path from
 * outside any pinned checkout.
 *
 * Note this is NOT the order the `./taskkling` wrapper uses (LocalBin.kt: local
 * pin BEFORE the env var, plus a main-checkout step for worktrees). The two
 * chains genuinely differ, so the report never asserts a rule in the abstract —
 * it compares the chain's winner against the binary that is actually running and
 * says so when they part.
 */
internal fun resolveBinaryChain(
    fs: FileSystem,
    cwd: Path,
    readEnv: (String) -> String?,
    pathEntries: List<Path>,
): Pair<BinaryRule, Path>? {
    envBinary(fs, readEnv)?.let { return BinaryRule.ENV to it }
    localBinFromTree(fs, cwd)?.let { return BinaryRule.LOCAL_BIN to it }
    binaryPathFromConfig(fs, cwd)?.let { return BinaryRule.CONFIG to it }
    onPath(fs, pathEntries)?.let { return BinaryRule.PATH to it }
    return null
}

private fun envBinary(fs: FileSystem, readEnv: (String) -> String?): Path? =
    readEnv("TASKKLING_BINARY")?.takeIf { it.isNotBlank() }?.toPath()?.takeIf { binaryExists(fs, it) }

/** Walk up from [cwd] for a self-installed `.taskkling/bin/taskkling[.exe]` (`init --local-bin`). */
private fun localBinFromTree(fs: FileSystem, cwd: Path): Path? =
    ancestors(cwd)
        .flatMap { dir -> EXE_NAMES.asSequence().map { dir / ".taskkling" / "bin" / it } }
        .firstOrNull { binaryExists(fs, it) }

/**
 * Walk up from [cwd] for `.taskkling/config.toml` and read its `binary_path`.
 * The NEAREST config decides and the walk stops there — a blank `binary_path`
 * (the template's own value) is that file's answer, "not configured", not a
 * reason to keep climbing. `CliDiscovery` stops the same way.
 */
private fun binaryPathFromConfig(fs: FileSystem, cwd: Path): Path? {
    val cfg = ancestors(cwd)
        .map { it / ".taskkling" / "config.toml" }
        .firstOrNull { fs.metadataOrNull(it)?.isRegularFile == true }
        ?: return null
    return Config.load(fs, cfg).binaryPath.takeIf { it.isNotBlank() }?.toPath()?.takeIf { binaryExists(fs, it) }
}

private fun onPath(fs: FileSystem, pathEntries: List<Path>): Path? =
    pathEntries.asSequence()
        .flatMap { dir -> EXE_NAMES.asSequence().map { dir / it } }
        .firstOrNull { binaryExists(fs, it) }

/** `PATH`, split into directories. Quoted entries (legal on Windows) are unwrapped; blanks dropped. */
internal fun splitPathVar(value: String?, separator: Char): List<Path> =
    value.orEmpty()
        .split(separator)
        .map { it.trim().trim('"') }
        .filter { it.isNotEmpty() }
        .map { it.toPath() }

/** The `PATH` list separator: `;` on Windows, `:` everywhere else. */
internal fun pathListSeparator(os: HostOs): Char = if (os == HostOs.WINDOWS) ';' else ':'

/** This process's `PATH`, as directories ([DoctorEffects.pathEntries]'s production impl). */
internal fun systemPathEntries(): List<Path> =
    splitPathVar(readEnvVar("PATH"), pathListSeparator(currentHostTarget().first))
