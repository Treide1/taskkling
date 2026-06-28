package io.taskkling.core

import okio.FileSystem
import okio.Path

/** Path of the active node [id] (`tasks/<id>--*.md`), or null if absent. */
public fun Workspace.findActiveFile(id: String): Path? {
    val fs = FileSystem.SYSTEM
    if (!fs.exists(tasksDir)) return null
    return fs.list(tasksDir).firstOrNull {
        it.name.endsWith(".md") && it.name.removeSuffix(".md").substringBefore("--") == id
    }
}

/** Raw `.md` content of the active node [id], verbatim (for `show`); null if absent. */
public fun Workspace.rawFile(id: String): String? {
    val path = findActiveFile(id) ?: return null
    return FileSystem.SYSTEM.read(path) { readUtf8() }
}

/** Parse the active node [id] into a [Task]; null if absent. */
public fun Workspace.loadTask(id: String): Task? {
    val path = findActiveFile(id) ?: return null
    return parseTask(path.name, FileSystem.SYSTEM.read(path) { readUtf8() })
}

/**
 * The generic write path for editing one existing node (PRD §7.1): under the
 * global lock, read the file **fresh**, apply [transform], validate, and write
 * via temp→rename. Carrying the whole task from the fresh read (not a stale
 * blob) means concurrent edits to different fields both survive. Renames the
 * file when the slug changes (e.g. a later title edit).
 */
public fun Workspace.updateTask(id: String, transform: (Task) -> Task): Task = withLock {
    val fs = FileSystem.SYSTEM
    val path = findActiveFile(id) ?: throw TkError(ExitCode.USAGE, "unknown id '$id'")
    val current = parseTask(path.name, fs.read(path) { readUtf8() })
    val updated = transform(current)
    validateInvariants(updated)
    val newPath = tasksDir / updated.fileName()
    writeFileAtomic(newPath, updated.toMarkdown())
    if (newPath != path) fs.delete(path, mustExist = false)
    updated
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
public fun Workspace.markDone(id: String): Task =
    updateTask(id) { it.copy(status = Status.DONE, closed = nowUtc(), waitingOn = null) }

/** `drop` — status=dropped, stamp `closed`, clear `waiting_on` (PRD §10.5). */
public fun Workspace.markDropped(id: String): Task =
    updateTask(id) { it.copy(status = Status.DROPPED, closed = nowUtc(), waitingOn = null) }

/** `reopen` — back to open, clear `closed` and `waiting_on` (PRD §10.5). */
public fun Workspace.reopenTask(id: String): Task =
    updateTask(id) { it.copy(status = Status.OPEN, closed = null, waitingOn = null) }

/**
 * `wait` — status=waiting; optionally set `defer` (`--until`) and/or `waiting_on`
 * (`--on`), each preserved when its flag is omitted (PRD §10.5).
 */
public fun Workspace.waitTask(id: String, until: String?, on: String?): Task =
    updateTask(id) { t ->
        t.copy(
            status = Status.WAITING,
            defer = until?.let { normalizeDateTime(it) } ?: t.defer,
            waitingOn = on ?: t.waitingOn,
        )
    }
