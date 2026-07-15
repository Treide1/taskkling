package io.taskkling.core

import okio.Path

/**
 * The shared seams the distribution verbs (`update`, `ui`, `uninstall`) run
 * against. Each verb's orchestration lives in :core as a runner
 * ([runUpdateVerb], [runUiVerb], [runUninstallVerb]) that takes a plain args
 * data class, a small per-verb effects bundle, and one of these output sinks —
 * so the download → verify → extract → spawn → self-heal machinery is
 * exercisable with fakes instead of only by hand at release time.
 *
 * The pieces here are the ones more than one verb needs; anything used by a
 * single verb stays in that verb's own effects bundle.
 */

/**
 * Where a runner's user-facing lines go. Runners never `println` themselves:
 * the wording IS behavior (it's what release QA reads), so it has to be
 * assertable. Production writes to the process's stdout/stderr from :cli,
 * which owns the platform's stderr primitive; tests collect the lines.
 *
 * `--quiet` is NOT handled here. The verbs suppress different things under it
 * (`update --check` and `uninstall`'s consequence prompts print regardless), so
 * the gate stays visible at each call site rather than hiding in the sink.
 */
public interface CliOutput {
    /** A line of primary, scriptable output (stdout). */
    public fun out(line: String)

    /** A line of diagnostics (stderr), so stdout stays clean for callers piping it. */
    public fun err(line: String)
}

/**
 * The HTTP GET seam (ADR-002's transport, as the verbs consume it): status
 * code plus body, no retry/backoff. Production is [SystemNet]; tests script
 * per-URL responses and can assert that no call happened at all — which is how
 * the "local checks run before network IO" ordering guarantee (t-6ouc) becomes
 * testable rather than reviewable.
 */
public interface NetEffects {
    /** GET [url] as text — `SHA256SUMS`, the GitHub release JSON. */
    public fun getText(url: String, userAgent: String): Pair<Int, String>

    /** GET [url] as raw bytes — release assets, which can't round-trip through text decoding. */
    public fun getBytes(url: String, userAgent: String): Pair<Int, ByteArray>
}

/** Production [NetEffects]: the real per-target HTTP engine ([httpGetTextBlocking] / [httpGetBytesBlocking]). */
public object SystemNet : NetEffects {
    override fun getText(url: String, userAgent: String): Pair<Int, String> = httpGetTextBlocking(url, userAgent)
    override fun getBytes(url: String, userAgent: String): Pair<Int, ByteArray> = httpGetBytesBlocking(url, userAgent)
}

/**
 * A resolved workspace as the distribution verbs see it — the paths they
 * target plus the facts `uninstall`'s prompts state. This is the seam that
 * keeps the runners off [Workspace]'s own `FileSystem.SYSTEM`-bound methods:
 * tests hand over a value, production hands over [describe]'s view of a real
 * workspace.
 *
 * [taskCount] and [purge] are lazy because only `uninstall` reads them —
 * resolving a workspace for `ui` or `update --local` must not start listing
 * task directories.
 */
public class WorkspaceInfo(
    public val root: Path,
    public val metaDir: Path,
    /** The configured `tasks_dir`, verbatim — `uninstall` quotes it when the purge plan refuses to touch it. */
    public val tasksDirSetting: String,
    taskCount: () -> Int,
    purge: () -> PurgePlan,
) {
    public val taskCount: Int by lazy(taskCount)
    public val purge: PurgePlan by lazy(purge)
}

/** The production view of a real [Workspace]. */
public fun Workspace.describe(): WorkspaceInfo = WorkspaceInfo(
    root = root,
    metaDir = metaDir,
    tasksDirSetting = config.tasksDir,
    taskCount = { allKnownIds().size },
    purge = { purgePlan() },
)

/** Production workspace resolution: discovery must succeed (`--local` and `ui` both require a real workspace). */
public fun requireWorkspaceInfo(rootOverride: String?): WorkspaceInfo = Workspace.discover(rootOverride).describe()

/**
 * Best-effort workspace resolution: `uninstall` is not necessarily run from
 * inside a project (a `--global` removal may have no workspace in scope at all).
 */
public fun findWorkspaceInfo(rootOverride: String?): WorkspaceInfo? =
    try { requireWorkspaceInfo(rootOverride) } catch (_: TkError) { null }
