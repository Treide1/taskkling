package io.taskkling.core

import okio.Path

/**
 * `taskkling ui` — the PURE planning half (ADR-010 verb semantics, ADR-011
 * asset vocabulary), mirroring [Update.kt]'s split: everything here is
 * side-effect-free and unit-tested in commonTest; the verb (`:cli`) wires
 * these plans to the impure pieces — downloads ([httpGetBytesBlocking]),
 * archive extraction, process spawning — which are QA-gated, not unit-tested.
 *
 * The vocabulary is shared with the CLI's own release machinery on purpose:
 * targets come from [releaseTarget] (the one place the four supported
 * `<os>-<arch>` names live), URLs from [releaseDownloadBaseUrl], checksums
 * from [findSha256], and the cache home from [userCacheDirPath] (ADR-005).
 */

/** The JDK major version the shipped runtime images are built from (ADR-009: Temurin 21). */
public const val UI_RUNTIME_JDK_MAJOR: Int = 21

// --- Release-target vocabulary (shared with the CLI binary's own asset naming) ----------------------------------

/**
 * The `<os>-<arch>` release-target name for one (os, arch) pair — the exact
 * vocabulary the CLI binaries already publish under (ADR-003), reused verbatim
 * for UI assets (ADR-011) so the verb derives every asset name from the
 * os/arch detection it already has. Throws [IllegalArgumentException] for a
 * combination taskkling doesn't publish (notably linux-arm64).
 */
public fun releaseTarget(os: HostOs, arch: HostArch): String {
    val osPart = when (os) {
        HostOs.LINUX -> "linux"
        HostOs.MACOS -> "macos"
        HostOs.WINDOWS -> "windows"
    }
    val archPart = when (arch) {
        HostArch.X64 -> "x64"
        HostArch.ARM64 -> "arm64"
    }
    val target = "$osPart-$archPart"
    val supported = setOf("linux-x64", "macos-arm64", "macos-x64", "windows-x64")
    require(target in supported) {
        "no prebuilt taskkling artifacts for $target (supported: linux-x64, macos-arm64, macos-x64, windows-x64)"
    }
    return target
}

// --- Asset naming (ADR-011: no version in filenames — the release tag carries it) --------------------------------

/** The per-target UI uberjar release asset (ADR-011): `taskkling-ui-<target>.jar`. */
public fun uiJarAssetName(os: HostOs, arch: HostArch): String =
    "taskkling-ui-${releaseTarget(os, arch)}.jar"

/**
 * The per-target runtime-image archive release asset (ADR-011):
 * `taskkling-ui-runtime-jdk<major>-<target>.tar.gz` (`.zip` for windows —
 * tar is not a given there, and the runtime carries no POSIX exec bits to
 * preserve). The JDK major is part of the name because the cache is keyed by
 * it (ADR-010) — the name IS the key.
 */
public fun uiRuntimeAssetName(os: HostOs, arch: HostArch, jdkMajor: Int = UI_RUNTIME_JDK_MAJOR): String {
    val ext = if (os == HostOs.WINDOWS) "zip" else "tar.gz"
    return "taskkling-ui-runtime-jdk$jdkMajor-${releaseTarget(os, arch)}.$ext"
}

// --- Cache layout (ADR-010 decision 3, under the ADR-005 cache home) ----------------------------------------------

/** The runtime cache key for a JDK major — the directory name under `ui/runtime/` (matches the asset vocabulary). */
public fun uiRuntimeCacheKey(jdkMajor: Int = UI_RUNTIME_JDK_MAJOR): String = "jdk$jdkMajor"

/** `<cacheHome>/ui/app/` — one subdirectory per cached UI version. */
public fun uiAppCacheRoot(cacheHome: Path): Path = cacheHome / "ui" / "app"

/** `<cacheHome>/ui/runtime/` — one subdirectory per cached runtime (keyed by JDK major, [uiRuntimeCacheKey]). */
public fun uiRuntimeCacheRoot(cacheHome: Path): Path = cacheHome / "ui" / "runtime"

/** The version-keyed directory holding one UI version's uberjar (ADR-010: `cache/ui/app/<version>/`). */
public fun uiAppDir(cacheHome: Path, version: String): Path =
    uiAppCacheRoot(cacheHome) / normalizeVersionTag(version).removePrefix("v")

/** The cached uberjar's full path: the app dir plus the release asset's own filename. */
public fun uiJarPath(cacheHome: Path, version: String, os: HostOs, arch: HostArch): Path =
    uiAppDir(cacheHome, version) / uiJarAssetName(os, arch)

