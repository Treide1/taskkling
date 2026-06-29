package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import platform.posix.chmod

/** 0755 (rwxr-xr-x) via `chmod`; identical on linux + macOS, so one actual covers both. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun markExecutable(path: String) {
    chmod(path, 0b111_101_101u.convert())
}
