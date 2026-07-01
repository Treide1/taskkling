package io.taskkling.core

/**
 * Whether this process's standard output is connected to an interactive
 * terminal (a TTY), as opposed to a pipe, a redirected file, or a CI log.
 *
 * ADR-006 gates the passive `--version` update-check notifier on this: with the
 * check on by default, only an *interactive* `--version` may make a network
 * call, so CI, pipes, `| grep`, and docker-build stay fully offline and silent.
 * This is the third per-OS primitive after [currentExecutablePath] and
 * [userConfigDir], implemented the same `expect`/`actual` way:
 * `isatty(STDOUT_FILENO)` on POSIX (linux + macOS), `_isatty(_fileno(stdout))`
 * on Windows, `System.console()` on the JVM (test-only).
 */
internal expect fun stdoutIsInteractive(): Boolean

/**
 * Public accessor for [stdoutIsInteractive] (the primitive is kept `internal`,
 * mirroring [runningExecutablePath] / [userConfigDirPath]). `:cli`'s `--version`
 * notifier calls this to decide whether the passive, on-by-default check is
 * allowed to run at all (ADR-006) — a non-interactive stdout returns before any
 * network or cache IO happens.
 */
public fun isStdoutInteractive(): Boolean = stdoutIsInteractive()
