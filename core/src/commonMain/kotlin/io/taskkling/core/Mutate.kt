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
 * Preventive write-path validation (PRD §7.5): the `waiting_on ⇒ waiting`
 * invariant, no self/dangling `depends`, and no dependency cycles. Run on every
 * mutation except `delete` (M2).
 */
public fun Workspace.validateInvariants(t: Task) {
    if (t.waitingOn != null && t.status != Status.WAITING) {
        throw TkError(ExitCode.VALIDATION, "waiting_on may be set only when status=waiting")
    }
    val active = activeIds()
    for (d in t.depends) {
        if (d == t.id) throw TkError(ExitCode.VALIDATION, "task cannot depend on itself ($d)")
        if (d !in active) throw TkError(ExitCode.VALIDATION, "depends references unknown id '$d'")
    }
    detectCycle(t)
}

/** Cycle check over the `depends` graph with [updated]'s edges substituted in. */
private fun Workspace.detectCycle(updated: Task) {
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

/** `done` — status=done, stamp `closed`, clear `waiting_on` (PRD §10.5). */
public fun Workspace.markDone(id: String, exportAfter: Boolean = false): MutationResult =
    updateTask(id, exportAfter) { it.copy(status = Status.DONE, closed = nowUtc(), waitingOn = null) }

/** `drop` — status=dropped, stamp `closed`, clear `waiting_on` (PRD §10.5). */
public fun Workspace.markDropped(id: String, exportAfter: Boolean = false): MutationResult =
    updateTask(id, exportAfter) { it.copy(status = Status.DROPPED, closed = nowUtc(), waitingOn = null) }

/** `reopen` — back to open, clear `closed` and `waiting_on` (PRD §10.5). */
public fun Workspace.reopenTask(id: String, exportAfter: Boolean = false): MutationResult =
    updateTask(id, exportAfter) { it.copy(status = Status.OPEN, closed = null, waitingOn = null) }

/**
 * `wait` — status=waiting; optionally set `defer` (`--until`) and/or `waiting_on`
 * (`--on`), each preserved when its flag is omitted (PRD §10.5).
 */
public fun Workspace.waitTask(id: String, until: String?, on: String?, exportAfter: Boolean = false): MutationResult =
    updateTask(id, exportAfter) { t ->
        t.copy(
            status = Status.WAITING,
            defer = until?.let { normalizeDateTime(it) } ?: t.defer,
            waitingOn = on ?: t.waitingOn,
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
 * unsets the (optional) field, equivalent to naming it in [clear]. Status and
 * `depends` are intentionally absent — they are owned by the lifecycle and
 * link/unlink verbs, not `set`.
 */
public data class SetArgs(
    val title: String? = null,
    val thread: String? = null,
    val due: String? = null,
    val defer: String? = null,
    val priority: String? = null,
    val clear: List<String> = emptyList(),
)

/**
 * `set <id> [--<field> …] [--clear <field>…]` — atomic multi-field metadata edit
 * (PRD §10.4). Runs the validated write path: `due`/`defer` are normalized,
 * `priority` is enum-checked, and a `title` change re-slugs the filename. `title`
 * cannot be cleared; clearing `priority` resets it to `normal`.
 */
public fun Workspace.setFields(id: String, args: SetArgs, exportAfter: Boolean = false): MutationResult =
    updateTask(id, exportAfter) { t ->
        var x = t
        args.title?.let { v ->
            if (v.isBlank()) throw TkError(ExitCode.USAGE, "title cannot be cleared")
            x = x.copy(title = v.trim())
        }
        args.thread?.let { v -> x = x.copy(thread = v.trim().ifEmpty { null }) }
        args.due?.let { v -> x = x.copy(due = v.trim().ifEmpty { null }?.let { normalizeDateTime(it) }) }
        args.defer?.let { v -> x = x.copy(defer = v.trim().ifEmpty { null }?.let { normalizeDateTime(it) }) }
        args.priority?.let { v -> x = x.copy(priority = if (v.isBlank()) Priority.NORMAL else Priority.from(v)) }
        for (f in args.clear) {
            x = when (f) {
                "thread" -> x.copy(thread = null)
                "due" -> x.copy(due = null)
                "defer" -> x.copy(defer = null)
                "priority" -> x.copy(priority = Priority.NORMAL)
                "title" -> throw TkError(ExitCode.USAGE, "title cannot be cleared")
                else -> throw TkError(ExitCode.USAGE, "cannot clear '$f' (clearable: thread, due, defer, priority)")
            }
        }
        x
    }

/** `write <id> "<text>"` — replace the body in full (PRD §10.6). */
public fun Workspace.writeBody(id: String, text: String, exportAfter: Boolean = false): MutationResult =
    updateTask(id, exportAfter) { it.copy(body = text.trim()) }

/**
 * `append <id> "<text>"` — append to the body (PRD §10.6), joined to existing
 * content by a single newline; an empty body just takes the text.
 */
public fun Workspace.appendBody(id: String, text: String, exportAfter: Boolean = false): MutationResult =
    updateTask(id, exportAfter) { t ->
        val add = text.trim()
        t.copy(body = if (t.body.isBlank()) add else t.body.trimEnd() + "\n" + add)
    }

/** `get <id> --body` — the task's body only, frontmatter stripped (PRD §10.2). */
public fun Workspace.readBody(id: String): String =
    loadTask(id)?.body ?: throw TkError(ExitCode.USAGE, "unknown id '$id'")
