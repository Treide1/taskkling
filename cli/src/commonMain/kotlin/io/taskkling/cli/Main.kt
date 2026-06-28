package io.taskkling.cli

import io.taskkling.contract.ExportDto
import io.taskkling.core.AddArgs
import io.taskkling.core.ExitCode
import io.taskkling.core.TkError
import io.taskkling.core.Taskkling
import io.taskkling.core.Workspace
import io.taskkling.core.addTask
import io.taskkling.core.computeExport
import io.taskkling.core.initWorkspace
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.default
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
        val deps = depends?.split(",").orEmpty()
        val id = ws.addTask(
            AddArgs(
                title = title,
                thread = thread,
                depends = deps,
                due = due,
                defer = defer,
                priority = priority,
                body = body,
            ),
        )
        println(id)
    }
}

/**
 * `export` (M0 stub). Real implementation — read `tasks/`, derive computed
 * attributes, stream JSON — lands next (PRD §7.2, §12).
 */
private class ExportCmd : TkCommand("export", "Print the JSON export (M0 stub: empty export)") {
    override fun run() {
        println(json.encodeToString(ExportDto.serializer(), computeExport()))
    }
}

/** Entry point for the native `taskkling` binary (PRD §6.2, §10). */
public fun main(args: Array<String>) {
    if (args.size == 1 && (args[0] == "--version" || args[0] == "-v")) {
        println("taskkling ${Taskkling.VERSION}")
        return
    }
    val parser = ArgParser("taskkling")
    parser.subcommands(InitCmd(), AddCmd(), ExportCmd())
    parser.parse(args)
}
