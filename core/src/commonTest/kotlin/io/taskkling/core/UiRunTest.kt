package io.taskkling.core

import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `ui`'s orchestration ([runUiVerb]): the fetch/verify/install path, the
 * headless refusal's placement, the one-shot self-heal, and the post-launch
 * prune (ADR-009 / ADR-010 / ADR-011). These are the scenarios release QA
 * cannot stage by hand — a corrupt cache, a tampered download, a spawn that
 * fails twice.
 */
class UiRunTest {
    private val version = "0.6.3"
    private val os = HostOs.LINUX
    private val arch = HostArch.X64
    private val cacheHome = "/cache".toPath()
    private val projectRoot = "/proj".toPath()

    private val jarAsset = uiJarAssetName(os, arch)
    private val runtimeAsset = uiRuntimeAssetName(os, arch)
    private val jarBytes = "UBERJAR".encodeToByteArray()
    private val runtimeBytes = "RUNTIME-ARCHIVE".encodeToByteArray()

    private val jarPath = uiJarPath(cacheHome, version, os, arch)
    private val runtimeDir = uiRuntimeDir(cacheHome)
    private val launcher = uiJavaLauncherPath(runtimeDir, os)
    private val logPath = uiLogFilePath(cacheHome)

    /** A graphical linux session — [headlessRefusalMessage] proceeds. */
    private val desktopEnv = mapOf("DISPLAY" to ":0")

    private class Spawns {
        val argv: MutableList<List<String>> = mutableListOf()
    }

    private fun assetsNet(vararg overrides: Pair<String, Pair<Int, ByteArray>>): FakeNet {
        val base = releaseDownloadBaseUrl(version)
        return FakeNet(
            texts = mapOf("$base/SHA256SUMS" to (200 to sha256SumsFor(jarAsset to jarBytes, runtimeAsset to runtimeBytes))),
            bytes = mapOf(
                "$base/$jarAsset" to (200 to jarBytes),
                "$base/$runtimeAsset" to (200 to runtimeBytes),
            ) + overrides.toMap(),
        )
    }

    /**
     * A tar that lays the image out the way the release archives do (bin/ at
     * top level); [wrapped] nests it one directory deeper, the layout the
     * fetch tolerates defensively.
     */
    private fun fakeExtract(fs: FakeFileSystem, wrapped: Boolean = false): (Path, Path) -> Boolean = { _, destDir ->
        val imageRoot = if (wrapped) destDir / "jdk-21" else destDir
        fs.createDirectories(imageRoot / "bin")
        fs.write(imageRoot / "bin" / "java") { writeUtf8("#!/bin/sh") }
        fs.createDirectories(imageRoot / "lib")
        fs.write(imageRoot / "lib" / "jvm.cfg") { writeUtf8("-server KNOWN") }
        true
    }

    private fun effects(
        fs: FakeFileSystem,
        net: NetEffects = assetsNet(),
        env: Map<String, String> = desktopEnv,
        spawnResults: MutableList<Boolean> = mutableListOf(true),
        spawns: Spawns = Spawns(),
        extract: (Path, Path) -> Boolean = fakeExtract(fs),
        hostOs: HostOs = os,
    ) = UiEffects(
        net = net,
        fs = fs,
        version = version,
        hostTarget = { hostOs to arch },
        cacheHome = { cacheHome },
        launchEnvironment = { env },
        requireWorkspace = { fakeWorkspace(projectRoot) },
        extractArchive = extract,
        spawn = { argv, _ ->
            spawns.argv += argv
            spawnResults.removeFirstOrNull() ?: true
        },
    )

    /** A cache in the state a successful previous run leaves behind. */
    private fun primedCache(fs: FakeFileSystem) {
        fs.createDirectories(jarPath.parent!!)
        fs.write(jarPath) { write(jarBytes) }
        fs.createDirectories(launcher.parent!!)
        fs.write(launcher) { writeUtf8("#!/bin/sh") }
        val marker = uiRuntimeMarkerPath(runtimeDir)
        fs.createDirectories(marker.parent!!)
        fs.write(marker) { writeUtf8("-server KNOWN") }
    }

