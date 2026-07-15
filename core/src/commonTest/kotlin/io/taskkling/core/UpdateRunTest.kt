package io.taskkling.core

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `update`'s orchestration ([runUpdateVerb]): the wording `--check` reports,
 * the ordering guarantee that local checks precede network IO (t-6ouc), the
 * checksum abort, and the self-vs-other install decision (ADR-002 / ADR-007).
 */
class UpdateRunTest {
    private val running = "/opt/bin/taskkling".toPath()
    private val asset = "taskkling-linux-x64"
    private val newBinary = "NEW-BINARY-BYTES".encodeToByteArray()

    /** Records which install primitive fired, with what, so the decision is assertable without a real binary. */
    private class Installs {
        val self: MutableList<Pair<Path, ByteArray>> = mutableListOf()
        val other: MutableList<Pair<Path, ByteArray>> = mutableListOf()
        val restamps: MutableList<Pair<Path, String>> = mutableListOf()
    }

    private fun effects(
        net: NetEffects = FakeNet(),
        fs: FileSystem = FakeFileSystem(),
        currentVersion: String = "0.6.3",
        installs: Installs = Installs(),
        globalInstallDir: Path = "/opt/bin".toPath(),
        workspace: (String?) -> WorkspaceInfo = { fakeWorkspace("/proj".toPath()) },
        savedCaches: MutableList<UpdateCheckCache> = mutableListOf(),
    ) = UpdateEffects(
        net = net,
        fs = fs,
        currentVersion = currentVersion,
        releaseAssetName = { asset },
        runningExecutable = { running },
        globalInstallDir = { globalInstallDir },
        requireWorkspace = workspace,
        installSelf = { p, b -> installs.self += p to b },
        installOther = { p, b -> installs.other += p to b },
        restampLocalBinVersion = { p, v -> installs.restamps += p to v },
        nowEpochSeconds = { 1_000L },
        saveCheckCache = { savedCaches += it },
    )

    private fun releaseNet(tag: String, vararg extraSums: Pair<String, ByteArray>): FakeNet {
        val base = releaseDownloadBaseUrl(tag)
        return FakeNet(
            texts = mapOf(
                GITHUB_API_LATEST_RELEASE to (200 to """{"tag_name":"$tag"}"""),
                "$base/SHA256SUMS" to (200 to sha256SumsFor(asset to newBinary, *extraSums)),
            ),
            bytes = mapOf("$base/$asset" to (200 to newBinary)),
        )
    }

    // --- `--check`: reports, never installs, warms the ADR-005 cache ------------------------------------------

    @Test
    fun check_reports_an_available_update() {
        val out = RecordingOutput()
        runUpdateVerb(UpdateVerbArgs(check = true), effects(net = releaseNet("v0.7.0")), out)

        assertEquals(listOf("taskkling: update available: 0.6.3 -> 0.7.0 (run 'taskkling update')"), out.stdout)
        assertTrue(out.stderr.isEmpty())
    }

    @Test
    fun check_reports_up_to_date_when_the_latest_release_is_the_running_version() {
        val out = RecordingOutput()
        runUpdateVerb(UpdateVerbArgs(check = true), effects(net = releaseNet("v0.6.3")), out)

        assertEquals(listOf("taskkling: up to date (0.6.3)"), out.stdout)
    }

    @Test
    fun check_reports_a_failed_lookup_on_stderr_and_leaves_the_cache_alone() {
        val savedCaches = mutableListOf<UpdateCheckCache>()
        val net = FakeNet(offline = setOf(GITHUB_API_LATEST_RELEASE))
        val out = RecordingOutput()

        runUpdateVerb(UpdateVerbArgs(check = true), effects(net = net, savedCaches = savedCaches), out)

        assertEquals(listOf("taskkling: could not check for updates (network error or rate-limited)"), out.stderr)
        assertTrue(out.stdout.isEmpty())
        assertTrue(savedCaches.isEmpty(), "a failed lookup must not invent cache data")
    }

