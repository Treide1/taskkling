package io.taskkling.core

import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

internal actual fun <T> withSystemFileLock(lockPath: String, timeoutSeconds: Int, block: () -> T): T {
    RandomAccessFile(lockPath, "rw").use { raf ->
        val channel = raf.channel
        val deadline = System.currentTimeMillis() + timeoutSeconds * 1000L
        var lock: FileLock? = null
        while (lock == null) {
            lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (lock == null) {
                if (System.currentTimeMillis() > deadline) {
                    throw TkError(ExitCode.LOCK, "could not acquire lock within ${timeoutSeconds}s ($lockPath)")
                }
                Thread.sleep(50)
            }
        }
        try {
            return block()
        } finally {
            lock.release()
        }
    }
}