    // --- --fetch-only (the headless / offline-prep path, ADR-010 decision 5) ---------------------------------

    @Test
    fun fetch_only_downloads_and_verifies_both_artifacts_without_launching() {
        val fs = FakeFileSystem()
        val spawns = Spawns()
        val out = RecordingOutput()

        runUiVerb(UiVerbArgs(fetchOnly = true), effects(fs, spawns = spawns), out)

        assertTrue(fs.exists(jarPath), "the jar is installed at its final, atomically-renamed path")
        assertTrue(fs.exists(launcher), "the runtime image is installed with its launcher in place")
        assertTrue(spawns.argv.isEmpty(), "--fetch-only never launches")
        assertEquals("taskkling: UI v$version fetched and verified — 'taskkling ui' will launch it", out.stdout.last())
    }

    @Test
    fun fetch_only_on_a_primed_cache_reports_and_fetches_nothing() {
        val fs = FakeFileSystem()
        primedCache(fs)
        val net = assetsNet()
        val out = RecordingOutput()

        runUiVerb(UiVerbArgs(fetchOnly = true), effects(fs, net = net), out)

        assertEquals(listOf("taskkling: UI v$version already fetched and verified"), out.stdout)
        assertContentEquals(emptyList(), net.calls)
    }

    @Test
    fun fetch_only_works_headless() {
        val fs = FakeFileSystem()
        runUiVerb(UiVerbArgs(fetchOnly = true), effects(fs, env = emptyMap()), RecordingOutput())

        assertTrue(fs.exists(jarPath))
    }

    // --- The headless refusal lands before ANY fetch or spawn (ADR-010 decision 6) --------------------------

    @Test
    fun a_headless_session_is_refused_before_fetching_or_spawning() {
        val fs = FakeFileSystem()
        val net = assetsNet()
        val spawns = Spawns()

        val e = assertFailsWith<TkError> {
            runUiVerb(UiVerbArgs(), effects(fs, net = net, env = emptyMap(), spawns = spawns), RecordingOutput())
        }

        assertEquals(ExitCode.VALIDATION, e.exit)
        assertEquals(headlessRefusalMessage(HostOs.LINUX, emptyMap()), e.message)
        assertContentEquals(emptyList(), net.calls, "the refusal must precede the fetch")
        assertTrue(spawns.argv.isEmpty())
        assertTrue(!fs.exists(jarPath))
    }

    // --- Launch -----------------------------------------------------------------------------------------------

    @Test
    fun a_primed_cache_launches_the_ui_against_the_cli_resolved_root() {
        val fs = FakeFileSystem()
        primedCache(fs)
        val spawns = Spawns()
        val out = RecordingOutput()

        runUiVerb(UiVerbArgs(), effects(fs, spawns = spawns), out)

        assertEquals(listOf(listOf(launcher.toString(), "-jar", jarPath.toString(), projectRoot.toString())), spawns.argv)
        assertEquals(listOf("taskkling: UI v$version launched (log: $logPath)"), out.stdout)
    }

    @Test
    fun macos_names_the_process_for_the_dock() {
        val fs = FakeFileSystem()
        val macLauncher = uiJavaLauncherPath(runtimeDir, HostOs.MACOS)
        val macJar = uiJarPath(cacheHome, version, HostOs.MACOS, arch)
        fs.createDirectories(macJar.parent!!)
        fs.write(macJar) { write(jarBytes) }
        fs.createDirectories(macLauncher.parent!!)
        fs.write(macLauncher) { writeUtf8("java") }
        val macMarker = uiRuntimeMarkerPath(runtimeDir)
        fs.createDirectories(macMarker.parent!!)
        fs.write(macMarker) { writeUtf8("-server KNOWN") }
        val spawns = Spawns()

        runUiVerb(UiVerbArgs(), effects(fs, spawns = spawns, hostOs = HostOs.MACOS), RecordingOutput())

        assertEquals("-Xdock:name=taskkling", spawns.argv.single()[1])
    }