    @Test
    fun check_warms_the_shared_update_check_cache() {
        val savedCaches = mutableListOf<UpdateCheckCache>()
        runUpdateVerb(UpdateVerbArgs(check = true), effects(net = releaseNet("v0.7.0"), savedCaches = savedCaches), RecordingOutput())

        assertEquals(listOf(UpdateCheckCache(1_000L, "v0.7.0")), savedCaches)
    }

    @Test
    fun check_never_installs() {
        val installs = Installs()
        runUpdateVerb(UpdateVerbArgs(check = true), effects(net = releaseNet("v0.7.0"), installs = installs), RecordingOutput())

        assertTrue(installs.self.isEmpty() && installs.other.isEmpty())
    }

    // --- Tier resolution happens BEFORE any network IO (t-6ouc) ----------------------------------------------

    @Test
    fun global_with_no_install_fails_without_touching_the_network() {
        val net = FakeNet()
        val e = assertFailsWith<TkError> {
            runUpdateVerb(UpdateVerbArgs(global = true), effects(net = net), RecordingOutput())
        }

        assertEquals(ExitCode.VALIDATION, e.exit)
        assertEquals("no global install found; install it with the install script", e.message)
        assertContentEquals(emptyList(), net.calls, "the tier check must fail before any download")
    }

    @Test
    fun local_with_no_install_fails_without_touching_the_network() {
        val net = FakeNet()
        val e = assertFailsWith<TkError> {
            runUpdateVerb(UpdateVerbArgs(local = true), effects(net = net), RecordingOutput())
        }

        assertEquals(ExitCode.VALIDATION, e.exit)
        assertEquals("no local-bin install here; run 'taskkling init --local-bin' first", e.message)
        assertContentEquals(emptyList(), net.calls)
    }

    @Test
    fun an_unresolvable_latest_tag_names_the_version_flag_as_the_way_out() {
        val e = assertFailsWith<TkError> {
            runUpdateVerb(UpdateVerbArgs(), effects(net = FakeNet(offline = setOf(GITHUB_API_LATEST_RELEASE))), RecordingOutput())
        }

        assertEquals(ExitCode.VALIDATION, e.exit)
        assertTrue(e.message!!.contains("pass --version vX.Y.Z to update without it"))
    }

    // --- Download + verify ------------------------------------------------------------------------------------

    @Test
    fun a_checksum_mismatch_aborts_before_any_install() {
        val installs = Installs()
        val base = releaseDownloadBaseUrl("v0.7.0")
        val net = FakeNet(
            texts = mapOf(
                GITHUB_API_LATEST_RELEASE to (200 to """{"tag_name":"v0.7.0"}"""),
                // A SHA256SUMS entry for DIFFERENT bytes: exactly a truncated/tampered transfer.
                "$base/SHA256SUMS" to (200 to sha256SumsFor(asset to "OTHER-BYTES".encodeToByteArray())),
            ),
            bytes = mapOf("$base/$asset" to (200 to newBinary)),
        )

        val e = assertFailsWith<TkError> {
            runUpdateVerb(UpdateVerbArgs(), effects(net = net, installs = installs), RecordingOutput())
        }

        assertEquals(ExitCode.VALIDATION, e.exit)
        assertTrue(e.message!!.startsWith("checksum mismatch for $asset"), e.message)
        assertTrue(e.message!!.endsWith("— aborting"))
        assertTrue(installs.self.isEmpty() && installs.other.isEmpty(), "nothing may be written on a failed verification")
        assertTrue(installs.restamps.isEmpty())
    }

    @Test
    fun a_missing_checksum_entry_aborts() {
        val base = releaseDownloadBaseUrl("v0.7.0")
        val net = FakeNet(
            texts = mapOf(
                GITHUB_API_LATEST_RELEASE to (200 to """{"tag_name":"v0.7.0"}"""),
                "$base/SHA256SUMS" to (200 to sha256SumsFor("some-other-asset" to newBinary)),
            ),
            bytes = mapOf("$base/$asset" to (200 to newBinary)),
        )

        val e = assertFailsWith<TkError> { runUpdateVerb(UpdateVerbArgs(), effects(net = net), RecordingOutput()) }

        assertEquals("no checksum entry for $asset in SHA256SUMS", e.message)
    }

