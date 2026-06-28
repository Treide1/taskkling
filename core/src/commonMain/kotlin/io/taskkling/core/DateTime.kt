package io.taskkling.core

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Current wall-clock time as a canonical ISO-8601 UTC string, truncated to whole
 * seconds (e.g. `2026-06-28T10:15:00Z`). Used for `created`/`closed` stamps.
 */
public fun nowUtc(): String =
    Instant.fromEpochSeconds(Clock.System.now().epochSeconds).toString()

private val FULL_SECONDS = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}""")
private val NO_SECONDS = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")
private val DATE_ONLY = Regex("""\d{4}-\d{2}-\d{2}""")

/**
 * Validate and canonicalize a user-supplied datetime (`due`/`defer`) to ISO-8601
 * UTC, seconds precision. Forgiving on input granularity (PRD §8.1, §14):
 *   `2026-07-31T23:59:00Z` · `…T23:59:00` · `…T23:59` · `2026-07-31`
 * are all accepted; anything unparseable is a validation error.
 */
public fun normalizeDateTime(input: String): String {
    val s = input.trim()
    val candidates = buildList {
        add(s)
        if (!s.endsWith("Z") && '+' !in s) {
            when {
                FULL_SECONDS.matches(s) -> add("${s}Z")
                NO_SECONDS.matches(s) -> add("$s:00Z")
                DATE_ONLY.matches(s) -> add("${s}T00:00:00Z")
            }
        }
    }
    for (c in candidates) {
        val inst = runCatching { Instant.parse(c) }.getOrNull()
        if (inst != null) return Instant.fromEpochSeconds(inst.epochSeconds).toString()
    }
    throw TkError(
        ExitCode.VALIDATION,
        "invalid datetime '$input' (use e.g. 2026-07-31T23:59:00Z or 2026-07-31)",
    )
}
