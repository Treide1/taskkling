package io.taskkling.core

/** This target is always macOS arm64 (Apple Silicon). */
public actual fun currentReleaseAssetName(): String = resolveAssetName(HostOs.MACOS, HostArch.ARM64)
