package io.taskkling.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Frontmatter parse/serialize round-trip (PRD §8.3, §17). */
class FrontmatterTest {

    private fun roundTrip(t: Task): Task = parseTask(t.fileName(), t.toMarkdown())

    @Test
    fun minimalTaskRoundTrips() {
        val t = Task(id = "t-a1z9", title = "Acquire insurance", created = "2026-06-28T10:15:00Z")
        val back = roundTrip(t)
        assertEquals(t.id, back.id)
        assertEquals(t.title, back.title)
        assertEquals(Status.OPEN, back.status)
        assertEquals(Priority.NORMAL, back.priority)
        assertTrue(back.depends.isEmpty())
        assertEquals(t.created, back.created)
    }

    @Test
    fun fullTaskRoundTrips() {
        val t = Task(
            id = "t-9f3c",
            title = "Tax questionnaire",
            thread = "legal",
            status = Status.WAITING,
            waitingOn = "provider quote callback",
            depends = listOf("t-a1z9", "t-7e1d"),
            due = "2026-07-31T23:59:00Z",
            defer = "2026-07-05T00:00:00Z",
            priority = Priority.HIGH,
            created = "2026-06-28T10:15:00Z",
            closed = null,
            body = "Need professional-indemnity cover.\nComparing three providers.",
        )
        assertEquals(t, roundTrip(t))
    }

    @Test
    fun titleWithYamlSpecialCharsRoundTrips() {
        val t = Task(id = "t-0000", title = "Fix: colons, \"quotes\" & [brackets]", created = "2026-01-01T00:00:00Z")
        assertEquals(t.title, roundTrip(t).title)
    }

    @Test
    fun umlautTitleRoundTripsAndSlugTransliterates() {
        val t = Task(id = "t-uuuu", title = "Übergröße prüfen", created = "2026-01-01T00:00:00Z")
        assertEquals(t.title, roundTrip(t).title)
        assertEquals("t-uuuu--uebergroesse-pruefen.md", t.fileName())
    }

    @Test
    fun waitingOnOmittedWhenNotWaiting() {
        val t = Task(id = "t-open", title = "open node", created = "2026-01-01T00:00:00Z", waitingOn = null)
        assertTrue("waiting_on" !in t.toMarkdown())
    }

    @Test
    fun emptyBodyProducesNoTrailingContent() {
        val t = Task(id = "t-body", title = "no body", created = "2026-01-01T00:00:00Z")
        assertEquals("", roundTrip(t).body)
    }
}
