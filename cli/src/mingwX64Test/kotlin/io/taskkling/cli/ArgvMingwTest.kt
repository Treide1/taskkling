package io.taskkling.cli

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * mingw-only regression coverage for the argv fix (t-jagq): exercises the real
 * `CommandLineToArgvW`-based decode ([parseWindowsCommandLine], `Argv.mingw.kt`)
 * against literal command-line strings — no live process/shell involved, so it's
 * independent of whatever locale/codepage happens to be active in CI. Kotlin strings
 * are UTF-16 internally, so embedding umlauts/CJK/emoji directly in the test source is
 * itself lossless; only `CommandLineToArgvW`'s own parsing is under test — the exact
 * call [platformArgv] makes against the real process argv.
 */
class ArgvMingwTest {
    @Test
    fun splitsAndPreservesNonAsciiArguments() {
        val cmd = "taskkling.exe add \"Grüße Prüfung äöüß €\" --thread \"日本語\""
        val args = parseWindowsCommandLine(cmd)
        assertEquals(
            listOf("add", "Grüße Prüfung äöüß €", "--thread", "日本語"),
            args,
        )
    }

    @Test
    fun dropsTheProgramNameArgvZero() {
        val args = parseWindowsCommandLine("\"C:\\Program Files\\taskkling\\taskkling.exe\" --version")
        assertEquals(listOf("--version"), args)
    }

    @Test
    fun noArgumentsYieldsEmptyList() {
        val args = parseWindowsCommandLine("taskkling.exe")
        assertEquals(emptyList(), args)
    }

    @Test
    fun quotedArgumentWithSpacesStaysOneToken() {
        val args = parseWindowsCommandLine("taskkling.exe add \"a title with spaces\"")
        assertEquals(listOf("add", "a title with spaces"), args)
    }
}
