package io.taskkling.core

import okio.FileSystem
import okio.Path
import okio.SYSTEM

/**
 * `ui`'s orchestration (ADR-009 / ADR-010 / ADR-011): the composition of
 * [UiAssets.kt]'s plans with the network, filesystem, extraction and spawn
 * primitives. This is the most failure-prone stretch of the distribution path
 * — lazy fetch, checksum verification, atomic install, and a one-shot
 * self-heal for a corrupt cache — so it lives here, drivable with fakes, and
 * :cli's `ui` subcommand is flags plus one [runUiVerb] call.
 */

/** `ui`'s flags, as :cli parsed them. */
public data class UiVerbArgs(
    val fetchOnly: Boolean = false,
    val quiet: Boolean = false,
    val root: String? = null,
)

/** `ui`'s impure surface (see [UpdateEffects] for the shape's rationale). */
public class UiEffects(
    public val net: NetEffects,
    public val fs: FileSystem,
    /** This CLI's version — what the fetch is PINNED to, never "latest" (ADR-010 decision 2). */
    public val version: String,
    public val hostTarget: () -> Pair<HostOs, HostArch>,
    public val cacheHome: () -> Path,
    public val launchEnvironment: () -> Map<String, String>,
    public val requireWorkspace: (String?) -> WorkspaceInfo,
    public val extractArchive: (Path, Path) -> Boolean,
    public val spawn: (List<String>, Path) -> Boolean,
)

/** The production bundle: every primitive as :cli used to call it inline. */
public fun productionUiEffects(): UiEffects = UiEffects(
    net = SystemNet,
    fs = FileSystem.SYSTEM,
    version = Taskkling.VERSION,
    hostTarget = ::currentHostTarget,
    cacheHome = ::userCacheDirPath,
    launchEnvironment = ::uiLaunchEnvironment,
    requireWorkspace = ::requireWorkspaceInfo,
    extractArchive = ::extractArchiveWithSystemTar,
    spawn = ::spawnDetachedProcess,
)

/** The cache paths one `ui` run works against, all derived from the pinned version + host target. */
private class UiPaths(fx: UiEffects, os: HostOs, arch: HostArch) {
    val cacheHome: Path = fx.cacheHome()
    val jar: Path = uiJarPath(cacheHome, fx.version, os, arch)
    val runtimeDir: Path = uiRuntimeDir(cacheHome)
    val launcher: Path = uiJavaLauncherPath(runtimeDir, os)
    val log: Path = uiLogFilePath(cacheHome)
}

/**
 * Run `ui`: fetch-and-verify what's missing, then spawn the UI detached
 * against the CLI-resolved workspace root. With [UiVerbArgs.fetchOnly] it
 * stops after the fetch — the headless/offline-prep path.
 */
public fun runUiVerb(args: UiVerbArgs, fx: UiEffects, out: CliOutput) {
    val (os, arch) = fx.hostTarget()
    val paths = UiPaths(fx, os, arch)
    val fs = fx.fs

    if (args.fetchOnly) {
        // No workspace, no display needed: this is the headless/offline-prep path (ADR-010 decision 5).
        when (planUiRun(fs.exists(paths.jar), fs.exists(paths.launcher))) {
            is UiRunPlan.Launch -> if (!args.quiet) out.out("taskkling: UI v${fx.version} already fetched and verified")
            else -> {
                fetchUiAssets(args, fx, out, os, arch, paths)
                if (!args.quiet) out.out("taskkling: UI v${fx.version} fetched and verified — 'taskkling ui' will launch it")
            }
        }
        return
    }

    // Workspace resolution FIRST (cheap, local, fails before any network — mirrors
    // update's ordering): the UI never re-runs discovery, it receives the
    // CLI-resolved root as its launch argument (ADR-010 decision 4).
    val ws = fx.requireWorkspace(args.root)

    // Refuse headless BEFORE fetching or spawning (ADR-010 decision 6).
    headlessRefusalMessage(os, fx.launchEnvironment())?.let { throw TkError(ExitCode.VALIDATION, it) }

    var refetched = false
    while (true) {
        when (planUiRun(fs.exists(paths.jar), fs.exists(paths.launcher))) {
            is UiRunPlan.FetchThenLaunch -> fetchUiAssets(args, fx, out, os, arch, paths)
            is UiRunPlan.Launch -> {
                paths.log.parent?.let { fs.createDirectories(it) }
                val argv = buildList {
                    add(paths.launcher.toString())
                    // macOS: name the plain-java process in the Dock (ADR-009's accepted identity
                    // mitigation). -Xdock:icon is skipped: no .icns exists at runtime — the
                    // in-window icon comes from the jar's own resources.
                    if (os == HostOs.MACOS) add("-Xdock:name=taskkling")
                    add("-jar")
                    add(paths.jar.toString())
                    add(ws.root.toString())
                }
                if (fx.spawn(argv, paths.log)) {
                    pruneUiCache(fx, paths.cacheHome)
                    if (!args.quiet) out.out("taskkling: UI v${fx.version} launched (log: ${paths.log})")
                    return
                }
                when (val heal = planAfterLaunchFailure(refetched)) {
                    is UiRunPlan.RefetchOnce -> {
                        // Corrupt cache: drop both artifacts SILENTLY and loop into the one re-fetch.
                        deleteCacheHomeBestEffort(uiAppDir(paths.cacheHome, fx.version), fs)
                        deleteCacheHomeBestEffort(paths.runtimeDir, fs)
                        refetched = true
                    }
                    is UiRunPlan.Fail -> throw TkError(ExitCode.VALIDATION, uiFailureMessage(heal.cause, paths.log))
                    else -> error("unreachable self-heal plan: $heal")
                }
            }
            else -> error("unreachable entry plan")
        }
    }
}

