package io.taskkling.core

import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Slug, datetime normalisation, and id generation (PRD §8.1, §17). */
class PrimitivesTest {

    @Test
    fun slugLowercasesJoinsAndTrims() {
        assertEquals("acquire-liability-insurance", slugify("Acquire Liability Insurance"))
        assertEquals("a-b-c", slugify("  a / b / c  "))
        assertEquals("task", slugify("!!!")) // empty slug falls back
    }

    @Test
    fun datetimeAcceptsForgivingGranularity() {
        assertEquals("2026-07-31T23:59:00Z", normalizeDateTime("2026-07-31T23:59:00Z"))
        assertEquals("2026-07-31T23:59:00Z", normalizeDateTime("2026-07-31T23:59"))
        assertEquals("2026-07-31T00:00:00Z", normalizeDateTime("2026-07-31"))
    }

    @Test
    fun datetimeRejectsGarbage() {
        assertFailsWith<TkError> { normalizeDateTime("not-a-date") }
        assertFailsWith<TkError> { normalizeDateTime("2026-13-99") }
    }

    @Test
    fun newIdHasPrefixAndAvoidsCollisions() {
        val ws = Workspace("/nowhere".toPath(), Config.DEFAULT)
        val taken = HashSet<String>()
        repeat(200) {
            val id = ws.newId(taken)
            assertTrue(id.startsWith("t-"))
            assertEquals(6, id.length) // "t-" + 4
            assertTrue(id !in taken, "newId returned a collision: $id")
            taken.add(id)
        }
    }
}
