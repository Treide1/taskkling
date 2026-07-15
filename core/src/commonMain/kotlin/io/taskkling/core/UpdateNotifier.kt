package io.taskkling.core

import okio.FileSystem
import okio.SYSTEM

/**
 * The `update_check` notifier (ADR-002 §3, narrowed by ADR-005, default
 * reversed by ADR-006): a best-effort, ~24h-cached, silent-on-failure "a newer
 * version is available" line, honoured from BOTH the per-workspace
 * `.taskkling/config.toml` and a user-level config (ADR-005's config/cache
 * home, [userConfigDirPath] / [userCacheDirPath]) — workspace overrides user.
 * The flag defaults ON (ADR-006, opt-OUT), but the passive `--version` surface
 * is additionally TTY-gated in `:cli` so automation stays offline. This file
 * holds the PURE, testable pieces
 * (precedence, cache-freshness, and notifier-gating decisions) plus the thin
 * IO wrapping them; the actual GitHub lookup is `Update.kt`'s existing
 * [parseLatestTagName] / [isNewerVersion] — reused here, not reimplemented.
 * `:cli` performs the network call itself and calls into this file for every
 * decision around it (precedence, cache, wording).
 */

// --- Config precedence (workspace overrides user; default false when neither sets it) ---------------------------

/**
 * Whether the update-check notifier should even attempt a check: [workspaceConfig]'s
 * `update_check`, if the key is set there, wins outright; otherwise fall back
 * to [userConfig]'s; if neither config sets the key, the default is `true`
 * (ADR-006 reversed ADR-002/005's default-off — the check is now on unless a
 * config turns it off, opt-OUT rather than opt-in). Note this only decides
 * whether the check is *permitted*; the passive `--version` surface is
 * additionally gated to an interactive TTY ([stdoutIsInteractive]) so CI /
 * pipes / scripts stay offline even with the default on (ADR-006).
 */
public fun resolveUpdateCheckEnabled(userConfig: Config?, workspaceConfig: Config?): Boolean =
    workspaceConfig?.updateCheck ?: userConfig?.updateCheck ?: true

// --- Cache freshness (fresh / stale / missing) --------------------------------------------------------------------

/** The opt-in check's cache entry (ADR-005): when it last ran and what it last found. */
public data class UpdateCheckCache(val lastCheckedEpochSeconds: Long, val latestVersion: String)

/** ~24h, per ADR-002/005. */
public const val UPDATE_CHECK_TTL_SECONDS: Long = 24L * 60 * 60

/**
 * Is [cache] fresh enough to reuse without a network call? Missing (`null`)
 * is never fresh. A negative age (clock skew or a corrupted timestamp) is
 * also treated as stale rather than trusted blindly — worst case that costs
 * one extra, harmless re-query; it never wedges the check permanently.
 */
public fun isUpdateCheckCacheFresh(
    cache: UpdateCheckCache?,
    nowEpochSeconds: Long,
    ttlSeconds: Long = UPDATE_CHECK_TTL_SECONDS,
): Boolean {
    if (cache == null) return false
    val age = nowEpochSeconds - cache.lastCheckedEpochSeconds
    return age in 0 until ttlSeconds
}

// --- Cache (de)serialization — the same flat `key = value` shape as config.toml ([Config.load]) ------------------

/** Parse the cache file's text. Anything unparseable (missing keys, garbage) yields `null` — silent-fail, never throws. */
public fun parseUpdateCheckCache(text: String): UpdateCheckCache? {
    var lastChecked: Long? = null
    var latest: String? = null
    for (raw in text.lines()) {
        val line = raw.substringBefore('#').trim()
        if (line.isEmpty() || '=' !in line) continue
        val key = line.substringBefore('=').trim()
        val value = line.substringAfter('=').trim().trim('"')
        when (key) {
            "last_checked" -> lastChecked = value.toLongOrNull()
            "latest_version" -> latest = value.ifEmpty { null }
        }
    }
    val checked = lastChecked ?: return null
    val version = latest ?: return null
    return UpdateCheckCache(checked, version)
}

/** Render [cache] back to the flat `key = value` text [parseUpdateCheckCache] reads. */
public fun encodeUpdateCheckCache(cache: UpdateCheckCache): String =
    "last_checked   = ${cache.lastCheckedEpochSeconds}\n" +
        "latest_version = \"${cache.latestVersion}\"\n"

// --- Notifier gating (flag off -> never; flag on + newer -> the line; flag on + up-to-date -> nothing) ------------

/**
 * The exact ADR-002 wording (`vX.Y.Z available — run 'taskkling update'`), or
 * `null` when nothing should print: the flag is off, no latest version is
 * known (a missing or failed check), or the known latest isn't actually
 * newer than [currentVersion]. Pure — `:cli` holds all the IO/network that
 * feeds [latestKnownVersion] and prints this result.
 */
public fun updateNotifierLine(updateCheckEnabled: Boolean, currentVersion: String, latestKnownVersion: String?): String? {
    if (!updateCheckEnabled) return null
    if (latestKnownVersion == null) return null
    if (!isNewerVersion(currentVersion, latestKnownVersion)) return null
    return "${normalizeVersionTag(latestKnownVersion)} available — run 'taskkling update'"
}

// --- Thin IO around the above (best-effort; :cli calls these directly) --------------------------------------------

/** The user-level config (ADR-005) — the same parser as the workspace one; [Config.DEFAULT] (all-unset) if absent. */
public fun loadUserConfig(fs: FileSystem = FileSystem.SYSTEM): Config = Config.load(fs, userConfigFilePath())

/** The cached ~24h check result, or `null` if absent/unreadable/corrupt — never throws (silent-fail, ADR-002). */
public fun loadUpdateCheckCache(fs: FileSystem = FileSystem.SYSTEM): UpdateCheckCache? =
    try {
        val path = userUpdateCheckCacheFilePath()
        if (!fs.exists(path)) null else parseUpdateCheckCache(fs.read(path) { readUtf8() })
    } catch (_: Exception) {
        null
    }

/** Persist a fresh check result; best-effort — a write failure (e.g. a read-only filesystem) never surfaces (ADR-002). */
public fun saveUpdateCheckCache(cache: UpdateCheckCache, fs: FileSystem = FileSystem.SYSTEM) {
    try {
        fs.createDirectories(userCacheDirPath())
        fs.write(userUpdateCheckCacheFilePath()) { writeUtf8(encodeUpdateCheckCache(cache)) }
    } catch (_: Exception) {
        // best-effort, silent-fail (ADR-002/005)
    }
}
