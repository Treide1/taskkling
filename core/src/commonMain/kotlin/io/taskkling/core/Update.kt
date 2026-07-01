package io.taskkling.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * `taskkling update` / `update --check` (ADR-002 / ADR-005). This file holds
 * the PURE, testable pieces — host-triple/asset naming, URL construction,
 * `SHA256SUMS` parsing, the GitHub `tag_name` JSON shape, and semver compare —
 * ported logic-for-logic from `install.sh` / `install.ps1` rather than
 * reinvented. The self-replace primitive below it is the one genuinely
 * platform-specific piece (an expect/actual, like [currentExecutablePath]).
 * `:cli`'s `update` subcommand wires these together with the network calls
 * ([httpGetTextBlocking] / [httpGetBytesBlocking]), which are impure by
 * nature and therefore QA-gated rather than unit-tested.
 */

/** The canonical GitHub `owner/repo` coordinates (installers + `update` agree). */
public const val GITHUB_REPO: String = "Treide1/taskkling"

/** Release assets (binaries, `SHA256SUMS`, install scripts) live under here — mirrors `install.sh`'s `$BASE_URL`. */
public const val GITHUB_RELEASES_BASE: String = "https://github.com/$GITHUB_REPO/releases"

/** `tag_name` of the newest release (ADR-002's latest-version lookup); requires a `User-Agent` header or GitHub 403s. */
public const val GITHUB_API_LATEST_RELEASE: String = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

// --- Host triple / asset naming (port of install.sh's `case "$os"`/`case "$arch"`, install.ps1's arch check) -----

/** A supported release host OS. */
public enum class HostOs { LINUX, MACOS, WINDOWS }

/** A supported release host CPU architecture. */
public enum class HostArch { X64, ARM64 }

/**
 * Map a POSIX `uname -s` value to [HostOs] — mirrors install.sh's
 * `case "$os"` (`Darwin` → macOS, `Linux` → linux; anything else unsupported).
 */
public fun parseUnameOs(sysname: String): HostOs? = when (sysname) {
    "Darwin" -> HostOs.MACOS
    "Linux" -> HostOs.LINUX
    else -> null
}

/**
 * Map a POSIX `uname -m` value to [HostArch] — mirrors install.sh's
 * `case "$arch"` (`x86_64`/`amd64` → x64, `arm64`/`aarch64` → arm64).
 */
public fun parseUnameArch(machine: String): HostArch? = when (machine) {
    "x86_64", "amd64" -> HostArch.X64
    "arm64", "aarch64" -> HostArch.ARM64
    else -> null
}

/**
 * Map Windows' `PROCESSOR_ARCHITECTURE` env var to [HostArch] — mirrors
 * install.ps1's check (`AMD64` is the only supported value; taskkling ships a
 * single Windows x64 binary).
 */
public fun parseWindowsArch(processorArchitecture: String): HostArch? = when (processorArchitecture) {
    "AMD64" -> HostArch.X64
    else -> null
}

/**
 * The release-asset filename for one (os, arch) pair — mirrors install.sh's
 * `asset="taskkling-${os_part}-${arch_part}"` plus its exhaustive support
 * check (only linux-x64, macos-arm64, macos-x64, windows-x64 are published;
 * linux-arm64 in particular is NOT built) and install.ps1's `.exe` suffix for
 * Windows. Throws [IllegalArgumentException] for an unsupported combination.
 */
public fun resolveAssetName(os: HostOs, arch: HostArch): String {
    val osPart = when (os) {
        HostOs.LINUX -> "linux"
        HostOs.MACOS -> "macos"
        HostOs.WINDOWS -> "windows"
    }
    val archPart = when (arch) {
        HostArch.X64 -> "x64"
        HostArch.ARM64 -> "arm64"
    }
    val name = "taskkling-$osPart-$archPart"
    val supported = setOf("taskkling-linux-x64", "taskkling-macos-arm64", "taskkling-macos-x64", "taskkling-windows-x64")
    require(name in supported) {
        "no prebuilt taskkling binary for $osPart-$archPart (supported: linux-x64, macos-arm64, macos-x64, windows-x64)"
    }
    return if (os == HostOs.WINDOWS) "$name.exe" else name
}