/** The JDK-major-keyed directory holding the extracted runtime image (ADR-010: `cache/ui/runtime/<jdk-major>/`). */
public fun uiRuntimeDir(cacheHome: Path, jdkMajor: Int = UI_RUNTIME_JDK_MAJOR): Path =
    uiRuntimeCacheRoot(cacheHome) / uiRuntimeCacheKey(jdkMajor)

/** The runtime image's `java` launcher, invoked by absolute path — `JAVA_HOME` is never consulted (ADR-010). */
public fun uiJavaLauncherPath(runtimeDir: Path, os: HostOs): Path =
    runtimeDir / "bin" / (if (os == HostOs.WINDOWS) "java.exe" else "java")

/**
 * The runtime image's completeness marker: `lib/jvm.cfg`, which every JDK
 * image ships and whose absence is exactly how a half-extracted runtime dies
 * (`java` refuses to start without it). Launcher presence alone is NOT
 * trusted — a tar that died mid-image (or lied with exit 0) can leave
 * `bin/java.exe` behind, and a windows spawn of that still "succeeds" before
 * dying into the log, invisible to the self-heal — so everywhere a runtime is
 * judged "present" requires the launcher AND this marker.
 */
public fun uiRuntimeMarkerPath(runtimeDir: Path): Path = runtimeDir / "lib" / "jvm.cfg"

/**
 * The UI's stdout/stderr log — the one post-mortem channel for UI crashes
 * (ADR-010 decision 4). A single file, truncated on each launch: the previous
 * session's log has no audience once a newer session exists.
 */
public fun uiLogFilePath(cacheHome: Path): Path = cacheHome / "ui" / "ui.log"

/**
 * The temp path a fetch downloads to before verify + atomic rename into
 * [finalPath] (ADR-010 decision 6) — a dot-prefixed sibling, same directory,
 * so the final move stays on one filesystem (mirrors [installNewExecutable]'s
 * temp naming). A crash mid-download leaves only this temp, never a
 * half-written "current" file; [planUiCachePrune] collects strays.
 */
public fun uiFetchTempPath(finalPath: Path): Path {
    val dir = finalPath.parent ?: error("fetch destination has no parent directory: $finalPath")
    return dir / ".${finalPath.name}.fetch.tmp"
}

/**
 * The temp directory a runtime archive is extracted into before being renamed
 * to [finalDir] as one atomic step — extraction is the non-atomic part, so it
 * happens entirely under a name that is never consulted as "current".
 */
public fun uiExtractTempDir(finalDir: Path): Path {
    val dir = finalDir.parent ?: error("extract destination has no parent directory: $finalDir")
    return dir / ".${finalDir.name}.extract.tmp"
}

// --- Prune plan (ADR-010 decision 3 + ADR-011 decision 2) ----------------------------------------------------------

/**
 * What to delete after a successful launch of [currentVersion] on
 * [currentJdkMajor]'s runtime: every OTHER entry under `ui/app/` and
 * `ui/runtime/` — stale versions, orphaned runtimes, and stray fetch/extract
 * temps alike. Takes directory LISTINGS (not a filesystem) so the plan stays
 * pure; the executor deletes best-effort with skip-locked semantics
 * ([deleteCacheHomeBestEffort]'s tree walk): a dir still held open by an older
 * running UI is simply abandoned and re-collected on the next successful
 * launch (ADR-011 — no reboot scheduling, no PID tracking).
 */
public fun planUiCachePrune(
    currentVersion: String,
    appDirListing: List<Path>,
    runtimeDirListing: List<Path>,
    currentJdkMajor: Int = UI_RUNTIME_JDK_MAJOR,
): List<Path> {
    val keepApp = normalizeVersionTag(currentVersion).removePrefix("v")
    val keepRuntime = uiRuntimeCacheKey(currentJdkMajor)
    return appDirListing.filter { it.name != keepApp } + runtimeDirListing.filter { it.name != keepRuntime }
}

// --- Launch / self-heal state machine (ADR-010 decision 6) ---------------------------------------------------------

/** Why a `taskkling ui` run terminally failed — the cause vocabulary the one actionable error message names. */
public enum class UiFailureCause {
    /** No network at all — the very first transport step couldn't connect anywhere. */
    OFFLINE,

    /** A download completed but failed `SHA256SUMS` verification (corrupted or tampered transfer). */
    CHECKSUM_MISMATCH,

    /** The network is up but GitHub didn't serve the assets (outage, block, HTTP error). */
    GITHUB_UNREACHABLE,

    /** The cache was corrupt AND the one silent re-fetch didn't cure it. */
    CORRUPT_AFTER_REFETCH,
}

