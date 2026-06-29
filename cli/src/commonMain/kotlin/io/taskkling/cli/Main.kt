package io.taskkling.cli

import io.taskkling.contract.ExportDto
import io.taskkling.contract.TaskDto
import io.taskkling.core.AddArgs
import io.taskkling.core.Computed
import io.taskkling.core.MutationResult
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
import io.taskkling.core.linkDepends
import io.taskkling.core.loadTasks
import io.taskkling.core.markDone
import io.taskkling.core.markDropped
import io.taskkling.core.rawFile
import io.taskkling.core.reopenTask
import io.taskkling.core.toDto
import io.taskkling.core.unlinkDepends
import io.taskkling.core.waitTask
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import kotlinx.cli.multiple
import kotlinx.cli.required
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

/**
 * Base for mutating verbs: adds `--export-on-success` (PRD §7.3) and the shared
 * output rule — emit the full export when requested, else the terse affected id.
 */
private abstract class MutationCommand(name: String, description: String) : TkCommand(name, description) {
    val exportOnSuccess by option(
        ArgType.Boolean, "export-on-success",
        description = "Emit the full post-mutation export instead of the id",
    ).default(false)

    fun emit(result: MutationResult) {
        val export = result.export
        when {
            export != null -> println(json.encodeToString(ExportDto.serializer(), export))
            !quiet -> println(result.task.id)
        }
    }
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
private class AddCmd : MutationCommand("add", "Create a task; prints the new id") {
    val title by argument(ArgType.String, description = "Task title")
    val thread by option(ArgType.String, "thread", "t", description = "Grouping label")
    val depends by option(ArgType.String, "depends", "d", description = "Comma-separated dependency ids")
    val due by option(ArgType.String, "due", description = "Deadline (e.g. 2026-07-31T23:59:00Z or 2026-07-31)")
    val defer by option(ArgType.String, "defer", description = "Not-before datetime (suppresses readiness)")
    val priority by option(ArgType.String, "priority", "p", description = "low | normal | high")
    val body by option(ArgType.String, "body", "b", description = "Body text")

    override fun run() {
        val ws = Workspace.discover(root)
        emit(
            ws.addTask(
                AddArgs(
                    title = title,
                    thread = thread,
                    depends = depends?.split(",").orEmpty(),
                    due = due,
                    defer = defer,
                    priority = priority,
                    body = body,
                ),
                exportAfter = exportOnSuccess,
            ),
        )
    }
}

/** `show <id>` — print the raw `.md` file verbatim (PRD §10.2). */
private class ShowCmd : TkCommand("show", "Print the raw .md file verbatim") {
    val id by argument(ArgType.String, description = "Task id")

    override fun run() {
        val ws = Workspace.discover(root)
        val raw = ws.rawFile(id) ?: throw TkError(ExitCode.USAGE, "unknown id '$id'")
        print(raw)
    }
}

/** `get <id> [-f field…] [--json]` — parsed field values, stored + computed (PRD §10.2). */
private class GetCmd : TkCommand("get", "Print a node's field values (stored + computed)") {
    val id by argument(ArgType.String, description = "Task id")
    val fields by option(ArgType.String, "field", "f", description = "Field to print (repeatable; default: all)").multiple()
    val asJson by option(ArgType.Boolean, "json", description = "Emit the node as a JSON object").default(false)

