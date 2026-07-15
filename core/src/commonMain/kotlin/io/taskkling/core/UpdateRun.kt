package io.taskkling.core

import kotlinx.datetime.Clock
import okio.FileSystem
import okio.Path

/**
 * `update`'s orchestration (ADR-002 / ADR-005 / ADR-007): the composition of
 * [Update.kt]'s pure planning pieces with the network, filesystem and
 * self-replace primitives. :cli's `update` subcommand is just flags plus one
 * [runUpdateVerb] call.
 */

/** `update`'s flags, as :cli parsed them. */
public data class UpdateVerbArgs(
    val check: Boolean = false,
    val global: Boolean = false,
    val local: Boolean = false,
    val versionOverride: String? = null,
    val quiet: Boolean = false,
    val root: String? = null,
)

/**
 * `update`'s impure surface. Every member is either a seam with a real second
 * implementation ([net], [fs]) or a thin delegate to the platform primitive
 * that already lives in :core — the bundle exists so a test can drive the
 * verb's decisions (which install call fires, in what order, after which
 * checks) without a network or a real binary to overwrite.
 */
public class UpdateEffects(
    public val net: NetEffects,
    public val fs: FileSystem,
    /** The running binary's version — the left side of every comparison and the User-Agent. */
    public val currentVersion: String,
    public val releaseAssetName: () -> String,
    public val runningExecutable: () -> Path,
    public val globalInstallDir: () -> Path,
    public val requireWorkspace: (String?) -> WorkspaceInfo,
    public val installSelf: (Path, ByteArray) -> Unit,
    public val installOther: (Path, ByteArray) -> Unit,
    public val restampLocalBinVersion: (Path, String) -> Unit,
    public val nowEpochSeconds: () -> Long,
    public val saveCheckCache: (UpdateCheckCache) -> Unit,
)

/** The production bundle: every primitive as :cli used to call it inline. */
public fun productionUpdateEffects(): UpdateEffects = UpdateEffects(
    net = SystemNet,
    fs = FileSystem.SYSTEM,
    currentVersion = Taskkling.VERSION,
    releaseAssetName = ::currentReleaseAssetName,
    runningExecutable = ::runningExecutablePath,
    globalInstallDir = ::globalInstallDirPath,
    requireWorkspace = ::requireWorkspaceInfo,
    installSelf = ::installNewExecutable,
    installOther = ::installOtherExecutable,
    restampLocalBinVersion = ::restampLocalBinVersionIfPresent,
    nowEpochSeconds = { Clock.System.now().epochSeconds },
    saveCheckCache = { saveUpdateCheckCache(it) },
)

/**
 * Best-effort GitHub `tag_name` lookup (ADR-002): a User-Agent is required
 * (GitHub 403s without one) and any failure — network, non-2xx, bad JSON —
 * silently yields null rather than throwing, so callers decide how loud to be.
 */
public fun fetchLatestTag(net: NetEffects, userAgent: String): String? =
    try {
        val (status, body) = net.getText(GITHUB_API_LATEST_RELEASE, userAgent)
        if (status in 200..299) parseLatestTagName(body) else null
    } catch (_: Exception) {
        null
    }

/**
 * Resolve the binary `update` will replace (ADR-007). Default (no flag) is the
 * running binary. `--global`/`--local` pick a tier explicitly and are
 * UPDATE-ONLY: a targeted tier with no install is an error, never a silent new
 * install (that's install.sh / `init --local-bin`'s job).
 */
private fun resolveUpdateTarget(args: UpdateVerbArgs, running: Path, fx: UpdateEffects): Path {
    val basename = running.name
    return when {
        args.global -> {
            val path = fx.globalInstallDir() / basename
            if (!fx.fs.exists(path)) throw TkError(ExitCode.VALIDATION, "no global install found; install it with the install script")
            path
        }
        args.local -> {
            val ws = fx.requireWorkspace(args.root) // explicit --local must resolve a real workspace
            val path = ws.metaDir / "bin" / basename
            if (!fx.fs.exists(path)) throw TkError(ExitCode.VALIDATION, "no local-bin install here; run 'taskkling init --local-bin' first")
            path
        }
        else -> running
    }
}

