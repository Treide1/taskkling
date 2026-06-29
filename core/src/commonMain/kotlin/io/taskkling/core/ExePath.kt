package io.taskkling.core

/**
 * Absolute path of the **currently running executable** (PRD §6.2). Used by
 * [installLocalBin] to copy the live binary into a per-project
 * `.taskkling/bin/`. Implemented per platform, mirroring the [withSystemFileLock]
 * expect/actual + cinterop pattern: `GetModuleFileNameW` (Windows),
 * `readlink("/proc/self/exe")` (linux), `NSBundle.mainBundle.executablePath`
 * (macOS), `ProcessHandle` (JVM, test-only).
 */
internal expect fun currentExecutablePath(): String

/**
 * Set the executable bit (0755) on [path] after copying a binary or wrapper.
 * No-op where the filesystem has no Unix permission bits (Windows). On POSIX a
 * single `chmod` covers both linux and macOS — the bit pattern is identical.
 */
internal expect fun markExecutable(path: String)
