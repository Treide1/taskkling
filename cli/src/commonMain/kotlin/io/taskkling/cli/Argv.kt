package io.taskkling.cli

/**
 * Recover the process argv exactly as the OS/shell delivered it, correcting for any
 * lossy decode Kotlin/Native's own runtime start-up performs on this target.
 *
 * On **mingwX64** (t-jagq): Kotlin/Native's `main(args)` is populated through the
 * ANSI-decoded argv (the narrow C entry point, not the wide one) — any character
 * outside the process's active ANSI codepage arrives as `?`, identically from
 * PowerShell and Git Bash, since both already handed the OS a correct UTF-16 command
 * line *before* that lossy decode runs. [Argv.mingw.kt] re-derives argv from
 * `GetCommandLineW` + `CommandLineToArgvW` and returns it losslessly.
 *
 * On linux/macOS, argv is already UTF-8 (glibc / Darwin) — Kotlin/Native's default
 * decode is lossless there, so [fallback] (Kotlin/Native's own `args`) passes through
 * unchanged.
 */
internal expect fun platformArgv(fallback: Array<String>): Array<String>
