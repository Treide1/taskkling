package io.taskkling.core

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.days

/**
 * Where `init --demo-mode` keeps the sandbox tasks (ADR-017): inside the meta
 * dir, so the repo's usual `.taskkling/` gitignore entry covers the whole demo
 * and deleting `.taskkling/` removes every trace.
 */
public const val DEMO_TASKS_DIR: String = ".taskkling/tasks"

/**
 * Seed the demo backlog for `init --demo-mode` (ADR-017): the taskkling
 * furnishing its burrow. Everything goes through the normal write path
 * ([addTask] and the lifecycle mutations), so the result is ordinary task files
 * a user can mutate freely — by CLI or UI — with all invariants enforced.
 *
 * The dataset is built to put every *reachable* primary state (DESIGN §2) on
 * screen at once: `done`, `dropped`, `waiting` (one resurfaced), deferred,
 * blocked (one card with several blockers, one two-layers deep) and ready (one
 * overdue), plus mixed priorities, due dates and bodies. Dates are relative to
 * seed time so the demo never goes stale. Returns the number of tasks created.
 *
 * The caller decides *whether* to seed (the CLI only seeds a workspace with no
 * known tasks); this function just plants.
 */
public fun Workspace.seedDemoTasks(): Int {
    val now = Clock.System.now()
    fun day(offset: Int): String =
        Instant.fromEpochSeconds((now + offset.days).epochSeconds).toString()

    var count = 0
    fun add(
        title: String,
        thread: String,
        depends: List<String> = emptyList(),
        due: String? = null,
        defer: String? = null,
        priority: String? = null,
        body: String = "",
    ): String {
        count++
        return addTask(
            AddArgs(
                title = title,
                thread = thread,
                depends = depends,
                due = due,
                defer = defer,
                priority = priority,
                body = body,
            ),
        ).task.id
    }

    add(
        "Read me first - this is a demo workspace", "guide",
        priority = "high",
        body = """
            This workspace was created by `taskkling init --demo-mode`. Everything in it
            is sandbox data: the taskkling is furnishing its burrow, and its little
            backlog exists so you can poke at a real, fully-formed task graph without
            consequences.

            Try things:
            - `taskkling list --ready`, or open the graph with `taskkling ui`
            - mark something done (`taskkling done <id>`) and watch dependents unblock
            - `taskkling wait <id> --on "an excuse"`, `link`/`unlink`, `set`, `reopen`

            Every state is on display: done, dropped, waiting (one resurfaced), deferred,
            blocked and ready, plus priorities and due dates (one overdue). Dates were
            seeded relative to the moment you ran init, so the graph stays lively.

            When you are done playing, delete `.taskkling/` and every trace is gone.
        """.trimIndent(),
    )

    // burrow: a done foundation chain feeding ready -> blocked -> deep-blocked layers.
    val tunnel = add(
        "Dig the entrance tunnel", "burrow",
        body = "Twelve claw-lengths, gentle slope, exit hidden under the fern.",
    )
    val moss = add(
        "Line the walls with moss", "burrow", depends = listOf(tunnel),
        body = "Only the springy kind from the north side of the oak.",
    )
    val nook = add(
        "Carve the storage nook", "burrow", depends = listOf(tunnel, moss),
        due = day(3), priority = "high",
        body = "Both blockers are done, so this is ready - and due soon.",
    )
    val bed = add("Move in the leaf bed", "burrow", depends = listOf(nook))
    add(
        "Patch the rain leak", "burrow", due = day(-2), priority = "high",
        body = "Drips right over the acorn pile. Ready AND overdue - do it first.",
    )
    val beehive = add(
        "Tunnel to the beehive", "burrow",
        body = "Direct honey pipeline. Abandoned: too sticky, bees objected.",
    )

    // pantry: waiting / resurfaced / deferred nodes that all block the stocking task.
    val mushrooms = add("Wait for the glowcap mushrooms to sprout", "pantry")
    val shelves = add("Chase up the acorn-shelf delivery", "pantry", priority = "low")
    val roots = add(
        "Plant winter roots", "pantry", defer = day(7),
        body = "Too early - the soil is still warm. Deferred a week from seeding.",
    )
    val stock = add(
        "Stock the pantry shelves", "pantry",
        depends = listOf(mushrooms, shelves, roots),
        body = "Blocked three ways: on a waiting task, a resurfaced one and a deferred one.",
    )
    add(
        "Host the housewarming feast", "burrow",
        depends = listOf(bed, stock), due = day(14),
        body = "The grand finale - two layers of blockers deep. Everyone is invited.",
    )

    markDone(tunnel)
    markDone(moss)
    markDropped(beehive)
    waitTask(mushrooms, until = null, on = "the autumn rains")
    // defer already elapsed -> the waiting task shows up resurfaced (PRD §11).
    waitTask(shelves, until = day(-1), on = "squirrel-post parcel #ss-427")

    return count
}
