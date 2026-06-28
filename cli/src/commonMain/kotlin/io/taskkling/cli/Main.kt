package io.taskkling.cli

import io.taskkling.contract.TaskDto
import io.taskkling.core.AddArgs
import io.taskkling.core.Status
import io.taskkling.core.Task
import io.taskkling.core.TkError
import io.taskkling.core.ExitCode
import io.taskkling.core.Taskkling
import io.taskkling.core.Workspace
import io.taskkling.core.addTask
import io.taskkling.core.buildExport
import io.taskkling.core.computeAll
import io.taskkling.core.initWorkspace
import io.taskkling.core.loadTasks
import io.taskkling.core.toDto
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

private val json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
}

/**
 * Base for every verb: carries the global flags (PRD §10.1) and renders a thrown
 * [TkError] to the matching exit code. Anything unexpected becomes exit `1`.
 */
private abstract class TkCommand(name: String, description: String) : Subcommand(name, description) {
    val root by option(ArgType.String, "root", description = "Workspace root (override discovery)")
    val quiet by option(ArgType.Boolean, "quiet", "q", description = "Suppress non-essential output").default(false)

    final override fun execute() {
        try {
            run()
        } catch (e: TkError) {
            println("taskkling: ${e.message}")
            exitProcess(e.exit.code)
        } catch (e: Exception) {
            println("taskkling: unexpected error: ${e.message}")
            exitProcess(1)
        }
    }

    abstract fun run()
}

/** `init` — scaffold a workspace in the cwd (PRD §10.7). */
private class InitCmd : TkCommand("init", "Scaffold a taskkling workspace (.taskkling/ + tasks/)") {
    override fun run() {
        val result = initWorkspace(root)
        if (!quiet) {
            val verb = if (result.alreadyExisted) "already initialized" else "initialized taskkling workspace"
            println("$verb: ${result.root}")
        }
    }
}

/** `add "<title>" [flags]` — create a node, print its id (PRD §10.4). */
private class AddCmd : TkCommand("add", "Create a task; prints the new id") {
    val title by argument(ArgType.String, description = "Task title")
    val thread by option(ArgType.String, "thread", "t", description = "Grouping label")
    val depends by option(ArgType.String, "depends", "d", description = "Comma-separated dependency ids")
    val due by option(ArgType.String, "due", description = "Deadline (e.g. 2026-07-31T23:59:00Z or 2026-07-31)")
    val defer by option(ArgType.String, "defer", description = "Not-before datetime (suppresses readiness)")
    val priority by option(ArgType.String, "priority", "p", description = "low | normal | high")
    val body by option(ArgType.String, "body", "b", description = "Body text")

    override fun run() {
        val ws = Workspace.discover(root)
        val id = ws.addTask(
            AddArgs(
                title = title,
                thread = thread,
                depends = depends?.split(",").orEmpty(),
                due = due,
                defer = defer,
                priority = priority,
                body = body,
            ),
        )
        println(id)
    }
}

/** `export [--include-body] [--archived]` — full JSON contract (PRD §12). */
private class ExportCmd : TkCommand("export", "Print the full JSON export") {
    val includeBody by option(ArgType.Boolean, "include-body", description = "Add a per-node body field").default(false)
    val archived by option(ArgType.Boolean, "archived", description = "Include the archive subtree").default(false)

    override fun run() {
        val ws = Workspace.discover(root)
        val export = ws.buildExport(includeBody = includeBody, includeArchived = archived)
        println(json.encodeToString(io.taskkling.contract.ExportDto.serializer(), export))
    }
}

/**
 * `list [filters] [--id-only] [--json] [--archived]` — a filtered/sorted
 * collection, `ls -la`-style (PRD §10.2/§10.3). Computed states fold in as
 * filters (`--ready`/`--blocked`/`--waiting`); dependency queries via
 * `--blocking`/`--blocked-by`.
 */
