package io.taskkling.core

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

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

/**
 * The default body of the user-level `config.toml`, materialized by
 * `config init` and by both installers (ADR-006). It carries only the
 * `update_check` toggle today: the check is on by default (ADR-006), so the
 * value shown is the OFF switch, present purely so a user can discover and
 * flip it without arcane per-OS path knowledge. Single-sourced here so the
 * shell installers never duplicate the string — they exec `config init` instead.
 */
public fun userConfigDefaultToml(): String =
    "update_check = true   # set false to disable the 'newer version available' check\n"

/** The outcome of `config init`: the user config's absolute path, and whether THIS call created it. */
public data class UserConfigInit(val path: Path, val created: Boolean)

/**
 * Materialize the user-level `config.toml` WRITE-IF-ABSENT (ADR-006): if it
 * already exists it is left byte-for-byte untouched (a user who edited it is
 * never clobbered — deleting the file is the only reset), otherwise its parent
 * directory is created and [userConfigDefaultToml] written. Returns the path
 * either way, with [UserConfigInit.created] telling the two cases apart.
 */
public fun materializeUserConfig(fs: FileSystem = FileSystem.SYSTEM): UserConfigInit {
    val path = userConfigFilePath()
    if (fs.exists(path)) return UserConfigInit(path, created = false)
    fs.createDirectories(userConfigDirPath())
    fs.write(path) { writeUtf8(userConfigDefaultToml()) }
    return UserConfigInit(path, created = true)
}
