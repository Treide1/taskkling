package io.taskkling.cli

/**
 * linux + macOS deliver argv as UTF-8 already (glibc / Darwin) — Kotlin/Native's
 * default decode is lossless there, so no correction is needed (t-jagq; mingw's ANSI
 * decode is the only lossy leg — see `Argv.mingw.kt`).
 */
internal actual fun platformArgv(fallback: Array<String>): Array<String> = fallback
