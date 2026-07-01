package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/** ADR-005: the user-level config/cache home on linux, per the XDG Base Directory spec. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun userConfigDir(): String {
    getenv("XDG_CONFIG_HOME")?.toKString()?.takeIf { it.isNotEmpty() }?.let { return "$it/taskkling" }
    val home = getenv("HOME")?.toKString() ?: error("HOME is not set")
    return "$home/.config/taskkling"
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun userCacheDir(): String {
    getenv("XDG_CACHE_HOME")?.toKString()?.takeIf { it.isNotEmpty() }?.let { return "$it/taskkling" }
    val home = getenv("HOME")?.toKString() ?: error("HOME is not set")
    return "$home/.cache/taskkling"
}
