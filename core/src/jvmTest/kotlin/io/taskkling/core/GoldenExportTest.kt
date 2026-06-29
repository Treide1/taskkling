package io.taskkling.core

import io.taskkling.contract.ComputedDto
import io.taskkling.contract.CountsDto
import io.taskkling.contract.ExportDto
import io.taskkling.contract.TaskDto
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** Golden coverage of the export contract (PRD §12, §17). */
class GoldenExportTest {

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
                created = "2026-01-01T00:00:00Z"),
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
}
