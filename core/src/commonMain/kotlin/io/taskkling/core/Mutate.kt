package io.taskkling.core

import io.taskkling.contract.ExportDto
import okio.FileSystem
import okio.Path

/** Path of the active task [id] (`tasks/<id>--*.md`), or null if absent. */
public fun Workspace.findActiveFile(id: String): Path? = fileFor(tasksDir, id)

/** Raw `.md` content of the active task [id], verbatim (for `show`); null if absent. */
public fun Workspace.rawFile(id: String): String? {
    val path = findActiveFile(id) ?: return null
    return FileSystem.SYSTEM.read(path) { readUtf8() }
}

/** Parse the active task [id] into a [Task]; null if absent. */
public fun Workspace.loadTask(id: String): Task? {
    val path = findActiveFile(id) ?: return null
    return parseTask(path.name, FileSystem.SYSTEM.read(path) { readUtf8() })
}

/**
 * Result of a mutation: the affected [task], plus the full post-mutation
 * [export] when `--export-on-success` was requested (computed under the lock for
 * a TOCTOU-free read-after-write, PRD §7.3); otherwise null.
 */
public data class MutationResult(val task: Task, val export: ExportDto?)

/**
 * The generic write path for editing one existing task (PRD §7.1): under the
 * global lock, read the file **fresh**, apply [transform], validate, and write
 * via temp→rename. Carrying the whole task from the fresh read (not a stale
 * blob) means concurrent edits to different fields both survive. Renames the
 * file when the slug changes (e.g. a later title edit). With [exportAfter], the
 * full export is computed before the lock releases.
 */
public fun Workspace.updateTask(
    id: String,
    exportAfter: Boolean = false,
    transform: (Task) -> Task,
): MutationResult = withLock {
    val fs = FileSystem.SYSTEM
    val path = findActiveFile(id) ?: throw TkError(ExitCode.USAGE, "unknown id '$id'")
    val current = parseTask(path.name, fs.read(path) { readUtf8() })
    val updated = transform(current)
    validateInvariants(updated)
    val newPath = tasksDir / updated.fileName()
    writeFileAtomic(newPath, updated.toMarkdown())
    if (newPath != path) fs.delete(path, mustExist = false)
    MutationResult(updated, if (exportAfter) buildExport(includeBody = false, includeArchived = false) else null)
}

/**
 * Preventive write-path validation (PRD §7.5): no self/dangling `depends`, and no
 * dependency cycles. Run on every mutation except `delete` (M2). Valid `depends`
 * targets are the active set *plus* `archive/` — the archive stays a graph node
 * source (graph-neutral archive, ADR-014), so sweeping a done dependency never
 * invalidates its dependents. `trash/` ids stay invalid (deletion cascade-pruned
 * the edges).
 *
 * The external requirement (`waiting_on`/`req`) is **independent** of status
 * (ADR-018): it may be set in any status, so there is no longer a
 * `waiting_on ⇒ waiting` invariant to enforce here.
 */
public fun Workspace.validateInvariants(t: Task) {
    val known = activeIds() + idsInDir(archiveDir)
    for (d in t.depends) {
        if (d == t.id) throw TkError(ExitCode.VALIDATION, "task cannot depend on itself ($d)")
        if (d !in known) throw TkError(ExitCode.VALIDATION, "depends references unknown id '$d'")
    }
    detectCycle(t)
}

/**
 * Cycle check over the `depends` graph with [updated]'s edges substituted in.
 * Archived nodes are terminals (their edges are not loaded): a cycle cannot
 * *close* through the archive while its member stays there, and [restoreTask]
 * re-runs this check before a closed-over node re-enters the active set.
 */
