package io.taskkling.core

import io.taskkling.contract.ComputedDto
import io.taskkling.contract.CountsDto
import io.taskkling.contract.ExportDto
import io.taskkling.contract.TaskDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Golden coverage of the export contract (PRD §12, §17). */
class GoldenExportTest {

    // Must mirror the CLI's export serializer config (cli Main.kt `json`:
    // prettyPrint/encodeDefaults/explicitNulls) so the wire-golden below reflects
    // the bytes the CLI actually emits — the JSON the UI's CliClient parses
    // (ADR-008). Keep in sync if that config changes.
    private val json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = true }

    private fun fixture(): Workspace {
        val ws = tempWorkspace()
        // Two fixed-id nodes: a done root and its now-ready child.
        ws.overwriteOnDisk(
            Task(id = "t-aaaa", title = "root", thread = "demo", status = Status.DONE,
                created = "2026-01-01T00:00:00Z", closed = "2026-01-02T00:00:00Z"),
        )
        ws.overwriteOnDisk(
            Task(id = "t-bbbb", title = "child", thread = "demo", depends = listOf("t-aaaa"),
                created = "2026-01-01T00:00:00Z", body = "child body"),
        )
        return ws
    }

    @Test
    fun exportMatchesGoldenDto() {
        val actual = fixture().buildExport(includeBody = false, includeArchived = false)
            .copy(generatedAt = "T")

        val expected = ExportDto(
            generatedAt = "T",
            counts = CountsDto(ready = 1, blocked = 0, waiting = 0, done = 1),
            tasks = listOf(
                TaskDto(
                    id = "t-aaaa", title = "root", thread = "demo", status = "done",
                    depends = emptyList(), priority = "normal",
                    created = "2026-01-01T00:00:00Z", closed = "2026-01-02T00:00:00Z",
                    computed = ComputedDto(
                        ready = false, blocked = false, deferred = false, overdue = false,
                        resurfaced = false, blockers = emptyList(), dependents = listOf("t-bbbb"),
                    ),
                ),
                TaskDto(
                    id = "t-bbbb", title = "child", thread = "demo", status = "open",
                    depends = listOf("t-aaaa"), priority = "normal",
                    created = "2026-01-01T00:00:00Z", closed = null,
                    computed = ComputedDto(
                        ready = true, blocked = false, deferred = false, overdue = false,
                        resurfaced = false, blockers = emptyList(), dependents = emptyList(),
                    ),
                ),
            ),
        )
        assertEquals(expected, actual)
    }

    @Test
    fun serializationIsDeterministic() {
        val ws = fixture()
        val a = json.encodeToString(ExportDto.serializer(), ws.buildExport(false, false).copy(generatedAt = "T"))
        val b = json.encodeToString(ExportDto.serializer(), ws.buildExport(false, false).copy(generatedAt = "T"))
        assertEquals(a, b) // tasks sorted by id => stable output
    }

    /**
     * Wire-format regression guard: assert the *serialized* JSON string, not just
     * the DTO. A camelCase/field-rename that still maps to the same DTO would slip
     * past [exportMatchesGoldenDto] yet break the UI's CliClient (ADR-008); pinning
     * the literal bytes catches it. `generatedAt` is neutralized (volatile).
     */
    @Test
    fun serializedWireGoldenIsStable() {
        val actual = json.encodeToString(
            ExportDto.serializer(),
            fixture().buildExport(includeBody = false, includeArchived = false).copy(generatedAt = "T"),
        )
        assertEquals(WIRE_GOLDEN, actual)
    }

    /** `includeBody=true` threads the stored body into the DTO; false omits it. */
    @Test
    fun includeBodyEmitsBodyOnlyWhenRequested() {
        val ws = fixture()

        val withBody = ws.buildExport(includeBody = true, includeArchived = false)
        assertEquals("child body", withBody.tasks.first { it.id == "t-bbbb" }.body)

        val withoutBody = ws.buildExport(includeBody = false, includeArchived = false)
        assertNull(withoutBody.tasks.first { it.id == "t-bbbb" }.body, "body suppressed when not requested")
    }

    /**
     * `includeArchived` toggles the `archive/` subtree into the export, changing
     * both the task list and the tallies (the done-in-archive still counts as done).
     */
    @Test
    fun includeArchivedTogglesArchiveSubtree() {
        val ws = tempWorkspace()
        ws.overwriteOnDisk(
            Task(id = "t-open", title = "active", thread = "demo",
                created = "2026-01-01T00:00:00Z"),
        )
        // A done task living in the archive subtree (not the active dir).
        val archived = Task(id = "t-arch", title = "archived", thread = "demo", status = Status.DONE,
            created = "2026-01-01T00:00:00Z", closed = "2026-01-02T00:00:00Z")
        ws.writeFileAtomic(ws.archiveDir / archived.fileName(), archived.toMarkdown())

        val active = ws.buildExport(includeBody = false, includeArchived = false)
        assertEquals(listOf("t-open"), active.tasks.map { it.id }, "archive hidden by default")
        assertEquals(0, active.counts.done, "archived done not tallied when excluded")

        val withArchive = ws.buildExport(includeBody = false, includeArchived = true)
        assertEquals(listOf("t-arch", "t-open"), withArchive.tasks.map { it.id }, "archive folded in, sorted by id")
        assertEquals(1, withArchive.counts.done, "archived done tallied when included")
    }

    /**
     * Counts branch coverage: a waiting/dropped/blocked mix. `waiting` counts
     * WAITING status; `done` counts DONE only (DROPPED is excluded); `blocked`
     * counts open tasks with unmet deps; `ready` excludes the blocked one.
     */
    @Test
    fun countsTallyWaitingDroppedAndBlockedMix() {
        val ws = tempWorkspace()
        ws.overwriteOnDisk(Task(id = "t-done", title = "done", status = Status.DONE,
            created = "2026-01-01T00:00:00Z", closed = "2026-01-02T00:00:00Z"))
        ws.overwriteOnDisk(Task(id = "t-wait", title = "waiting", status = Status.WAITING,
            created = "2026-01-01T00:00:00Z"))
        ws.overwriteOnDisk(Task(id = "t-drop", title = "dropped", status = Status.DROPPED,
            created = "2026-01-01T00:00:00Z", closed = "2026-01-02T00:00:00Z"))
        // open, depends on a not-done task => blocked (not ready).
        ws.overwriteOnDisk(Task(id = "t-blok", title = "blocked", depends = listOf("t-wait"),
            created = "2026-01-01T00:00:00Z"))
        // open, no deps => ready.
        ws.overwriteOnDisk(Task(id = "t-redy", title = "ready",
            created = "2026-01-01T00:00:00Z"))

        val counts = ws.buildExport(includeBody = false, includeArchived = false).counts
        assertEquals(CountsDto(ready = 1, blocked = 1, waiting = 1, done = 1), counts)
    }

    private companion object {
        // The exact pretty-printed bytes the CLI emits for [fixture] (generatedAt
        // neutralized to "T"). Empty arrays render inline `[]`; camelCase keys
        // (`waitingOn`, `generatedAt`) are pinned here — the ADR-008 contract.
        val WIRE_GOLDEN = """
            {
                "generatedAt": "T",
                "counts": {
                    "ready": 1,
                    "blocked": 0,
                    "waiting": 0,
                    "done": 1
                },
                "tasks": [
                    {
                        "id": "t-aaaa",
                        "title": "root",
                        "thread": "demo",
                        "status": "done",
                        "waitingOn": null,
                        "depends": [],
                        "due": null,
                        "defer": null,
                        "priority": "normal",
                        "created": "2026-01-01T00:00:00Z",
                        "closed": "2026-01-02T00:00:00Z",
                        "computed": {
                            "ready": false,
                            "blocked": false,
                            "deferred": false,
                            "overdue": false,
                            "resurfaced": false,
                            "blockers": [],
                            "dependents": [
                                "t-bbbb"
                            ]
                        },
                        "body": null
                    },
                    {
                        "id": "t-bbbb",
                        "title": "child",
                        "thread": "demo",
                        "status": "open",
                        "waitingOn": null,
                        "depends": [
                            "t-aaaa"
                        ],
                        "due": null,
                        "defer": null,
                        "priority": "normal",
                        "created": "2026-01-01T00:00:00Z",
                        "closed": null,
                        "computed": {
                            "ready": true,
                            "blocked": false,
                            "deferred": false,
                            "overdue": false,
                            "resurfaced": false,
                            "blockers": [],
                            "dependents": []
                        },
                        "body": null
                    }
                ]
            }
        """.trimIndent()
    }
}
