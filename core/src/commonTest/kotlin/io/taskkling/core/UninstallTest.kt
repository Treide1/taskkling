package io.taskkling.core

import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure `uninstall` logic (ADR-004): the Windows `PATH` de-entry
 * string-munging — the exact inverse of install.ps1's add
 * (`$rawPath.Split(';') | Where-Object { $_ -ne '' }`, then
 * append-if-absent). Registry IO, self-delete, and the interactive prompt
 * flow are platform primitives / impure IO — QA-gated, not unit-tested here
 * (mirrors [UpdateTest]'s split for the self-replace primitive).
 */
class UninstallTest {

    private val installDir = """C:\Users\me\AppData\Local\Programs\taskkling"""

    // --- removePathEntry -------------------------------------------------------------------------------------------

    @Test
    fun removesTheEntryPreservingOrderOfEveryOther() {
        val path = """C:\Windows\system32;C:\Windows;$installDir;C:\Users\me\.cargo\bin"""
        assertEquals(
            """C:\Windows\system32;C:\Windows;C:\Users\me\.cargo\bin""",
            removePathEntry(path, installDir),
        )
    }

    @Test
    fun removesTheOnlyEntryLeavingAnEmptyString() {
        assertEquals("", removePathEntry(installDir, installDir))
    }

    @Test
    fun removesFromTheFront() {
        val path = """$installDir;C:\Windows;C:\Windows\system32"""
        assertEquals("""C:\Windows;C:\Windows\system32""", removePathEntry(path, installDir))
    }

    @Test
    fun removesFromTheEnd() {
        val path = """C:\Windows;C:\Windows\system32;$installDir"""
        assertEquals("""C:\Windows;C:\Windows\system32""", removePathEntry(path, installDir))
    }

    @Test
    fun isCaseInsensitive() {
        // Windows paths are case-insensitive; the installer's own dupe-check is a plain string
        // compare but the OS treats these as the same path — de-entry must match either way.
        val path = """C:\Windows;${installDir.uppercase()};C:\Users\me\.cargo\bin"""
        assertEquals("""C:\Windows;C:\Users\me\.cargo\bin""", removePathEntry(path, installDir))
    }

    @Test
    fun isNoOpWhenTheEntryIsAbsent() {
        val path = """C:\Windows;C:\Windows\system32"""
        assertEquals(path, removePathEntry(path, installDir))
    }

    @Test
    fun collapsesEmptySegmentsFromTrailingSemicolons() {
        // A trailing (or leading, or doubled) `;` produces empty split segments — install.ps1's
        // own add normalizes these away (`Where-Object { $_ -ne '' }`); de-entry must match, both
        // when removing the target entry AND when leaving an unrelated PATH otherwise untouched.
        val path = """C:\Windows;;$installDir;C:\Users\me\.cargo\bin;"""
        assertEquals("""C:\Windows;C:\Users\me\.cargo\bin""", removePathEntry(path, installDir))
    }

    @Test
    fun isAnUnconditionalNoOpWhenTheEntryIsAbsentEvenWithEmptySegments() {
        // A no-op uninstall must never rewrite a PATH it didn't touch — not even to normalize away
        // pre-existing empty segments the installer's own add would have collapsed had it run again.
        val path = """;C:\Windows;;C:\Windows\system32;"""
        assertEquals(path, removePathEntry(path, installDir))
    }

    @Test
    fun removePathEntryIsTheExactInverseOfInstallPs1sAdd() {
        // Mirrors install.ps1's add: split, drop empties, append-if-absent.
        fun addLikeInstallPs1(rawPath: String, dir: String): String {
            val entries = rawPath.split(';').filter { it.isNotEmpty() }
            return if (entries.contains(dir)) rawPath else (entries + dir).joinToString(";")
        }
        val original = """C:\Windows\system32;C:\Windows;C:\Users\me\.cargo\bin"""
        val added = addLikeInstallPs1(original, installDir)
        assertTrue(installDir in added.split(';'))
        assertEquals(original, removePathEntry(added, installDir))
    }

    // --- pathContainsEntry -----------------------------------------------------------------------------------------

    @Test
    fun pathContainsEntryFindsAnExactMatch() {
        val path = """C:\Windows;$installDir;C:\Windows\system32"""
        assertTrue(pathContainsEntry(path, installDir))
    }

    @Test
    fun pathContainsEntryIsCaseInsensitive() {
        val path = """C:\Windows;${installDir.uppercase()}"""
        assertTrue(pathContainsEntry(path, installDir))
    }

    @Test
    fun pathContainsEntryIsFalseWhenAbsent() {
        val path = """C:\Windows;C:\Windows\system32"""
        assertFalse(pathContainsEntry(path, installDir))
    }

    @Test
    fun pathContainsEntryRejectsASubstringThatIsNotADistinctEntry() {
        // "…\taskkling" must not match "…\taskkling-extra" — entries compare whole, not by substring.
        val path = """C:\Windows;$installDir-extra"""
        assertFalse(pathContainsEntry(path, installDir))
    }

    // --- resolveInstallTier -----------------------------------------------------------------------------------------
    // (FileSystem-backed but pure in the same sense installLocalBin/restampLocalBinVersionIfPresent are —
    // no platform primitive involved; exercised here against a bare in-memory-style temp check is out of
    // scope for commonTest without a JVM temp dir, so this stays a jvmTest-only concern if ever added.)

    // --- cache-home scope (ADR-011) ---------------------------------------------------------------------------------

    @Test
    fun globalUninstallScopeCoversTheCacheHome() {
        assertTrue(uninstallScopeCoversCacheHome(InstallTier.GLOBAL))
    }

    @Test
    fun localUninstallScopeNeverTouchesTheCacheHome() {
        // A surviving global install may still be using the user-level cache (ADR-011's tier guard).
        assertFalse(uninstallScopeCoversCacheHome(InstallTier.LOCAL))
    }

    // --- deleteCacheHomeBestEffort (ADR-011) ------------------------------------------------------------------------

    private val cacheHome = "/home/me/.cache/taskkling".toPath()

    /** A populated ADR-010-shaped cache: versioned UI jars + a runtime image + update-check state. */
    private fun populatedCacheFs(): FakeFileSystem = FakeFileSystem().apply {
        createDirectories(cacheHome / "ui" / "app" / "0.6.0")
        write(cacheHome / "ui" / "app" / "0.6.0" / "taskkling-ui.jar") { writeUtf8("jar") }
        createDirectories(cacheHome / "ui" / "runtime" / "jdk21" / "bin")
        write(cacheHome / "ui" / "runtime" / "jdk21" / "bin" / "java") { writeUtf8("elf") }
        write(cacheHome / "update-check.toml") { writeUtf8("checked_at = 0") }
    }

    @Test
    fun deletesTheWholeCacheHomeAndReportsNothing() {
        val fs = populatedCacheFs()
        assertEquals(emptyList(), deleteCacheHomeBestEffort(cacheHome, fs))
        assertFalse(fs.exists(cacheHome))
    }

    @Test
    fun isANoOpWithNoLeftoversWhenTheCacheHomeDoesNotExist() {
        // Fresh install that never ran `taskkling ui`: nothing to delete, nothing to report.
        assertEquals(emptyList(), deleteCacheHomeBestEffort(cacheHome, FakeFileSystem()))
    }

    /** Wraps [delegate] so deleting exactly [locked] throws — a running UI's file lock, deterministically. */
    private class LockedFileFs(delegate: FileSystem, private val locked: Path) : ForwardingFileSystem(delegate) {
        override fun delete(path: Path, mustExist: Boolean) {
            if (path == locked) throw IOException("locked: $path")
            super.delete(path, mustExist)
        }
    }

    @Test
    fun lockedFileIsSkippedReportedByPathAndEverythingElseStillGoes() {
        val lockedJava = cacheHome / "ui" / "runtime" / "jdk21" / "bin" / "java"
        val fs = LockedFileFs(populatedCacheFs(), lockedJava)

        val leftovers = deleteCacheHomeBestEffort(cacheHome, fs)

        // The locked file is the ONLY reported leftover — its still-standing ancestor dirs are
        // implied by it, not listed; every sibling subtree is gone despite the failure.
        assertEquals(listOf(lockedJava), leftovers)
        assertTrue(fs.exists(lockedJava))
        assertFalse(fs.exists(cacheHome / "ui" / "app"))
        assertFalse(fs.exists(cacheHome / "update-check.toml"))
    }

    @Test
    fun leftoverKeepsOnlyItsOwnAncestorChainStanding() {
        val lockedJar = cacheHome / "ui" / "app" / "0.6.0" / "taskkling-ui.jar"
        val fs = LockedFileFs(populatedCacheFs(), lockedJar)

        deleteCacheHomeBestEffort(cacheHome, fs)

        assertTrue(fs.exists(cacheHome / "ui" / "app" / "0.6.0")) // ancestor of the locked file: must survive
        assertFalse(fs.exists(cacheHome / "ui" / "runtime"))      // unrelated subtree: fully collected
    }
}
