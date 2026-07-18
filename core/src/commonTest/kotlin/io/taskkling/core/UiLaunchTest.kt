package io.taskkling.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The pure half of the `taskkling ui` platform seam ([UiLaunch.kt]): the
 * host-target parse and the two quoting rules the spawn/extract actuals feed
 * their shells. The actuals themselves (env read, tar, detached spawn) are
 * thin OS calls — QA-gated, mirroring [UpdateTest]'s treatment of the
 * self-replace primitive.
 */
class UiLaunchTest {

    // --- hostTargetFromCliAssetName (inverse of resolveAssetName) -----------------------------------------------------

    @Test
    fun parsesEveryPublishedCliAssetNameBackToItsTarget() {
        assertEquals(HostOs.LINUX to HostArch.X64, hostTargetFromCliAssetName("taskkling-linux-x64"))
        assertEquals(HostOs.MACOS to HostArch.ARM64, hostTargetFromCliAssetName("taskkling-macos-arm64"))
        assertEquals(HostOs.MACOS to HostArch.X64, hostTargetFromCliAssetName("taskkling-macos-x64"))
        assertEquals(HostOs.WINDOWS to HostArch.X64, hostTargetFromCliAssetName("taskkling-windows-x64.exe"))
    }

    @Test
    fun isTheExactInverseOfResolveAssetName() {
        // Round-trip through the one authoritative naming rule: whatever a binary calls
        // itself, the parse must recover the (os, arch) that produced the name.
        listOf(
            HostOs.LINUX to HostArch.X64,
            HostOs.MACOS to HostArch.ARM64,
            HostOs.MACOS to HostArch.X64,
            HostOs.WINDOWS to HostArch.X64,
        ).forEach { (os, arch) ->
            assertEquals(os to arch, hostTargetFromCliAssetName(resolveAssetName(os, arch)))
        }
    }

    @Test
    fun rejectsForeignAssetNames() {
        assertFailsWith<IllegalArgumentException> { hostTargetFromCliAssetName("taskkling-ui-linux-x64.jar") }
        assertFailsWith<IllegalArgumentException> { hostTargetFromCliAssetName("something-else") }
    }

    // --- shellSingleQuote (what the posix actuals feed sh -c) ---------------------------------------------------------

    @Test
    fun singleQuotingMakesSpacesAndMetacharactersInert() {
        assertEquals("'/Users/me/Library/Application Support/x'", shellSingleQuote("/Users/me/Library/Application Support/x"))
        assertEquals("'a\$b`c\"d'", shellSingleQuote("a\$b`c\"d")) // all inert inside single quotes
    }

    @Test
    fun singleQuotingEscapesEmbeddedSingleQuotes() {
        // ' closes the quote, \' escapes one literal ', ' reopens: the POSIX idiom.
        assertEquals("'it'\\''s'", shellSingleQuote("it's"))
    }

    // --- windowsSystemTarCommand (what the windows extract actual feeds cmd.exe via system) -----------------------------

    @Test
    fun windowsTarIsInvokedByAbsoluteSystem32PathUnderOneOuterQuotePair() {
        // Absolute path so Git for Windows' GNU tar (PATH-shadowing bsdtar) is never picked;
        // the outer quote pair is what cmd /C strips from a line starting with a quote.
        assertEquals(
            "\"\"C:\\WINDOWS\\System32\\tar.exe\" -xf \"C:\\cache\\rt.zip\" -C \"C:\\cache\\.jdk21.extract.tmp\"\"",
            windowsSystemTarCommand("C:\\WINDOWS", "C:\\cache\\rt.zip", "C:\\cache\\.jdk21.extract.tmp"),
        )
    }

    @Test
    fun windowsTarFallsBackToCWindowsWhenSystemRootIsUnset() {
        assertEquals(
            "\"\"C:\\Windows\\System32\\tar.exe\" -xf \"a.zip\" -C \"dest\"\"",
            windowsSystemTarCommand(null, "a.zip", "dest"),
        )
        assertEquals(
            "\"\"C:\\Windows\\System32\\tar.exe\" -xf \"a.zip\" -C \"dest\"\"",
            windowsSystemTarCommand("  ", "a.zip", "dest"),
        )
    }

    // --- windowsCommandLine (what CreateProcessW receives; parsed back by the JVM launcher) -----------------------------

    @Test
    fun windowsQuotingWrapsEveryArgAndJoinsWithSpaces() {
        assertEquals(
            "\"C:\\rt\\bin\\java.exe\" \"-jar\" \"C:\\cache\\app.jar\" \"C:\\my project\"",
            windowsCommandLine(listOf("C:\\rt\\bin\\java.exe", "-jar", "C:\\cache\\app.jar", "C:\\my project")),
        )
    }

    @Test
    fun windowsQuotingEscapesQuotesAndDoublesBackslashesBeforeThem() {
        // A literal " becomes \" — and N backslashes right before it double to 2N (MSVCRT rule).
        assertEquals("\"say \\\"hi\\\"\"", windowsCommandLine(listOf("say \"hi\"")))
        assertEquals("\"back\\\\\\\" slash\"", windowsCommandLine(listOf("back\\\" slash")))
    }

    @Test
    fun windowsQuotingDoublesTrailingBackslashesSoTheClosingQuoteSurvives() {
        // C:\dir\ → "C:\dir\\" — otherwise the final backslash would escape the closing quote.
        assertEquals("\"C:\\dir\\\\\"", windowsCommandLine(listOf("C:\\dir\\")))
    }
}
