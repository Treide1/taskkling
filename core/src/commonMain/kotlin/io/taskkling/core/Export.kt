package io.taskkling.core

import io.taskkling.contract.ComputedDto
import io.taskkling.contract.CountsDto
import io.taskkling.contract.ExportDto
import io.taskkling.contract.TaskDto
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** Derived, read-time attributes for one node (PRD §8.2); never stored. */
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
 * Compute every node's derived attributes over the given set (PRD §8.2, §11).
 * A `depends` id that isn't `done` (including dangling ids) counts as a blocker;
 * `dependents` is the inverse edge. A `defer`/`due` that won't parse is ignored
 * rather than fatal (the read path stays robust).
 */
public fun computeAll(tasks: List<Task>): Map<String, Computed> {
    val now = Clock.System.now()
    val doneIds = tasks.asSequence().filter { it.status == Status.DONE }.map { it.id }.toSet()

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
    val computed = computeAll(tasks)
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
