package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.readlink

@OptIn(ExperimentalForeignApi::class)
internal actual fun currentExecutablePath(): String = memScoped {
    val size = 4096
    val buffer = allocArray<ByteVar>(size)
    val len = readlink("/proc/self/exe", buffer, (size - 1).convert())
    if (len < 0) error("could not resolve executable path (readlink /proc/self/exe)")
    buffer[len.toInt()] = 0.toByte() // readlink does not null-terminate
    buffer.toKString()
}
