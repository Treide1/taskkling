package io.taskkling.core

import okio.FileSystem
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Direct coverage of the global write lock ([Workspace.withLock] → the
 * [withSystemFileLock] shim). The pre-existing [ConcurrencyTest] only exercises
 * the *re-read-fresh* merge protocol single-threaded; here we drive the lock
 * primitive itself: real contention, the `lock_timeout` -> [ExitCode.LOCK]
 * deadline, release on both normal and exceptional exit, and true mutual
 * exclusion across threads.
 *
 * The JVM actual ([Lock.jvm]) locks via `FileChannel.tryLock`, so a second
 * acquirer *in the same JVM* trips `OverlappingFileLockException` (which the
 * actual maps to "still held") — that lets us test in-process contention
 * deterministically. The genuinely *cross-process* leg (a crashed holder's lock
 * being reclaimed by the kernel) is covered by [aDeadHoldersLockIsReclaimed],
 * which spawns a real second JVM. The NATIVE actuals (`Lock.mingw`/`Lock.posix`)
 * run only under the native test targets in CI and are NOT exercised here.
 */
class WriteLockTest {

    // A contender must poll for at least ~the full lock_timeout before giving up.
    // Asserted against a 1s timeout with slack for coarse wall-clock granularity;
    // the shim only throws once it observes now > deadline, so this can never be
    // exceeded downward by a correct implementation (an instant failure = a bug).
    private val minTimeoutElapsedMs = 900L

    /**
     * Acquire [ws]'s write lock on a background thread, run [whileHeld] with the
     * lock genuinely held by that other thread, then release and join. Lets a test
     * drive a second acquirer against a live holder without repeating the latch
     * choreography (local helper; the shared [tempWorkspace] fixture is untouched).
     */
    private fun holdingTheLock(ws: Workspace, whileHeld: () -> Unit) {
        val acquired = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread(name = "lock-holder") {
            ws.withLock {
                acquired.countDown()
                release.await()
            }
        }
        try {
            assertTrue(acquired.await(5, TimeUnit.SECONDS), "holder failed to acquire the lock")
            whileHeld()
        } finally {
            release.countDown()
            holder.join(5_000)
        }
    }

    @Test
    fun withLockReturnsTheBlockValue() {
        val ws = tempWorkspace()
        assertEquals(42, ws.withLock { 42 })
    }

    @Test
    fun sequentialAcquisitionsEachSucceed() {
        // Proves the lock is actually released between calls (a leak would wedge
        // the second acquire until its timeout, or forever).
        val ws = tempWorkspace()
        var ran = 0
        ws.withLock { ran++ }
        ws.withLock { ran++ }
        assertEquals(2, ran)
    }

    @Test
    fun lockIsReleasedWhenTheBlockThrows() {
        val ws = tempWorkspace()
        assertFailsWith<IllegalStateException> {
            ws.withLock { throw IllegalStateException("boom") }
        }
        // If the exceptional path leaked the lock, this would block until timeout.
        assertEquals("ok", ws.withLock { "ok" })
    }

    @Test
    fun aSecondAcquirerTimesOutWhileTheLockIsHeld() {
        val ws = tempWorkspace()
        val contender = Workspace(ws.root, ws.config.copy(lockTimeout = 1))
        holdingTheLock(ws) {
            val start = System.currentTimeMillis()
            val err = assertFailsWith<TkError> {
                contender.withLock { fail("must not acquire a lock that is already held") }
            }
            val elapsed = System.currentTimeMillis() - start

            assertEquals(ExitCode.LOCK, err.exit, "a timed-out acquire must map to ExitCode.LOCK")
            assertTrue(
                elapsed >= minTimeoutElapsedMs,
                "the contender must poll for ~the full lock_timeout (1s) before giving up, was ${elapsed}ms",
            )
        }
    }

    @Test
    fun aBlockedAcquirerWinsOnceTheHolderReleases() {
        val ws = tempWorkspace()
        val contender = Workspace(ws.root, ws.config.copy(lockTimeout = 10))
        val won = AtomicBoolean(false)
        lateinit var blocked: Thread
        holdingTheLock(ws) {
            // An acquirer that starts while the lock is held must BLOCK, not fail.
            blocked = thread(name = "blocked-acquirer") { contender.withLock { won.set(true) } }
            Thread.sleep(300) // let it enter the poll loop; it must still be waiting
            assertFalse(won.get(), "the contender must not acquire while the lock is held")
        }
        // holdingTheLock has now released; the previously-blocked acquirer must win.
        blocked.join(11_000)
        assertTrue(won.get(), "a blocked acquirer must succeed once the holder releases")
    }

