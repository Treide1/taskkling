package io.taskkling.core

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure `taskkling ui` planning layer (ADR-010/011): asset naming, cache
 * layout, prune plan, launch/self-heal decisions, and the headless refusal —
 * mirrors [UpdateTest]/[UninstallTest]'s split (the verb's downloads,
 * extraction, and spawning are impure and QA-gated, not unit-tested).
 */
class UiAssetsTest {

    private val cacheHome = "/home/me/.cache/taskkling".toPath()

    // --- asset naming (ADR-011 decision 1) ---------------------------------------------------------------------------

    @Test
    fun uiJarAssetNamesReuseTheCliTargetVocabulary() {
        assertEquals("taskkling-ui-linux-x64.jar", uiJarAssetName(HostOs.LINUX, HostArch.X64))
        assertEquals("taskkling-ui-macos-arm64.jar", uiJarAssetName(HostOs.MACOS, HostArch.ARM64))
        assertEquals("taskkling-ui-macos-x64.jar", uiJarAssetName(HostOs.MACOS, HostArch.X64))
        assertEquals("taskkling-ui-windows-x64.jar", uiJarAssetName(HostOs.WINDOWS, HostArch.X64))
    }

    @Test
    fun uiRuntimeAssetNamesCarryTheJdkMajorAndPerOsArchiveFormat() {
        // tar.gz everywhere except windows (.zip) — ADR-011's exact filenames; the JDK
        // major is in the name because the cache is keyed by it (the name IS the key).
        assertEquals("taskkling-ui-runtime-jdk21-linux-x64.tar.gz", uiRuntimeAssetName(HostOs.LINUX, HostArch.X64))
        assertEquals("taskkling-ui-runtime-jdk21-macos-arm64.tar.gz", uiRuntimeAssetName(HostOs.MACOS, HostArch.ARM64))
        assertEquals("taskkling-ui-runtime-jdk21-macos-x64.tar.gz", uiRuntimeAssetName(HostOs.MACOS, HostArch.X64))
        assertEquals("taskkling-ui-runtime-jdk21-windows-x64.zip", uiRuntimeAssetName(HostOs.WINDOWS, HostArch.X64))
    }

    @Test
    fun uiAssetNamesRejectUnpublishedTargets() {
        // linux-arm64 is not published (same exhaustive check the CLI's own asset naming enforces).
        assertFailsWith<IllegalArgumentException> { uiJarAssetName(HostOs.LINUX, HostArch.ARM64) }
        assertFailsWith<IllegalArgumentException> { uiRuntimeAssetName(HostOs.WINDOWS, HostArch.ARM64) }
    }

    @Test
    fun uiAssetUrlsComposeWithTheExistingReleaseUrlBuilder() {
        // Pinning (ADR-010): the verb fetches from the CLI's OWN tag — same URL builder `update` uses.
        assertEquals(
            "https://github.com/Treide1/taskkling/releases/download/v0.6.0/taskkling-ui-linux-x64.jar",
            "${releaseDownloadBaseUrl("0.6.0")}/${uiJarAssetName(HostOs.LINUX, HostArch.X64)}",
        )
    }

    // --- cache layout (ADR-010 decision 3) ---------------------------------------------------------------------------

    @Test
    fun appCacheIsVersionKeyedWithoutTheTagsVPrefix() {
        assertEquals(cacheHome / "ui" / "app" / "0.6.0", uiAppDir(cacheHome, "0.6.0"))
        // Tag-form input normalizes to the same key — one directory per version, however spelled.
        assertEquals(uiAppDir(cacheHome, "0.6.0"), uiAppDir(cacheHome, "v0.6.0"))
    }

    @Test
    fun runtimeCacheIsKeyedByJdkMajorSoItSurvivesAppOnlyUpdates() {
        assertEquals(cacheHome / "ui" / "runtime" / "jdk21", uiRuntimeDir(cacheHome))
        assertEquals(cacheHome / "ui" / "runtime" / "jdk25", uiRuntimeDir(cacheHome, jdkMajor = 25))
    }