internal fun Workspace.detectCycle(updated: Task) {
    val deps = loadTasks().associate { it.id to it.depends }.toMutableMap()
    deps[updated.id] = updated.depends

    val state = HashMap<String, Int>() // 0/absent = unvisited, 1 = on stack, 2 = done
    fun dfs(node: String): String? {
        state[node] = 1
        for (next in deps[node].orEmpty()) {
            when (state[next]) {
                1 -> return next
                2 -> {}
                else -> dfs(next)?.let { return it }
            }
        }
        state[node] = 2
        return null
    }
    for (n in deps.keys) {
        if (state[n] == null) dfs(n)?.let {
            throw TkError(ExitCode.VALIDATION, "dependency cycle detected at '$it'")
        }
    }
}

/**
 * Apply a status transition with the shared `closed`-stamp rule (ADR-018): entering
 * a closed state (`done`/`dropped`) stamps `closed` fresh on every close; entering
 * an open state (`open`/`waiting`) clears it. The external requirement
 * (`waiting_on`/`req`) is **independent** of status and is deliberately left
 * untouched — it persists across every transition until explicitly cleared.
 */
internal fun Task.withStatus(next: Status): Task = copy(
    status = next,
    closed = if (next == Status.DONE || next == Status.DROPPED) nowUtc() else null,
)

/** `done` — status=done, stamp `closed`; the external requirement persists (PRD §10.5, ADR-018). */
public fun Workspace.markDone(id: String, exportAfter: Boolean = false): MutationResult =
    updateTask(id, exportAfter) { it.withStatus(Status.DONE) }

/** `drop` — status=dropped, stamp `closed`; the external requirement persists (PRD §10.5, ADR-018). */
public fun Workspace.markDropped(id: String, exportAfter: Boolean = false): MutationResult =
    updateTask(id, exportAfter) { it.withStatus(Status.DROPPED) }

/** `reopen` — back to open, clear `closed`; the external requirement persists (PRD §10.5, ADR-018). */
public fun Workspace.reopenTask(id: String, exportAfter: Boolean = false): MutationResult =
    updateTask(id, exportAfter) { it.withStatus(Status.OPEN) }

/**
 * `wait` — status=waiting; optionally set `defer` (`--until`) and/or the external
 * requirement (`--req`), each preserved when its flag is omitted (PRD §10.5).
 * Sugar over the shared status path (ADR-018): `--req` may equally be set on any
 * status via `set --req`, independent of the waiting transition.
 */
public fun Workspace.waitTask(id: String, until: String?, req: String?, exportAfter: Boolean = false): MutationResult =
    updateTask(id, exportAfter) { t ->
        t.withStatus(Status.WAITING).copy(
            defer = until?.let { normalizeDateTime(it) } ?: t.defer,
            waitingOn = req?.trim()?.ifEmpty { null } ?: t.waitingOn,
        )
    }

/** `link <id> --depends <dep>…` — add one or more dependency edges in one mutation; cycle-checked (PRD §10.6). */
public fun Workspace.linkDepends(id: String, deps: List<String>, exportAfter: Boolean = false): MutationResult =
    updateTask(id, exportAfter) { it.copy(depends = (it.depends + deps).distinct()) }

/** `unlink <id> --depends <dep>…` — remove one or more dependency edges in one mutation (PRD §10.6). */
public fun Workspace.unlinkDepends(id: String, deps: List<String>, exportAfter: Boolean = false): MutationResult =
    updateTask(id, exportAfter) { t -> t.copy(depends = t.depends.filter { it !in deps }) }

/**
 * Inputs for `set` (PRD §10.4). Each non-null field is applied; an empty string
 * unsets the (optional) field, equivalent to naming it in [clear]. `depends` is
 * intentionally absent — it is owned by the link/unlink verbs. `status` and the
 * external requirement (`req`) are now first-class editable fields (ADR-018),
 * independent of one another; the lifecycle verbs (`done`/`drop`/…) remain as
 * sugar over the same status path.
 */
