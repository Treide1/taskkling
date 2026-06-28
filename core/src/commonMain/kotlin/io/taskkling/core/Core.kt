package io.taskkling.core

import io.taskkling.contract.CountsDto
import io.taskkling.contract.ExportDto

/**
 * Placeholder domain entry point for the M0 skeleton.
 *
 * The real `:core` will own frontmatter parse/serialize, the DAG + ready-set,
 * write-path validation, and the lock/atomic-write primitives (PRD §6.1, §7).
 * None of that exists yet — this module only proves the dependency wiring
 * (`:core` → `:contract`, plus Okio on the classpath) and compiles on every
 * declared target.
 */
public object Taskkling {
    /** Tool version. Bumped as milestones land. */
    public const val VERSION: String = "0.0.1-SNAPSHOT"
}

/**
 * Stub export computation. Returns an empty, well-formed [ExportDto] with a
 * fixed timestamp. Real implementation (read tasks/, derive computed attrs,
 * stream JSON) arrives in M0/M1 per PRD §7.2 / §11 / §12.
 */
public fun computeExport(): ExportDto =
    ExportDto(
        generatedAt = "1970-01-01T00:00:00Z",
        counts = CountsDto(ready = 0, blocked = 0, waiting = 0, done = 0),
        tasks = emptyList(),
    )