/**
 * The release-asset filename for **this compiled binary's** host triple.
 * Unlike the installers (one script must handle any host it runs on, hence
 * their `uname`/env-var detection), a Kotlin/Native binary is already fixed
 * to one target at compile time — so each actual is a constant naming its own
 * target, funneled through [resolveAssetName] so the naming rule lives in
 * exactly one, unit-tested place.
 */
public expect fun currentReleaseAssetName(): String

// --- Download URL construction (port of install.sh's `$dl`/install.ps1's `$dl`) -----------------------------------

/** Accept both `0.1.0` and `v0.1.0` — release tags are always `vX.Y.Z` (mirrors both installers). */
public fun normalizeVersionTag(version: String): String = if (version.startsWith("v")) version else "v$version"

/**
 * The `releases/download/<tag>` base URL a release's assets (binary +
 * `SHA256SUMS`) live under, given an already-resolved concrete tag. `update`
 * always resolves a concrete tag first — either `--version` (skips the API)
 * or the GitHub API's `tag_name` — so, unlike the installers' `.../latest/download`
 * alias, there is no "unresolved latest" case to build a URL for here.
 */
public fun releaseDownloadBaseUrl(tag: String, releasesBaseUrl: String = GITHUB_RELEASES_BASE): String =
    "$releasesBaseUrl/download/${normalizeVersionTag(tag)}"

// --- SHA256SUMS parsing (port of install.sh's awk line / install.ps1's split) --------------------------------------

/**
 * Parse a `SHA256SUMS` file's lines (`<hex>  <filename>` or `<hex> *<filename>`
 * — the two forms `sha256sum`/`Get-FileHash`-style tools emit) into a
 * filename → lowercase hex-digest map. Mirrors install.sh's awk line (strips
 * a leading `*` from the matched field) and install.ps1's
 * `-split '\s+', 2` + `TrimStart('*')`.
 */
public fun parseSha256Sums(text: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        val idx = line.indexOfFirst { it.isWhitespace() }
        if (idx < 0) continue
        val hash = line.substring(0, idx).lowercase()
        val name = line.substring(idx).trim().removePrefix("*")
        if (name.isNotEmpty()) result[name] = hash
    }
    return result
}

/** The expected checksum for [assetName] in a `SHA256SUMS` file's [text], or null if no matching line exists. */
public fun findSha256(text: String, assetName: String): String? = parseSha256Sums(text)[assetName]

// --- GitHub API `tag_name` lookup (ADR-002's latest-version lookup) -------------------------------------------------

@Serializable
private data class GithubLatestRelease(@SerialName("tag_name") val tagName: String)

private val releaseJson = Json { ignoreUnknownKeys = true }

/**
 * Pull `tag_name` out of a GitHub `.../releases/latest` API JSON body.
 * Null on anything unparseable — the caller silent-fails (ADR-002: a failed
 * lookup never crashes `update`/`update --check`, it just can't proceed).
 */
public fun parseLatestTagName(json: String): String? =
    try {
        releaseJson.decodeFromString(GithubLatestRelease.serializer(), json).tagName
    } catch (_: Exception) {
        null
    }

// --- Semver compare (release tags are always `vX.Y.Z`, per gradle.properties' tag guard) ---------------------------

/** A parsed `X.Y.Z` release version (the `v` prefix, if present, is stripped). */
public data class SemVer(val major: Int, val minor: Int, val patch: Int) : Comparable<SemVer> {
    override fun compareTo(other: SemVer): Int =
        compareValuesBy(this, other, SemVer::major, SemVer::minor, SemVer::patch)

    public companion object {
        /** Parse `vX.Y.Z` or `X.Y.Z`. Throws [IllegalArgumentException] for anything else. */
        public fun parse(raw: String): SemVer {
            val parts = raw.removePrefix("v").split(".")
            require(parts.size == 3) { "invalid semver '$raw' (expected vX.Y.Z)" }
            fun part(s: String): Int = s.toIntOrNull() ?: throw IllegalArgumentException("invalid semver '$raw' (expected vX.Y.Z)")
            return SemVer(part(parts[0]), part(parts[1]), part(parts[2]))
        }
    }
}

/** `-1`/`0`/`1` per [Comparable], comparing two `vX.Y.Z` (or `X.Y.Z`) version strings. */
public fun compareSemver(a: String, b: String): Int = SemVer.parse(a).compareTo(SemVer.parse(b))

