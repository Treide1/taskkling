package io.taskkling.core

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wrapper scripts exist in two places by design (t-htr1): the TRACKED
 * repo-root files (so a fresh git worktree — where the git-ignored
 * `.taskkling/` is absent — always has a working `./taskkling` entry point)
 * and the constants [installLocalBin] regenerates from. These tests are the
 * sync tripwire: byte-for-byte equality, deterministic because
 * `.gitattributes` pins `/taskkling` to LF and `*.cmd` to CRLF in the working
 * tree on every platform.
 */
class LocalBinWrapperTest {
    private val fs = FileSystem.SYSTEM

    /** Repo root, resolved from the test's working directory (the `core/` module dir). */
    private val repoRoot: Path = fs.canonicalize("..".toPath())

    @Test
    fun trackedPosixWrapperMatchesTheGeneratedContent() {
        assertEquals(
            POSIX_WRAPPER,
            fs.read(repoRoot / "taskkling") { readUtf8() },
            "tracked ./taskkling must byte-match LocalBin.kt's POSIX_WRAPPER — edit both together",
        )
    }

    @Test
    fun trackedCmdWrapperMatchesTheGeneratedContent() {
        assertEquals(
            CMD_WRAPPER,
            fs.read(repoRoot / "taskkling.cmd") { readUtf8() },
            "tracked ./taskkling.cmd must byte-match LocalBin.kt's CMD_WRAPPER — edit both together",
        )
    }

    @Test
    fun cmdWrapperIsStrictlyCrlf() {
        val lonelyLf = Regex("(?<!\r)\n")
        assertTrue(
            !lonelyLf.containsMatchIn(CMD_WRAPPER),
            "cmd.exe batch parsing has LF edge cases — every line ending must be CRLF",
        )
        assertTrue(!POSIX_WRAPPER.contains('\r'), "a CR in the #!/bin/sh script breaks the interpreter")
    }
}