/**
 * Run `update` end to end: resolve the target tier, resolve the release tag,
 * download + SHA-256-verify the asset, then self-replace (or plain-overwrite
 * another tier's copy). With [UpdateVerbArgs.check] it only reports and
 * touches no binary.
 *
 * :cli validates the flag combinations before calling (`--global`/`--local`
 * mutual exclusion, `--check` tier-agnosticism).
 */
public fun runUpdateVerb(args: UpdateVerbArgs, fx: UpdateEffects, out: CliOutput) {
    val current = fx.currentVersion
    val userAgent = "taskkling/$current"

    if (args.check) {
        val latest = fetchLatestTag(fx.net, userAgent)
        if (latest != null) {
            // Opportunistic cache warm (ADR-005): `update --check` always performs a
            // LIVE lookup — it "ignores the flag" because invoking it IS the consent —
            // but feeding its result into the shared ~24h cache lets a later opt-in
            // `--version` notifier skip a redundant network round trip. Best-effort;
            // never affects this command's own output.
            fx.saveCheckCache(UpdateCheckCache(fx.nowEpochSeconds(), latest))
        }
        when {
            latest == null -> out.err("taskkling: could not check for updates (network error or rate-limited)")
            isNewerVersion(current, latest) ->
                out.out("taskkling: update available: $current -> ${latest.removePrefix("v")} (run 'taskkling update')")
            else -> out.out("taskkling: up to date ($current)")
        }
        return
    }

    // Resolve what we're replacing FIRST — it's cheap and local-only, so a tier
    // flag that can't be satisfied fails before any network IO (t-6ouc), not
    // after a full asset download.
    val running = fx.runningExecutable()
    val target = resolveUpdateTarget(args, running, fx)
    fun canon(p: Path): Path = try { fx.fs.canonicalize(p) } catch (_: Exception) { p }
    val isSelf = canon(target) == canon(running)

    val targetTag = args.versionOverride
        ?: fetchLatestTag(fx.net, userAgent)
        ?: throw TkError(
            ExitCode.VALIDATION,
            "could not resolve the latest release (network error or rate-limited) — pass --version vX.Y.Z to update without it",
        )
    val targetVersion = normalizeVersionTag(targetTag).removePrefix("v")

    val assetName = fx.releaseAssetName()
    val base = releaseDownloadBaseUrl(targetTag)
    val assetUrl = "$base/$assetName"
    val sumsUrl = "$base/SHA256SUMS"

    if (!args.quiet) out.out("Downloading $assetName ($targetVersion) ...")
    val (assetStatus, assetBytes) = fx.net.getBytes(assetUrl, userAgent)
    if (assetStatus !in 200..299) throw TkError(ExitCode.VALIDATION, "download failed: HTTP $assetStatus for $assetUrl")
    val (sumsStatus, sumsText) = fx.net.getText(sumsUrl, userAgent)
    if (sumsStatus !in 200..299) throw TkError(ExitCode.VALIDATION, "download failed: HTTP $sumsStatus for $sumsUrl")

    val expected = findSha256(sumsText, assetName)
        ?: throw TkError(ExitCode.VALIDATION, "no checksum entry for $assetName in SHA256SUMS")
    val actualHash = Sha256.hashHex(assetBytes)
    if (!expected.equals(actualHash, ignoreCase = true)) {
        throw TkError(ExitCode.VALIDATION, "checksum mismatch for $assetName (expected $expected, got $actualHash) — aborting")
    }
    if (!args.quiet) out.out("Checksum OK ($actualHash)")

    // Self vs other, forced by the OS (ADR-007): replacing the running image needs the
    // Windows-safe self-replace dance ([installNewExecutable]); a different, unlocked
    // tier's copy is a plain overwrite ([installOtherExecutable]).
    if (isSelf) fx.installSelf(target, assetBytes) else fx.installOther(target, assetBytes)
    fx.restampLocalBinVersion(target, targetVersion)

    if (!args.quiet) {
        if (isSelf) out.out("$current -> $targetVersion")
        else out.out("updated $target to $targetVersion")
    }
}
