package io.taskkling.core

import okio.FileSystem
import okio.Path
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coverage for [Workspace.writeFileAtomic] — the writer half of the protocol
 * (PRD §7.1, steps 5-6): serialize into `.taskkling/tmp/`, then atomically rename
 * onto the target so lock-free readers never see a torn file. We verify content
 * integrity, in-place overwrite (which on Windows takes the delete+rename fallback
 * path), that the temp file is consumed rather than leaked, and — the load-bearing
 * property — that a concurrent reader only ever observes a *complete* payload.
 */
class AtomicWriteTest {

    private fun leftoverTmps(ws: Workspace): List<Path> {
        val fs = FileSystem.SYSTEM
        if (!fs.exists(ws.tmpDir)) return emptyList()
        return fs.list(ws.tmpDir).filter { it.name.endsWith(".tmp") }
    }

    @Test
    fun writesTheFullContentAndLeavesNoTempBehind() {
        val ws = tempWorkspace()
        val target = ws.tasksDir / "atomic-create.md"
        ws.withLock { ws.writeFileAtomic(target, "hello\nworld\n") }

        assertEquals("hello\nworld\n", FileSystem.SYSTEM.read(target) { readUtf8() })
        assertTrue(leftoverTmps(ws).isEmpty(), "the temp file must be consumed by the rename, not leaked")
    }

    @Test
    fun overwritesAnExistingTargetInPlace() {
        // On Windows the fast atomicMove refuses an existing target, so the second
        // write exercises the delete+rename fallback branch explicitly.
        val ws = tempWorkspace()
        val target = ws.tasksDir / "atomic-overwrite.md"
        ws.withLock { ws.writeFileAtomic(target, "v1") }
        ws.withLock { ws.writeFileAtomic(target, "v2-considerably-longer") }

        assertEquals("v2-considerably-longer", FileSystem.SYSTEM.read(target) { readUtf8() })
        assertTrue(leftoverTmps(ws).isEmpty(), "no temp file should survive a successful overwrite")
    }

    @Test
    fun aCrashedWriterLeavesThePriorTargetIntactAndRecovers() {
        // A writer that dies AFTER its tmp is written but BEFORE the rename must
        // leave the target untouched (still the last committed content) with only an
        // orphan .tmp as residue. We cannot kill withLock/writeFileAtomic mid-call
        // deterministically on the JVM (there is no fault-injection seam), so we plant
        // exactly that residue and assert the two observable guarantees: the target
        // never went torn/half-written, and a later write commits cleanly over it.
        val ws = tempWorkspace()
        val fs = FileSystem.SYSTEM
        val target = ws.tasksDir / "atomic-crash.md"
        ws.withLock { ws.writeFileAtomic(target, "good-v1") }

        // Residue of an interrupted write: a fully-written tmp that never got renamed.
        fs.createDirectories(ws.tmpDir)
        fs.write(ws.tmpDir / "${target.name}.999.tmp") { writeUtf8("half-done-never-committed") }

        // The target is still the last committed content, NOT the abandoned tmp.
        assertEquals("good-v1", fs.read(target) { readUtf8() }, "an interrupted write must not corrupt the target")

        // A subsequent write commits cleanly and is not tripped up by the orphan tmp.
        ws.withLock { ws.writeFileAtomic(target, "good-v2") }
        assertEquals("good-v2", fs.read(target) { readUtf8() }, "the target recovers on the next committed write")
    }

    @Test
    fun aConcurrentReaderNeverObservesATornFile() {
        val ws = tempWorkspace()
        val fs = FileSystem.SYSTEM
        val target = ws.tasksDir / "atomic-torn.md"
        fs.createDirectories(ws.tasksDir)

        // Two complete payloads of clearly different lengths: a torn/partial write
        // would match NEITHER, so the reader can catch it by exact-match.
        val small = "S".repeat(64)
        val large = "L".repeat(8192)
        val valid = setOf(small, large)

        ws.withLock { ws.writeFileAtomic(target, small) } // seed

        val stop = AtomicBoolean(false)
        val violation = AtomicReference<String?>(null)
        // Read through java.nio, which opens with FILE_SHARE_DELETE on Windows, so a
        // lock-free reader never blocks the writer's delete+rename fallback. This mirrors
        // the real contract: readers must not need the lock and must never see a torn file.
        val targetPath = Paths.get(target.toString())
        val reader = thread(name = "torn-reader") {
            while (!stop.get()) {
                val seen: String? = try {
                    String(Files.readAllBytes(targetPath), Charsets.UTF_8)
                } catch (_: IOException) {
                    // A brief absence during the Windows delete+rename window reads as
                    // "old state" — acceptable, and never a torn read.
                    null
                }
                if (seen != null && seen !in valid) {
                    violation.set("torn read observed: length=${seen.length}")
                    break
                }
            }
        }

        repeat(400) { i ->
            ws.withLock { ws.writeFileAtomic(target, if (i % 2 == 0) large else small) }
        }
        stop.set(true)
        reader.join(10_000)

        assertNull(
            violation.get(),
            "a lock-free reader must only ever see a whole payload (or none), never a partial file",
        )
    }
}