/** Is [candidate] strictly newer than [current]? (Both `vX.Y.Z` or `X.Y.Z`.) */
public fun isNewerVersion(current: String, candidate: String): Boolean = compareSemver(candidate, current) > 0

// --- Self-replace primitive (ADR-002) --------------------------------------------------------------------------

/**
 * Rename the currently-running executable at [exePath] to a `.old` sibling in
 * the same directory, freeing the original path so a new binary can be moved
 * into it. This is purely the Windows work-around for a running `.exe`'s
 * image file being locked (it cannot be overwritten or replaced in place
 * while executing) — POSIX has no such restriction (a running binary can be
 * unlinked while open, so a plain atomic rename over the live path suffices),
 * so the POSIX actual is a no-op that returns [exePath] unchanged.
 *
 * Factored out (rather than inlined into the `update` flow) because it is
 * shared with a future `uninstall` verb: `update` moves a freshly-verified
 * binary into the freed path and sweeps the `.old` file on the NEXT run
 * ([sweepStaleOldExecutable]); `uninstall`'s Windows path instead schedules
 * the `.old` file for delete-on-reboot, since there is no "next run" of the
 * app left to sweep it. Both build on this one primitive.
 *
 * @return the path the live executable now lives at (`<exePath>.old` on
 *   Windows; [exePath] itself, unchanged, on POSIX).
 */
internal expect fun renameSelfToOld(exePath: String): String

/**
 * Install the already-downloaded, checksum-verified [newBinaryBytes] as
 * [exePath] (ADR-002's self-replace step). Writes the bytes to a temp file in
 * the SAME directory as [exePath] (required so the final move is on one
 * filesystem — cross-filesystem renames aren't atomic), marks it executable,
 * then swaps it in: [renameSelfToOld] frees the live path on Windows (a
 * no-op on POSIX, where the live path is simply overwritten directly — a
 * running binary may be unlinked while open).
 */
public fun installNewExecutable(exePath: Path, newBinaryBytes: ByteArray) {
    val fs = FileSystem.SYSTEM
    val dir = exePath.parent ?: error("executable path has no parent directory: $exePath")
    val tmp = dir / ".${exePath.name}.update.tmp"
    fs.delete(tmp, mustExist = false)
    fs.write(tmp) { write(newBinaryBytes) }
    markExecutable(tmp.toString())
    renameSelfToOld(exePath.toString())
    fs.atomicMove(tmp, exePath)
    markExecutable(exePath.toString())
}

/**
 * Best-effort delete of a stale `<exePath>.old` sibling, left behind by a
 * prior Windows `update` run ([renameSelfToOld]) that couldn't be swept
 * immediately (the old image was still locked while that process was
 * exiting). Called from the CLI's startup hook on every invocation
 * ([sweepStaleOldExecutableForRunningBinary]) — a no-op everywhere else
 * (POSIX never creates a `.old` file; a fresh install has none to sweep).
 * Never throws: failure (e.g. still locked) is silently retried next run.
 */
public fun sweepStaleOldExecutable(exePath: String) {
    val fs = FileSystem.SYSTEM
    val old = "$exePath.old".toPath()
    try {
        if (fs.exists(old)) fs.delete(old, mustExist = false)
    } catch (_: Exception) {
        // Best-effort; a subsequent run will retry.
    }
}

/** [sweepStaleOldExecutable] for the currently-running binary — the CLI's startup hook calls this on every invocation. */
public fun sweepStaleOldExecutableForRunningBinary() {
    try {
        sweepStaleOldExecutable(currentExecutablePath())
    } catch (_: Exception) {
        // Best-effort; must never break normal command execution.
    }
}

/**
 * If [exePath] is a per-project local-bin copy — marked by the sibling
 * `.version` file [installLocalBin] writes — re-stamp it with [newVersion] so
 * the pinned copy tracks what `update` just installed. No-op for the global
 * tier (no sibling `.version` file there).
 */
public fun restampLocalBinVersionIfPresent(exePath: Path, newVersion: String) {
    val versionFile = (exePath.parent ?: return) / ".version"
    val fs = FileSystem.SYSTEM
    if (fs.exists(versionFile)) {
        fs.write(versionFile) { writeUtf8(newVersion + "\n") }
    }
}