    @Test
    fun a_non_2xx_asset_download_aborts_naming_the_status_and_url() {
        val base = releaseDownloadBaseUrl("v0.7.0")
        val net = FakeNet(texts = mapOf(GITHUB_API_LATEST_RELEASE to (200 to """{"tag_name":"v0.7.0"}""")))

        val e = assertFailsWith<TkError> { runUpdateVerb(UpdateVerbArgs(), effects(net = net), RecordingOutput()) }

        assertEquals("download failed: HTTP 404 for $base/$asset", e.message)
    }

    // --- Self vs other (ADR-007) ------------------------------------------------------------------------------

    @Test
    fun the_default_target_is_the_running_binary_and_self_replaces() {
        val installs = Installs()
        val out = RecordingOutput()

        runUpdateVerb(UpdateVerbArgs(versionOverride = "v0.7.0"), effects(net = releaseNet("v0.7.0"), installs = installs), out)

        assertEquals(listOf(running to newBinary.toList()), installs.self.map { it.first to it.second.toList() })
        assertTrue(installs.other.isEmpty())
        assertEquals(listOf(running to "0.7.0"), installs.restamps)
        assertEquals(
            listOf("Downloading $asset (0.7.0) ...", "Checksum OK (${Sha256.hashHex(newBinary)})", "0.6.3 -> 0.7.0"),
            out.stdout,
        )
    }

    @Test
    fun a_local_tier_target_that_is_not_the_running_binary_is_a_plain_overwrite() {
        val fs = FakeFileSystem()
        val localBin = "/proj/.taskkling/bin/taskkling".toPath()
        fs.createDirectories(localBin.parent!!)
        fs.write(localBin) { writeUtf8("old") }
        fs.createDirectories(running.parent!!)
        fs.write(running) { writeUtf8("running") }
        val installs = Installs()
        val out = RecordingOutput()

        runUpdateVerb(
            UpdateVerbArgs(local = true, versionOverride = "v0.7.0"),
            effects(net = releaseNet("v0.7.0"), fs = fs, installs = installs, workspace = { fakeWorkspace("/proj".toPath()) }),
            out,
        )

        assertEquals(listOf(localBin), installs.other.map { it.first })
        assertTrue(installs.self.isEmpty(), "a different tier's copy is unlocked — no self-replace dance")
        assertEquals(listOf("updated $localBin to 0.7.0"), out.stdout.takeLast(1))
        fs.checkNoOpenFiles()
    }

    @Test
    fun a_tier_flag_naming_the_running_binary_through_another_path_still_self_replaces() {
        // --global re-derives the path as a string; only canonicalization reveals it is the running image.
        val fs = FakeFileSystem().apply { allowSymlinks = true }
        fs.createDirectories(running.parent!!)
        fs.write(running) { writeUtf8("running") }
        fs.createDirectories("/usr".toPath())
        fs.createSymlink("/usr/bin".toPath(), running.parent!!)
        val installs = Installs()

        runUpdateVerb(
            UpdateVerbArgs(global = true, versionOverride = "v0.7.0"),
            effects(net = releaseNet("v0.7.0"), fs = fs, installs = installs, globalInstallDir = "/usr/bin".toPath()),
            RecordingOutput(),
        )

        assertTrue(installs.other.isEmpty(), "the un-canonicalized path must not be mistaken for another tier's copy")
        assertEquals(1, installs.self.size)
    }

    @Test
    fun quiet_suppresses_the_progress_lines_but_not_the_work() {
        val installs = Installs()
        val out = RecordingOutput()

        runUpdateVerb(UpdateVerbArgs(versionOverride = "v0.7.0", quiet = true), effects(net = releaseNet("v0.7.0"), installs = installs), out)

        assertTrue(out.stdout.isEmpty())
        assertEquals(1, installs.self.size)
    }

    @Test
    fun version_override_skips_the_latest_lookup_entirely() {
        val net = releaseNet("v0.7.0")
        runUpdateVerb(UpdateVerbArgs(versionOverride = "v0.7.0"), effects(net = net), RecordingOutput())

        assertTrue(GITHUB_API_LATEST_RELEASE !in net.calls, "an explicit --version needs no latest-release lookup")
    }
}
