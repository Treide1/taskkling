package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * ADR-005: the user-level config/cache home on macOS. `XDG_CONFIG_HOME` /
 * `XDG_CACHE_HOME`, when set (common on Homebrew-managed setups), win over
 * the platform-native `~/Library/Application Support` / `~/Library/Caches`.
 * Shared by macosArm64 + macosX64 (compile/link-checked on CI only), mirroring
 * [ExePath.macos.kt]'s split.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun userConfigDir(): String {
    getenv("XDG_CONFIG_HOME")?.toKString()?.takeIf { it.isNotEmpty() }?.let { return "$it/taskkling" }
    val home = getenv("HOME")?.toKString() ?: error("HOME is not set")
    return "$home/Library/Application Support/taskkling"
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun userCacheDir(): String {
    getenv("XDG_CACHE_HOME")?.toKString()?.takeIf { it.isNotEmpty() }?.let { return "$it/taskkling" }
    val home = getenv("HOME")?.toKString() ?: error("HOME is not set")
    return "$home/Library/Caches/taskkling"
}