/** The verb's next move — what [planUiRun] / [planAfterLaunchFailure] decide, one step at a time. */
public sealed interface UiRunPlan {
    /** Cache complete: spawn the detached java process. */
    public data object Launch : UiRunPlan

    /** Cache incomplete (first run, post-update, or post-prune): fetch with progress, then launch. */
    public data object FetchThenLaunch : UiRunPlan

    /** Launch hit a corrupt/missing artifact: silently re-fetch ONCE, then launch again. */
    public data object RefetchOnce : UiRunPlan

    /** Terminal: emit ONE actionable message ([uiFailureMessage]) and exit non-zero. */
    public data class Fail(val cause: UiFailureCause) : UiRunPlan
}

/**
 * The entry decision: both artifacts present → [UiRunPlan.Launch]; anything
 * missing → [UiRunPlan.FetchThenLaunch]. Presence means the ATOMICALLY-renamed
 * final paths exist ([uiJarPath], [uiRuntimeDir]) — temps never count
 * (ADR-010: a half-extracted state can never become "current") — and for the
 * runtime, that its completeness marker does too ([uiRuntimeMarkerPath]).
 */
public fun planUiRun(jarPresent: Boolean, runtimePresent: Boolean): UiRunPlan =
    if (jarPresent && runtimePresent) UiRunPlan.Launch else UiRunPlan.FetchThenLaunch

/**
 * The self-heal decision after a launch attempt failed on a corrupt or
 * missing artifact: re-fetch silently ONCE ([UiRunPlan.RefetchOnce]); if this
 * run already re-fetched, give up with [UiFailureCause.CORRUPT_AFTER_REFETCH]
 * — never a second retry, never user-facing cache surgery.
 */
public fun planAfterLaunchFailure(alreadyRefetchedThisRun: Boolean): UiRunPlan =
    if (alreadyRefetchedThisRun) UiRunPlan.Fail(UiFailureCause.CORRUPT_AFTER_REFETCH) else UiRunPlan.RefetchOnce

/**
 * The ONE terminal error line (ADR-010: name the cause and the log path,
 * point at the action that helps). [logPath] is appended when the log can
 * carry more detail (it exists once a launch was ever attempted).
 */
public fun uiFailureMessage(cause: UiFailureCause, logPath: Path? = null): String {
    val body = when (cause) {
        UiFailureCause.OFFLINE ->
            "cannot download the UI: you appear to be offline. Connect and retry - 'taskkling ui --fetch-only' prefetches for later offline use."
        UiFailureCause.CHECKSUM_MISMATCH ->
            "the downloaded UI failed SHA256 verification (corrupted or tampered transfer). Nothing was installed; retry - a clean download will verify."
        UiFailureCause.GITHUB_UNREACHABLE ->
            "github.com is not reachable from here (outage or blocked network), so the UI assets cannot be downloaded. Retry later."
        UiFailureCause.CORRUPT_AFTER_REFETCH ->
            "the cached UI is corrupt and re-downloading did not fix it."
    }
    val log = logPath?.let { " Log: $it" } ?: ""
    return "taskkling ui: $body$log"
}

// --- Headless decision (ADR-010 decision 6: refuse BEFORE spawning java) --------------------------------------------

/**
 * Should `taskkling ui` refuse to launch because there is no display to
 * launch INTO? Returns the refusal message (naming `--fetch-only`, the
 * operation that does work headless) or null to proceed.
 *
 * Only linux is decidable from the environment: a graphical session always
 * sets `DISPLAY` (X11, and SSH `-X` forwarding) or `WAYLAND_DISPLAY`; both
 * blank means headless — which also covers plain SSH sessions, so the SSH_*
 * variables add no signal here. On macOS and Windows there is NO reliable
 * env marker (an SSH session into a logged-in mac can legitimately reach the
 * GUI, and `SSH_CONNECTION` would misfire on it), so those always proceed —
 * a truly display-less spawn fails into the log rather than blocking
 * legitimate launches (ADR-010's stated risk trade-off).
 */
public fun headlessRefusalMessage(os: HostOs, env: Map<String, String>): String? {
    if (os != HostOs.LINUX) return null
    val display = env["DISPLAY"].orEmpty()
    val wayland = env["WAYLAND_DISPLAY"].orEmpty()
    if (display.isNotBlank() || wayland.isNotBlank()) return null
    return "taskkling ui: no display found (DISPLAY and WAYLAND_DISPLAY are both unset) - " +
        "a desktop session is required to launch the UI. In a headless session, " +
        "'taskkling ui --fetch-only' still works to prefetch the UI for later."
}
