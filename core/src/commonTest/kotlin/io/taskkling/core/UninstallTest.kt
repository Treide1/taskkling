package io.taskkling.core

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
}
