package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKStringFromUtf16
import platform.windows.GetModuleFileNameW

@OptIn(ExperimentalForeignApi::class)
internal actual fun currentExecutablePath(): String = memScoped {
    val size = 32768 // wide chars; comfortably covers long (\\?\-prefixed) paths
    val buffer = allocArray<UShortVar>(size)
    val len = GetModuleFileNameW(null, buffer, size.toUInt())
    if (len == 0u) error("could not resolve executable path (GetModuleFileNameW)")
    buffer.toKStringFromUtf16()
}

/** Windows has no Unix executable bit — copying a `.exe` is enough. */
internal actual fun markExecutable(path: String) {
    // no-op
}
