package io.taskkling.core

import io.taskkling.contract.ComputedDto
import io.taskkling.contract.CountsDto
import io.taskkling.contract.ExportDto
import io.taskkling.contract.TaskDto
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import okio.FileSystem

/** Derived, read-time attributes for one task (PRD §8.2); never stored. */
public data class Computed(
    val ready: Boolean,
    val blocked: Boolean,
    val deferred: Boolean,
    val overdue: Boolean,
    val resurfaced: Boolean,
    val blockers: List<String>,
    val dependents: List<String>,
)

private fun instantOrNull(iso: String?): Instant? =
    iso?.let { runCatching { Instant.parse(it) }.getOrNull() }

/**
 * Compute every task's derived attributes over the given set (PRD §8.2, §11).
 * A `depends` id that isn't `done` (including dangling ids) counts as a blocker;
 * `dependents` is the inverse edge. A `defer`/`due` that won't parse is ignored
 * rather than fatal (the read path stays robust).
 *
 * [alsoDone] supplements the done set with ids satisfied *outside* [tasks] —
 * the graph-neutral archive rule (ADR-014). Callers holding a [Workspace]
 * should prefer the [Workspace.computeAll] overload, which derives it; this
 * pure kernel stays filesystem-free for tests.
 *
 * [now] is the reference instant for all time-relative attributes (defer/due
 * boundaries, waiting resurface). It defaults to the current System clock so
 * existing callers are unchanged; tests inject a fixed instant to pin the
 * defer/due/resurface edges deterministically.
 */
public fun computeAll(
    tasks: List<Task>,
    now: Instant = Clock.System.now(),
    alsoDone: Set<String> = emptySet(),
): Map<String, Computed> {
    val doneIds = tasks.asSequence().filter { it.status == Status.DONE }.map { it.id }.toSet() + alsoDone

    val dependents = HashMap<String, MutableList<String>>()
    for (t in tasks) for (d in t.depends) dependents.getOrPut(d) { ArrayList() }.add(t.id)

    val result = HashMap<String, Computed>(tasks.size)
    for (t in tasks) {
        val blockers = t.depends.filter { it !in doneIds }
        val deferInst = instantOrNull(t.defer)
        val dueInst = instantOrNull(t.due)
        val deferActive = deferInst != null && deferInst > now

        result[t.id] = Computed(
            ready = t.status == Status.OPEN && blockers.isEmpty() && !deferActive,
            blocked = t.status == Status.OPEN && blockers.isNotEmpty(),
            deferred = deferActive,
            overdue = dueInst != null && dueInst < now &&
                t.status != Status.DONE && t.status != Status.DROPPED,
            resurfaced = t.status == Status.WAITING && deferInst != null && deferInst <= now,
            blockers = blockers,
            dependents = (dependents[t.id] ?: emptyList()).sorted(),
        )
    }
    return result
}

/**
 * [computeAll] with the graph-neutral archive rule (ADR-014): a `depends` id
 * missing from [tasks] is resolved by a targeted lookup into `archive/`, and an
 * archived `done` task satisfies the edge exactly as an active one would — so
 * `cleanup` never changes what the graph means. An archived `dropped` task (or
 * a truly dangling id) still counts as a blocker. Only referenced-but-missing
 * ids are read (filename prefix match), keeping the read path O(active).
 */
public fun Workspace.computeAll(
    tasks: List<Task>,
    now: Instant = Clock.System.now(),
): Map<String, Computed> {
    val present = tasks.mapTo(HashSet(tasks.size)) { it.id }
    val missing = tasks.asSequence()
        .flatMap { it.depends.asSequence() }
        .filterNot { it in present }
        .toSet()
    return computeAll(tasks, now, alsoDone = archivedDoneIds(missing))
}

/** The subset of [ids] that exist in `archive/` with status `done` (targeted reads only). */
private fun Workspace.archivedDoneIds(ids: Set<String>): Set<String> {
    if (ids.isEmpty()) return emptySet()
    val fs = FileSystem.SYSTEM
    if (!fs.exists(archiveDir)) return emptySet()
    val out = HashSet<String>()
    for (p in fs.list(archiveDir)) {
        val id = idOfFileName(p.name)
        if (!p.name.endsWith(".md") || id !in ids) continue
        val content = runCatching { fs.read(p) { readUtf8() } }.getOrNull() ?: continue
        if (parseTask(p.name, content).status == Status.DONE) out.add(id)
    }
    return out
}

/** Project a [Task] + its [Computed] attrs to the wire [TaskDto] (PRD §12). */
public fun Task.toDto(computed: Computed, includeBody: Boolean): TaskDto =
    TaskDto(
        id = id,
        title = title,
        thread = thread,
        status = status.wire,
        waitingOn = waitingOn,
        depends = depends,
        due = due,
        defer = defer,
        priority = priority.wire,
        created = created,
        closed = closed,
        computed = ComputedDto(
            ready = computed.ready,
            blocked = computed.blocked,
            deferred = computed.deferred,
            overdue = computed.overdue,
            resurfaced = computed.resurfaced,
            blockers = computed.blockers,
            dependents = computed.dependents,
        ),
        body = if (includeBody) body else null,
    )

/**
 * Build the full export DTO (PRD §12): load tasks, derive computed attributes,
 * project to DTOs (sorted by id for stable output), and tally the headline
 * counts. Lock-free (PRD §7.2).
 */
public fun Workspace.buildExport(includeBody: Boolean, includeArchived: Boolean): ExportDto {
    val tasks = loadTasks(includeArchived)
    val computed = this.computeAll(tasks)
    val dtos = tasks
        .map { it.toDto(computed.getValue(it.id), includeBody) }
        .sortedBy { it.id }
    val counts = CountsDto(
        ready = computed.values.count { it.ready },
        blocked = computed.values.count { it.blocked },
        waiting = tasks.count { it.status == Status.WAITING },
        done = tasks.count { it.status == Status.DONE },
    )
    return ExportDto(generatedAt = nowUtc(), counts = counts, tasks = dtos)
}
