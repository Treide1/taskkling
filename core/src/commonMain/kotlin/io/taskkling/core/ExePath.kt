package io.taskkling.core

import okio.Path
import okio.Path.Companion.toPath

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
 * Public accessor for [currentExecutablePath] (kept `internal` since only
 * `:core` needs the raw platform primitive). `:cli`'s `update` self-replace
 * flow and its startup `.old`-sweep hook both need to know which binary is
 * actually running — this is the one place outside `:core` allowed to ask.
 */
public fun runningExecutablePath(): Path = currentExecutablePath().toPath()

/**
 * Set the executable bit (0755) on [path] after copying a binary or wrapper.
 * No-op where the filesystem has no Unix permission bits (Windows). On POSIX a
 * single `chmod` covers both linux and macOS — the bit pattern is identical.
 */
internal expect fun markExecutable(path: String)
