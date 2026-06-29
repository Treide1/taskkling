package io.taskkling.core

import java.io.File

/** Best-effort; the JVM target is test-only (the shipped binaries are native). */
internal actual fun currentExecutablePath(): String =
    ProcessHandle.current().info().command().orElse("taskkling")

internal actual fun markExecutable(path: String) {
    File(path).setExecutable(true, false)
}
