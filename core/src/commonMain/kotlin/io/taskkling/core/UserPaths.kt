package io.taskkling.core

import okio.Path
import okio.Path.Companion.toPath

/**
 * The user-level config + cache home (ADR-005) — where the opt-in
 * `update_check` flag and its ~24h cache live so they're honoured for the
 * GLOBAL binary, run from outside any workspace, mirroring the
 * [globalInstallDir] / [currentExecutablePath] expect/actual OS-split:
 *
 *  - **Windows**: `%LOCALAPPDATA%\taskkling` (config), `%LOCALAPPDATA%\taskkling\cache` (cache).
 *  - **macOS**: `XDG_CONFIG_HOME`/`XDG_CACHE_HOME`, when set, else
 *    `~/Library/Application Support/taskkling` (config) / `~/Library/Caches/taskkling` (cache).
 *  - **linux**: `XDG_CONFIG_HOME`/`XDG_CACHE_HOME`, when set, else `~/.config/taskkling` (config) /
 *    `~/.cache/taskkling` (cache), per the XDG Base Directory spec.
 *
 * A workspace's `.taskkling/config.toml` OVERRIDES this user-level one
 * ([resolveUpdateCheckEnabled]) — this is purely the fallback home for when a
 * command runs outside any workspace, or a workspace doesn't set the key.
 */
internal expect fun userConfigDir(): String

/** The ~24h `update_check` cache home — a sibling of [userConfigDir] (ADR-005). */
internal expect fun userCacheDir(): String

/** Public accessor for [userConfigDir] (kept `internal`, mirroring [runningExecutablePath]'s split). */
public fun userConfigDirPath(): Path = userConfigDir().toPath()

/** Public accessor for [userCacheDir]. */
public fun userCacheDirPath(): Path = userCacheDir().toPath()

/** The user-level `config.toml` — same shape/parser as a workspace's ([Config.load]); only `update_check` matters here today. */
public fun userConfigFilePath(): Path = userConfigDirPath() / "config.toml"

/** The opt-in check's ~24h cache file (ADR-005): last-checked timestamp + last-known latest version. */
public fun userUpdateCheckCacheFilePath(): Path = userCacheDirPath() / "update-check.toml"
