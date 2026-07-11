package io.taskkling.core

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The self-overwrite guard (t-1g3t): `init --local-bin` invoked through the
 * in-repo wrapper resolves to the PINNED copy, so the running binary IS the
 * dest — copying it onto itself is meaningless and destructive (a raw
 * "Permission denied" on Windows; an unlink of the source on POSIX). These
 * exercise the guard via the [source]-injecting seam, so no locked image or
 * scratch install tree is needed.
 */
class LocalBinSelfOverwriteTest {
    private val fs = FileSystem.SYSTEM
    private val tmp: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
        "tk-localbin-selfoverwrite-${kotlin.random.Random.nextLong().toString(16)}"

    @AfterTest
    fun cleanup() {
        fs.deleteRecursively(tmp, mustExist = false)
    }

    @Test
    fun reinstallingThePinnedCopyOverItselfFailsWithActionableError() {
        // The dest install lands at <root>/.taskkling/bin/<basename>; point the
        // "running binary" straight at that path so source canonicalizes to dest.
        val root = tmp / "repo"
        val binDir = root / ".taskkling" / "bin"
        fs.createDirectories(binDir)
        val pinned = binDir / "taskkling"
        fs.write(pinned) { writeUtf8("#!fake pinned binary\n") }

        val err = assertFailsWith<TkError> { installLocalBin(root, pinned) }

        assertEquals(ExitCode.VALIDATION, err.exit, "self-overwrite is a validation refusal, not an unexpected crash")
        val msg = err.message ?: ""
        assertTrue("running binary" in msg, "message must name the cause: $msg")
        assertTrue("update --local" in msg, "message must offer a way out: $msg")
        // The source must survive: the guard fires BEFORE the destructive delete.
        assertTrue(fs.exists(pinned), "the pinned binary must not be deleted by a refused self-install")
    }

    @Test
    fun installingADistinctBinaryStillCopiesAndPinsIt() {
        val root = tmp / "fresh"
        val source = tmp / "global" / "taskkling"
        fs.createDirectories(source.parent!!)
        fs.write(source) { writeUtf8("#!global binary\n") }

        val result = installLocalBin(root, source)

        val dest = root / ".taskkling" / "bin" / "taskkling"
        assertEquals(dest, result.binary)
        assertTrue(fs.exists(dest), "a distinct source is copied into the local bin")
        assertEquals("#!global binary\n", fs.read(dest) { readUtf8() })
        assertTrue(fs.exists(root / "taskkling"), "the POSIX wrapper is written")
        assertTrue(fs.exists(root / "taskkling.cmd"), "the cmd wrapper is written")
    }
}
