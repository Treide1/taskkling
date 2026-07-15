package io.taskkling.contract

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the export/JSON wire contract (PRD §12) — the boundary where the CLI writes and the
 * UI reads. Two properties matter and are asserted here:
 *
 *  1. **Field names are the contract.** kotlinx-serialization uses the Kotlin property names
 *     verbatim (no `@SerialName`), so a rename is a silent breaking wire change. ADR-008 froze
 *     the stored vocabulary on the wire — `depends`, `computed.blockers`, `computed.dependents`
 *     keep the stored-family names and are *not* renamed to the UI's blocker wording. These
 *     tests pin the exact key set so any rename fails loudly and forces a follow-up ADR.
 *  2. **Absence round-trips faithfully.** Optional fields are nullable / defaulted so a minimal
 *     payload decodes to the documented defaults and re-encodes without inventing data.
 */
class ContractWireTest {

    // The writer emits every key (encodeDefaults) with explicit nulls; the reader (mirroring
    // the UI's CliClient) tolerates unknown keys and keeps explicit nulls. We assert against
    // both sides so the field-name set reflects what actually crosses the wire.
    private val writer = Json { encodeDefaults = true; explicitNulls = true }
    private val reader = Json { ignoreUnknownKeys = true; explicitNulls = true }

    private fun fullTask() = TaskDto(
        id = "t-aaaa",
        title = "root",
        thread = "demo",
        status = "done",
        waitingOn = null,
        depends = listOf("t-zzzz"),
        due = "2026-02-01T00:00:00Z",
        defer = "2026-01-15T00:00:00Z",
        priority = "high",
        created = "2026-01-01T00:00:00Z",
        closed = "2026-01-02T00:00:00Z",
        computed = ComputedDto(
            ready = false, blocked = true, deferred = false, overdue = true,
            resurfaced = false, blockers = listOf("t-zzzz"), dependents = listOf("t-bbbb"),
        ),
        body = "the body",
    )

    private fun fullExport() = ExportDto(
        generatedAt = "2026-01-02T00:00:00Z",
        counts = CountsDto(ready = 1, blocked = 2, waiting = 3, done = 4),
        tasks = listOf(fullTask()),
        defaultThread = "main",
        workspaceName = "my-repo",
    )

    // --- Field names: the ADR-008 vocabulary boundary ----------------------------------------

    @Test
    fun exportWireKeysAreExactlyGeneratedAtCountsTasksDefaultThreadWorkspaceName() {
        val obj = writer.encodeToJsonElement(ExportDto.serializer(), fullExport()).jsonObject
        assertEquals(setOf("generatedAt", "counts", "tasks", "defaultThread", "workspaceName"), obj.keys)
    }

    @Test
    fun countsWireKeysAreTheFourHeadlineTallies() {
        val obj = writer.encodeToJsonElement(CountsDto.serializer(), CountsDto()).jsonObject
        assertEquals(setOf("ready", "blocked", "waiting", "done"), obj.keys)
    }

    @Test
    fun taskWireKeysMirrorTheStoredVocabulary() {
        val obj = writer.encodeToJsonElement(TaskDto.serializer(), fullTask()).jsonObject
        assertEquals(
            setOf(
                "id", "title", "thread", "status", "waitingOn", "depends", "due", "defer",
                "priority", "created", "closed", "computed", "body",
            ),
            obj.keys,
        )
        // ADR-008: the stored dependency vocabulary is on the wire, never the UI's blocker
        // wording. `depends` (not `dependsOn`), and the computed edge is `dependents`.
        assertTrue("depends" in obj.keys, "stored 'depends' key must not be renamed")
    }

    @Test
    fun computedWireKeysKeepStoredDependentsAndBlockers() {
        val obj = writer.encodeToJsonElement(ComputedDto.serializer(), ComputedDto()).jsonObject
        assertEquals(
            setOf(
                "ready", "blocked", "deferred", "overdue", "resurfaced", "blockers", "dependents",
            ),
            obj.keys,
        )
        // ADR-008 explicitly kept these two names: `blockers` = the unmet-depends subset,
        // `dependents` = the downstream edge (NOT the UI's "blocker of").
        assertTrue("blockers" in obj.keys && "dependents" in obj.keys)
    }

    // --- Round-trip fidelity -----------------------------------------------------------------

    @Test
    fun fullExportRoundTripsThroughTheReaderConfig() {
        val encoded = writer.encodeToString(ExportDto.serializer(), fullExport())
        val decoded = reader.decodeFromString(ExportDto.serializer(), encoded)
        assertEquals(fullExport(), decoded)
    }

