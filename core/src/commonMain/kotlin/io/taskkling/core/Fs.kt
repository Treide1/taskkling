package io.taskkling.core

import kotlinx.datetime.Clock
import okio.FileSystem
import okio.IOException

/**
 * Run [block] holding the global write lock (PRD §7.1, step 1 & 7): an exclusive
 * **OS advisory lock** on `.taskkling/lock` via the platform shim
 * ([withSystemFileLock] — `flock`/`LockFileEx`/`FileChannel`). The kernel drops
 * the lock when the process exits, so a crash can't wedge the repo (no
 * stale-reclaim needed). Blocks up to `lock_timeout` seconds, then [ExitCode.LOCK].
 */
public fun <T> Workspace.withLock(block: () -> T): T {
    val fs = FileSystem.SYSTEM
    fs.createDirectories(metaDir)
    fs.createDirectories(tmpDir)
    return withSystemFileLock(lockFile.toString(), config.lockTimeout, block)
}

/**
 * The writer-vs-reader half of the protocol (PRD §7.1, steps 5–6): serialize to
 * a temp file under `.taskkling/tmp/`, then atomically rename it onto [target].
 * Lock-free readers never observe a torn file. Must be called while holding the
 * lock (see [withLock]).
 */
public fun Workspace.writeFileAtomic(target: okio.Path, content: String) {
    val fs = FileSystem.SYSTEM
    fs.createDirectories(tmpDir)
    target.parent?.let { fs.createDirectories(it) }
    val tmp = tmpDir / "${target.name}.${Clock.System.now().toEpochMilliseconds()}.tmp"
    fs.write(tmp) { writeUtf8(content) }
    try {
        fs.atomicMove(tmp, target)
    } catch (_: IOException) {
        // POSIX/JVM rename replaces atomically (fast path above). Windows
        // rename() refuses an existing target, so replace explicitly. Safe
        // under the global write lock; the proper atomic MoveFileEx shim
        // (PRD §7.1) removes even this brief gap in M1 lock hardening.
        fs.delete(target, mustExist = false)
        fs.atomicMove(tmp, target)
    }
}
