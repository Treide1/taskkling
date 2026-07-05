package io.taskkling.core

import io.taskkling.contract.ExportDto
import okio.FileSystem

/**
 * `delete <id>` (PRD §9.5, §10.5) — move the task to `trash/`, stamp `closed`,
 * and **cascade-prune** this id from the `depends` of every active dependent so
 * no dangling edge survives. **Validation-free** by design (the single mutation
 * that skips §7.5 preventive checks); reversible via [restoreTask]. The whole
 * operation runs under the global lock. Dependents are pruned *before* the task
 * leaves the active set, so a lock-free reader never observes a dangling edge.
 */
public fun Workspace.deleteTask(id: String, exportAfter: Boolean = false): MutationResult = withLock {
    val fs = FileSystem.SYSTEM
    val path = findActiveFile(id) ?: throw TkError(ExitCode.USAGE, "unknown id '$id'")
    val task = parseTask(path.name, fs.read(path) { readUtf8() })

    // Cascade-prune: drop this id from every active dependent's depends.
    for (dep in loadTasks()) {
        if (id in dep.depends) {
            val pruned = dep.copy(depends = dep.depends.filter { it != id })
            writeFileAtomic(tasksDir / pruned.fileName(), pruned.toMarkdown())
        }
    }

    // Move the task to trash/, stamping closed (preserving an existing stamp).
    val trashed = task.copy(closed = task.closed ?: nowUtc())
    writeFileAtomic(trashDir / trashed.fileName(), trashed.toMarkdown())
    fs.delete(path, mustExist = false)

    MutationResult(trashed, if (exportAfter) buildExport(includeBody = false, includeArchived = false) else null)
}

/**
 * Outcome of [restoreTask]: the restored [task], the `depends` edges that could
 * **not** be re-wired (their targets are no longer active, so they were dropped),
 * and the optional post-mutation [export].
 */
public data class RestoreResult(
    val task: Task,
    val droppedEdges: List<String>,
    val export: ExportDto?,
)

/**
 * `restore <id>` (PRD §9.5, §10.5) — move a task from `trash/` (preferred) or
 * `archive/` back into the active set. Clears `closed`; a task that was
 * `done`/`dropped` returns as `open` (so it is actionable again and `cleanup`
 * won't immediately re-sweep it), which also clears `waiting_on`. Severed
 * inbound edges are **not** re-added (git history is the only full undo); and any
 * of the task's own `depends` whose target is no longer active is dropped and
 * reported, keeping the active set free of dangling edges.
 */
public fun Workspace.restoreTask(id: String, exportAfter: Boolean = false): RestoreResult = withLock {
    val fs = FileSystem.SYSTEM
    if (findActiveFile(id) != null) throw TkError(ExitCode.USAGE, "'$id' is already active")
    val src = fileFor(trashDir, id) ?: fileFor(archiveDir, id)
        ?: throw TkError(ExitCode.USAGE, "no trashed or archived task '$id'")

    val task = parseTask(src.name, fs.read(src) { readUtf8() })
    val active = activeIds()
    val (keep, drop) = task.depends.partition { it in active }
    val reopen = task.status == Status.DONE || task.status == Status.DROPPED
    val restored = task.copy(
        status = if (reopen) Status.OPEN else task.status,
        waitingOn = if (reopen) null else task.waitingOn,
        closed = null,
        depends = keep,
    )

    writeFileAtomic(tasksDir / restored.fileName(), restored.toMarkdown())
    fs.delete(src, mustExist = false)

    RestoreResult(restored, drop, if (exportAfter) buildExport(includeBody = false, includeArchived = false) else null)
}

/** Outcome of [cleanup]: how many tasks were swept to archive and purged. */
public data class CleanupResult(val archived: Int, val purged: Int, val export: ExportDto?)

/**
 * `cleanup [--delete-before <dt>] [--include-archive]` (PRD §9.5, §10.7).
 * Always sweeps closed (`done`/`dropped`) active tasks from `tasks/` → `archive/`.
 * With [deleteBefore], permanently purges `trash/` entries whose `closed < dt`
 * (and, with [includeArchive], `archive/` entries too). ISO-8601 UTC stamps
 * compare lexicographically, so the string `<` is a chronological one.
 */
public fun Workspace.cleanup(
    deleteBefore: String?,
    includeArchive: Boolean,
    exportAfter: Boolean = false,
): CleanupResult = withLock {
    val fs = FileSystem.SYSTEM

    var archived = 0
    if (fs.exists(tasksDir)) {
        for (p in fs.list(tasksDir)) {
            if (!p.name.endsWith(".md")) continue
            val t = parseTask(p.name, fs.read(p) { readUtf8() })
            if (t.status == Status.DONE || t.status == Status.DROPPED) {
                writeFileAtomic(archiveDir / t.fileName(), t.toMarkdown())
                fs.delete(p, mustExist = false)
                archived++
            }
        }
    }

    var purged = 0
    if (deleteBefore != null) {
        val cutoff = normalizeDateTime(deleteBefore)
        val dirs = buildList { add(trashDir); if (includeArchive) add(archiveDir) }
        for (dir in dirs) {
            if (!fs.exists(dir)) continue
            for (p in fs.list(dir)) {
                if (!p.name.endsWith(".md")) continue
                val t = parseTask(p.name, fs.read(p) { readUtf8() })
                if (t.closed != null && t.closed < cutoff) {
                    fs.delete(p, mustExist = false)
                    purged++
                }
            }
        }
    }

    CleanupResult(archived, purged, if (exportAfter) buildExport(includeBody = false, includeArchived = false) else null)
}
