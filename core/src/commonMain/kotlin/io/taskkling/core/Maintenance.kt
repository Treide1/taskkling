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
 * **not** be re-wired (their targets are neither active nor archived, so they
 * were dropped), and the optional post-mutation [export].
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
 * won't immediately re-sweep it). The external requirement (`waiting_on`/`req`)
 * is independent of status (ADR-018) and is preserved across the reopen. Severed
 * inbound edges are **not** re-added (git history is the only full undo); and any
 * of the task's own `depends` whose target is neither active nor archived
 * (graph-neutral archive, ADR-014) is dropped and reported, keeping the active
 * set free of dangling edges. Re-runs the cycle check before writing: edges may
 * point *into* the archive, so reopening an archived node could otherwise close
 * a cycle its `done` status had kept dormant.
 */
public fun Workspace.restoreTask(id: String, exportAfter: Boolean = false): RestoreResult = withLock {
    val fs = FileSystem.SYSTEM
    if (findActiveFile(id) != null) throw TkError(ExitCode.USAGE, "'$id' is already active")
    val src = fileFor(trashDir, id) ?: fileFor(archiveDir, id)
        ?: throw TkError(ExitCode.USAGE, "no trashed or archived task '$id'")

    val task = parseTask(src.name, fs.read(src) { readUtf8() })
    val known = activeIds() + idsInDir(archiveDir)
    val (keep, drop) = task.depends.partition { it in known && it != id }
    val reopen = task.status == Status.DONE || task.status == Status.DROPPED
    val restored = task.copy(
        status = if (reopen) Status.OPEN else task.status,
        closed = null,
        depends = keep,
    )
    detectCycle(restored)

    writeFileAtomic(tasksDir / restored.fileName(), restored.toMarkdown())
    fs.delete(src, mustExist = false)

    RestoreResult(restored, drop, if (exportAfter) buildExport(includeBody = false, includeArchived = false) else null)
}

/**
 * Outcome of [cleanup]: how many tasks were swept to archive, purged, and — under
 * `--include-archive` — [retained] because an active task still depends on them
 * (purging them would strand the dependent with a dangling edge; see [cleanup]).
 */
public data class CleanupResult(val archived: Int, val purged: Int, val retained: Int, val export: ExportDto?)

/**
 * `cleanup [--only <status>…] [--delete-before <dt>] [--include-archive]`
 * (PRD §9.5, §10.7). Sweeps closed (`done`/`dropped`) active tasks from
 * `tasks/` → `archive/`; [only] narrows the sweep to a subset of the closed
 * statuses (null = both, the historical behavior — the UI archive dialog is
 * the selective caller). With [deleteBefore], permanently purges `trash/`
 * entries whose `closed < dt` (and, with [includeArchive], `archive/` entries
 * too); the purge ignores [only]. ISO-8601 UTC stamps compare
 * lexicographically, so the string `<` is a chronological one.
 *
 * **Edge safety (t-5z3y):** ADR-014 sanctions long-lived `depends` edges pointing
 * *into* `archive/` (an archived `done` task satisfies its dependents), so a naive
 * archive purge would reproduce the very spurious-blocking bug ADR-014 killed:
 * delete an archived file still referenced by an active task and that dependent
 * flips to blocked-forever with an invisible blocker. So archive purging **skips**
 * (retains, counting them in [CleanupResult.retained]) any file whose id is still
 * in some active task's `depends`. `trash/` needs no such guard: `delete`
 * cascade-prunes before trashing, and no valid edge can target a trashed id.
 * Skipping (rather than cascade-pruning at purge time) preserves authored `depends`
 * lists, consistent with ADR-014 rejecting prune-at-sweep as semantically
 * destructive — a still-referenced archive entry is simply kept until the
 * dependent is itself deleted or unlinked.
 */
public fun Workspace.cleanup(
    deleteBefore: String?,
    includeArchive: Boolean,
    exportAfter: Boolean = false,
    only: Set<Status>? = null,
): CleanupResult = withLock {
    val fs = FileSystem.SYSTEM

    var archived = 0
    if (fs.exists(tasksDir)) {
        for (p in fs.list(tasksDir)) {
            if (!p.name.endsWith(".md")) continue
            val t = parseTask(p.name, fs.read(p) { readUtf8() })
            if ((t.status == Status.DONE || t.status == Status.DROPPED) && (only == null || t.status in only)) {
                writeFileAtomic(archiveDir / t.fileName(), t.toMarkdown())
                fs.delete(p, mustExist = false)
                archived++
            }
        }
    }

    var purged = 0
    var retained = 0
    if (deleteBefore != null) {
        val cutoff = normalizeDateTime(deleteBefore)
        // Ids some active task still depends on — purging any of these from
        // archive/ would strand its dependent with a dangling edge (ADR-014).
        // Computed after the sweep so freshly-archived deps are already accounted for.
        val referencedByActive: Set<String> =
            if (includeArchive) loadTasks().flatMapTo(HashSet()) { it.depends } else emptySet()
        val dirs = buildList { add(trashDir); if (includeArchive) add(archiveDir) }
        for (dir in dirs) {
            if (!fs.exists(dir)) continue
            val guardEdges = dir == archiveDir
            for (p in fs.list(dir)) {
                if (!p.name.endsWith(".md")) continue
                val t = parseTask(p.name, fs.read(p) { readUtf8() })
                if (t.closed == null || t.closed >= cutoff) continue
                if (guardEdges && t.id in referencedByActive) {
                    retained++
                    continue
                }
                fs.delete(p, mustExist = false)
                purged++
            }
        }
    }

    CleanupResult(archived, purged, retained, if (exportAfter) buildExport(includeBody = false, includeArchived = false) else null)
}
