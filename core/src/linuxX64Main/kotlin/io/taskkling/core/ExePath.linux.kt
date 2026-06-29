package io.taskkling.core

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import platform.posix.readlink

/**
 * `/proc/self/exe` is a symlink to the running binary. `readlink` does not
 * null-terminate, so read exactly the byte count it reports and decode that —
 * no indexed null-write, no reliance on a terminator.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun currentExecutablePath(): String = memScoped {
    val size = 4096
    val buffer = allocArray<ByteVar>(size)
    val len = readlink("/proc/self/exe", buffer, (size - 1).convert())
    if (len < 0) error("could not resolve executable path (readlink /proc/self/exe)")
    buffer.readBytes(len.toInt()).decodeToString()
}
