package io.taskkling.core

/** Best-effort; the JVM target is test-only (the shipped binaries are native). */
internal actual fun globalInstallDir(): String {
    val override = System.getenv("TASKKLING_INSTALL_DIR")
    if (!override.isNullOrEmpty()) return override
    val home = System.getenv("HOME") ?: System.getProperty("user.home")
    return "$home/.local/bin"
}

internal actual fun readWindowsUserPath(): WindowsPathValue? = null
internal actual fun writeWindowsUserPath(value: WindowsPathValue) { /* no-op: JVM target is test-only */ }
internal actual fun broadcastEnvironmentChange() { /* no-op */ }
internal actual fun scheduleDeleteOnReboot(path: String) { /* no-op */ }
