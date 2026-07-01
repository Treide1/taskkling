package io.taskkling.core

/**
 * POSIX has no "running executable image is locked" restriction — a binary
 * can be unlinked (or renamed over) while it's still executing, so `update`
 * just atomically renames the new binary directly over the live path
 * ([installNewExecutable]) without needing to free it first. No-op, returning
 * [exePath] unchanged.
 */
internal actual fun renameSelfToOld(exePath: String): String = exePath
