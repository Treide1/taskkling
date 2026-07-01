package io.taskkling.core

/** This target is always Linux x64 — the only published Linux binary (linux-arm64 is NOT built). */
public actual fun currentReleaseAssetName(): String = resolveAssetName(HostOs.LINUX, HostArch.X64)
