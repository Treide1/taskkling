@file:OptIn(ExperimentalCli::class, ExperimentalForeignApi::class)

package io.taskkling.cli

import io.taskkling.contract.ExportDto
import io.taskkling.contract.TaskDto
import io.taskkling.core.AddArgs
import io.taskkling.core.addTask
import io.taskkling.core.appendBody
import io.taskkling.core.buildExport
import io.taskkling.core.cleanup
import io.taskkling.core.CliOutput
import io.taskkling.core.computeAll
import io.taskkling.core.deleteTask
import io.taskkling.core.ExitCode
import io.taskkling.core.fetchLatestTag
import io.taskkling.core.initWorkspace
import io.taskkling.core.installLocalBin
import io.taskkling.core.isStdoutInteractive
import io.taskkling.core.isUpdateCheckCacheFresh
import io.taskkling.core.linkDepends
import io.taskkling.core.loadTasks
import io.taskkling.core.loadUpdateCheckCache
import io.taskkling.core.loadUserConfig
import io.taskkling.core.markDone
import io.taskkling.core.markDropped
import io.taskkling.core.materializeUserConfig
import io.taskkling.core.MutationResult
import io.taskkling.core.productionUiEffects
import io.taskkling.core.productionUninstallEffects
import io.taskkling.core.productionUpdateEffects
import io.taskkling.core.rawFile
import io.taskkling.core.readBody
import io.taskkling.core.reopenTask
import io.taskkling.core.resolveUpdateCheckEnabled
import io.taskkling.core.restoreTask
import io.taskkling.core.runUiVerb
import io.taskkling.core.runUninstallVerb
import io.taskkling.core.runUpdateVerb
import io.taskkling.core.saveUpdateCheckCache
import io.taskkling.core.seedDemoTasks
import io.taskkling.core.SetArgs
import io.taskkling.core.setFields
import io.taskkling.core.Status
import io.taskkling.core.sweepStaleOldExecutableForRunningBinary
import io.taskkling.core.SystemNet
import io.taskkling.core.Task
import io.taskkling.core.Taskkling
import io.taskkling.core.TkError
import io.taskkling.core.toDto
import io.taskkling.core.uiAppDir
import io.taskkling.core.uiFailureMessage
import io.taskkling.core.uiRuntimeDir
import io.taskkling.core.UiVerbArgs
import io.taskkling.core.UninstallVerbArgs
import io.taskkling.core.unlinkDepends
import io.taskkling.core.UpdateCheckCache
import io.taskkling.core.updateNotifierLine
import io.taskkling.core.UpdateVerbArgs
import io.taskkling.core.waitTask
import io.taskkling.core.Workspace
import io.taskkling.core.writeBody
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import kotlinx.cli.multiple
import kotlinx.cli.ExperimentalCli
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.datetime.Clock
import platform.posix.fputs
import platform.posix.stderr
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.system.exitProcess

private val json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
}

/** Write a line to stderr (diagnostics) so stdout stays clean for scriptable output. */
private fun eprintln(message: String) {
    fputs(message + "\n", stderr)
}

/**
 * Global flags parsed from the **leading** position (before the subcommand),
 * git-style: `taskkling --root <path> --quiet <verb> …` (PRD §10.1). They are
 * also accepted *after* the verb (per-subcommand options below), so both forms
 * work; the explicit per-verb value wins when given in both places.
 */
private object GlobalFlags {
    var root: String? = null
    var quiet: Boolean = false
    var noColor: Boolean = false
}

/**
 * Consume recognised global flags that appear *before* the subcommand name and
 * fold them into [GlobalFlags], returning the remaining argv (subcommand + its
 * args) for kotlinx-cli. The parse itself is the pure, unit-tested
 * [parseLeadingGlobals] (CliHelpers.kt); this thin wrapper just applies the result
 * to the process-global [GlobalFlags].
 */
private fun extractLeadingGlobals(args: Array<String>): Array<String> {
    val g = parseLeadingGlobals(args)
    GlobalFlags.root = g.root
    GlobalFlags.quiet = g.quiet
    GlobalFlags.noColor = g.noColor
    return g.rest.toTypedArray()
}

