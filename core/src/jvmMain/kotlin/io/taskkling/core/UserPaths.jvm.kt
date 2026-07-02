package io.taskkling.core

/** Best-effort; the JVM target is test-only (the shipped binaries are native). Mirrors the linux XDG default. */
internal actual fun userConfigDir(): String {
    val xdg = System.getenv("XDG_CONFIG_HOME")
    if (!xdg.isNullOrEmpty()) return "$xdg/taskkling"
    val home = System.getenv("HOME") ?: System.getProperty("user.home")
    return "$home/.config/taskkling"
}

internal actual fun userCacheDir(): String {
    val xdg = System.getenv("XDG_CACHE_HOME")
    if (!xdg.isNullOrEmpty()) return "$xdg/taskkling"
    val home = System.getenv("HOME") ?: System.getProperty("user.home")
    return "$home/.cache/taskkling"
}
