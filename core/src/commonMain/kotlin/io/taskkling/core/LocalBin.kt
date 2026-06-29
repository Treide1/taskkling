package io.taskkling.core

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/** Outcome of [installLocalBin]: where the binary landed and the wrappers written. */
public data class LocalBinResult(
    val binary: Path,
    val wrappers: List<Path>,
    val version: String,
)

/**
 * Self-install the running binary into a per-project `.taskkling/bin/` and drop
 * `./taskkling` (POSIX) + `./taskkling.cmd` (Windows) wrappers at [root], so the
 * project carries its own pinned tool (`init --local-bin`, PRD §6.2). Steps:
 *
 *  1. copy [currentExecutablePath] to `root/.taskkling/bin/<basename>` and mark
 *     it executable (the basename is `taskkling.exe` on Windows, `taskkling`
 *     elsewhere — taken from the running executable);
 *  2. stamp `root/.taskkling/bin/.version` with [Taskkling.VERSION] (pins what
 *     was installed);
 *  3. write BOTH wrapper scripts regardless of host, so a cross-platform
 *     checkout has each: the POSIX `taskkling` and the Windows `taskkling.cmd`,
 *     both delegating to the copied binary by their own directory.
 *
 * Idempotent: existing copies and wrappers are overwritten.
 */
public fun installLocalBin(root: Path): LocalBinResult {
    val fs = FileSystem.SYSTEM
    val source = currentExecutablePath().toPath()
    val basename = source.name // taskkling.exe on Windows, taskkling elsewhere

    val binDir = root / ".taskkling" / "bin"
    fs.createDirectories(binDir)

    val dest = binDir / basename
    fs.delete(dest, mustExist = false)
    fs.copy(source, dest)
    markExecutable(dest.toString())

    fs.write(binDir / ".version") { writeUtf8(Taskkling.VERSION + "\n") }

    // POSIX wrapper: resolve own dir, exec the copied binary with all args.
    val posixWrapper = root / "taskkling"
    fs.write(posixWrapper) {
        writeUtf8("#!/bin/sh\n")
        writeUtf8("exec \"\$(CDPATH= cd -- \"\$(dirname -- \"\$0\")\" && pwd)/.taskkling/bin/taskkling\" \"\$@\"\n")
    }
    markExecutable(posixWrapper.toString())

    // Windows wrapper: %~dp0 is this script's directory (with trailing backslash).
    val cmdWrapper = root / "taskkling.cmd"
    fs.write(cmdWrapper) {
        writeUtf8("@echo off\r\n")
        writeUtf8("\"%~dp0.taskkling\\bin\\taskkling.exe\" %*\r\n")
    }

    return LocalBinResult(dest, listOf(posixWrapper, cmdWrapper), Taskkling.VERSION)
}
