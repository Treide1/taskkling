package io.taskkling.core

import okio.Path

/**
 * `taskkling ui` — the platform seam (ADR-010 decisions 4 + 6), mirroring
 * [Update.kt]'s split: the pure pieces here (host-target parse, shell/argv
 * quoting) are unit-tested in commonTest; the three `expect`s below are the
 * verb's ONLY impure primitives (env read, archive extraction, detached
 * spawn), each a thin OS call. The planning logic they act on lives in
 * [UiAssets.kt]; `:cli`'s `ui` subcommand wires the two together.
 */

// --- Host target (pure parse off the CLI's own compile-time asset name) --------------------------------------------

/**
 * Recover (os, arch) from a CLI release-asset name (`taskkling-<os>-<arch>[.exe]`).
 * The inverse of [resolveAssetName]; throws [IllegalArgumentException] on
 * anything that isn't a published CLI asset name.
 */
public fun hostTargetFromCliAssetName(assetName: String): Pair<HostOs, HostArch> {
    val target = assetName.removePrefix("taskkling-").removeSuffix(".exe")
    val os = when (target.substringBefore('-')) {
        "linux" -> HostOs.LINUX
        "macos" -> HostOs.MACOS
        "windows" -> HostOs.WINDOWS
        else -> throw IllegalArgumentException("not a taskkling CLI asset name: $assetName")
    }
    val arch = when (target.substringAfterLast('-')) {
        "x64" -> HostArch.X64
        "arm64" -> HostArch.ARM64
        else -> throw IllegalArgumentException("not a taskkling CLI asset name: $assetName")
    }
    return os to arch
}

/**
 * This binary's own (os, arch) — derived from [currentReleaseAssetName] (each
 * native target already names its compile-time constant there) rather than a
 * second set of per-target actuals, so the target vocabulary keeps exactly
 * one authoritative source per binary.
 */
public fun currentHostTarget(): Pair<HostOs, HostArch> = hostTargetFromCliAssetName(currentReleaseAssetName())

// --- Quoting (pure, one function per shell the actuals go through) --------------------------------------------------

/**
 * POSIX single-quoting: wraps [arg] so `sh -c` (what [system] runs through)
 * treats it as one literal word — `'` becomes `'\''`, everything else is
 * inert inside single quotes. Used by the posix spawn/extract actuals.
 */
public fun shellSingleQuote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

/**
 * Windows argv quoting per the MSVCRT/CommandLineToArgvW rules the JVM's own
 * launcher parses with: quote every arg; a literal `"` becomes `\"`, and a run
 * of N backslashes immediately before a `"` (or the closing quote) doubles to
 * 2N. Produces the single command-line string `CreateProcessW` takes.
 */
public fun windowsCommandLine(argv: List<String>): String = argv.joinToString(" ") { arg ->
    buildString {
        append('"')
        var backslashes = 0
        for (c in arg) {
            when (c) {
                '\\' -> backslashes++
                '"' -> {
                    repeat(backslashes * 2 + 1) { append('\\') }
                    backslashes = 0
                    append('"')
                }
                else -> {
                    repeat(backslashes) { append('\\') }
                    backslashes = 0
                    append(c)
                }
            }
        }
        repeat(backslashes * 2) { append('\\') }
        append('"')
    }
}

// --- Platform primitives (expect/actual, mirroring Update.kt / Uninstall.kt's split) --------------------------------

/** Read one environment variable ([headlessRefusalMessage]'s input). Null when unset. */
internal expect fun readEnvVar(name: String): String?

/** The display-relevant environment, in the shape [headlessRefusalMessage] consumes. */
public fun uiLaunchEnvironment(): Map<String, String> =
    listOf("DISPLAY", "WAYLAND_DISPLAY").mapNotNull { name -> readEnvVar(name)?.let { name to it } }.toMap()

/**
 * Extract [archive] (tar.gz or zip) into the EXISTING directory [destDir]
 * using the system `tar` — GNU tar and bsdtar both auto-detect compression,
 * and Windows 10+'s bundled bsdtar reads zip; the CLI doing its own
 * extraction is what preserves the no-quarantine path on macOS (ADR-009).
 * Returns false when tar is missing or exits non-zero.
 */
public expect fun extractArchiveWithSystemTar(archive: Path, destDir: Path): Boolean

/**
 * Spawn [argv] as a DETACHED process — the verb prints one line and returns
 * the prompt while the UI lives on (ADR-010 decision 4) — with stdout+stderr
 * redirected to [logFile] (truncated; the previous session's log has no
 * audience once a newer one exists). Returns whether the SPAWN succeeded;
 * anything the child does after that is the log file's story.
 */
public expect fun spawnDetachedProcess(argv: List<String>, logFile: Path): Boolean