/**
 * Base for every verb: carries the global flags (PRD §10.1) and renders a thrown
 * [TkError] to the matching exit code. Anything unexpected becomes exit `1`.
 */
private abstract class TkCommand(name: String, description: String) : Subcommand(name, description) {
    private val localRoot by option(ArgType.String, "root", description = "Workspace root (override discovery)")
    private val localQuiet by option(ArgType.Boolean, "quiet", "q", description = "Suppress non-essential output").default(false)

    /** Resolve a global flag from the per-verb position first, then the leading position. */
    val root: String? get() = localRoot ?: GlobalFlags.root
    val quiet: Boolean get() = localQuiet || GlobalFlags.quiet

    final override fun execute() {
        try {
            run()
        } catch (e: TkError) {
            eprintln("taskkling: ${e.message}")
            exitProcess(e.exit.code)
        } catch (e: Exception) {
            eprintln("taskkling: unexpected error: ${e.message}")
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

    /**
     * The shared emission rule (PRD §7.3): the full export when
     * `--export-on-success`, else the terse [text] — which `--quiet` suppresses
     * unless it is [essential]. [text] is lazy so the suppressed case never
     * builds a summary nobody reads.
     */
    fun emitExportOr(export: ExportDto?, essential: Boolean = false, text: () -> String) {
        when {
            export != null -> println(json.encodeToString(ExportDto.serializer(), export))
            !quiet || essential -> println(text())
        }
    }

    /**
     * Emit the mutation's result: the export, else the affected id. [idIsEssential]
     * marks the case where the id *is* the primary result (`add` mints a fresh id you
     * can't know beforehand and capture to wire deps), so it prints even under `-q`.
     */
    fun emit(result: MutationResult, idIsEssential: Boolean = false): Unit =
        emitExportOr(result.export, idIsEssential) { result.task.id }
}

/**
 * `init [--local-bin] [--demo-mode]` — scaffold a workspace in the cwd (PRD §10.7);
 * optionally self-install the binary and/or seed the demo sandbox (ADR-017).
 */
private class InitCmd : TkCommand("init", "Scaffold a taskkling workspace (.taskkling/ + tasks/)") {
    val localBin by option(
        ArgType.Boolean, "local-bin",
        description = "Also install the running binary into <root>/.taskkling/bin and drop ./taskkling wrappers",
    ).default(false)
    val demoMode by option(
        ArgType.Boolean, "demo-mode", "dm",
        description = "Seed a self-contained demo backlog (kept under .taskkling/tasks) to explore risk-free",
    ).default(false)

    override fun run() {
        val result = initWorkspace(root, demoLayout = demoMode)
        if (!quiet) {
            val verb = if (result.alreadyExisted) "already initialized" else "initialized taskkling workspace"
            println("$verb: ${result.root}")
        }
        if (demoMode) {
            // Seed only a task-free workspace: `init` stays idempotent, and a
            // re-run (e.g. a worktree-creation hook firing twice) can never dump
            // demo data into a store someone has started using for real.
            val ws = Workspace.discover(result.root.toString())
            if (ws.allKnownIds().isEmpty()) {
                val n = ws.seedDemoTasks()
                if (!quiet) println("seeded $n demo tasks (sandbox data - mutate freely)")
            } else if (!quiet) {
                println("demo seed skipped: workspace already contains tasks")
            }
        }
        if (localBin) {
            val installed = installLocalBin(result.root)
            if (!quiet) println("installed local binary: ${installed.binary} (taskkling ${installed.version})")
        }
    }
}

/** `add "<title>" [flags]` — create a task, print its id (PRD §10.4). */
private class AddCmd : MutationCommand("add", "Create a task; prints the new id") {
    val title by argument(ArgType.String, description = "Task title")
    val thread by option(ArgType.String, "thread", "t", description = "Grouping label")
    val status by option(ArgType.String, "status", "s", description = "Initial status: open | waiting | done | dropped (default open)")
    val req by option(ArgType.String, "req", description = "External requirement text (independent of status)")
    val depends by option(ArgType.String, "depends", "d", description = "Dependency ids (comma-separated and/or repeatable)").multiple()
    val due by option(ArgType.String, "due", description = "Deadline (e.g. 2026-07-31T23:59:00Z or 2026-07-31)")
    val defer by option(ArgType.String, "defer", description = "Not-before datetime (suppresses readiness)")
    val priority by option(ArgType.String, "priority", "p", description = "low | normal | high")
    val body by option(ArgType.String, "body", "b", description = "Body text; - reads the body from stdin")

    override fun run() {
        val ws = Workspace.discover(root)
        emit(
            ws.addTask(
                AddArgs(
                    title = title,
                    thread = thread,
                    status = status,
                    req = req,
                    depends = flattenDepends(depends),
                    due = due,
                    defer = defer,
                    priority = priority,
                    body = body?.let { bodyArg(it) },
                ),
                exportAfter = exportOnSuccess,
            ),
            idIsEssential = true,
        )
    }
}

/**
 * `get <id> [--body|--info|-f field…] [--json]` — read a task. The common case,
 * bare `get <id>`, prints the raw `.md` **verbatim** (frontmatter + body) so an
 * agent picking up a task sees the whole brief in one cheap read (this subsumes the
 * former `show`). Narrowings: `--body`/`-b` the body alone; `--info`/`-i` the parsed
 * fields — stored **and** computed, the value-add over the raw frontmatter; `-f`
 * plucks named field values. `--json` emits the structured task (body unless `--info`).
 */
private class GetCmd : TkCommand("get", "Print a task verbatim (.md); --body / --info / -f narrow it") {
    val id by argument(ArgType.String, description = "Task id")
    val body by option(ArgType.Boolean, "body", "b", description = "Print the body only (frontmatter stripped)").default(false)
    val info by option(ArgType.Boolean, "info", "i", description = "Print parsed fields only (stored + computed)").default(false)
    val fields by option(ArgType.String, "field", "f", description = "Field to print (repeatable)").multiple()
    val asJson by option(ArgType.Boolean, "json", description = "Emit the task as a JSON object").default(false)

    override fun run() {
        val ws = Workspace.discover(root)
        // The parsed-field projections (--json / --info / -f) need the computed graph.
        if (asJson || info || fields.isNotEmpty()) {
            val all = ws.loadTasks()
            val computed = ws.computeAll(all)
            val task = all.firstOrNull { it.id == id } ?: throw TkError(ExitCode.USAGE, "unknown id '$id'")
            val c = computed.getValue(id)
            when {
                asJson -> println(json.encodeToString(TaskDto.serializer(), task.toDto(c, includeBody = !info)))
                fields.isNotEmpty() -> {
                    val map = fieldMap(task, c)
                    fields.forEach { name ->
                        println(map[name] ?: throw TkError(ExitCode.USAGE, "unknown field '$name'"))
                    }
                }
                else -> fieldMap(task, c).forEach { (k, v) -> println("$k: $v") }
            }
            return
        }
        // Body-only, else the common case: the raw .md verbatim.
        if (body) {
            println(ws.readBody(id))
        } else {
            val raw = ws.rawFile(id) ?: throw TkError(ExitCode.USAGE, "unknown id '$id'")
            print(raw)
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

/** `wait <id> [--until <dt>] [--req "<text>"]` — set waiting, fold defer (PRD §10.5). */
private class WaitCmd : MutationCommand("wait", "Set status=waiting; optionally defer (--until) and external requirement (--req)") {
    val id by argument(ArgType.String, description = "Task id")
    val until by option(ArgType.String, "until", description = "Defer until this datetime (suppresses readiness)")
    // ADR-018: the external requirement is independent of status; --req here is
    // sugar (set it in one call while parking to waiting). `set --req` sets it in
    // any status. --on is kept as a hidden back-compat alias for existing scripts.
    val req by option(ArgType.String, "req", description = "External requirement text (independent of status)")
    val on by option(ArgType.String, "on", description = "Deprecated alias for --req")
    override fun run() = emit(Workspace.discover(root).waitTask(id, until, req ?: on, exportOnSuccess))
}

/** `link <id> --depends <dep>` — add a dependency edge; cycle-checked (PRD §10.6). */
private class LinkCmd : MutationCommand("link", "Add a dependency edge (<id> depends on <dep>)") {
    val id by argument(ArgType.String, description = "Task id")
    val depends by option(ArgType.String, "depends", "d", description = "Dependency id(s) to add (comma-separated and/or repeatable)").multiple()
    override fun run() {
        val deps = flattenDepends(depends)
        if (deps.isEmpty()) throw TkError(ExitCode.USAGE, "link needs at least one --depends id")
        emit(Workspace.discover(root).linkDepends(id, deps, exportOnSuccess))
    }
}

/** `unlink <id> --depends <dep>` — remove a dependency edge (PRD §10.6). */
private class UnlinkCmd : MutationCommand("unlink", "Remove a dependency edge (<id> depends on <dep>)") {
    val id by argument(ArgType.String, description = "Task id")
    val depends by option(ArgType.String, "depends", "d", description = "Dependency id(s) to remove (comma-separated and/or repeatable)").multiple()
    override fun run() {
        val deps = flattenDepends(depends)
        if (deps.isEmpty()) throw TkError(ExitCode.USAGE, "unlink needs at least one --depends id")
        emit(Workspace.discover(root).unlinkDepends(id, deps, exportOnSuccess))
    }
}

/** `set <id> [--<field> …] [--clear <field>…]` — atomic multi-field edit (PRD §10.4). */
private class SetCmd : MutationCommand("set", "Edit metadata fields (title/thread/status/req/due/defer/priority)") {
    val id by argument(ArgType.String, description = "Task id")
    val title by option(ArgType.String, "title", description = "Set title")
    val thread by option(ArgType.String, "thread", "t", description = "Set thread (empty clears)")
    val status by option(ArgType.String, "status", "s", description = "Set status open|waiting|done|dropped")
    val req by option(ArgType.String, "req", description = "Set external requirement (empty clears); independent of status")
    val due by option(ArgType.String, "due", description = "Set due datetime (empty clears)")
    val defer by option(ArgType.String, "defer", description = "Set defer datetime (empty clears)")
    val priority by option(ArgType.String, "priority", "p", description = "Set priority low|normal|high")
    val clear by option(ArgType.String, "clear", description = "Field to unset (repeatable)").multiple()

    override fun run() {
        if (title == null && thread == null && status == null && req == null &&
            due == null && defer == null && priority == null && clear.isEmpty()
        ) {
            throw TkError(ExitCode.USAGE, "set needs at least one field to change")
        }
        val ws = Workspace.discover(root)
        emit(ws.setFields(id, SetArgs(title, thread, status, req, due, defer, priority, clear), exportOnSuccess))
    }
}

/** `write <id> "<text>"` — replace the body (PRD §10.6); `-` reads stdin. */
private class WriteCmd : MutationCommand("write", "Replace a task's body (use - to read stdin)") {
    val id by argument(ArgType.String, description = "Task id")
    val text by argument(ArgType.String, description = "Body text, or - for stdin")
    override fun run() = emit(Workspace.discover(root).writeBody(id, bodyArg(text), exportOnSuccess))
}

/** `append <id> "<text>"` — append to the body (PRD §10.6); `-` reads stdin. */
private class AppendCmd : MutationCommand("append", "Append to a task's body (use - to read stdin)") {
    val id by argument(ArgType.String, description = "Task id")
    val text by argument(ArgType.String, description = "Body text, or - for stdin")
    override fun run() = emit(Workspace.discover(root).appendBody(id, bodyArg(text), exportOnSuccess))
}

/** `delete <id>` — move to trash, prune dependents; validation-free (PRD §9.5, §10.5). */
private class DeleteCmd : MutationCommand("delete", "Move a task to trash and prune it from dependents") {
    val id by argument(ArgType.String, description = "Task id")
    override fun run() = emit(Workspace.discover(root).deleteTask(id, exportOnSuccess))
}

/** `restore <id>` — bring a task back from trash/archive; report non-rewired edges (PRD §9.5). */
private class RestoreCmd : MutationCommand("restore", "Restore a task from trash/archive to the active set") {
    val id by argument(ArgType.String, description = "Task id")
    override fun run() {
        val result = Workspace.discover(root).restoreTask(id, exportOnSuccess)
        if (result.droppedEdges.isNotEmpty() && !quiet) {
            eprintln("taskkling: dropped ${result.droppedEdges.size} dangling dependency edge(s): ${result.droppedEdges.joinToString(",")}")
        }
        emitExportOr(result.export) { result.task.id }
    }
}

/** `cleanup [--only <status>…] [--delete-before <dt>] [--include-archive]` — sweep closed → archive; purge trash (PRD §10.7). */
private class CleanupCmd : MutationCommand("cleanup", "Sweep closed tasks to archive; optionally purge old trash") {
    val only by option(ArgType.String, "only", description = "Sweep only these closed statuses: done|dropped (comma-separated and/or repeatable)").multiple()
    val deleteBefore by option(ArgType.String, "delete-before", description = "Purge trash entries closed before this datetime")
    val includeArchive by option(ArgType.Boolean, "include-archive", description = "Also purge archive entries with --delete-before").default(false)

    override fun run() {
        val result = Workspace.discover(root).cleanup(deleteBefore, includeArchive, exportOnSuccess, parseOnlyStatuses(only))
        emitExportOr(result.export) {
            val retained = if (result.retained > 0) ", retained ${result.retained} (still-referenced archive)" else ""
            "archived ${result.archived}, purged ${result.purged}$retained"
        }
    }
}

/** `doctor [--fix]` — integrity + logical-resolution scan (PRD §7.5; stub, post-v0.1 §19). */
private class DoctorCmd : TkCommand("doctor", "Integrity + logical-resolution scan (stub)") {
    @Suppress("unused")
    val fix by option(ArgType.Boolean, "fix", description = "Apply deterministic fixes (stub)").default(false)
    override fun run() = eprintln("taskkling: doctor is a post-v0.1 stub (PRD §19) — not yet implemented")
}

/**
 * The process's own stdout/stderr, as the `:core` verb runners' output sink.
 * The runners never print themselves — their wording is behavior, asserted in
 * core's tests — so this is the one place their lines reach the terminal.
 */
private object StdCliOutput : CliOutput {
    override fun out(line: String): Unit = println(line)
    override fun err(line: String): Unit = eprintln(line)
}

/**
 * The opt-in notifier's cached "is a newer release known?" lookup (ADR-002 §3
 * / ADR-005): reuse the cache if it's still fresh (~24h, [isUpdateCheckCacheFresh]);
 * otherwise perform ONE live lookup via [fetchLatestTag] and refresh the cache
 * on success. Silent-fail: a failed lookup returns null and leaves the cache
 * untouched — no retry storm, no stale data invented.
 */
private fun resolveLatestVersionCached(currentVersion: String): String? {
    val cache = loadUpdateCheckCache()
    val now = Clock.System.now().epochSeconds
    if (isUpdateCheckCacheFresh(cache, now)) return cache!!.latestVersion
    val latest = fetchLatestTag(SystemNet, "taskkling/$currentVersion") ?: return null
    saveUpdateCheckCache(UpdateCheckCache(now, latest))
    return latest
}

/**
 * `--version`'s passive notifier surface (ADR-005, default reversed by
 * ADR-006): fires only when `update_check` is enabled in scope — a workspace's
 * `.taskkling/config.toml` overrides the user-level one
 * ([resolveUpdateCheckEnabled]) — AND stdout is an interactive terminal.
 * The check now defaults ON (ADR-006), so the TTY gate is what keeps `--version`
 * local/offline/fast for the scripts and CI that scrape it: a non-interactive
 * stdout returns BEFORE any network or cache IO, so no call is made and no cache
 * is written. Best-effort end to end: any failure (workspace discovery, config
 * parse, TTY probe, network, cache IO) is swallowed so a broken check can never
 * break `--version` itself.
 */
private fun printVersionNotifierIfEnabled(currentVersion: String) {
    try {
        val workspaceConfig = try { Workspace.discover(null).config } catch (_: TkError) { null }
        val userConfig = loadUserConfig()
        if (!resolveUpdateCheckEnabled(userConfig, workspaceConfig)) return
        // ADR-006's TTY gate: with the check on by default, only an interactive
        // `--version` may touch the network. Decide this BEFORE any IO so CI /
        // pipes / `| grep` / docker-build stay truly offline (no call, no cache write).
        if (!isStdoutInteractive()) return
        val latest = resolveLatestVersionCached(currentVersion)
        val line = updateNotifierLine(true, currentVersion, latest) ?: return
        println("taskkling: $line")
    } catch (_: Exception) {
        // best-effort, silent-fail (ADR-002/005/006) — never break `--version`.
    }
}

/**
 * `update [--global|--local] [--check] [--version vX.Y.Z]` — self-replace the
 * running binary (or, with a tier flag, another tier's copy) with the latest
 * (or a pinned) GitHub release, resolve + SHA-256-verify ported from
 * `install.sh`/`install.ps1` (ADR-002). `--global`/`--local` mirror
 * `uninstall`'s tier targeting (ADR-007), update-only: they never install into
 * a tier that has none. `--check` only reports whether a newer release exists;
 * it never installs, always runs (no config gate — it's unambiguously
 * user-initiated), and is tier-agnostic (rejects `--global`/`--local`).
 */
private class UpdateCmd : TkCommand("update", "Self-update the running binary; --global/--local target a tier; --check only reports") {
    val check by option(ArgType.Boolean, "check", description = "Report whether a newer release is available; does not install").default(false)
    val global by option(ArgType.Boolean, "global", description = "Update the global-tier binary explicitly (update-only)").default(false)
    val local by option(ArgType.Boolean, "local", description = "Update the per-project local-bin binary explicitly (update-only)").default(false)
    val versionOverride by option(
        ArgType.String, "version",
        description = "Update to a specific release tag (e.g. v0.3.0), skipping the latest-version lookup",
    )

    override fun run() {
        if (global && local) throw TkError(ExitCode.USAGE, "--global and --local are mutually exclusive")
        // --check reports latest-vs-running and touches no binary, so a tier flag is meaningless here (ADR-007).
        if (check && (global || local)) {
            throw TkError(ExitCode.USAGE, "--check reports on the running binary and cannot be combined with --global/--local")
        }
        runUpdateVerb(
            UpdateVerbArgs(check = check, global = global, local = local, versionOverride = versionOverride, quiet = quiet, root = root),
            productionUpdateEffects(),
            StdCliOutput,
        )
    }
}

/**
 * `taskkling ui [--fetch-only]` — launch the desktop UI (ADR-010). Lazy fetch
 * pinned to THIS binary's version tag: on first use (or after an update) it
 * downloads its platform's uberjar + jlink runtime from the CLI's own release,
 * verifies both against `SHA256SUMS`, installs them atomically (temp → verify
 * → rename) into the version-keyed cache (ADR-005 home, layout per
 * [uiAppDir]/[uiRuntimeDir]), then spawns `<runtime>/bin/java -jar <jar>
 * <resolved-root>` DETACHED — one confirmation line and the prompt returns;
 * UI stdout/stderr go to the cache log. The verb owns its whole error
 * surface: headless sessions are refused pre-spawn (naming `--fetch-only`,
 * which works headless), a corrupt cache self-heals with ONE silent re-fetch,
 * and terminal failures emit exactly one actionable message naming the cause
 * and log path ([uiFailureMessage]). After a successful launch, stale cache
 * entries are pruned best-effort (locked dirs skipped, retried next launch —
 * ADR-011). `--fetch-only` is the ONLY flag: pinning makes `--version` /
 * `--check` meaningless here, and `update` stays CLI-only.
 */
private class UiCmd : TkCommand("ui", "Launch the desktop UI (fetched on first use, pinned to this CLI's version)") {
    val fetchOnly by option(
        ArgType.Boolean, "fetch-only",
        description = "Download and verify the UI without launching (works headless; prefetch before going offline)",
    ).default(false)

    override fun run() {
        runUiVerb(UiVerbArgs(fetchOnly = fetchOnly, quiet = quiet, root = root), productionUiEffects(), StdCliOutput)
    }
}

/**
 * `uninstall [--global|--local] [--purge] [-y]` — the symmetric inverse of
 * install (ADR-004): removes the tier-resolved binary, the `PATH` entry
 * install(.sh/.ps1) added, and — GLOBAL tier only (ADR-011) — the user-level
 * cache home (UI jars, runtime images, update-check state: machine-replaceable
 * tool bytes, best-effort, locked leftovers reported by path); NEVER the
 * workspace's data — `.taskkling/` plus the
 * resolved tasks dir ([Workspace.purgePlan], t-qoyn) — unless `--purge` says so
 * explicitly, on the command line, in every
 * mode. Interactive by default (states consequences, then asks); `-y` runs
 * the safe scope (binary + `PATH`) non-interactively; `--purge -y` is the
 * only non-interactive way to also destroy data, and interactive `--purge`
 * still confirms that wipe on its own, separately.
 */
private class UninstallCmd : TkCommand("uninstall", "Remove the taskkling binary, PATH entry + user cache; --purge also deletes .taskkling/") {
    val global by option(ArgType.Boolean, "global", description = "Target the global-tier binary explicitly").default(false)
    val local by option(ArgType.Boolean, "local", description = "Target the per-project local-bin binary explicitly").default(false)
    val purge by option(
        ArgType.Boolean, "purge",
        description = "Also PERMANENTLY delete the .taskkling/ workspace (tasks, config, caches) — irreversible",
    ).default(false)
    val yes by option(ArgType.Boolean, "yes", "y", description = "Run non-interactively (safe scope only unless --purge)").default(false)

    override fun run() {
        if (global && local) throw TkError(ExitCode.USAGE, "--global and --local are mutually exclusive")
        runUninstallVerb(
            UninstallVerbArgs(global = global, local = local, purge = purge, yes = yes, quiet = quiet, root = root),
            productionUninstallEffects(),
            StdCliOutput,
        )
    }
}

/**
 * `config init` — materialize the user-level `config.toml` write-if-absent and
 * print its path (ADR-006). Its purpose is discoverability: with the
 * `update_check` notifier on by default, this file surfaces the OFF switch (and
 * the config's location) right after install. Re-running never clobbers a user
 * who edited it. Both installers exec this so the file exists from the first run.
 *
 * `config` is a verb GROUP: kotlinx-cli runs a parent subcommand's `execute()`
 * *after* the selected child's (see ArgParser.parse), so the child flips
 * [ConfigCmd.handledBySubcommand] and the parent stays silent; a bare `config`
 * with no subcommand reports the usage error itself.
 */
private class ConfigCmd : Subcommand("config", "Manage user-level configuration (config init)") {
    var handledBySubcommand: Boolean = false

    init {
        // Stop at the first verb and hand the rest down, so `config init` dispatches to the
        // nested `init` rather than colliding with the top-level `init` (kotlinx-cli's
        // non-strict mode keeps scanning and would overwrite the selected subcommand).
        strictSubcommandOptionsOrder = true
        subcommands(ConfigInitCmd(this))
    }

    override fun execute() {
        if (handledBySubcommand) return
        eprintln("taskkling: 'config' needs a subcommand (try: taskkling config init)")
        exitProcess(ExitCode.USAGE.code)
    }
}

/** `config init` — write the user-level config.toml if absent; always prints its absolute path. */
private class ConfigInitCmd(private val parent: ConfigCmd) :
    TkCommand("init", "Create the user-level config.toml (write-if-absent); prints its path") {
    override fun run() {
        parent.handledBySubcommand = true
        val result = materializeUserConfig()
        if (!quiet) {
            eprintln("taskkling: user config ${if (result.created) "created" else "already present"}")
        }
        println(result.path) // essential output (the path) → stdout, greppable
    }
}

/** `export [--include-body] [--archived] [--ics]` — full JSON contract (PRD §12). */
private class ExportCmd : TkCommand("export", "Print the full JSON export") {
    val includeBody by option(ArgType.Boolean, "include-body", description = "Add a per-task body field").default(false)
    val archived by option(ArgType.Boolean, "archived", description = "Include the archive subtree").default(false)
    val ics by option(ArgType.Boolean, "ics", description = "Emit an iCalendar feed from due dates (stub)").default(false)

    override fun run() {
        if (ics) {
            eprintln("taskkling: export --ics is a post-v0.1 stub (PRD §19) — not yet implemented")
            return
        }
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
    val asJson by option(ArgType.Boolean, "json", description = "Emit JSON array of tasks").default(false)
    val status by option(ArgType.String, "status", "s", description = "Filter by stored status")
    val thread by option(ArgType.String, "thread", "t", description = "Filter by thread")
    val ready by option(ArgType.Boolean, "ready", description = "Only ready tasks").default(false)
    val blocked by option(ArgType.Boolean, "blocked", description = "Only blocked tasks").default(false)
    val waiting by option(ArgType.Boolean, "waiting", description = "Only waiting tasks").default(false)
    val blocking by option(ArgType.String, "blocking", description = "Tasks blocking <id> — its upstream depends")
    val blockedBy by option(ArgType.String, "blocked-by", description = "Tasks blocked by <id> — its downstream dependents")

    override fun run() {
        val ws = Workspace.discover(root)
        val all = ws.loadTasks(includeArchived = archived)
        val computed = ws.computeAll(all)
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

/** Entry point for the native `taskkling` binary (PRD §6.2, §10). */
public fun main(rawArgs: Array<String>) {
    // Recover argv losslessly (t-jagq): on mingwX64, Kotlin/Native's own decode of
    // rawArgs is ANSI-lossy (non-ASCII titles/text passed as literal CLI arguments
    // arrive as `?`); platformArgv corrects that on mingw and passes through
    // unchanged everywhere else. See Argv.kt.
    val args = platformArgv(rawArgs)

    // Best-effort startup hook (ADR-002): sweep a stale `taskkling.exe.old`
    // sibling left by a prior Windows `update` run that couldn't delete it
    // immediately (still locked while that process was exiting). No-op on
    // POSIX and on a fresh install; never throws, so it can't break a normal
    // command even if it fails.
    sweepStaleOldExecutableForRunningBinary()

    // Hidden, undocumented self-test seam (never in help/usage, no effect on any
    // normal command): forces the ktor engine into the link graph and proves an
    // HTTPS round trip on the platform HTTP client.
    if (args.size == 2 && args[0] == "__http-selftest") {
        val (status, body) = io.taskkling.core.httpGetTextBlocking(args[1])
        println(status)
        println(body.take(200))
        return
    }
    if (args.size == 1 && (args[0] == "--version" || args[0] == "-v")) {
        println("taskkling ${Taskkling.VERSION}")
        // Passive notifier (ADR-005/006): on by default, but TTY-gated inside —
        // a non-interactive `--version` (CI / pipes / scripts) makes no network
        // call at all, so it stays local/offline/fast.
        printVersionNotifierIfEnabled(Taskkling.VERSION)
        return
    }
    // Fold leading global flags (--root/--quiet/--no-color) into GlobalFlags so
    // they work git-style before the verb; per-verb forms still work after it.
    val rest = extractLeadingGlobals(args)
    // strictSubcommandOptionsOrder: stop at the first verb and pass the remainder to it,
    // rather than scanning every token for a subcommand name. Required for the nested
    // `config init` (its `init` would otherwise collide with the top-level `init`), and it
    // also fixes a latent misparse where an argument value equal to a verb name (e.g.
    // `add list`) was consumed as a subcommand. Leading globals are already extracted above.
    val parser = ArgParser("taskkling", strictSubcommandOptionsOrder = true)
    parser.subcommands(
        InitCmd(), AddCmd(), ListCmd(), ExportCmd(),
        GetCmd(),
        DoneCmd(), DropCmd(), ReopenCmd(), WaitCmd(),
        LinkCmd(), UnlinkCmd(),
        SetCmd(), WriteCmd(), AppendCmd(),
        DeleteCmd(), RestoreCmd(), CleanupCmd(), DoctorCmd(), UpdateCmd(), UiCmd(), UninstallCmd(),
        ConfigCmd(),
    )
    parser.parse(rest)
}
