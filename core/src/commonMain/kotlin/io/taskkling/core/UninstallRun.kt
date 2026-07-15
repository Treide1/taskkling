package io.taskkling.core

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * `uninstall`'s orchestration (ADR-004 / ADR-007 / ADR-011): tier resolution,
 * the consequence prompts, the removal sequence, and the leftover report. The
 * prompts are the whole safety story — they are what stands between `--purge`
 * and someone's task graph — so they live here where a test can read them,
 * rather than only in front of a human at uninstall time.
 */

/** `uninstall`'s flags, as :cli parsed them. */
public data class UninstallVerbArgs(
    val global: Boolean = false,
    val local: Boolean = false,
    val purge: Boolean = false,
    val yes: Boolean = false,
    val quiet: Boolean = false,
    val root: String? = null,
)

/** `uninstall`'s impure surface (see [UpdateEffects] for the shape's rationale). */
public class UninstallEffects(
    public val fs: FileSystem,
    public val runningExecutable: () -> Path,
    public val globalInstallDir: () -> Path,
    public val userCacheDir: () -> Path,
    public val requireWorkspace: (String?) -> WorkspaceInfo,
    public val findWorkspace: (String?) -> WorkspaceInfo?,
    public val resolveTier: (Path) -> InstallTier,
    public val pathHasEntry: (String) -> Boolean,
    /** De-entry the install dir from the user's `PATH`; returns whether anything changed. NOT [removePathEntry], which is the pure string edit. */
    public val removeFromUserPath: (String) -> Boolean,
    public val uninstallSelf: (Path) -> Unit,
    public val uninstallOther: (Path) -> Unit,
    /** Ask the user to confirm [String]; production prints the prompt and reads a line, tests script answers. */
    public val confirm: (String) -> Boolean,
)

/** The production bundle: every primitive as :cli used to call it inline. */
public fun productionUninstallEffects(): UninstallEffects = UninstallEffects(
    fs = FileSystem.SYSTEM,
    runningExecutable = ::runningExecutablePath,
    globalInstallDir = ::globalInstallDirPath,
    userCacheDir = ::userCacheDirPath,
    requireWorkspace = ::requireWorkspaceInfo,
    findWorkspace = ::findWorkspaceInfo,
    resolveTier = ::resolveInstallTier,
    pathHasEntry = ::windowsPathHasEntry,
    removeFromUserPath = ::removeFromWindowsUserPath,
    uninstallSelf = ::uninstallRunningBinary,
    uninstallOther = ::uninstallOtherBinary,
    confirm = { prompt ->
        print("$prompt [y/N] ")
        val answer = readlnOrNull()?.trim()?.lowercase()
        answer == "y" || answer == "yes"
    },
)

/** What `uninstall` will act on: the binary, its local-bin version stamp / wrapper scripts (if any), and the tier. */
private data class UninstallTarget(val tier: InstallTier, val path: Path, val versionStamp: Path?, val wrappers: List<Path>)

/** The tier-resolved target plus the workspace (if any) that is in scope alongside it (ADR-007). */
private fun resolveUninstallTarget(args: UninstallVerbArgs, running: Path, fx: UninstallEffects): Pair<UninstallTarget, WorkspaceInfo?> {
    val basename = running.name
    return when {
        args.local -> {
            val ws = fx.requireWorkspace(args.root) // explicit --local: must resolve to a real workspace
            val binDir = ws.metaDir / "bin"
            val target = UninstallTarget(
                InstallTier.LOCAL,
                binDir / basename,
                binDir / ".version",
                listOf(ws.root / "taskkling", ws.root / "taskkling.cmd"),
            )
            target to ws
        }
        args.global -> UninstallTarget(InstallTier.GLOBAL, fx.globalInstallDir() / basename, null, emptyList()) to fx.findWorkspace(args.root)
        else -> when (fx.resolveTier(running)) {
            InstallTier.LOCAL -> {
                val binDir = running.parent ?: throw TkError(ExitCode.VALIDATION, "the running executable has no parent directory")
                val projectRoot = binDir.parent?.parent // bin/ -> .taskkling/ -> project root
                val wrappers = if (projectRoot != null) listOf(projectRoot / "taskkling", projectRoot / "taskkling.cmd") else emptyList()
                UninstallTarget(InstallTier.LOCAL, running, binDir / ".version", wrappers) to projectRoot?.let { fx.findWorkspace(it.toString()) }
            }
            InstallTier.GLOBAL -> UninstallTarget(InstallTier.GLOBAL, running, null, emptyList()) to fx.findWorkspace(args.root)
        }
    }
}

/**
 * Run `uninstall`: resolve the tier, state the consequences and confirm
 * (unless `-y`), then remove the binary, the `PATH` entry, the global tier's
 * cache home, and — only behind `--purge` — the workspace's own data.
 *
 * :cli validates `--global`/`--local` mutual exclusion before calling.
 */
