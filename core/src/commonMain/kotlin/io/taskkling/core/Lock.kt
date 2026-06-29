package io.taskkling.core

/**
 * Acquire an **exclusive OS advisory lock** on [lockPath], run [block], release
 * (PRD §7.1, step 1 & 7). Implemented per platform: `flock` (POSIX), `LockFileEx`
 * (Windows), `FileChannel.lock` (JVM). Non-blocking acquisition is polled until
 * [timeoutSeconds] elapses, then [TkError] with [ExitCode.LOCK] is thrown.
 *
 * Because the kernel drops the lock when the holding process exits, a crashed
 * process can never wedge the repo — so no PID/timestamp stale-reclaim is needed
 * (PRD §7.4). The lock file is a persistent target; its contents are unused.
 */
internal expect fun <T> withSystemFileLock(lockPath: String, timeoutSeconds: Int, block: () -> T): T
