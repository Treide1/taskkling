package io.taskkling.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.value
import platform.windows.CommandLineToArgvW
import platform.windows.GetCommandLineW
import platform.windows.LocalFree

/**
 * Split a Win32 command line the same way the shell that launched us did: via
 * `CommandLineToArgvW`, the exact routine `CreateProcessW`'s quoting rules are
 * designed around. `argv[0]` (the program name/path) is dropped, matching where
 * Kotlin/Native's own `args` (passed to `main`) already starts.
 *
 * This is pure wide-string parsing with no live-process coupling — [cmdLine] is just
 * a Kotlin `String` (already lossless UTF-16 internally) — so it's independently unit
 * tested (see `ArgvMingwTest`) against literal command-line strings, exercising the
 * exact same Win32 call [platformArgv] uses on the real process argv.
 *
 * Returns null on any Win32 failure so the caller can fall back to Kotlin/Native's own
 * (ANSI-lossy but never-null) `args`.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun parseWindowsCommandLine(cmdLine: String): List<String>? = memScoped {
    val argc = alloc<IntVar>()
    val argv = CommandLineToArgvW(cmdLine, argc.ptr) ?: return null
    try {
        val count = argc.value
        if (count <= 1) return emptyList()
        (1 until count).map { i -> (argv[i] ?: return null).toKStringFromUtf16() }
    } finally {
        LocalFree(argv)
    }
}

/**
 * mingwX64's `main(args)` is populated from the ANSI-decoded argv (Kotlin/Native's
 * runtime start-up goes through the narrow entry point, not the wide one) — any
 * character outside the process's active ANSI codepage arrives as `?`, identically
 * from PowerShell and Git Bash, since both already handed the OS a correct UTF-16
 * command line before that lossy decode ever runs (t-jagq). Recover it losslessly by
 * asking Windows for that wide command line directly ([GetCommandLineW]) and
 * re-splitting it ([parseWindowsCommandLine]) instead of trusting Kotlin/Native's own
 * decode of [fallback].
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun platformArgv(fallback: Array<String>): Array<String> {
    val cmdLine = GetCommandLineW()?.toKStringFromUtf16() ?: return fallback
    val parsed = parseWindowsCommandLine(cmdLine) ?: return fallback
    return parsed.toTypedArray()
}
