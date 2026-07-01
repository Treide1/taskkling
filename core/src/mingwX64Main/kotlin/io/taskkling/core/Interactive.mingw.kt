package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix._fileno
import platform.posix._isatty
import platform.posix.stdout

/** `_isatty(_fileno(stdout))` — the MSVCRT spelling of the POSIX check; non-zero when the console handle is a character device. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun stdoutIsInteractive(): Boolean = _isatty(_fileno(stdout)) != 0
