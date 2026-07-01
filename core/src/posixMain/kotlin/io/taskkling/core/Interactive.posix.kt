package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.STDOUT_FILENO
import platform.posix.isatty

/** `isatty(STDOUT_FILENO)` — 1 when stdout is a terminal. Identical on linux + macOS, so one actual covers both. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun stdoutIsInteractive(): Boolean = isatty(STDOUT_FILENO) == 1