    @Test
    fun a_successful_launch_prunes_stale_cache_entries() {
        val fs = FakeFileSystem()
        primedCache(fs)
        val staleApp = uiAppCacheRoot(cacheHome) / "0.5.0"
        val staleRuntime = uiRuntimeCacheRoot(cacheHome) / "jdk17"
        fs.createDirectories(staleApp)
        fs.write(staleApp / "taskkling-ui.jar") { writeUtf8("old") }
        fs.createDirectories(staleRuntime)

        runUiVerb(UiVerbArgs(), effects(fs), RecordingOutput())

        assertTrue(!fs.exists(staleApp), "the previous version's jar is collected")
        assertTrue(!fs.exists(staleRuntime), "an orphaned runtime is collected")
        assertTrue(fs.exists(jarPath) && fs.exists(launcher), "the current entries survive the prune")
    }

    // --- Self-heal (ADR-010 decision 6) -----------------------------------------------------------------------

    @Test
    fun a_failed_launch_silently_refetches_once_and_succeeds() {
        val fs = FakeFileSystem()
        primedCache(fs)
        val spawns = Spawns()
        val out = RecordingOutput()

        // First spawn fails (corrupt cache), second — after the silent re-fetch — succeeds.
        runUiVerb(UiVerbArgs(), effects(fs, spawnResults = mutableListOf(false, true), spawns = spawns), out)

        assertEquals(2, spawns.argv.size)
        assertEquals(listOf("taskkling: UI v$version launched (log: $logPath)"), out.stdout.takeLast(1))
        assertTrue(out.stdout.none { it.contains("corrupt") }, "the self-heal is SILENT about the cache surgery")
    }

    @Test
    fun the_silent_refetch_drops_both_artifacts_before_retrying() {
        val fs = FakeFileSystem()
        primedCache(fs)
        val net = assetsNet()

        runUiVerb(UiVerbArgs(), effects(fs, net = net, spawnResults = mutableListOf(false, true)), RecordingOutput())

        val base = releaseDownloadBaseUrl(version)
        assertTrue("$base/$jarAsset" in net.calls, "the jar is re-fetched")
        assertTrue("$base/$runtimeAsset" in net.calls, "the runtime is re-fetched too — either could be the corrupt one")
    }

    @Test
    fun a_second_launch_failure_surfaces_the_one_actionable_message() {
        val fs = FakeFileSystem()
        primedCache(fs)
        val spawns = Spawns()

        val e = assertFailsWith<TkError> {
            runUiVerb(UiVerbArgs(), effects(fs, spawnResults = mutableListOf(false, false), spawns = spawns), RecordingOutput())
        }

        assertEquals(ExitCode.VALIDATION, e.exit)
        assertEquals(uiFailureMessage(UiFailureCause.CORRUPT_AFTER_REFETCH, logPath), e.message)
        assertEquals(2, spawns.argv.size, "never a second retry")
    }

    // --- Fetch failures ---------------------------------------------------------------------------------------

    @Test
    fun a_release_without_ui_assets_names_the_pre_v0_6_0_hint() {
        val fs = FakeFileSystem()
        val base = releaseDownloadBaseUrl(version)
        val net = FakeNet(texts = mapOf("$base/SHA256SUMS" to (200 to sha256SumsFor("taskkling-linux-x64" to jarBytes))))

        val e = assertFailsWith<TkError> { runUiVerb(UiVerbArgs(), effects(fs, net = net), RecordingOutput()) }

        assertEquals(
            "release v$version publishes no UI asset '$jarAsset' — releases before v0.6.0 carry no UI; run 'taskkling update' and retry",
            e.message,
        )
    }

    @Test
    fun a_tampered_download_fails_verification_and_installs_nothing() {
        val fs = FakeFileSystem()
        val base = releaseDownloadBaseUrl(version)
        val net = assetsNet("$base/$jarAsset" to (200 to "TRUNCATED".encodeToByteArray()))

        val e = assertFailsWith<TkError> { runUiVerb(UiVerbArgs(), effects(fs, net = net), RecordingOutput()) }

        assertEquals(uiFailureMessage(UiFailureCause.CHECKSUM_MISMATCH, null), e.message)
        assertTrue(!fs.exists(jarPath), "a failed verification never reaches the final path")
    }