    @Test
    fun jarPathLivesInTheVersionDirUnderItsAssetName() {
        assertEquals(
            cacheHome / "ui" / "app" / "0.6.0" / "taskkling-ui-windows-x64.jar",
            uiJarPath(cacheHome, "0.6.0", HostOs.WINDOWS, HostArch.X64),
        )
    }

    @Test
    fun javaLauncherIsResolvedByAbsolutePathPerOs() {
        val runtime = uiRuntimeDir(cacheHome)
        assertEquals(runtime / "bin" / "java", uiJavaLauncherPath(runtime, HostOs.LINUX))
        assertEquals(runtime / "bin" / "java.exe", uiJavaLauncherPath(runtime, HostOs.WINDOWS))
    }

    @Test
    fun fetchAndExtractTempsAreDotPrefixedSiblingsOfTheirFinalPaths() {
        // Same-directory temps keep the final rename on one filesystem (mirrors installNewExecutable).
        val jar = uiJarPath(cacheHome, "0.6.0", HostOs.LINUX, HostArch.X64)
        assertEquals(jar.parent!! / ".taskkling-ui-linux-x64.jar.fetch.tmp", uiFetchTempPath(jar))
        val runtime = uiRuntimeDir(cacheHome)
        assertEquals(runtime.parent!! / ".jdk21.extract.tmp", uiExtractTempDir(runtime))
    }

    // --- prune plan (ADR-010 decision 3, best-effort semantics per ADR-011 decision 2) --------------------------------

    @Test
    fun pruneDeletesEveryOtherAppVersionAndOrphanedRuntime() {
        val appRoot = uiAppCacheRoot(cacheHome)
        val runtimeRoot = uiRuntimeCacheRoot(cacheHome)
        val plan = planUiCachePrune(
            currentVersion = "0.6.1",
            appDirListing = listOf(appRoot / "0.6.0", appRoot / "0.6.1", appRoot / ".0.6.1.fetch-leftover.tmp"),
            runtimeDirListing = listOf(runtimeRoot / "jdk21", runtimeRoot / "jdk17"),
        )
        // Stale version, stray temp, orphaned runtime — everything but the current keys.
        assertEquals(
            listOf(appRoot / "0.6.0", appRoot / ".0.6.1.fetch-leftover.tmp", runtimeRoot / "jdk17"),
            plan,
        )
    }

    @Test
    fun pruneKeepsExactlyTheCurrentVersionAndRuntimeEvenForTagFormInput() {
        val appRoot = uiAppCacheRoot(cacheHome)
        val plan = planUiCachePrune(
            currentVersion = "v0.6.0", // tag form must match the un-prefixed cache key
            appDirListing = listOf(appRoot / "0.6.0"),
            runtimeDirListing = listOf(uiRuntimeCacheRoot(cacheHome) / "jdk21"),
        )
        assertEquals(emptyList(), plan)
    }

    @Test
    fun pruneOfAnEmptyCacheIsAnEmptyPlan() {
        assertEquals(emptyList(), planUiCachePrune("0.6.0", emptyList(), emptyList()))
    }

    // --- launch / self-heal state machine (ADR-010 decision 6) --------------------------------------------------------

    @Test
    fun completeCacheLaunchesDirectly() {
        assertEquals(UiRunPlan.Launch, planUiRun(jarPresent = true, runtimePresent = true))
    }

    @Test
    fun anyMissingArtifactFetchesFirst() {
        // First run, post-update (new version key), and post-JDK-bump all land here.
        assertEquals(UiRunPlan.FetchThenLaunch, planUiRun(jarPresent = false, runtimePresent = false))
        assertEquals(UiRunPlan.FetchThenLaunch, planUiRun(jarPresent = true, runtimePresent = false))
        assertEquals(UiRunPlan.FetchThenLaunch, planUiRun(jarPresent = false, runtimePresent = true))
    }

