package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/** ADR-005: the user-level config/cache home on Windows, under `%LOCALAPPDATA%\taskkling`. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun userConfigDir(): String {
    val localAppData = getenv("LOCALAPPDATA")?.toKString() ?: error("LOCALAPPDATA is not set")
    return "$localAppData\\taskkling"
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun userCacheDir(): String {
    val localAppData = getenv("LOCALAPPDATA")?.toKString() ?: error("LOCALAPPDATA is not set")
    return "$localAppData\\taskkling\\cache"
}
