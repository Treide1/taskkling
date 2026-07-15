package io.taskkling.ui

import io.taskkling.contract.ComputedDto
import io.taskkling.contract.CountsDto
import io.taskkling.contract.ExportDto
import io.taskkling.contract.TaskDto

/**
 * The in-memory second adapter behind [TaskklingClient] — an interpreter for the handful
 * of verbs [AppStore] actually emits, over a mutable task list.
 *
 * The computed fields it derives are deliberately NAIVE (blocked = has any blocker, ready =
 * open and unblocked). :core owns the real graph semantics and tests them; reimplementing
 * them here would only test the reimplementation. What these tests target is the store's
 * orchestration — busy sequencing, refresh reconciliation, error routing — none of which
 * reads a computed field.
 *
 * [calls] records every verb in order, so a test can assert the store did *not* shell out.
 * [failOn] injects a CLI failure for a chosen verb.
 */
internal class FakeClient(seed: List<TaskDto> = emptyList()) : TaskklingClient {
    val tasks: MutableList<TaskDto> = seed.toMutableList()
    val calls: MutableList<List<String>> = mutableListOf()
    val writes: MutableList<Pair<String, String>> = mutableListOf()

    /** Task bodies, seedable directly so a read can be tested without a write first. */
    val bodies: MutableMap<String, String> = mutableMapOf()

    /** Return a message to make that invocation fail as the CLI would; null runs it. */
    var failOn: (List<String>) -> String? = { null }

    /** What `--version` reports. Defaults to the UI's own version, i.e. no skew (t-eeze). */
    var reportedVersion: String? = BUILD_VERSION

    private var nextId = 0

    override fun export(includeBody: Boolean): ExportDto {
        calls += listOf("export")
        failOn(listOf("export"))?.let { throw CliException(1, it) }
        return snapshot()
    }

    override fun mutate(args: List<String>): ExportDto {
        calls += args
        failOn(args)?.let { throw CliException(1, it) }
        interpret(args)
        return snapshot()
    }

    override fun body(id: String): String {
        calls += listOf("get", id)
        failOn(listOf("get", id))?.let { throw CliException(1, it) }
        return bodies[id] ?: ""
    }

    override fun writeBody(id: String, text: String) {
        failOn(listOf("write", id))?.let { throw CliException(1, it) }
        writes += id to text
        bodies[id] = text
    }

    override fun version(): String? = reportedVersion

    private fun interpret(args: List<String>) {
        val verb = args.first()
        val flag = { name: String -> args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) } }
        val target = args.getOrNull(1)?.takeUnless { it.startsWith("--") }
        when (verb) {
            "add" -> tasks += task(
                id = "t-new${nextId++}",
                title = flag("--title") ?: "untitled",
                thread = flag("--thread"),
            )
            "done" -> replace(target) { it.copy(status = "done", closed = "2026-01-02T00:00:00Z") }
            "drop" -> replace(target) { it.copy(status = "dropped", closed = "2026-01-02T00:00:00Z") }
            "reopen" -> replace(target) { it.copy(status = "open", closed = null) }
            "set" -> replace(target) { t -> flag("--title")?.let { t.copy(title = it) } ?: t }
            "link" -> replace(target) { it.copy(depends = (it.depends + flag("--depends")!!).distinct()) }
            "unlink" -> replace(target) { it.copy(depends = it.depends - flag("--depends")!!) }
            // Deleting prunes the id out of every dependent, mirroring the CLI.
            "delete" -> {
                val gone = target ?: error("delete needs an id")
                tasks.removeAll { it.id == gone }
                tasks.replaceAll { it.copy(depends = it.depends - gone) }
            }
            "cleanup" -> Unit // archives out of the active set; the store only refreshes off it
            else -> error("FakeClient has no verb '$verb' — teach it one if the store started emitting it")
        }
    }

    private fun replace(id: String?, edit: (TaskDto) -> TaskDto) {
        val i = tasks.indexOfFirst { it.id == id }
        if (i < 0) throw CliException(2, "taskkling: unknown id '$id'")
        tasks[i] = edit(tasks[i])
    }

    private fun snapshot(): ExportDto {
        val computed = tasks.map { t ->
            val blocked = t.depends.isNotEmpty()
            t.copy(
                computed = ComputedDto(
                    ready = !blocked && t.status == "open",
                    blocked = blocked,
                    blockers = t.depends,
                    dependents = tasks.filter { t.id in it.depends }.map { it.id },
                ),
            )
        }
        return ExportDto(
            generatedAt = "2026-01-02T00:00:00Z",
            counts = CountsDto(
                ready = computed.count { it.computed.ready },
                blocked = computed.count { it.computed.blocked },
                done = computed.count { it.status == "done" },
            ),
            tasks = computed,
            defaultThread = "demo",
        )
    }
}

/** A minimal open task; tests override only the fields they care about. */
internal fun task(
    id: String,
    title: String = id,
    thread: String? = null,
    status: String = "open",
    depends: List<String> = emptyList(),
): TaskDto = TaskDto(
    id = id,
    title = title,
    thread = thread,
    status = status,
    depends = depends,
    created = "2026-01-01T00:00:00Z",
    computed = ComputedDto(),
)