private class ListCmd : TkCommand("list", "List tasks (ls -la style); filters fold computed states") {
    val archived by option(ArgType.Boolean, "archived", description = "Include the archive subtree").default(false)
    val idOnly by option(ArgType.Boolean, "id-only", description = "Print only ids, one per line").default(false)
    val asJson by option(ArgType.Boolean, "json", description = "Emit JSON array of nodes").default(false)
    val status by option(ArgType.String, "status", "s", description = "Filter by stored status")
    val thread by option(ArgType.String, "thread", "t", description = "Filter by thread")
    val ready by option(ArgType.Boolean, "ready", description = "Only ready nodes").default(false)
    val blocked by option(ArgType.Boolean, "blocked", description = "Only blocked nodes").default(false)
    val waiting by option(ArgType.Boolean, "waiting", description = "Only waiting nodes").default(false)
    val blocking by option(ArgType.String, "blocking", description = "Nodes that <id> depends on (upstream)")
    val blockedBy by option(ArgType.String, "blocked-by", description = "Nodes that depend on <id> (downstream)")

    override fun run() {
        val ws = Workspace.discover(root)
        val all = ws.loadTasks(includeArchived = archived)
        val computed = computeAll(all)
        val byId = all.associateBy { it.id }

        val statusFilter = status?.let { Status.from(it) }

        fun refTask(id: String): Task =
            byId[id] ?: throw TkError(ExitCode.USAGE, "unknown id '$id'")

        val blockingSet = blocking?.let { refTask(it).depends.toSet() }
        val blockedBySet = blockedBy?.let { id -> refTask(id); all.filter { id in it.depends }.map { it.id }.toSet() }

        val rows = all.filter { t ->
            val c = computed.getValue(t.id)
            (statusFilter == null || t.status == statusFilter) &&
                (thread == null || t.thread == thread) &&
                (!ready || c.ready) &&
                (!blocked || c.blocked) &&
                (!waiting || t.status == Status.WAITING) &&
                (blockingSet == null || t.id in blockingSet) &&
                (blockedBySet == null || t.id in blockedBySet)
        }.sortedBy { it.created }

        when {
            idOnly -> rows.forEach { println(it.id) }
            asJson -> {
                val dtos = rows.map { it.toDto(computed.getValue(it.id), includeBody = false) }
                println(json.encodeToString(ListSerializer(TaskDto.serializer()), dtos))
            }
            else -> {
                val table = formatTable(rows)
                if (table.isNotEmpty()) println(table)
            }
        }
    }
}

/** Aligned, header-less `ls -la`-style table: id · title · thread · status · attributes. */
private fun formatTable(rows: List<Task>): String {
    if (rows.isEmpty()) return ""
    data class Row(val id: String, val title: String, val thread: String, val status: String, val attrs: String)

    val cells = rows.map { t ->
        Row(
            id = t.id,
            title = if (t.title.length > 50) t.title.take(49) + "…" else t.title,
            thread = t.thread ?: "-",
            status = t.status.wire,
            attrs = buildAttrs(t),
        )
    }
    val idW = cells.maxOf { it.id.length }
    val titleW = cells.maxOf { it.title.length }
    val threadW = cells.maxOf { it.thread.length }
    val statusW = cells.maxOf { it.status.length }

    return cells.joinToString("\n") { r ->
        buildString {
            append(r.id.padEnd(idW)); append("  ")
            append(r.title.padEnd(titleW)); append("  ")
            append(r.thread.padEnd(threadW)); append("  ")
            append(r.status.padEnd(statusW))
            if (r.attrs.isNotEmpty()) {
                append("  "); append(r.attrs)
            }
        }
    }
}

/** Fold the non-empty relational/time fields into one column (PRD §10.2). */
private fun buildAttrs(t: Task): String {
    val parts = ArrayList<String>()
    if (t.depends.isNotEmpty()) parts.add("depends:" + t.depends.joinToString(","))
    if (t.due != null) parts.add("due:${t.due}")
    if (t.defer != null) parts.add("defer:${t.defer}")
    if (t.waitingOn != null) parts.add("waiting:${t.waitingOn}")
    return parts.joinToString("  ")
}

/** Entry point for the native `taskkling` binary (PRD §6.2, §10). */
public fun main(args: Array<String>) {
    if (args.size == 1 && (args[0] == "--version" || args[0] == "-v")) {
        println("taskkling ${Taskkling.VERSION}")
        return
    }
    val parser = ArgParser("taskkling")
    parser.subcommands(InitCmd(), AddCmd(), ListCmd(), ExportCmd())
    parser.parse(args)
}
