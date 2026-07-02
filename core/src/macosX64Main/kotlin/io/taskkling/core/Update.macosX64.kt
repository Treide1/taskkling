package io.taskkling.core

/** This target is always macOS x64 (Intel). */
public actual fun currentReleaseAssetName(): String = resolveAssetName(HostOs.MACOS, HostArch.X64)