/** Download + SHA256SUMS-verify + atomically install whichever of the two artifacts is missing (ADR-010 decision 6). */
private fun fetchUiAssets(args: UiVerbArgs, fx: UiEffects, out: CliOutput, os: HostOs, arch: HostArch, paths: UiPaths) {
    val fs = fx.fs
    val version = fx.version
    val userAgent = "taskkling/$version"
    val logForMsg = if (fs.exists(paths.log)) paths.log else null
    fun fail(cause: UiFailureCause): Nothing = throw TkError(ExitCode.VALIDATION, uiFailureMessage(cause, logForMsg))

    val base = releaseDownloadBaseUrl(version) // pinning: the CLI's OWN tag, never "latest" (ADR-010 decision 2)
    val (sumsStatus, sumsText) = try {
        fx.net.getText("$base/SHA256SUMS", userAgent)
    } catch (_: Exception) {
        fail(UiFailureCause.OFFLINE)
    }
    if (sumsStatus !in 200..299) fail(UiFailureCause.GITHUB_UNREACHABLE)

    fun fetchVerified(assetName: String): ByteArray {
        val expected = findSha256(sumsText, assetName) ?: throw TkError(
            ExitCode.VALIDATION,
            "release v$version publishes no UI asset '$assetName' — releases before v0.6.0 carry no UI; run 'taskkling update' and retry",
        )
        if (!args.quiet) out.out("Downloading $assetName (v$version) ...")
        val (status, bytes) = try {
            fx.net.getBytes("$base/$assetName", userAgent)
        } catch (_: Exception) {
            fail(UiFailureCause.OFFLINE)
        }
        if (status !in 200..299) fail(UiFailureCause.GITHUB_UNREACHABLE)
        if (!Sha256.hashHex(bytes).equals(expected, ignoreCase = true)) fail(UiFailureCause.CHECKSUM_MISMATCH)
        return bytes
    }

    if (!fs.exists(paths.jar)) {
        val bytes = fetchVerified(uiJarAssetName(os, arch))
        paths.jar.parent?.let { fs.createDirectories(it) }
        val tmp = uiFetchTempPath(paths.jar)
        fs.delete(tmp, mustExist = false)
        fs.write(tmp) { write(bytes) }
        fs.atomicMove(tmp, paths.jar) // presence of the final path is the only "exists" ever trusted
    }

    if (!fs.exists(paths.launcher)) {
        val assetName = uiRuntimeAssetName(os, arch)
        val bytes = fetchVerified(assetName)
        val runtimeRoot = paths.runtimeDir.parent ?: error("runtime dir has no parent: ${paths.runtimeDir}")
        fs.createDirectories(runtimeRoot)
        val archiveTmp = uiFetchTempPath(runtimeRoot / assetName)
        val extractTmp = uiExtractTempDir(paths.runtimeDir)
        fs.delete(archiveTmp, mustExist = false)
        fs.deleteRecursively(extractTmp, mustExist = false)
        fs.write(archiveTmp) { write(bytes) }
        fs.createDirectories(extractTmp)
        // The CLI extracts the archive ITSELF — that's what keeps macOS quarantine off
        // the runtime tree (ADR-009). Extraction is the non-atomic part, so it happens
        // entirely under the temp name; the rename below is the atomic "install".
        val extracted = fx.extractArchive(archiveTmp, extractTmp)
        fs.delete(archiveTmp, mustExist = false)
        if (!extracted) throw TkError(ExitCode.VALIDATION, "could not extract $assetName — is 'tar' available on this system?")
        // Release archives carry the image contents (bin/, lib/) at top level (t-6q6a's
        // contract); tolerate one wrapping directory defensively.
        val imageRoot = when {
            fs.exists(extractTmp / "bin") -> extractTmp
            else -> fs.list(extractTmp).singleOrNull()?.takeIf { fs.exists(it / "bin") }
                ?: throw TkError(ExitCode.VALIDATION, "unexpected runtime archive layout in $assetName (no bin/ directory)")
        }
        fs.deleteRecursively(paths.runtimeDir, mustExist = false) // stale half-state only; the launcher was missing
        fs.atomicMove(imageRoot, paths.runtimeDir)
        fs.deleteRecursively(extractTmp, mustExist = false)
    }
}

/** After a successful launch: collect every stale cache entry, best-effort and SILENT (ADR-011 decision 2). */
private fun pruneUiCache(fx: UiEffects, cacheHome: Path) {
    try {
        fun listOrEmpty(dir: Path): List<Path> = try { fx.fs.list(dir) } catch (_: Exception) { emptyList() }
        planUiCachePrune(fx.version, listOrEmpty(uiAppCacheRoot(cacheHome)), listOrEmpty(uiRuntimeCacheRoot(cacheHome)))
            .forEach { deleteCacheHomeBestEffort(it, fx.fs) } // locked dirs abandoned; the next launch re-collects them
    } catch (_: Exception) {
        // Cleanup must never break a launch that already succeeded.
    }
}
