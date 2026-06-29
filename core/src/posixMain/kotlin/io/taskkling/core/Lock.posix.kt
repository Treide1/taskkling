package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.datetime.Clock
import platform.posix.LOCK_EX
import platform.posix.LOCK_NB
import platform.posix.LOCK_UN
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.close
import platform.posix.flock
import platform.posix.open
import platform.posix.usleep

@OptIn(ExperimentalForeignApi::class)
internal actual fun <T> withSystemFileLock(lockPath: String, timeoutSeconds: Int, block: () -> T): T {
    val fd = open(lockPath, O_RDWR or O_CREAT, 438) // 438 = 0o666
    if (fd < 0) throw TkError(ExitCode.LOCK, "could not open lock file ($lockPath)")
    try {
        val deadline = Clock.System.now().toEpochMilliseconds() + timeoutSeconds * 1000L
        while (flock(fd, LOCK_EX or LOCK_NB) != 0) {
            if (Clock.System.now().toEpochMilliseconds() > deadline) {
                throw TkError(ExitCode.LOCK, "could not acquire lock within ${timeoutSeconds}s ($lockPath)")
            }
            usleep(50_000u) // 50 ms
        }
        try {
            return block()
        } finally {
            flock(fd, LOCK_UN)
        }
    } finally {
        close(fd)
    }
}
