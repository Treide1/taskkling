package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import platform.posix.chmod

/**
 * 0755 (rwxr-xr-x) via `chmod`; identical on linux + macOS, so one actual covers both.
 *
 * [UnsafeNumber] opt-in: `chmod`'s `mode_t` is commonized across this shared source
 * set but has a different bit width per platform (`UInt` on linux, `UInt16` on macOS),
 * so the commonizer marks it unsafe-to-share. The width is irrelevant here — the mode
 * is a small constant and [convert] widens/narrows it to whatever the target expects.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun markExecutable(path: String) {
    chmod(path, 0b111_101_101u.convert())
}
