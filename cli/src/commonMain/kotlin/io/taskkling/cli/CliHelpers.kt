package io.taskkling.cli

import io.taskkling.core.Computed
import io.taskkling.core.ExitCode
import io.taskkling.core.Status
import io.taskkling.core.Task
import io.taskkling.core.TkError

/**
 * Pure CLI helpers, extracted from Main.kt into a test seam (mirrors the `:ui`
 * layout-math seam, t-qkqo). These are the single read+write path's argv-parsing and
 * rendering primitives — `internal` so the `commonTest` source set can exercise their
 * edge cases in isolation, while `Main.kt` still calls them for the real commands.
 */

/** Read all of stdin to end-of-input, for body text supplied as `-` (agent ergonomics). */
internal fun readStdin(): String = buildString {
    while (true) {
        val line = readlnOrNull() ?: break
        append(line).append('\n')
    }
}.trimEnd('\n')

/**
 * Resolve body text: a literal `-` means "read the body from stdin" (t-gmb9). The stdin
 * reader is injectable so the `-` convention can be tested without a real pipe; production
 * callers use the default [readStdin].
 */
internal fun bodyArg(text: String, readStdin: () -> String = ::readStdin): String =
    if (text == "-") readStdin() else text

/**
 * Normalize `-d`/`--depends` values into a clean id list (regression guard for t-sy9n).
 * The option may be repeated AND each occurrence may itself be comma-separated, so
 * `-d a -d b,c` == `-d a,b,c`. Segments are trimmed, empties (stray/trailing commas)
 * dropped, and the union de-duplicated preserving first-seen order.
 */
internal fun flattenDepends(raw: List<String>): List<String> =
    raw.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/**
 * Normalize `cleanup --only` values (same repeatable/comma grammar as `-d`) into
 * the closed-status subset the sweep is narrowed to, or null when the flag is
 * absent (= sweep both, the historical behavior). Only the two closed statuses
 * are nameable; anything else is a usage error.
 */
internal fun parseOnlyStatuses(raw: List<String>): Set<Status>? {
    val values = raw.flatMap { it.split(",") }.map { it.trim() }.filter { it.isNotEmpty() }
    if (values.isEmpty()) return null
    return values.map {
        when (it) {
            "done" -> Status.DONE
            "dropped" -> Status.DROPPED
            else -> throw TkError(ExitCode.USAGE, "invalid --only value '$it' (allowed: done, dropped)")
        }
    }.toSet()
}

/**
 * The parsed leading globals (PRD §10.1): the recognised git-style flags that appear
 * *before* the subcommand, plus [rest] — the remaining argv (subcommand + its args) that
 * kotlinx-cli parses. A pure value so [parseLeadingGlobals] can be unit-tested; `Main.kt`
 * folds the flag fields into its `GlobalFlags` object.
 */
internal data class LeadingGlobals(
    val rest: List<String>,
    val root: String? = null,
    val quiet: Boolean = false,
    val noColor: Boolean = false,
)

/**
 * Parse recognised global flags that appear *before* the subcommand name
 * (`taskkling --root <path> --quiet <verb> …`, PRD §10.1). Only the leading run is
 * scanned — once the subcommand token is seen every later token passes straight into
 * [LeadingGlobals.rest], so a literal `--root` later in the line (e.g. body text) is never
 * mistaken for a flag. Unknown leading flags also pass through so kotlinx-cli reports them.
 */
internal fun parseLeadingGlobals(args: Array<String>): LeadingGlobals {
    val rest = ArrayList<String>()
    var root: String? = null
    var quiet = false
    var noColor = false
    var i = 0
    var sawVerb = false
    while (i < args.size) {
        val a = args[i]
        if (sawVerb) { rest.add(a); i++; continue }
        when {
            a == "--root" -> { root = args.getOrNull(i + 1); i += 2 }
            a.startsWith("--root=") -> { root = a.substringAfter('='); i++ }
            a == "--quiet" || a == "-q" -> { quiet = true; i++ }
            a == "--no-color" -> { noColor = true; i++ }
            a.startsWith("-") -> { rest.add(a); i++ } // unknown leading flag: let the parser report it
            else -> { sawVerb = true; rest.add(a); i++ } // the subcommand token
        }
    }
    return LeadingGlobals(rest, root, quiet, noColor)
}

/** Aligned, header-less `ls -la`-style table: id · title · thread · status · attributes. */
internal fun formatTable(rows: List<Task>): String {
    if (rows.isEmpty()) return ""
    data class Row(val id: String, val title: String, val thread: String, val status: String, val attrs: String)

    val cells = rows.map { t ->
        Row(
            id = t.id,
            title = if (t.title.length > 50) t.title.take(49) + "…" else t.title,
            thread = t.thread ?: "-",
            status = t.status.wire,
            attrs = buildAttrs(t),
        )
    }
    val idW = cells.maxOf { it.id.length }
    val titleW = cells.maxOf { it.title.length }
    val threadW = cells.maxOf { it.thread.length }
    val statusW = cells.maxOf { it.status.length }

    return cells.joinToString("\n") { r ->
        buildString {
            append(r.id.padEnd(idW)); append("  ")
            append(r.title.padEnd(titleW)); append("  ")
            append(r.thread.padEnd(threadW)); append("  ")
            append(r.status.padEnd(statusW))
            if (r.attrs.isNotEmpty()) {
                append("  "); append(r.attrs)
            }
        }
    }
}

/** Ordered stored + computed fields for `get` (PRD §8.1/§8.2). */
internal fun fieldMap(t: Task, c: Computed): LinkedHashMap<String, String> {
    fun s(v: String?) = v ?: ""
    return linkedMapOf(
        "id" to t.id,
        "title" to t.title,
        "thread" to s(t.thread),
        "status" to t.status.wire,
        "waiting_on" to s(t.waitingOn),
        "depends" to t.depends.joinToString(","),
        "due" to s(t.due),
        "defer" to s(t.defer),
        "priority" to t.priority.wire,
        "created" to t.created,
        "closed" to s(t.closed),
        "ready" to c.ready.toString(),
        "blocked" to c.blocked.toString(),
        "deferred" to c.deferred.toString(),
        "overdue" to c.overdue.toString(),
        "resurfaced" to c.resurfaced.toString(),
        "blockers" to c.blockers.joinToString(","),
        "dependents" to c.dependents.joinToString(","),
    )
}

/** Fold the non-empty relational/time fields into one column (PRD §10.2). */
internal fun buildAttrs(t: Task): String {
    val parts = ArrayList<String>()
    if (t.depends.isNotEmpty()) parts.add("depends:" + t.depends.joinToString(","))
    if (t.due != null) parts.add("due:${t.due}")
    if (t.defer != null) parts.add("defer:${t.defer}")
    if (t.waitingOn != null) parts.add("waiting:${t.waitingOn}")
    return parts.joinToString("  ")
}
