package io.taskkling.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure `update`/`update --check` logic (ADR-002): SHA-256, host-triple/asset
 * resolution, `SHA256SUMS` parsing, the GitHub `tag_name` JSON shape, and
 * semver compare. Network IO and the self-replace primitive are QA-gated
 * (not unit-testable without an HTTP mock harness / a real running binary).
 */
class UpdateTest {

    // --- Sha256 ------------------------------------------------------------------------------------------------

    @Test
    fun sha256KnownVectors() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256.hashHex("abc".encodeToByteArray()),
        )
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Sha256.hashHex("".encodeToByteArray()),
        )
        assertEquals(
            "5a3b4e913bf092817462d26c0efb8c37ba0b5b4dae37b3a2d5486b9e4c19ef5e",
            Sha256.hashHex("taskkling".encodeToByteArray()),
        )
    }

    @Test
    fun sha256HandlesLongerInputRequiringMultipleBlocks() {
        // 64 'a's — long enough to force the padding/multi-block path, cross-checked against `sha256sum`.
        val input = "a".repeat(64)
        assertEquals(
            "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb",
            Sha256.hashHex(input.encodeToByteArray()),
        )
    }

    // --- Host triple / asset resolution --------------------------------------------------------------------------

    @Test
    fun resolveAssetNameCoversAllPublishedTargets() {
        assertEquals("taskkling-linux-x64", resolveAssetName(HostOs.LINUX, HostArch.X64))
        assertEquals("taskkling-macos-arm64", resolveAssetName(HostOs.MACOS, HostArch.ARM64))
        assertEquals("taskkling-macos-x64", resolveAssetName(HostOs.MACOS, HostArch.X64))
        assertEquals("taskkling-windows-x64.exe", resolveAssetName(HostOs.WINDOWS, HostArch.X64))
    }

    @Test
    fun resolveAssetNameRejectsUnpublishedCombinations() {
        // linux-arm64 is explicitly NOT built (install.sh's comment); windows-arm64 was never published either.
        assertFailsWith<IllegalArgumentException> { resolveAssetName(HostOs.LINUX, HostArch.ARM64) }
        assertFailsWith<IllegalArgumentException> { resolveAssetName(HostOs.WINDOWS, HostArch.ARM64) }
    }

    @Test
    fun unameMappingMirrorsInstallSh() {
        assertEquals(HostOs.MACOS, parseUnameOs("Darwin"))
        assertEquals(HostOs.LINUX, parseUnameOs("Linux"))
        assertNull(parseUnameOs("SunOS"))

        assertEquals(HostArch.X64, parseUnameArch("x86_64"))
        assertEquals(HostArch.X64, parseUnameArch("amd64"))
        assertEquals(HostArch.ARM64, parseUnameArch("arm64"))
        assertEquals(HostArch.ARM64, parseUnameArch("aarch64"))
        assertNull(parseUnameArch("i686"))

        assertEquals(HostArch.X64, parseWindowsArch("AMD64"))
        assertNull(parseWindowsArch("ARM64"))
    }

    // --- Download URL construction ------------------------------------------------------------------------------

    @Test
    fun normalizeVersionTagAddsMissingVPrefix() {
        assertEquals("v0.3.0", normalizeVersionTag("0.3.0"))
        assertEquals("v0.3.0", normalizeVersionTag("v0.3.0"))
    }

    @Test
    fun releaseDownloadBaseUrlBuildsTagSpecificPath() {
        assertEquals(
            "https://example.test/releases/download/v0.3.0",
            releaseDownloadBaseUrl("v0.3.0", "https://example.test/releases"),
        )
        assertEquals(
            "https://example.test/releases/download/v0.3.0",
            releaseDownloadBaseUrl("0.3.0", "https://example.test/releases"),
        )
    }

    // --- SHA256SUMS parsing ---------------------------------------------------------------------------------------

    @Test
    fun parseSha256SumsHandlesTwoSpaceFormat() {
        val text = """
            aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa  taskkling-linux-x64
            bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb  taskkling-macos-arm64
        """.trimIndent()
        val sums = parseSha256Sums(text)
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", sums["taskkling-linux-x64"])
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", sums["taskkling-macos-arm64"])
    }

    @Test
    fun parseSha256SumsHandlesBinaryModeAsteriskFormat() {
        // `sha256sum -b` style: "<hex> *<filename>".
        val text = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc *taskkling-windows-x64.exe\n"
        assertEquals(
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            findSha256(text, "taskkling-windows-x64.exe"),
        )
    }

    @Test
    fun findSha256IsNullForMissingEntry() {
        val text = "aaaa  taskkling-linux-x64\n"
        assertNull(findSha256(text, "taskkling-macos-x64"))
    }

    // --- GitHub `tag_name` lookup -----------------------------------------------------------------------------------

    @Test
    fun parseLatestTagNameExtractsFromRealisticApiResponse() {
        val json = """
            {
              "url": "https://api.github.com/repos/Treide1/taskkling/releases/12345",
              "tag_name": "v0.3.0",
              "name": "v0.3.0",
              "draft": false,
              "prerelease": false,
              "assets": []
            }
        """.trimIndent()
        assertEquals("v0.3.0", parseLatestTagName(json))
    }

    @Test
    fun parseLatestTagNameSilentlyFailsOnMalformedOrMissingField() {
        assertNull(parseLatestTagName("not json at all"))
        assertNull(parseLatestTagName("""{"name": "v0.3.0"}"""))
        assertNull(parseLatestTagName(""))
    }

    // --- Semver compare -------------------------------------------------------------------------------------------

    @Test
    fun compareSemverOrdersByMajorMinorPatch() {
        assertTrue(compareSemver("0.2.2", "0.3.0") < 0)
        assertTrue(compareSemver("1.0.0", "0.9.9") > 0)
        assertEquals(0, compareSemver("0.2.2", "v0.2.2"))
        assertTrue(compareSemver("0.10.0", "0.9.0") > 0) // numeric, not lexicographic
    }

    @Test
    fun isNewerVersionDetectsAvailableUpdates() {
        assertTrue(isNewerVersion("0.2.2", "0.3.0"))
        assertFalse(isNewerVersion("0.2.2", "0.2.2"))
        assertFalse(isNewerVersion("0.3.0", "0.2.2"))
    }

    @Test
    fun semVerParseRejectsGarbage() {
        assertFailsWith<IllegalArgumentException> { SemVer.parse("not-a-version") }
        assertFailsWith<IllegalArgumentException> { SemVer.parse("1.2") }
        assertFailsWith<IllegalArgumentException> { SemVer.parse("1.2.3.4") }
    }
}
