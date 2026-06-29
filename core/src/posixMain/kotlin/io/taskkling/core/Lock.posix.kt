package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.datetime.Clock
import platform.posix.F_SETLK
import platform.posix.F_UNLCK
import platform.posix.F_WRLCK
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.fcntl
import platform.posix.flock
import platform.posix.open
import platform.posix.usleep

/**
 * POSIX advisory lock via `fcntl(F_SETLK)` with a whole-file `struct flock`
 * (write lock). Chosen over BSD `flock()` because on linux the `flock` name
 * resolves to the `struct`, shadowing the function — `fcntl` is unambiguous and
 * identical on linux + macOS. The kernel releases the lock on process exit.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun <T> withSystemFileLock(lockPath: String, timeoutSeconds: Int, block: () -> T): T {
    val fd = open(lockPath, O_RDWR or O_CREAT, 438) // 438 = 0o666
    if (fd < 0) throw TkError(ExitCode.LOCK, "could not open lock file ($lockPath)")
    try {
        memScoped {
            val fl = alloc<flock>()
            fl.l_type = F_WRLCK.convert()
            fl.l_whence = SEEK_SET.convert()
            fl.l_start = 0.convert()
            fl.l_len = 0.convert()
            val deadline = Clock.System.now().toEpochMilliseconds() + timeoutSeconds * 1000L
            while (fcntl(fd, F_SETLK, fl.ptr) != 0) {
                if (Clock.System.now().toEpochMilliseconds() > deadline) {
                    throw TkError(ExitCode.LOCK, "could not acquire lock within ${timeoutSeconds}s ($lockPath)")
                }
                usleep(50_000u) // 50 ms
            }
        }
        try {
            return block()
        } finally {
            memScoped {
                val fl = alloc<flock>()
                fl.l_type = F_UNLCK.convert()
                fl.l_whence = SEEK_SET.convert()
                fl.l_start = 0.convert()
                fl.l_len = 0.convert()
                fcntl(fd, F_SETLK, fl.ptr)
            }
        }
    } finally {
        close(fd)
    }
}
