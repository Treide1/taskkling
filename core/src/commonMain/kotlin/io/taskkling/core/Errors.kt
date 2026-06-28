package io.taskkling.core

/**
 * Process exit codes (PRD §10.1). The CLI maps a thrown [TkError] to its
 * [ExitCode.code]; anything uncaught is a `1` (unexpected).
 */
public enum class ExitCode(public val code: Int) {
    OK(0),
    USAGE(2),
    VALIDATION(3),
    LOCK(4),
}

/**
 * A domain failure carrying the exit code the CLI should terminate with.
 * Thrown by `:core` operations; caught and rendered at the `:cli` boundary.
 */
public class TkError(
    public val exit: ExitCode,
    message: String,
) : Exception(message)