    @Test
    fun anExportPredatingTheConfigEchoFieldsStillDecodes() {
        // Backward-compat (ADR-008): `defaultThread` and `workspaceName` were added to a
        // shipped contract, so an export written before either existed must still decode,
        // with both falling back to "". The UI leans on exactly this — an empty
        // workspaceName is what makes its header degrade to the bare wordmark.
        val legacy = """
            {"generatedAt":"2026-01-02T00:00:00Z",
             "counts":{"ready":1,"blocked":2,"waiting":3,"done":4},
             "tasks":[]}
        """.trimIndent()
        val export = reader.decodeFromString(ExportDto.serializer(), legacy)

        assertEquals("", export.defaultThread)
        assertEquals("", export.workspaceName)
        assertEquals(emptyList(), export.tasks)
    }

    @Test
    fun minimalTaskDecodesToDocumentedDefaults() {
        // Only the non-defaulted required fields present; everything optional is absent.
        val minimal = """
            {"id":"t-cccc","title":"solo","status":"open","created":"2026-01-01T00:00:00Z",
             "computed":{}}
        """.trimIndent()
        val task = reader.decodeFromString(TaskDto.serializer(), minimal)

        assertNull(task.thread)
        assertNull(task.waitingOn)
        assertNull(task.due)
        assertNull(task.defer)
        assertNull(task.closed)
        assertNull(task.body)
        assertEquals(emptyList(), task.depends)
        assertEquals("normal", task.priority)
        // Computed defaults: all false, both edge lists empty.
        assertEquals(ComputedDto(), task.computed)
        assertEquals(emptyList(), task.computed.blockers)
        assertEquals(emptyList(), task.computed.dependents)
    }

    @Test
    fun unknownWireKeysAreIgnoredByTheReader() {
        // Forward-compat: a newer CLI adds a field the UI doesn't know; the reader must not choke.
        val withExtra = """
            {"id":"t-dddd","title":"x","status":"open","created":"2026-01-01T00:00:00Z",
             "computed":{},"someFutureField":42}
        """.trimIndent()
        val task = reader.decodeFromString(TaskDto.serializer(), withExtra)
        assertEquals("t-dddd", task.id)
    }

    // --- A realistic pretty-printed export payload -------------------------------------------

    @Test
    fun decodesARealisticPrettyPrintedExportPayload() {
        // Shape + whitespace match the CLI's `export` output (prettyPrint = true,
        // encodeDefaults = true): a done root and its now-ready child.
        val payload = """
            {
                "generatedAt": "2026-01-02T00:00:00Z",
                "counts": { "ready": 1, "blocked": 0, "waiting": 0, "done": 1 },
                "tasks": [
                    {
                        "id": "t-aaaa", "title": "root", "thread": "demo", "status": "done",
                        "waitingOn": null, "depends": [], "due": null, "defer": null,
                        "priority": "normal", "created": "2026-01-01T00:00:00Z",
                        "closed": "2026-01-02T00:00:00Z",
                        "computed": {
                            "ready": false, "blocked": false, "deferred": false, "overdue": false,
                            "resurfaced": false, "blockers": [], "dependents": ["t-bbbb"]
                        },
                        "body": null
                    },
                    {
                        "id": "t-bbbb", "title": "child", "thread": "demo", "status": "open",
                        "waitingOn": null, "depends": ["t-aaaa"], "due": null, "defer": null,
                        "priority": "normal", "created": "2026-01-01T00:00:00Z", "closed": null,
                        "computed": {
                            "ready": true, "blocked": false, "deferred": false, "overdue": false,
                            "resurfaced": false, "blockers": [], "dependents": []
                        },
                        "body": null
                    }
                ]
            }
        """.trimIndent()
        val export = reader.decodeFromString(ExportDto.serializer(), payload)

        assertEquals(1, export.counts.done)
        assertEquals(1, export.counts.ready)
        assertEquals(2, export.tasks.size)

        val root = export.tasks.first { it.id == "t-aaaa" }
        val child = export.tasks.first { it.id == "t-bbbb" }
        // The dependency edge, both directions, using the stored vocabulary (ADR-008).
        assertEquals(listOf("t-bbbb"), root.computed.dependents)
        assertEquals(listOf("t-aaaa"), child.depends)
        assertEquals(emptyList(), child.computed.blockers) // root is done → nothing blocks the child
        assertTrue(child.computed.ready)
    }
}
