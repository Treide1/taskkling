package io.taskkling.core

import kotlinx.datetime.Clock
import okio.FileSystem
import okio.IOException

/**
 * Run [block] holding the global write lock (PRD §7.1, step 1 & 7).
 *
 * M0 lock: an advisory **lock file** created exclusively (`mustCreate`) under
 * `.taskkling/`. If it already exists, a single stale-reclaim pass runs (a lock
 * older than `lock_timeout` is removed) before one retry; a live lock then fails
 * fast with [ExitCode.LOCK]. Contention is effectively nil at solo scale.
 *
 * Hardening deferred to M1: OS advisory locks (`flock`/`LockFileEx`) and
 * PID-liveness-based reclaim with bounded backoff (PRD §7.1, §7.4).
 */
public fun <T> Workspace.withLock(block: () -> T): T {
    val fs = FileSystem.SYSTEM
    fs.createDirectories(metaDir)
    fs.createDirectories(tmpDir)

    if (!tryCreateLock(fs)) {
        reclaimStaleLock(fs)
        if (!tryCreateLock(fs)) {
            throw TkError(ExitCode.LOCK, "another taskkling process holds the lock ($lockFile)")
        }
    }
    try {
        return block()
    } finally {
        runCatching { fs.delete(lockFile, mustExist = false) }
    }
}

private fun Workspace.tryCreateLock(fs: FileSystem): Boolean =
    try {
        fs.write(lockFile, mustCreate = true) {
            writeUtf8("acquired=${Clock.System.now().epochSeconds}\n")
        }
        true
    } catch (_: IOException) {
        false
    }

private fun Workspace.reclaimStaleLock(fs: FileSystem) {
    val acquired = runCatching { fs.read(lockFile) { readUtf8() } }
        .getOrNull()
        ?.lineSequence()
        ?.map { it.trim() }
        ?.firstOrNull { it.startsWith("acquired=") }
        ?.removePrefix("acquired=")
        ?.toLongOrNull()
    val now = Clock.System.now().epochSeconds
    if (acquired == null || now - acquired > config.lockTimeout) {
        runCatching { fs.delete(lockFile, mustExist = false) }
    }
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
    fs.atomicMove(tmp, target)
}
