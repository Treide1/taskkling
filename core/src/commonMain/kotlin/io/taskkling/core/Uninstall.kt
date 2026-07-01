package io.taskkling.core

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * `taskkling uninstall` (ADR-004) — the symmetric inverse of install. This
 * file holds the PURE, testable pieces (Windows `PATH` de-entry
 * string-munging; tier auto-detection off [installLocalBin]'s own marker)
 * plus the platform-specific primitives (`expect`/`actual`, mirroring
 * [ExePath.kt] / [Update.kt]'s split): the fixed global-tier install
 * directory per OS, the Windows `HKCU\Environment\Path` registry read/write,
 * its change-broadcast, and delete-on-reboot scheduling for the renamed
 * running `.exe`. `:cli`'s `uninstall` subcommand wires these into the
 * interactive prompt / `-y` / `--purge` consent flow (ADR-004); the impure IO
 * (prompting, println) stays there, thin, around this file's decision logic.
 */

/** Which install tier a resolved binary belongs to (ADR-001's two tiers). */
public enum class InstallTier { GLOBAL, LOCAL }

/**
 * A per-project local-bin copy is marked by the sibling `.version` file
 * [installLocalBin] writes next to the binary (the same marker
 * [restampLocalBinVersionIfPresent] updates on `update`). Auto-detecting the
 * tier from the resolved binary's own path — rather than assuming a fixed
 * directory name — works regardless of where a project root lives.
 */
public fun resolveInstallTier(exePath: Path): InstallTier {
    val parent = exePath.parent ?: return InstallTier.GLOBAL
    return if (FileSystem.SYSTEM.exists(parent / ".version")) InstallTier.LOCAL else InstallTier.GLOBAL
}

// --- Windows PATH de-entry (inverts install.ps1's add; preserves REG_EXPAND_SZ, t-3vt8) ------------------------

/** The registry value KIND install.ps1 preserves when adding an entry (t-3vt8) — uninstall must preserve it too. */
public enum class WindowsPathValueType { REG_SZ, REG_EXPAND_SZ }

/** A raw (unexpanded) Windows `PATH`-style registry value plus the type it was stored as. */
public data class WindowsPathValue(val raw: String, val type: WindowsPathValueType)

/**
 * Remove [dirToRemove] from a `;`-delimited Windows `PATH`-style value,
 * preserving the order and content of every OTHER entry. The exact inverse of
 * install.ps1's add (`$rawPath.Split(';') | Where-Object { $_ -ne '' }`, then
 * append-if-absent): split on `;`, drop empty segments — a leading/trailing
 * `;` or a doubled `;;` collapses away on both add AND remove, matching the
 * installer's own normalization — drop entries matching [dirToRemove]
 * case-insensitively (Windows paths are case-insensitive), rejoin with `;`.
 * Returns [rawPath] unchanged if [dirToRemove] is not present as an entry —
 * a no-op uninstall must never rewrite a `PATH` it didn't touch.
 */
public fun removePathEntry(rawPath: String, dirToRemove: String): String {
    val entries = rawPath.split(';').filter { it.isNotEmpty() }
    if (entries.none { it.equals(dirToRemove, ignoreCase = true) }) return rawPath
    return entries.filterNot { it.equals(dirToRemove, ignoreCase = true) }.joinToString(";")
}

/** Whether [dirToRemove] is present as a distinct entry in [rawPath] (pre-check before touching the registry). */
public fun pathContainsEntry(rawPath: String, dirToRemove: String): Boolean =
    rawPath.split(';').filter { it.isNotEmpty() }.any { it.equals(dirToRemove, ignoreCase = true) }

// --- Platform primitives (expect/actual, mirroring ExePath.kt / Update.kt's split) ------------------------------

/**
 * The fixed, per-OS global-tier install directory — mirrors install.ps1's
 * `%LOCALAPPDATA%\Programs\taskkling` / install.sh's
 * `${TASKKLING_INSTALL_DIR:-$HOME/.local/bin}` — where `--global` targets
 * when it isn't simply the already-running binary.
 */
internal expect fun globalInstallDir(): String

/**
 * Read `HKCU\Environment\Path` RAW (undecoded `%VAR%` refs), returning both
 * the string and the value's registry type — install.ps1's own concern
 * (REG_EXPAND_SZ vs REG_SZ, t-3vt8) applies symmetrically on the way out.
 * Null if the value doesn't exist. POSIX/JVM: always null — nothing to read,
 * since install.sh never edits `PATH` (ADR-004).
 */
internal expect fun readWindowsUserPath(): WindowsPathValue?

/**
 * Write `HKCU\Environment\Path`, using the SAME registry type it was read
 * with ([WindowsPathValue.type]) — never downgrades REG_EXPAND_SZ to REG_SZ,
 * the exact bug install.ps1's own fix (t-3vt8) guards against on the add
 * side. No-op on POSIX/JVM.
 */
internal expect fun writeWindowsUserPath(value: WindowsPathValue)

/**
 * Nudge running processes so a fresh shell sees the `PATH` change without a
 * reboot (mirrors install.ps1's dummy-env-var round trip, which fires
 * `WM_SETTINGCHANGE` under the hood) by broadcasting it directly via
 * `SendMessageTimeoutW(HWND_BROADCAST, WM_SETTINGCHANGE, ...)`. No-op on
 * POSIX/JVM.
 */
internal expect fun broadcastEnvironmentChange()

/**
 * Schedule [path] for deletion on the next Windows reboot
 * (`MoveFileExW(path, NULL, MOVEFILE_DELAY_UNTIL_REBOOT)`) — used only for
 * uninstall's Windows self-delete, after [renameSelfToOld] has freed the live
 * path: unlike `update` (ADR-002), uninstall has no "next run" left to sweep
 * the renamed `.old` sibling, so the OS itself is asked to clear it on boot.
 * No-op on POSIX (self-delete there is a direct, immediate unlink — see
 * [uninstallRunningBinary]).
 */
internal expect fun scheduleDeleteOnReboot(path: String)

// --- Public orchestration (the thin, testable-by-construction layer :cli calls) ---------------------------------

/** Public accessor for [globalInstallDir] (kept `internal`, mirroring [runningExecutablePath]'s split). */
public fun globalInstallDirPath(): Path = globalInstallDir().toPath()

/** Whether [installDir] is currently present in the Windows user `PATH`. False (nothing to check) on POSIX/JVM. */
public fun windowsPathHasEntry(installDir: String): Boolean {
    val current = readWindowsUserPath() ?: return false
    return pathContainsEntry(current.raw, installDir)
}

/**
 * Remove [installDir] from the Windows user `PATH` registry value if
 * present, preserving the value's type and every other entry
 * ([removePathEntry]), then broadcast the change so a fresh shell picks it up
 * without a reboot ([broadcastEnvironmentChange]) — the read/modify/write/
 * broadcast sequence `:cli`'s `uninstall` calls as one step. Returns whether
 * a change was made; false (no-op) on POSIX, where install.sh never touched
 * `PATH` to begin with (ADR-004), and whenever [installDir] wasn't present.
 */
public fun removeFromWindowsUserPath(installDir: String): Boolean {
    val current = readWindowsUserPath() ?: return false
    if (!pathContainsEntry(current.raw, installDir)) return false
    writeWindowsUserPath(WindowsPathValue(removePathEntry(current.raw, installDir), current.type))
    broadcastEnvironmentChange()
    return true
}

/**
 * Remove the CURRENTLY RUNNING binary at [exePath]. POSIX: [renameSelfToOld]
 * is a no-op there, so this falls through to a plain delete — a running
 * binary may be `unlink`ed while open (the inode survives until the last
 * handle closes). Windows: [renameSelfToOld] (ADR-002's shared self-replace
 * primitive) frees the locked live path by renaming it to a `.old` sibling,
 * and [scheduleDeleteOnReboot] asks the OS to clear that sibling on next
 * boot, since — unlike `update` — there is no subsequent run to sweep it.
 */
public fun uninstallRunningBinary(exePath: Path) {
    val renamedTo = renameSelfToOld(exePath.toString())
    if (renamedTo != exePath.toString()) {
        scheduleDeleteOnReboot(renamedTo) // Windows: the rename actually happened; schedule the sibling's cleanup.
    } else {
        FileSystem.SYSTEM.delete(exePath, mustExist = false) // POSIX: no-op rename, so unlink it directly.
    }
}

/**
 * Remove a binary that is NOT the currently running process — a plain,
 * unlocked delete on both OSes (the running-image lock only bites the
 * process's own file, ADR-004).
 */
public fun uninstallOtherBinary(exePath: Path) {
    FileSystem.SYSTEM.delete(exePath, mustExist = false)
}