    @Test
    fun concurrentWritersAreMutuallyExcluded() {
        // Non-atomic read-modify-write under the lock: any overlap would drop
        // increments, so a correct final count proves the critical sections never
        // ran concurrently. AtomicInteger is used only for cross-thread visibility,
        // NOT to make the increment atomic (we deliberately split get/set).
        val ws = tempWorkspace()
        val counter = AtomicInteger(0)
        val threadCount = 4
        val perThread = 10

        val workers = (1..threadCount).map {
            thread(name = "writer-$it") {
                repeat(perThread) {
                    ws.withLock {
                        val seen = counter.get()
                        Thread.sleep(1) // widen the window a real race would exploit
                        counter.set(seen + 1)
                    }
                }
            }
        }
        workers.forEach { it.join(60_000) }

        assertEquals(threadCount * perThread, counter.get(), "an increment was lost -> the lock did not serialize writers")
    }

    /**
     * The cross-PROCESS safety property the whole design rests on (PRD §7.4): the
     * kernel drops a held lock when the owning process exits, so a crashed writer
     * can never wedge the repo. We spawn a real second JVM that grabs the same OS
     * lock, verify we cannot acquire it while that process lives, kill the process,
     * and verify we then reclaim the lock.
     *
     * The holder is a self-contained single-file `java` program (JDK 11+ source
     * launcher) so it needs no classpath and stays independent of the test's own
     * dependencies. This validates the JVM actual only; the native actuals'
     * cross-process behaviour remains a CI/native-target concern.
     */
    @Test
    fun aDeadHoldersLockIsReclaimed() {
        val ws = tempWorkspace()
        FileSystem.SYSTEM.createDirectories(ws.metaDir)
        val lockPath = ws.lockFile.toString()

        val javaHome = System.getProperty("java.home")
        val isWindows = System.getProperty("os.name").lowercase().contains("win")
        val javaBin = File(javaHome, if (isWindows) "bin/java.exe" else "bin/java")
        if (!javaBin.isFile) fail("could not locate the java launcher at $javaBin")

        val marker = File.createTempFile("tk-lock-ready", ".marker").also { it.delete() }
        val holderSrc = File.createTempFile("LockHolder", ".java").apply {
            writeText(
                """
                import java.io.FileWriter;
                import java.io.RandomAccessFile;
                import java.nio.channels.FileLock;

                public class LockHolder {
                    public static void main(String[] a) throws Exception {
                        RandomAccessFile raf = new RandomAccessFile(a[0], "rw");
                        FileLock lock = raf.getChannel().lock(); // blocking, whole-file, exclusive
                        try (FileWriter w = new FileWriter(a[1])) { w.write("locked"); }
                        Thread.sleep(600000L); // held until forcibly killed by the test
                        lock.release();
                        raf.close();
                    }
                }
                """.trimIndent(),
            )
        }
        // The single-file source launcher requires the file's base name to match
        // the public class name ("LockHolder").
        val holderJava = File(holderSrc.parentFile, "LockHolder.java")
        holderSrc.copyTo(holderJava, overwrite = true)
        holderSrc.delete()

        val proc = ProcessBuilder(
            javaBin.absolutePath, holderJava.absolutePath, lockPath, marker.absolutePath,
        ).inheritIO().start()

        try {
            // Wait until the child actually holds the OS lock (signalled via the marker).
            val readyDeadline = System.currentTimeMillis() + 30_000
            while (!marker.exists()) {
                assertTrue(proc.isAlive, "the lock-holder subprocess died before acquiring the lock")
                assertTrue(System.currentTimeMillis() < readyDeadline, "the subprocess never signalled acquisition")
                Thread.sleep(50)
            }

            // 1) Another live process holds the lock -> we must fail to acquire.
            val contender = Workspace(ws.root, ws.config.copy(lockTimeout = 1))
            val err = assertFailsWith<TkError> {
                contender.withLock { fail("acquired a lock held by another live process") }
            }
            assertEquals(ExitCode.LOCK, err.exit)

            // 2) Kill the holder; the kernel releases its lock on process exit.
            proc.destroyForcibly()
            assertTrue(proc.waitFor(30, TimeUnit.SECONDS), "the subprocess did not exit after being killed")

            // 3) The lock is reclaimable now that the owner is gone.
            val reclaimer = Workspace(ws.root, ws.config.copy(lockTimeout = 15))
            assertEquals("reclaimed", reclaimer.withLock { "reclaimed" })
        } finally {
            proc.destroyForcibly()
            marker.delete()
            holderJava.delete()
        }
    }
}