public data class SetArgs(
    val title: String? = null,
    val thread: String? = null,
    val status: String? = null,
    val req: String? = null,
    val due: String? = null,
    val defer: String? = null,
    val priority: String? = null,
    val clear: List<String> = emptyList(),
)

/**
 * `set <id> [--<field> …] [--clear <field>…]` — atomic multi-field metadata edit
 * (PRD §10.4). Runs the validated write path: `due`/`defer` are normalized,
 * `priority`/`status` are enum-checked, and a `title` change re-slugs the
 * filename. A `status` change applies the shared `closed`-stamp rule
 * ([withStatus]); the external requirement (`req`) is set independently. `title`
 * cannot be cleared; clearing `priority` resets it to `normal`; `status` cannot
 * be cleared (there is no null status).
 */
public fun Workspace.setFields(id: String, args: SetArgs, exportAfter: Boolean = false): MutationResult =
    updateTask(id, exportAfter) { t ->
        var x = t
        args.title?.let { v ->
            if (v.isBlank()) throw TkError(ExitCode.USAGE, "title cannot be cleared")
            x = x.copy(title = v.trim())
        }
        args.thread?.let { v -> x = x.copy(thread = v.trim().ifEmpty { null }) }
        args.status?.let { v -> x = x.withStatus(Status.from(v.trim())) }
        args.req?.let { v -> x = x.copy(waitingOn = v.trim().ifEmpty { null }) }
        args.due?.let { v -> x = x.copy(due = v.trim().ifEmpty { null }?.let { normalizeDateTime(it) }) }
        args.defer?.let { v -> x = x.copy(defer = v.trim().ifEmpty { null }?.let { normalizeDateTime(it) }) }
        args.priority?.let { v -> x = x.copy(priority = if (v.isBlank()) Priority.NORMAL else Priority.from(v)) }
        for (f in args.clear) {
            x = when (f) {
                "thread" -> x.copy(thread = null)
                "req" -> x.copy(waitingOn = null)
                "due" -> x.copy(due = null)
                "defer" -> x.copy(defer = null)
                "priority" -> x.copy(priority = Priority.NORMAL)
                "title" -> throw TkError(ExitCode.USAGE, "title cannot be cleared")
                "status" -> throw TkError(ExitCode.USAGE, "status cannot be cleared (set --status open|waiting|done|dropped)")
                else -> throw TkError(ExitCode.USAGE, "cannot clear '$f' (clearable: thread, req, due, defer, priority)")
            }
        }
        x
    }

/**
 * Strip one leading U+FEFF from an incoming body chunk: Windows PowerShell 5.1
 * pipes prepend a UTF-8 BOM to each piped payload, which would otherwise land
 * verbatim in the stored body (mid-file for `append`, where the parse-side
 * strip in [parseTask] can't reach it). A FEFF anywhere else is content.
 * `internal` so the sibling `add` path ([addTask], fed a piped body) shares it.
 */
internal fun String.stripLeadingBom(): String = removePrefix("﻿")

/** `write <id> "<text>"` — replace the body in full (PRD §10.6). */
public fun Workspace.writeBody(id: String, text: String, exportAfter: Boolean = false): MutationResult =
    updateTask(id, exportAfter) { it.copy(body = text.stripLeadingBom().trim()) }

/**
 * `append <id> "<text>"` — append to the body (PRD §10.6), joined to existing
 * content by a single newline; an empty body just takes the text.
 */
public fun Workspace.appendBody(id: String, text: String, exportAfter: Boolean = false): MutationResult =
    updateTask(id, exportAfter) { t ->
        val add = text.stripLeadingBom().trim()
        t.copy(body = if (t.body.isBlank()) add else t.body.trimEnd() + "\n" + add)
    }

/** `get <id> --body` — the task's body only, frontmatter stripped (PRD §10.2). */
public fun Workspace.readBody(id: String): String =
    loadTask(id)?.body ?: throw TkError(ExitCode.USAGE, "unknown id '$id'")
