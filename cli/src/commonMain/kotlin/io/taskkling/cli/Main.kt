@file:OptIn(ExperimentalCli::class, ExperimentalForeignApi::class)

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
import io.taskkling.core.appendBody
import io.taskkling.core.buildExport
import io.taskkling.core.cleanup
import io.taskkling.core.computeAll
import io.taskkling.core.deleteTask
import io.taskkling.core.initWorkspace
import io.taskkling.core.installLocalBin
import io.taskkling.core.installNewExecutable
import io.taskkling.core.installOtherExecutable
import io.taskkling.core.currentReleaseAssetName
import io.taskkling.core.findSha256
import io.taskkling.core.GITHUB_API_LATEST_RELEASE
import io.taskkling.core.globalInstallDirPath
import io.taskkling.core.httpGetBytesBlocking
import io.taskkling.core.httpGetTextBlocking
import io.taskkling.core.InstallTier
import io.taskkling.core.isNewerVersion
import io.taskkling.core.isStdoutInteractive
import io.taskkling.core.isUpdateCheckCacheFresh
import io.taskkling.core.linkDepends
import io.taskkling.core.loadTasks
import io.taskkling.core.loadUpdateCheckCache
import io.taskkling.core.loadUserConfig
import io.taskkling.core.markDone
import io.taskkling.core.markDropped
import io.taskkling.core.materializeUserConfig
import io.taskkling.core.normalizeVersionTag
import io.taskkling.core.parseLatestTagName
import io.taskkling.core.rawFile
import io.taskkling.core.readBody
import io.taskkling.core.releaseDownloadBaseUrl
import io.taskkling.core.removeFromWindowsUserPath
import io.taskkling.core.reopenTask
import io.taskkling.core.resolveInstallTier
import io.taskkling.core.resolveUpdateCheckEnabled
import io.taskkling.core.restampLocalBinVersionIfPresent
import io.taskkling.core.restoreTask
import io.taskkling.core.runningExecutablePath
import io.taskkling.core.saveUpdateCheckCache
import io.taskkling.core.setFields
import io.taskkling.core.SetArgs
import io.taskkling.core.Sha256
import io.taskkling.core.sweepStaleOldExecutableForRunningBinary
import io.taskkling.core.toDto
import io.taskkling.core.uninstallOtherBinary
import io.taskkling.core.uninstallRunningBinary
import io.taskkling.core.unlinkDepends
import io.taskkling.core.updateNotifierLine
import io.taskkling.core.UpdateCheckCache
import io.taskkling.core.waitTask
import io.taskkling.core.windowsPathHasEntry
import io.taskkling.core.writeBody
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.Subcommand
import kotlinx.cli.default
import kotlinx.cli.multiple
import kotlinx.cli.required
import kotlinx.cli.ExperimentalCli
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.datetime.Clock
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
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

/** Read all of stdin to end-of-input, for body text supplied as `-` (agent ergonomics). */
private fun readStdin(): String = buildString {
    while (true) {
        val line = readlnOrNull() ?: break
        append(line).append('\n')
    }
}.trimEnd('\n')

/** Resolve body text: a literal `-` means "read the body from stdin". */
private fun bodyArg(text: String): String = if (text == "-") readStdin() else text

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
 * args) for kotlinx-cli. Only the leading run is scanned, so a literal `--root`
 * later in the line (e.g. body text) is never mistaken for a flag.
 */
private fun extractLeadingGlobals(args: Array<String>): Array<String> {
    val rest = ArrayList<String>()
    var i = 0
    var sawVerb = false
    while (i < args.size) {
        val a = args[i]
        if (sawVerb) { rest.add(a); i++; continue }
        when {
            a == "--root" -> { GlobalFlags.root = args.getOrNull(i + 1); i += 2 }
            a.startsWith("--root=") -> { GlobalFlags.root = a.substringAfter('='); i++ }
            a == "--quiet" || a == "-q" -> { GlobalFlags.quiet = true; i++ }
            a == "--no-color" -> { GlobalFlags.noColor = true; i++ }
            a.startsWith("-") -> { rest.add(a); i++ } // unknown leading flag: let the parser report it
            else -> { sawVerb = true; rest.add(a); i++ } // the subcommand token
        }
    }
    return rest.toTypedArray()
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

    fun emit(result: MutationResult) {
        val export = result.export
        when {
            export != null -> println(json.encodeToString(ExportDto.serializer(), export))
            !quiet -> println(result.task.id)
        }
    }
}

