package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/** Mirrors install.sh's `INSTALL_DIR="${TASKKLING_INSTALL_DIR:-${HOME}/.local/bin}"`. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun globalInstallDir(): String {
    val override = getenv("TASKKLING_INSTALL_DIR")?.toKString()
    if (!override.isNullOrEmpty()) return override
    val home = getenv("HOME")?.toKString() ?: error("HOME is not set")
    return "$home/.local/bin"
}

// install.sh never edits PATH (ADR-004) — there is nothing to read/write/broadcast on POSIX.
internal actual fun readWindowsUserPath(): WindowsPathValue? = null
internal actual fun writeWindowsUserPath(value: WindowsPathValue) { /* no-op: no PATH registry on POSIX */ }
internal actual fun broadcastEnvironmentChange() { /* no-op: nothing was written */ }

// POSIX self-delete is a direct, immediate unlink (uninstallRunningBinary) — nothing to schedule.
internal actual fun scheduleDeleteOnReboot(path: String) { /* no-op */ }
