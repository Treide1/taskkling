package io.taskkling.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Adversarial / hand-edited inputs for the lenient reader ([parseTask], PRD §8.3).
 * The reader's promise is that a single malformed file can never throw on the
 * lock-free read path — bad enums fall back to defaults, unknown keys are
 * ignored, and structural damage degrades to defaults rather than crashing.
 * Structural problems are the `doctor`/`validate` verbs' job, not the read path.
 */
class ParseTaskAdversarialTest {

    @Test
    fun missingClosingTerminatorDoesNotThrowAndFallsBackToDefaults() {
        // No second `---`: the whole block is unterminated frontmatter. The reader
        // must not throw; with no parsed frontmatter every field takes its default
        // and id falls back to the filename.
        val raw = "---\n" +
            "id: t-noterm\n" +
            "title: Unterminated\n" +
            "status: waiting\n"
        val back = parseTask("t-noterm--unterminated.md", raw)
        assertEquals("t-noterm", back.id) // filename fallback, since fm never closed
        assertEquals("(untitled)", back.title)
        assertEquals(Status.OPEN, back.status)
        assertEquals(Priority.NORMAL, back.priority)
        assertTrue(back.depends.isEmpty())
        assertEquals("", back.body)
    }

    @Test
    fun absentIdKeyFallsBackToFilenameId() {
        val raw = "---\n" +
            "title: No id key present\n" +
            "created: 2026-01-01T00:00:00Z\n" +
            "---\n" +
            "\n" +
            "Body.\n"
        val back = parseTask("t-fromfn--no-id-key-present.md", raw)
        assertEquals("t-fromfn", back.id)
        assertEquals("No id key present", back.title)
    }

    @Test
    fun blankIdValueAlsoFallsBackToFilenameId() {
        // A present-but-empty `id:` is treated like an absent one (scalar → null).
        val raw = "---\n" +
            "id:\n" +
            "title: Blank id value\n" +
            "---\n"
        val back = parseTask("t-blankid--blank-id-value.md", raw)
        assertEquals("t-blankid", back.id)
    }

    @Test
    fun unparseableStatusSilentlyFallsBackToOpen() {
        val raw = "---\n" +
            "id: t-badst\n" +
            "title: Bad status\n" +
            "status: banana\n" +
            "---\n"
        val back = parseTask("t-badst--bad-status.md", raw)
        assertEquals(Status.OPEN, back.status)
    }

    @Test
    fun unparseablePrioritySilentlyFallsBackToNormal() {
        val raw = "---\n" +
            "id: t-badpr\n" +
            "title: Bad priority\n" +
            "priority: critical\n" +
            "---\n"
        val back = parseTask("t-badpr--bad-priority.md", raw)
        assertEquals(Priority.NORMAL, back.priority)
    }

    @Test
    fun emptyBracketDependsIsEmptyList() {
        val raw = "---\n" +
            "id: t-dep0\n" +
            "title: Empty depends\n" +
            "depends: []\n" +
            "---\n"
        val back = parseTask("t-dep0--empty-depends.md", raw)
        assertTrue(back.depends.isEmpty())
    }

    @Test
    fun dependsToleratesTrailingAndStrayCommas() {
        val raw = "---\n" +
            "id: t-dep1\n" +
            "title: Messy depends\n" +
            "depends: [t-aaaa, , t-bbbb,]\n" +
            "---\n"
        val back = parseTask("t-dep1--messy-depends.md", raw)
        assertEquals(listOf("t-aaaa", "t-bbbb"), back.depends)
    }

    @Test
    fun nonBracketDependsValueYieldsEmptyList() {
        // A bare (non-`[...]`) value is not a YAML flow sequence: the reader
        // refuses to guess and returns an empty list rather than throwing.
        val raw = "---\n" +
            "id: t-dep2\n" +
            "title: Bare depends\n" +
            "depends: t-aaaa, t-bbbb\n" +
            "---\n"
        val back = parseTask("t-dep2--bare-depends.md", raw)
        assertTrue(back.depends.isEmpty())
    }

    @Test
    fun unknownKeysAreIgnoredForForwardCompat() {
        // Keys the current schema doesn't know must be silently dropped so a newer
        // writer's files still read on an older binary. Known fields around them
        // must still parse correctly.
        val raw = "---\n" +
            "id: t-unk\n" +
            "future_field: some future value\n" +
            "title: Has unknown keys\n" +
            "another_unknown: 42\n" +
            "priority: high\n" +
            "---\n" +
            "\n" +
            "Body survives.\n"
        val back = parseTask("t-unk--has-unknown-keys.md", raw)
        assertEquals("t-unk", back.id)
        assertEquals("Has unknown keys", back.title)
        assertEquals(Priority.HIGH, back.priority)
        assertEquals("Body survives.", back.body)
    }
}