/** `init [--local-bin]` — scaffold a workspace in the cwd (PRD §10.7); optionally self-install the binary. */
private class InitCmd : TkCommand("init", "Scaffold a taskkling workspace (.taskkling/ + tasks/)") {
    val localBin by option(
        ArgType.Boolean, "local-bin",
        description = "Also install the running binary into <root>/.taskkling/bin and drop ./taskkling wrappers",
    ).default(false)

    override fun run() {
        val result = initWorkspace(root)
        if (!quiet) {
            val verb = if (result.alreadyExisted) "already initialized" else "initialized taskkling workspace"
            println("$verb: ${result.root}")
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
            val computed = computeAll(all)
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

/** `wait <id> [--until <dt>] [--on "<text>"]` — set waiting, fold defer (PRD §10.5). */
private class WaitCmd : MutationCommand("wait", "Set status=waiting; optionally defer (--until) and external requirement (--on)") {
    val id by argument(ArgType.String, description = "Task id")
    val until by option(ArgType.String, "until", description = "Defer until this datetime (suppresses readiness)")
    val on by option(ArgType.String, "on", description = "External requirement text (stored as waiting_on)")
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

/** `set <id> [--<field> …] [--clear <field>…]` — atomic multi-field edit (PRD §10.4). */
private class SetCmd : MutationCommand("set", "Edit metadata fields (title/thread/due/defer/priority)") {
    val id by argument(ArgType.String, description = "Task id")
    val title by option(ArgType.String, "title", description = "Set title")
    val thread by option(ArgType.String, "thread", "t", description = "Set thread (empty clears)")
    val due by option(ArgType.String, "due", description = "Set due datetime (empty clears)")
    val defer by option(ArgType.String, "defer", description = "Set defer datetime (empty clears)")
    val priority by option(ArgType.String, "priority", "p", description = "Set priority low|normal|high")
    val clear by option(ArgType.String, "clear", description = "Field to unset (repeatable)").multiple()

    override fun run() {
        if (title == null && thread == null && due == null && defer == null && priority == null && clear.isEmpty()) {
            throw TkError(ExitCode.USAGE, "set needs at least one field to change")
        }
        val ws = Workspace.discover(root)
        emit(ws.setFields(id, SetArgs(title, thread, due, defer, priority, clear), exportOnSuccess))
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
        val export = result.export
        when {
            export != null -> println(json.encodeToString(ExportDto.serializer(), export))
            !quiet -> println(result.task.id)
        }
    }
}

/** `cleanup [--delete-before <dt>] [--include-archive]` — sweep closed → archive; purge trash (PRD §10.7). */
private class CleanupCmd : MutationCommand("cleanup", "Sweep closed tasks to archive; optionally purge old trash") {
    val deleteBefore by option(ArgType.String, "delete-before", description = "Purge trash entries closed before this datetime")
    val includeArchive by option(ArgType.Boolean, "include-archive", description = "Also purge archive entries with --delete-before").default(false)

    override fun run() {
        val result = Workspace.discover(root).cleanup(deleteBefore, includeArchive, exportOnSuccess)
        val export = result.export
        when {
            export != null -> println(json.encodeToString(ExportDto.serializer(), export))
            !quiet -> println("archived ${result.archived}, purged ${result.purged}")
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
 * Best-effort GitHub `tag_name` lookup (ADR-002): a User-Agent is required
 * (GitHub 403s without one) and any failure — network, non-2xx, bad JSON —
 * silently yields null rather than throwing, so callers decide how loud to be.
 */
private fun fetchLatestTag(userAgent: String): String? =
    try {
        val (status, body) = httpGetTextBlocking(GITHUB_API_LATEST_RELEASE, userAgent)
        if (status in 200..299) parseLatestTagName(body) else null
    } catch (_: Exception) {
        null
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
    val latest = fetchLatestTag("taskkling/$currentVersion") ?: return null
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

    /**
     * Resolve the binary `update` will replace (ADR-007). Default (no flag) is
     * the running binary. `--global`/`--local` pick a tier explicitly and are
     * UPDATE-ONLY: a targeted tier with no install is an error, never a silent
     * new install (that's install.sh / `init --local-bin`'s job).
     */
    private fun resolveTarget(running: Path): Path {
        val fs = FileSystem.SYSTEM
        val basename = running.name
        return when {
            global -> {
                val path = globalInstallDirPath() / basename
                if (!fs.exists(path)) throw TkError(ExitCode.VALIDATION, "no global install found; install it with the install script")
                path
            }
            local -> {
                val ws = Workspace.discover(root) // explicit --local must resolve a real workspace
                val path = ws.metaDir / "bin" / basename
                if (!fs.exists(path)) throw TkError(ExitCode.VALIDATION, "no local-bin install here; run 'taskkling init --local-bin' first")
                path
            }
            else -> running
        }
    }

    override fun run() {
        if (global && local) throw TkError(ExitCode.USAGE, "--global and --local are mutually exclusive")

        val current = Taskkling.VERSION
        val userAgent = "taskkling/$current"

        if (check) {
            // --check reports latest-vs-running and touches no binary, so a tier flag is meaningless here (ADR-007).
            if (global || local) throw TkError(ExitCode.USAGE, "--check reports on the running binary and cannot be combined with --global/--local")
            val latest = fetchLatestTag(userAgent)
            if (latest != null) {
                // Opportunistic cache warm (ADR-005): `update --check` always performs a
                // LIVE lookup — it "ignores the flag" because invoking it IS the consent —
                // but feeding its result into the shared ~24h cache lets a later opt-in
                // `--version` notifier skip a redundant network round trip. Best-effort;
                // never affects this command's own output.
                saveUpdateCheckCache(UpdateCheckCache(Clock.System.now().epochSeconds, latest))
            }
            when {
                latest == null -> eprintln("taskkling: could not check for updates (network error or rate-limited)")
                isNewerVersion(current, latest) ->
                    println("taskkling: update available: $current -> ${latest.removePrefix("v")} (run 'taskkling update')")
                else -> println("taskkling: up to date ($current)")
            }
            return
        }

        // Resolve what we're replacing FIRST — it's cheap and local-only, so a tier
        // flag that can't be satisfied fails before any network IO (t-6ouc), not
        // after a full asset download.
        val running = runningExecutablePath()
        val target = resolveTarget(running)
        val fs = FileSystem.SYSTEM
        fun canon(p: Path): Path = try { fs.canonicalize(p) } catch (_: Exception) { p }
        val isSelf = canon(target) == canon(running)

        val targetTag = versionOverride
            ?: fetchLatestTag(userAgent)
            ?: throw TkError(
                ExitCode.VALIDATION,
                "could not resolve the latest release (network error or rate-limited) — pass --version vX.Y.Z to update without it",
            )
        val targetVersion = normalizeVersionTag(targetTag).removePrefix("v")

        val assetName = currentReleaseAssetName()
        val base = releaseDownloadBaseUrl(targetTag)
        val assetUrl = "$base/$assetName"
        val sumsUrl = "$base/SHA256SUMS"

        if (!quiet) println("Downloading $assetName ($targetVersion) ...")
        val (assetStatus, assetBytes) = httpGetBytesBlocking(assetUrl, userAgent)
        if (assetStatus !in 200..299) throw TkError(ExitCode.VALIDATION, "download failed: HTTP $assetStatus for $assetUrl")
        val (sumsStatus, sumsText) = httpGetTextBlocking(sumsUrl, userAgent)
        if (sumsStatus !in 200..299) throw TkError(ExitCode.VALIDATION, "download failed: HTTP $sumsStatus for $sumsUrl")

        val expected = findSha256(sumsText, assetName)
            ?: throw TkError(ExitCode.VALIDATION, "no checksum entry for $assetName in SHA256SUMS")
        val actualHash = Sha256.hashHex(assetBytes)
        if (!expected.equals(actualHash, ignoreCase = true)) {
            throw TkError(ExitCode.VALIDATION, "checksum mismatch for $assetName (expected $expected, got $actualHash) — aborting")
        }
        if (!quiet) println("Checksum OK ($actualHash)")

        // Self vs other, forced by the OS (ADR-007): replacing the running image needs the
        // Windows-safe self-replace dance ([installNewExecutable]); a different, unlocked
        // tier's copy is a plain overwrite ([installOtherExecutable]).
        if (isSelf) installNewExecutable(target, assetBytes) else installOtherExecutable(target, assetBytes)
        restampLocalBinVersionIfPresent(target, targetVersion)

        if (!quiet) {
            if (isSelf) println("$current -> $targetVersion")
            else println("updated $target to $targetVersion")
        }
    }
}

/**
 * `uninstall [--global|--local] [--purge] [-y]` — the symmetric inverse of
 * install (ADR-004): removes the tier-resolved binary and the `PATH` entry
 * install(.sh/.ps1) added; NEVER the workspace's data — `.taskkling/` plus the
 * resolved tasks dir ([Workspace.purgePlan], t-qoyn) — unless `--purge` says so
 * explicitly, on the command line, in every
 * mode. Interactive by default (states consequences, then asks); `-y` runs
 * the safe scope (binary + `PATH`) non-interactively; `--purge -y` is the
 * only non-interactive way to also destroy data, and interactive `--purge`
 * still confirms that wipe on its own, separately.
 */
private class UninstallCmd : TkCommand("uninstall", "Remove the taskkling binary + PATH entry; --purge also deletes .taskkling/") {
    val global by option(ArgType.Boolean, "global", description = "Target the global-tier binary explicitly").default(false)
    val local by option(ArgType.Boolean, "local", description = "Target the per-project local-bin binary explicitly").default(false)
    val purge by option(
        ArgType.Boolean, "purge",
        description = "Also PERMANENTLY delete the .taskkling/ workspace (tasks, config, caches) — irreversible",
    ).default(false)
    val yes by option(ArgType.Boolean, "yes", "y", description = "Run non-interactively (safe scope only unless --purge)").default(false)

    private fun confirm(prompt: String): Boolean {
        print("$prompt [y/N] ")
        val answer = readlnOrNull()?.trim()?.lowercase()
        return answer == "y" || answer == "yes"
    }

    /** What `uninstall` will act on: the binary, its local-bin version stamp / wrapper scripts (if any), and the tier. */
    private data class Target(val tier: InstallTier, val path: Path, val versionStamp: Path?, val wrappers: List<Path>)

    override fun run() {
        if (global && local) throw TkError(ExitCode.USAGE, "--global and --local are mutually exclusive")

        val running = runningExecutablePath()
        val basename = running.name
        val fs = FileSystem.SYSTEM

        // Best-effort workspace discovery — uninstall is not necessarily run from inside a project
        // (a --global removal in particular may have no workspace in scope at all).
        fun discoverWorkspace(rootOverride: String?): Workspace? =
            try { Workspace.discover(rootOverride) } catch (_: TkError) { null }

        val target: Target
        val workspace: Workspace?
        when {
            local -> {
                val ws = Workspace.discover(root) // explicit --local: must resolve to a real workspace
                val binDir = ws.metaDir / "bin"
                target = Target(InstallTier.LOCAL, binDir / basename, binDir / ".version", listOf(ws.root / "taskkling", ws.root / "taskkling.cmd"))
                workspace = ws
            }
            global -> {
                target = Target(InstallTier.GLOBAL, globalInstallDirPath() / basename, null, emptyList())
                workspace = discoverWorkspace(root)
            }
            else -> when (resolveInstallTier(running)) {
                InstallTier.LOCAL -> {
                    val binDir = running.parent ?: throw TkError(ExitCode.VALIDATION, "the running executable has no parent directory")
                    val projectRoot = binDir.parent?.parent // bin/ -> .taskkling/ -> project root
                    val wrappers = if (projectRoot != null) listOf(projectRoot / "taskkling", projectRoot / "taskkling.cmd") else emptyList()
                    target = Target(InstallTier.LOCAL, running, binDir / ".version", wrappers)
                    workspace = projectRoot?.let { discoverWorkspace(it.toString()) }
                }
                InstallTier.GLOBAL -> {
                    target = Target(InstallTier.GLOBAL, running, null, emptyList())
                    workspace = discoverWorkspace(root)
                }
            }
        }

        // Canonicalize before comparing — --global/--local may re-derive a path that refers to the
        // same file as `running` through a different (but equivalent) string.
        fun canon(p: Path): Path = try { fs.canonicalize(p) } catch (_: Exception) { p }
        val isSelf = canon(target.path) == canon(running)

        val pathEntryDir = if (target.tier == InstallTier.GLOBAL) target.path.parent?.toString() else null
        val pathEntryPresent = pathEntryDir?.let { windowsPathHasEntry(it) } ?: false
        val taskCount = workspace?.allKnownIds()?.size ?: 0
        // What --purge would erase (t-qoyn): the meta dir PLUS the default layout's
        // root-level tasks dir. coversTasks=false flags a tasks_dir the plan refuses
        // to touch (it resolves to the root itself, or escapes it) — the prompts
        // below must not claim task deletion then.
        val purgePlan = workspace?.purgePlan()

        if (!yes) {
            println("taskkling uninstall (${target.tier.name.lowercase()} tier):")
            println("  binary:  ${target.path}")
            if (pathEntryPresent) println("  PATH:    remove '$pathEntryDir' from your user PATH")
            if (workspace != null && purgePlan != null) {
                if (purge) {
                    val what = purgePlan.targets.joinToString(" + ")
                    if (purgePlan.coversTasks) {
                        println("  PURGE:   $what — PERMANENTLY DELETES $taskCount task(s), config, and caches")
                    } else {
                        println("  PURGE:   $what — PERMANENTLY DELETES config and caches (tasks_dir '${workspace.config.tasksDir}' does not resolve to a directory inside the workspace; tasks are NOT touched)")
                    }
                } else if (taskCount > 0) {
                    println("  (kept)   ${workspace.metaDir} — $taskCount task(s) preserved; pass --purge to also delete them")
                }
            }
            if (!confirm("Proceed with removing the binary" + (if (pathEntryPresent) " and PATH entry" else "") + "?")) {
                if (!quiet) println("taskkling: uninstall aborted; nothing was changed")
                return
            }
            if (purge && workspace != null && purgePlan != null) {
                val consequence = if (purgePlan.coversTasks) {
                    "This PERMANENTLY deletes $taskCount task(s) at ${purgePlan.targets.joinToString(" + ")} and cannot be undone. Continue?"
                } else {
                    "This PERMANENTLY deletes the workspace config and caches at ${workspace.metaDir} and cannot be undone. Continue?"
                }
                if (!confirm(consequence)) {
                    if (!quiet) println("taskkling: uninstall aborted; workspace preserved")
                    return
                }
            }
        }

        // Binary + local-bin sidecar removal.
        if (isSelf) uninstallRunningBinary(target.path) else uninstallOtherBinary(target.path)
        target.versionStamp?.let { fs.delete(it, mustExist = false) }
        target.wrappers.forEach { fs.delete(it, mustExist = false) }

        // PATH de-entry (global tier only; a true no-op on POSIX and whenever the entry was already absent).
        val pathChanged = pathEntryDir?.let { removeFromWindowsUserPath(it) } ?: false

        // Purge — the ONLY path that touches the task graph, and only ever behind the explicit flag.
        val purgedTargets = if (purge && purgePlan != null) purgePlan.targets else emptyList()
        purgedTargets.forEach { fs.deleteRecursively(it, mustExist = false) }

        if (!quiet) {
            println("taskkling: removed ${target.path}")
            if (isSelf && fs.exists("${target.path}.old".toPath())) {
                println("taskkling: off PATH now; the locked file will clear on your next reboot")
            }
            if (pathChanged) println("taskkling: removed '$pathEntryDir' from your user PATH")
            if (purgedTargets.isNotEmpty()) println("taskkling: purged ${purgedTargets.joinToString(" + ")}")
        }
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
        DeleteCmd(), RestoreCmd(), CleanupCmd(), DoctorCmd(), UpdateCmd(), UninstallCmd(),
        ConfigCmd(),
    )
    parser.parse(rest)
}
