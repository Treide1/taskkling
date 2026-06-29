package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle

/**
 * For a command-line tool the main bundle's `executablePath` is the running
 * executable. Shared by macosArm64 + macosX64 (compile/link-checked on CI only).
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun currentExecutablePath(): String =
    NSBundle.mainBundle.executablePath
        ?: error("could not resolve executable path (NSBundle.mainBundle.executablePath was null)")
