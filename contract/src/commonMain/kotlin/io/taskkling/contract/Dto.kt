package io.taskkling.contract

import kotlinx.serialization.Serializable

/**
 * The export/JSON contract shared by CLI output (writer) and UI input (reader).
 *
 * Mirrors PRD §12. These are deliberately thin placeholder DTOs for the M0
 * skeleton; semantics (enums, validation, computation) live in `:core`, not here.
 * Datetimes are canonical ISO-8601 UTC strings (PRD §8.1). Optional fields are
 * nullable so absence round-trips faithfully.
 */
@Serializable
public data class ExportDto(
    val generatedAt: String,
    val counts: CountsDto,
    val tasks: List<TaskDto>,
)

/** Aggregate counts surfaced alongside the task list (PRD §12). */
@Serializable
public data class CountsDto(
    val ready: Int = 0,
    val blocked: Int = 0,
    val waiting: Int = 0,
    val done: Int = 0,
)

/**
 * One task = one markdown file. Stored fields (PRD §8.1) plus a nested
 * [ComputedDto] (PRD §8.2, derived at read, never stored). Field names mirror
 * the stored vocabulary, never UI labels (ADR-008, DOMAIN_LANGUAGE §6). `body`
 * is present only when the export was produced with `--include-body`.
 */
@Serializable
public data class TaskDto(
    val id: String,
    val title: String,
    val thread: String? = null,
    val status: String,
    val waitingOn: String? = null,
    val depends: List<String> = emptyList(),
    val due: String? = null,
    val defer: String? = null,
    val priority: String = "normal",
    val created: String,
    val closed: String? = null,
    val computed: ComputedDto,
    val body: String? = null,
)

/** Derived attributes computed at read time (PRD §8.2). */
@Serializable
public data class ComputedDto(
    val ready: Boolean = false,
    val blocked: Boolean = false,
    val deferred: Boolean = false,
    val overdue: Boolean = false,
    val resurfaced: Boolean = false,
    val blockers: List<String> = emptyList(),
    val dependents: List<String> = emptyList(),
)
