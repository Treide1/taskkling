package io.taskkling.core

import java.io.File

/** Best-effort; the JVM target is test-only (the shipped binaries are native). */
internal actual fun renameSelfToOld(exePath: String): String {
    val old = File("$exePath.old")
    old.delete()
    val src = File(exePath)
    return if (src.exists() && src.renameTo(old)) old.path else exePath
}

/**
 * Best-effort host detection for the JVM target (test/dev-only; never
 * shipped — unlike the native targets, a JVM build isn't fixed to one host).
 */
public actual fun currentReleaseAssetName(): String {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val archName = System.getProperty("os.arch").orEmpty().lowercase()
    val os = when {
        osName.contains("win") -> HostOs.WINDOWS
        osName.contains("mac") -> HostOs.MACOS
        else -> HostOs.LINUX
    }
    val arch = when {
        archName.contains("aarch64") || archName.contains("arm64") -> HostArch.ARM64
        else -> HostArch.X64
    }
    return resolveAssetName(os, arch)
}