    @Test
    fun a_dead_transport_reports_offline() {
        val fs = FakeFileSystem()
        val base = releaseDownloadBaseUrl(version)
        val net = FakeNet(offline = setOf("$base/SHA256SUMS"))

        val e = assertFailsWith<TkError> { runUiVerb(UiVerbArgs(), effects(fs, net = net), RecordingOutput()) }

        assertEquals(uiFailureMessage(UiFailureCause.OFFLINE, null), e.message)
    }

    @Test
    fun a_github_outage_is_distinguished_from_being_offline() {
        val fs = FakeFileSystem()
        val base = releaseDownloadBaseUrl(version)
        val net = FakeNet(texts = mapOf("$base/SHA256SUMS" to (503 to "unavailable")))

        val e = assertFailsWith<TkError> { runUiVerb(UiVerbArgs(), effects(fs, net = net), RecordingOutput()) }

        assertEquals(uiFailureMessage(UiFailureCause.GITHUB_UNREACHABLE, null), e.message)
    }

    @Test
    fun a_wrapped_runtime_archive_layout_is_tolerated() {
        val fs = FakeFileSystem()

        runUiVerb(UiVerbArgs(fetchOnly = true), effects(fs, extract = fakeExtract(fs, wrapped = true)), RecordingOutput())

        assertTrue(fs.exists(launcher), "one wrapping directory is unwrapped into the runtime dir")
    }

    @Test
    fun an_unusable_runtime_archive_layout_is_an_error() {
        val fs = FakeFileSystem()
        val extract: (Path, Path) -> Boolean = { _, destDir ->
            fs.createDirectories(destDir / "unexpected" / "lib")
            true
        }

        val e = assertFailsWith<TkError> { runUiVerb(UiVerbArgs(fetchOnly = true), effects(fs, extract = extract), RecordingOutput()) }

        assertEquals("unexpected or incomplete runtime image in $runtimeAsset (missing bin/ launcher or lib/jvm.cfg)", e.message)
    }

    @Test
    fun a_partial_extraction_that_exits_zero_installs_nothing() {
        val fs = FakeFileSystem()
        // A tar that "succeeds" but dies mid-image: the launcher lands, lib/jvm.cfg never does.
        val extract: (Path, Path) -> Boolean = { _, destDir ->
            fs.createDirectories(destDir / "bin")
            fs.write(destDir / "bin" / "java") { writeUtf8("#!/bin/sh") }
            true
        }

        val e = assertFailsWith<TkError> { runUiVerb(UiVerbArgs(fetchOnly = true), effects(fs, extract = extract), RecordingOutput()) }

        assertEquals("unexpected or incomplete runtime image in $runtimeAsset (missing bin/ launcher or lib/jvm.cfg)", e.message)
        assertTrue(!fs.exists(runtimeDir), "an incomplete image never reaches the final runtime path")
    }

    @Test
    fun a_poisoned_runtime_missing_the_completeness_marker_is_refetched() {
        val fs = FakeFileSystem()
        primedCache(fs)
        // The state an older CLI's failed extraction left behind: launcher present, marker missing.
        fs.delete(uiRuntimeMarkerPath(runtimeDir))
        val net = assetsNet()
        val spawns = Spawns()

        runUiVerb(UiVerbArgs(), effects(fs, net = net, spawns = spawns), RecordingOutput())

        val base = releaseDownloadBaseUrl(version)
        assertTrue("$base/$runtimeAsset" in net.calls, "launcher presence alone is not trusted — the runtime is re-fetched")
        assertTrue(fs.exists(uiRuntimeMarkerPath(runtimeDir)), "the re-fetch replaces the poisoned image with a complete one")
        assertEquals(1, spawns.argv.size, "the healed cache launches normally")
    }

    @Test
    fun a_missing_tar_names_tar_as_the_problem() {
        val fs = FakeFileSystem()

        val e = assertFailsWith<TkError> {
            runUiVerb(UiVerbArgs(fetchOnly = true), effects(fs, extract = { _, _ -> false }), RecordingOutput())
        }

        assertEquals("could not extract $runtimeAsset — is 'tar' available on this system?", e.message)
    }
}
