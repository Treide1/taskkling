package io.taskkling.core

import java.lang.reflect.Modifier
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

/**
 * The lenient `config.toml` parser [Config.load] (PRD §14): comment stripping,
 * optional-quote trimming, unknown-key tolerance, bad-int fallback, and the
 * nullable `update_check` tri-state that ADR-005's precedence is built on
 * (`null` = "absent from THIS file", distinct from an explicit `false`).
 *
 * Also a KEY-DRIFT GUARD ([configKeysStayInSyncAcrossTheThreeSites]) binding the
 * three sites that must agree — the [Config.defaultToml] template, the
 * [Config.load] `when`-branch, and the [Config] fields — so a key added to one
 * site but not the others fails the build. Reflection over the JVM-compiled
 * [Config] is why this lives in jvmTest rather than commonTest.
 */
class ConfigLoadTest {

    private val fs = FileSystem.SYSTEM

    /** Write [text] to a fresh temp `config.toml` and parse it. */
    private fun loadToml(text: String): Config {
        val dir = Files.createTempDirectory("tk-config-test").toAbsolutePath().toString().toPath()
        val path = dir / "config.toml"
        fs.write(path) { writeUtf8(text) }
        return Config.load(fs, path)
    }

    // --- parser behavior ----------------------------------------------------------------------------------------

    @Test
    fun missingFileReturnsDefault() {
        val absent: Path = "/no/such/dir/config.toml".toPath()
        assertEquals(Config.DEFAULT, Config.load(fs, absent))
    }

    @Test
    fun commentsAreStrippedInlineAndOnFullLines() {
        val cfg = loadToml(
            """
            # a whole-line comment, ignored entirely
            id_prefix = "x-"   # trailing comment after a value
            """.trimIndent(),
        )
        assertEquals("x-", cfg.idPrefix)
    }

    @Test
    fun surroundingQuotesAreTrimmedButOptional() {
        assertEquals("quoted", loadToml("""tasks_dir = "quoted"""").tasksDir)
        // Quotes are optional — a bare, unquoted value parses the same.
        assertEquals("bare", loadToml("tasks_dir = bare").tasksDir)
    }

    @Test
    fun unknownKeysAreIgnoredAndDoNotDisturbKnownOnes() {
        val cfg = loadToml(
            """
            granularity = "minute"
            totally_made_up = 42
            id_prefix = "y-"
            """.trimIndent(),
        )
        // Documented-but-unused and nonsense keys are tolerated; the real key still lands.
        assertEquals("y-", cfg.idPrefix)
        assertEquals(Config.DEFAULT.tasksDir, cfg.tasksDir)
    }

    @Test
    fun badIntFallsBackToTheDefaultLockTimeout() {
        assertEquals(Config.DEFAULT.lockTimeout, loadToml("lock_timeout = not-a-number").lockTimeout)
        assertEquals(45, loadToml("lock_timeout = 45").lockTimeout)
    }

    // --- update_check nullable tri-state (ADR-005 basis) --------------------------------------------------------

    @Test
    fun updateCheckIsNullWhenAbsentFromThisFile() {
        // The distinction ADR-005 leans on: silence != false. Even a non-empty file leaves it null.
        assertNull(loadToml("id_prefix = \"t-\"").updateCheck)
    }

    @Test
    fun updateCheckParsesExplicitTrueAndFalse() {
        assertEquals(true, loadToml("update_check = true").updateCheck)
        assertEquals(false, loadToml("update_check = false").updateCheck)
    }

    @Test
    fun updateCheckGarbageFallsBackToNullRatherThanFalse() {
        // toBooleanStrictOrNull() rejects "yes"/"1"; the fallback must keep it absent (null), not coerce to false.
        assertNull(loadToml("update_check = yes").updateCheck)
    }

    // --- key-drift guard: the three sites that must agree -------------------------------------------------------

    /**
     * One canonical binding per parsed key: its `config.toml` name, the [Config]
     * field it maps to, and a non-default sample proving [Config.load] actually
     * routes it. Adding a key means adding a row here — which then forces the
     * three guard tests below to fail until the three product sites all agree.
     */
    private data class KeyBinding(
        val tomlKey: String,
        val fieldName: String,
        val sample: String,
        val get: (Config) -> Any?,
        val expected: Any?,
    )

    private val bindings = listOf(
        KeyBinding("tasks_dir", "tasksDir", "custom_dir", { it.tasksDir }, "custom_dir"),
        KeyBinding("id_prefix", "idPrefix", "z-", { it.idPrefix }, "z-"),
        KeyBinding("default_thread", "defaultThread", "main", { it.defaultThread }, "main"),
        KeyBinding("lock_timeout", "lockTimeout", "99", { it.lockTimeout }, 99),
        KeyBinding("update_check", "updateCheck", "false", { it.updateCheck }, false),
        KeyBinding("binary_path", "binaryPath", "/opt/tk", { it.binaryPath }, "/opt/tk"),
    )

    /** Instance (non-static) field names of the compiled [Config] data class. */
    private fun configFieldNames(): Set<String> =
        Config::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .toSet()

    @Test
    fun configFieldsMatchTheKeyBindings() {
        // Site 3: a field added/removed without a matching binding row fails here,
        // which in turn keeps the other two guard tests honest.
        assertEquals(
            bindings.map { it.fieldName }.toSet(),
            configFieldNames(),
            "Config fields drifted from the key bindings — update defaultToml(), load()'s when, and this table together.",
        )
    }

    @Test
    fun everyBindingKeyIsDocumentedInDefaultToml() {
        // Site 1: each parsed key must appear in the template init writes (active or commented).
        val template = Config.defaultToml()
        for (b in bindings) {
            assertTrue(
                b.tomlKey in template,
                "defaultToml() is missing the '${b.tomlKey}' key that load() parses.",
            )
        }
    }

    @Test
    fun everyBindingKeyIsRoutedByLoad() {
        // Site 2: build one file setting every key to a non-default sample and confirm
        // each lands on its field — a dropped when-branch leaves that field at default.
        val text = bindings.joinToString("\n") { "${it.tomlKey} = \"${it.sample}\"" }
        val cfg = loadToml(text)
        for (b in bindings) {
            assertEquals(b.expected, b.get(cfg), "load() did not route the '${b.tomlKey}' key onto ${b.fieldName}.")
        }
    }
}