    @Test
    fun corruptLaunchSelfHealsExactlyOnce() {
        assertEquals(UiRunPlan.RefetchOnce, planAfterLaunchFailure(alreadyRefetchedThisRun = false))
        assertEquals(
            UiRunPlan.Fail(UiFailureCause.CORRUPT_AFTER_REFETCH),
            planAfterLaunchFailure(alreadyRefetchedThisRun = true),
        )
    }

    @Test
    fun everyFailureMessageNamesItsCauseActionably() {
        // ADR-010: ONE actionable error naming the cause; offline points at --fetch-only as the prefetch.
        assertTrue("offline" in uiFailureMessage(UiFailureCause.OFFLINE))
        assertTrue("--fetch-only" in uiFailureMessage(UiFailureCause.OFFLINE))
        assertTrue("SHA256" in uiFailureMessage(UiFailureCause.CHECKSUM_MISMATCH))
        assertTrue("github.com" in uiFailureMessage(UiFailureCause.GITHUB_UNREACHABLE))
        assertTrue("corrupt" in uiFailureMessage(UiFailureCause.CORRUPT_AFTER_REFETCH))
    }

    @Test
    fun failureMessageAppendsTheLogPathWhenOneExists() {
        val log = uiLogFilePath(cacheHome)
        assertTrue(log.toString() in uiFailureMessage(UiFailureCause.CORRUPT_AFTER_REFETCH, log))
        assertTrue("Log:" !in uiFailureMessage(UiFailureCause.OFFLINE, logPath = null))
    }

    // --- headless decision (ADR-010 decision 6: refuse BEFORE spawning java) ------------------------------------------

    @Test
    fun linuxWithNoDisplayAtAllIsRefusedNamingFetchOnly() {
        val msg = headlessRefusalMessage(HostOs.LINUX, env = emptyMap())
        assertNotNull(msg)
        assertTrue("--fetch-only" in msg)
    }

    @Test
    fun linuxSshWithoutForwardingIsRefusedByTheDisplayRuleAlone() {
        // Plain SSH sets SSH_CONNECTION but no DISPLAY — the display rule already covers it.
        val msg = headlessRefusalMessage(
            HostOs.LINUX,
            env = mapOf("SSH_CONNECTION" to "10.0.0.1 22 10.0.0.2 22", "SSH_TTY" to "/dev/pts/0"),
        )
        assertNotNull(msg)
    }

    @Test
    fun linuxWithX11WaylandOrForwardedDisplayProceeds() {
        assertNull(headlessRefusalMessage(HostOs.LINUX, mapOf("DISPLAY" to ":0")))
        assertNull(headlessRefusalMessage(HostOs.LINUX, mapOf("WAYLAND_DISPLAY" to "wayland-1")))
        // SSH -X forwarding sets DISPLAY — must proceed even though SSH_* are present.
        assertNull(
            headlessRefusalMessage(
                HostOs.LINUX,
                mapOf("DISPLAY" to "localhost:10.0", "SSH_CONNECTION" to "10.0.0.1 22 10.0.0.2 22"),
            ),
        )
    }

    @Test
    fun blankDisplayValuesCountAsUnset() {
        assertNotNull(headlessRefusalMessage(HostOs.LINUX, mapOf("DISPLAY" to "", "WAYLAND_DISPLAY" to " ")))
    }

    @Test
    fun macosAndWindowsAlwaysProceedEvenOverSsh() {
        // No reliable env marker there (an SSH session into a logged-in mac can reach the GUI);
        // blocking legitimate launches is the worse failure mode — a true headless spawn fails into the log.
        val sshEnv = mapOf("SSH_CONNECTION" to "10.0.0.1 22 10.0.0.2 22")
        assertNull(headlessRefusalMessage(HostOs.MACOS, sshEnv))
        assertNull(headlessRefusalMessage(HostOs.WINDOWS, sshEnv))
    }
}