    override fun run() {
        val ws = Workspace.discover(root)
        val all = ws.loadTasks()
        val computed = computeAll(all)
        val task = all.firstOrNull { it.id == id } ?: throw TkError(ExitCode.USAGE, "unknown id '$id'")
        val c = computed.getValue(id)

        if (asJson) {
            println(json.encodeToString(TaskDto.serializer(), task.toDto(c, includeBody = false)))
            return
        }
        val map = fieldMap(task, c)
        if (fields.isEmpty()) {
            map.forEach { (k, v) -> println("$k: $v") }
        } else {
            fields.forEach { name ->
                val v = map[name] ?: throw TkError(ExitCode.USAGE, "unknown field '$name'")
                println(v)
            }
        }
    }
}

/** `done <id>` — mark done, stamp closed (PRD §10.5). */
private class DoneCmd : MutationCommand("done", "Mark a task done (stamps closed)") {
    val id by argument(ArgType.String, description = "Task id")
    override fun run() = emit(Workspace.discover(root).markDone(id, exportOnSuccess))
}

/** `drop <id>` — mark dropped, stamp closed (PRD §10.5). */
private class DropCmd : MutationCommand("drop", "Mark a task dropped (stamps closed)") {
    val id by argument(ArgType.String, description = "Task id")
    override fun run() = emit(Workspace.discover(root).markDropped(id, exportOnSuccess))
}

/** `reopen <id>` — return to open, clear closed (PRD §10.5). */
private class ReopenCmd : MutationCommand("reopen", "Reopen a task (clears closed)") {
    val id by argument(ArgType.String, description = "Task id")
    override fun run() = emit(Workspace.discover(root).reopenTask(id, exportOnSuccess))
}

/** `wait <id> [--until <dt>] [--on "<text>"]` — set waiting, fold defer (PRD §10.5). */
private class WaitCmd : MutationCommand("wait", "Set status=waiting; optionally defer (--until) and reason (--on)") {
    val id by argument(ArgType.String, description = "Task id")
    val until by option(ArgType.String, "until", description = "Defer until this datetime (suppresses readiness)")
    val on by option(ArgType.String, "on", description = "waiting_on reason text")
    override fun run() = emit(Workspace.discover(root).waitTask(id, until, on, exportOnSuccess))
}

/** `link <id> --depends <dep>` — add a dependency edge; cycle-checked (PRD §10.6). */
private class LinkCmd : MutationCommand("link", "Add a dependency edge (<id> depends on <dep>)") {
    val id by argument(ArgType.String, description = "Task id")
    val depends by option(ArgType.String, "depends", "d", description = "Dependency id to add").required()
    override fun run() = emit(Workspace.discover(root).linkDepends(id, depends, exportOnSuccess))
}

/** `unlink <id> --depends <dep>` — remove a dependency edge (PRD §10.6). */
private class UnlinkCmd : MutationCommand("unlink", "Remove a dependency edge (<id> depends on <dep>)") {
    val id by argument(ArgType.String, description = "Task id")
    val depends by option(ArgType.String, "depends", "d", description = "Dependency id to remove").required()
    override fun run() = emit(Workspace.discover(root).unlinkDepends(id, depends, exportOnSuccess))
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

/** Ordered stored + computed fields for `get` (PRD §8.1/§8.2). */
private fun fieldMap(t: Task, c: Computed): LinkedHashMap<String, String> {
    fun s(v: String?) = v ?: ""
    return linkedMapOf(
        "id" to t.id,
        "title" to t.title,
        "thread" to s(t.thread),
        "status" to t.status.wire,
        "waiting_on" to s(t.waitingOn),
        "depends" to t.depends.joinToString(","),
        "due" to s(t.due),
        "defer" to s(t.defer),
        "priority" to t.priority.wire,
        "created" to t.created,
        "closed" to s(t.closed),
        "ready" to c.ready.toString(),
        "blocked" to c.blocked.toString(),
        "deferred" to c.deferred.toString(),
        "overdue" to c.overdue.toString(),
        "resurfaced" to c.resurfaced.toString(),
        "blockers" to c.blockers.joinToString(","),
        "dependents" to c.dependents.joinToString(","),
    )
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
    parser.subcommands(
        InitCmd(), AddCmd(), ListCmd(), ExportCmd(),
        ShowCmd(), GetCmd(),
        DoneCmd(), DropCmd(), ReopenCmd(), WaitCmd(),
        LinkCmd(), UnlinkCmd(),
    )
    parser.parse(args)
}
