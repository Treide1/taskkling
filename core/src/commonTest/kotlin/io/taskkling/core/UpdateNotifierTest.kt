package io.taskkling.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure opt-in `update_check` notifier logic (ADR-002 §3 / ADR-005): config
 * precedence (workspace overrides user), cache-freshness (~24h TTL), and the
 * notifier-gating decision. The user/workspace config/cache HOME resolution
 * (`expect`/`actual` per OS) and the actual GitHub network call are platform
 * primitives / impure IO — QA-gated, not unit-tested here (mirrors
 * [UpdateTest]'s split for the self-replace primitive).
 */
class UpdateNotifierTest {

    // --- resolveUpdateCheckEnabled: precedence -------------------------------------------------------------------

    @Test
    fun defaultsToFalseWhenNeitherConfigSetsIt() {
        assertFalse(resolveUpdateCheckEnabled(userConfig = null, workspaceConfig = null))
        assertFalse(resolveUpdateCheckEnabled(userConfig = Config.DEFAULT, workspaceConfig = Config.DEFAULT))
    }

    @Test
    fun userConfigEnablesItOutsideAnyWorkspace() {
        assertTrue(resolveUpdateCheckEnabled(userConfig = Config(updateCheck = true), workspaceConfig = null))
    }

    @Test
    fun workspaceConfigOverridesUserConfigWhenBothSetIt() {
        // Workspace says off, user says on -> workspace wins (off).
        assertFalse(
            resolveUpdateCheckEnabled(
                userConfig = Config(updateCheck = true),
                workspaceConfig = Config(updateCheck = false),
            ),
        )
        // Workspace says on, user says off -> workspace wins (on).
        assertTrue(
            resolveUpdateCheckEnabled(
                userConfig = Config(updateCheck = false),
                workspaceConfig = Config(updateCheck = true),
            ),
        )
    }

    @Test
    fun unsetWorkspaceKeyFallsThroughToUserConfig() {
        // Workspace config.toml exists but doesn't mention update_check (null, not false) -> user value applies.
        assertTrue(
            resolveUpdateCheckEnabled(
                userConfig = Config(updateCheck = true),
                workspaceConfig = Config(updateCheck = null),
            ),
        )
    }

    // --- isUpdateCheckCacheFresh: fresh / stale / missing --------------------------------------------------------

    @Test
    fun missingCacheIsNeverFresh() {
        assertFalse(isUpdateCheckCacheFresh(cache = null, nowEpochSeconds = 1_000_000))
    }

    @Test
    fun freshWithinTheTtlWindow() {
        val cache = UpdateCheckCache(lastCheckedEpochSeconds = 1_000_000, latestVersion = "v0.3.0")
        assertTrue(isUpdateCheckCacheFresh(cache, nowEpochSeconds = 1_000_000)) // just checked
        assertTrue(isUpdateCheckCacheFresh(cache, nowEpochSeconds = 1_000_000 + UPDATE_CHECK_TTL_SECONDS - 1))
    }

    @Test
    fun staleAtOrPastTheTtlWindow() {
        val cache = UpdateCheckCache(lastCheckedEpochSeconds = 1_000_000, latestVersion = "v0.3.0")
        assertFalse(isUpdateCheckCacheFresh(cache, nowEpochSeconds = 1_000_000 + UPDATE_CHECK_TTL_SECONDS))
        assertFalse(isUpdateCheckCacheFresh(cache, nowEpochSeconds = 1_000_000 + UPDATE_CHECK_TTL_SECONDS + 100))
    }

    @Test
    fun negativeAgeIsTreatedAsStale() {
        // Clock skew / corrupted timestamp: a "checked in the future" entry must not be trusted blindly.
        val cache = UpdateCheckCache(lastCheckedEpochSeconds = 1_000_000, latestVersion = "v0.3.0")
        assertFalse(isUpdateCheckCacheFresh(cache, nowEpochSeconds = 999_999))
    }

    // --- update-check cache (de)serialization --------------------------------------------------------------------

    @Test
    fun cacheRoundTripsThroughItsTextForm() {
        val cache = UpdateCheckCache(lastCheckedEpochSeconds = 1_751_328_000, latestVersion = "v0.3.0")
        val parsed = parseUpdateCheckCache(encodeUpdateCheckCache(cache))
        assertEquals(cache, parsed)
    }

    @Test
    fun cacheParseIsNullOnMissingOrGarbageFields() {
        assertNull(parseUpdateCheckCache(""))
        assertNull(parseUpdateCheckCache("last_checked = 123\n")) // no latest_version
        assertNull(parseUpdateCheckCache("latest_version = \"v0.3.0\"\n")) // no last_checked
        assertNull(parseUpdateCheckCache("last_checked = not-a-number\nlatest_version = \"v0.3.0\"\n"))
    }

    // --- updateNotifierLine: gating -------------------------------------------------------------------------------

    @Test
    fun flagOffNeverPrintsEvenWithANewerVersionKnown() {
        assertNull(updateNotifierLine(updateCheckEnabled = false, currentVersion = "0.2.2", latestKnownVersion = "v0.3.0"))
    }

    @Test
    fun flagOnAndNewerVersionPrintsTheAdrWording() {
        assertEquals(
            "v0.3.0 available — run 'taskkling update'",
            updateNotifierLine(updateCheckEnabled = true, currentVersion = "0.2.2", latestKnownVersion = "v0.3.0"),
        )
        // Bare (no "v" prefix) latest is normalized in the printed line too.
        assertEquals(
            "v0.3.0 available — run 'taskkling update'",
            updateNotifierLine(updateCheckEnabled = true, currentVersion = "0.2.2", latestKnownVersion = "0.3.0"),
        )
    }

    @Test
    fun flagOnAndUpToDatePrintsNothing() {
        assertNull(updateNotifierLine(updateCheckEnabled = true, currentVersion = "0.2.2", latestKnownVersion = "v0.2.2"))
        assertNull(updateNotifierLine(updateCheckEnabled = true, currentVersion = "0.3.0", latestKnownVersion = "v0.2.2"))
    }

    @Test
    fun flagOnAndNoKnownLatestPrintsNothing() {
        // A missing/failed check (silent-fail) must never fabricate a notification.
        assertNull(updateNotifierLine(updateCheckEnabled = true, currentVersion = "0.2.2", latestKnownVersion = null))
    }
}