public fun runUninstallVerb(args: UninstallVerbArgs, fx: UninstallEffects, out: CliOutput) {
    val fs = fx.fs
    val running = fx.runningExecutable()
    val (target, workspace) = resolveUninstallTarget(args, running, fx)

    // Canonicalize before comparing — --global/--local may re-derive a path that refers to the
    // same file as `running` through a different (but equivalent) string.
    fun canon(p: Path): Path = try { fs.canonicalize(p) } catch (_: Exception) { p }
    val isSelf = canon(target.path) == canon(running)

    val pathEntryDir = if (target.tier == InstallTier.GLOBAL) target.path.parent?.toString() else null
    val pathEntryPresent = pathEntryDir?.let { fx.pathHasEntry(it) } ?: false
    // Cache home (ADR-011): part of the GLOBAL safe scope — machine-replaceable tool bytes
    // (UI jars, runtime images, update-check state), nothing authored. A LOCAL uninstall
    // never touches it: a surviving global install may still be using it.
    val cacheHome = fx.userCacheDir()
    val cacheHomeInScope = uninstallScopeCoversCacheHome(target.tier) && fs.exists(cacheHome)
    val taskCount = workspace?.taskCount ?: 0
    // What --purge would erase (t-qoyn): the meta dir PLUS the default layout's
    // root-level tasks dir. coversTasks=false flags a tasks_dir the plan refuses
    // to touch (it resolves to the root itself, or escapes it) — the prompts
    // below must not claim task deletion then.
    val purgePlan = workspace?.purge

    if (!args.yes) {
        out.out("taskkling uninstall (${target.tier.name.lowercase()} tier):")
        out.out("  binary:  ${target.path}")
        if (pathEntryPresent) out.out("  PATH:    remove '$pathEntryDir' from your user PATH")
        if (cacheHomeInScope) out.out("  cache:   $cacheHome — cached UI/runtime artifacts and update-check state (re-downloadable)")
        if (workspace != null && purgePlan != null) {
            if (args.purge) {
                val what = purgePlan.targets.joinToString(" + ")
                if (purgePlan.coversTasks) {
                    out.out("  PURGE:   $what — PERMANENTLY DELETES $taskCount task(s), config, and caches")
                } else {
                    out.out("  PURGE:   $what — PERMANENTLY DELETES config and caches (tasks_dir '${workspace.tasksDirSetting}' does not resolve to a directory inside the workspace; tasks are NOT touched)")
                }
            } else if (taskCount > 0) {
                out.out("  (kept)   ${workspace.metaDir} — $taskCount task(s) preserved; pass --purge to also delete them")
            }
        }
        if (!fx.confirm("Proceed with removing the binary" + (if (pathEntryPresent) " and PATH entry" else "") + "?")) {
            if (!args.quiet) out.out("taskkling: uninstall aborted; nothing was changed")
            return
        }
        if (args.purge && workspace != null && purgePlan != null) {
            val consequence = if (purgePlan.coversTasks) {
                "This PERMANENTLY deletes $taskCount task(s) at ${purgePlan.targets.joinToString(" + ")} and cannot be undone. Continue?"
            } else {
                "This PERMANENTLY deletes the workspace config and caches at ${workspace.metaDir} and cannot be undone. Continue?"
            }
            if (!fx.confirm(consequence)) {
                if (!args.quiet) out.out("taskkling: uninstall aborted; workspace preserved")
                return
            }
        }
    }

    // Binary + local-bin sidecar removal.
    if (isSelf) fx.uninstallSelf(target.path) else fx.uninstallOther(target.path)
    target.versionStamp?.let { fs.delete(it, mustExist = false) }
    target.wrappers.forEach { fs.delete(it, mustExist = false) }

    // PATH de-entry (global tier only; a true no-op on POSIX and whenever the entry was already absent).
    val pathChanged = pathEntryDir?.let { fx.removeFromUserPath(it) } ?: false

    // Cache home (ADR-011, global safe scope): best-effort — locked files (a running UI)
    // are skipped and reported below, never an error; no reboot scheduling.
    val cacheLeftovers = if (cacheHomeInScope) deleteCacheHomeBestEffort(cacheHome, fs) else emptyList()

    // Purge — the ONLY path that touches the task graph, and only ever behind the explicit flag.
    val purgedTargets = if (args.purge && purgePlan != null) purgePlan.targets else emptyList()
    purgedTargets.forEach { fs.deleteRecursively(it, mustExist = false) }

    if (!args.quiet) {
        out.out("taskkling: removed ${target.path}")
        if (isSelf && fs.exists("${target.path}.old".toPath())) {
            out.out("taskkling: off PATH now; the locked file will clear on your next reboot")
        }
        if (pathChanged) out.out("taskkling: removed '$pathEntryDir' from your user PATH")
        if (cacheHomeInScope) {
            if (cacheLeftovers.isEmpty()) {
                out.out("taskkling: removed cache home $cacheHome")
            } else {
                out.out("taskkling: cache home $cacheHome partially removed; still present (in use by a running UI?) — delete manually:")
                cacheLeftovers.forEach { out.out("  $it") }
            }
        }
        if (purgedTargets.isNotEmpty()) out.out("taskkling: purged ${purgedTargets.joinToString(" + ")}")
    }
}
