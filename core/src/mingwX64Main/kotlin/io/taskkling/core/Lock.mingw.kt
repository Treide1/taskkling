package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.datetime.Clock
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.GENERIC_READ
import platform.windows.GENERIC_WRITE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.LOCKFILE_EXCLUSIVE_LOCK
import platform.windows.LOCKFILE_FAIL_IMMEDIATELY
import platform.windows.LockFileEx
import platform.windows.OPEN_ALWAYS
import platform.windows.OVERLAPPED
import platform.windows.Sleep
import platform.windows.UnlockFileEx

@OptIn(ExperimentalForeignApi::class)
internal actual fun <T> withSystemFileLock(lockPath: String, timeoutSeconds: Int, block: () -> T): T {
    val handle = CreateFileW(
        lockPath,
        (GENERIC_READ or GENERIC_WRITE.toUInt()),
        (FILE_SHARE_READ.toUInt() or FILE_SHARE_WRITE.toUInt()),
        null,
        OPEN_ALWAYS.toUInt(),
        FILE_ATTRIBUTE_NORMAL.toUInt(),
        null,
    )
    if (handle == null || handle == INVALID_HANDLE_VALUE) {
        throw TkError(ExitCode.LOCK, "could not open lock file ($lockPath)")
    }
    try {
        val flags = (LOCKFILE_EXCLUSIVE_LOCK.toUInt() or LOCKFILE_FAIL_IMMEDIATELY.toUInt())
        val deadline = Clock.System.now().toEpochMilliseconds() + timeoutSeconds * 1000L
        memScoped {
            val ov = alloc<OVERLAPPED>()
            ov.Offset = 0u
            ov.OffsetHigh = 0u
            ov.hEvent = null
            while (LockFileEx(handle, flags, 0u, 1u, 0u, ov.ptr) == 0) {
                if (Clock.System.now().toEpochMilliseconds() > deadline) {
                    throw TkError(ExitCode.LOCK, "could not acquire lock within ${timeoutSeconds}s ($lockPath)")
                }
                Sleep(50u)
            }
        }
        try {
            return block()
        } finally {
            memScoped {
                val ov = alloc<OVERLAPPED>()
                ov.Offset = 0u
                ov.OffsetHigh = 0u
                ov.hEvent = null
                UnlockFileEx(handle, 0u, 1u, 0u, ov.ptr)
            }
        }
    } finally {
        CloseHandle(handle)
    }
}
