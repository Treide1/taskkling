package io.taskkling.core

import io.taskkling.contract.ExportDto
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.random.Random

private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

/** Outcome of [initWorkspace]. */
public data class InitResult(val root: Path, val alreadyExisted: Boolean)

/**
 * Scaffold a workspace in [rootOverride] (or the cwd): `.taskkling/`
 * (config.toml, tmp/) plus `tasks/` with `archive/` and `trash/` (PRD §9, §10.7).
 * Idempotent — re-running never clobbers an existing `config.toml`, and a
 * pre-existing config's `tasks_dir` is honored (t-wqwt): scaffolding happens at
 * the RESOLVED location, never at the default one beside it.
 *
 * With [demoLayout] (`init --demo-mode`, ADR-017) a *freshly written* config
 * points `tasks_dir` inside the meta dir ([DEMO_TASKS_DIR]) so the whole sandbox
 * lives — and dies — with `.taskkling/`, invisible to git. An existing config's
 * layout always wins; the flag never rewrites it.
 */
public fun initWorkspace(rootOverride: String?, demoLayout: Boolean = false): InitResult {
    val fs = FileSystem.SYSTEM
    val root = if (rootOverride != null) {
        fs.createDirectories(rootOverride.toPath())
        fs.canonicalize(rootOverride.toPath())
    } else {
        fs.canonicalize(".".toPath())
    }
    val meta = root / ".taskkling"
    val existed = fs.exists(meta)
    val configFile = meta / "config.toml"
    val cfg = when {
        fs.exists(configFile) -> Config.load(fs, configFile)
        demoLayout -> Config.DEFAULT.copy(tasksDir = DEMO_TASKS_DIR)
        else -> Config.DEFAULT
    }

    fs.createDirectories(meta)
    fs.createDirectories(meta / "tmp")
    fs.createDirectories(root / cfg.tasksDir)
    fs.createDirectories(root / cfg.tasksDir / "archive")
    fs.createDirectories(root / cfg.tasksDir / "trash")

    if (!fs.exists(configFile)) fs.write(configFile) { writeUtf8(Config.defaultToml(cfg.tasksDir)) }

    return InitResult(root, existed)
}

/** User-supplied inputs for [addTask] (PRD §10.4). */
public data class AddArgs(
    val title: String,
    val thread: String? = null,
    val status: String? = null,
    val req: String? = null,
    val depends: List<String> = emptyList(),
    val due: String? = null,
    val defer: String? = null,
    val priority: String? = null,
    val body: String? = null,
)

/**
 * One record of an atomic batch create ([addTasks]): exactly the [AddArgs] a single
 * `add` takes, plus an optional local handle [ref] that a LATER record may name in its
 * `depends` to wire an edge onto a task whose id does not exist yet (it is minted by
 * this same call). Backward references only — see [addTasks].
 */
public data class BatchAddArgs(val args: AddArgs, val ref: String? = null)

/** Outcome of [addTasks]: the created tasks **in input order**, plus the optional post-batch export. */
public data class BatchResult(val tasks: List<Task>, val export: ExportDto?)

/**
 * Validate [args] and build the [Task] to write — all of `add`'s §7.5 preventive checks,
 * minus the write. Extracted so [addTask] and [addTasks] share ONE definition of what a
 * valid new task is (and one BOM-strip/trim of the body); a batch that re-derived these
 * rules would drift from the single-add path the moment either changed.
 *
 * [known] is the id set `depends` may reference and [taken] the ids the minted id must
 * avoid. Both are parameters rather than reads of the workspace because a batch widens
 * them row by row with the ids it has already minted but not yet written.
 *
 * Caller must hold the write lock.
 */
private fun Workspace.buildNewTask(args: AddArgs, known: Set<String>, taken: Set<String>): Task {
    val title = args.title.trim()
    if (title.isEmpty()) throw TkError(ExitCode.VALIDATION, "title must not be empty")

    val priority = args.priority?.let { Priority.from(it) } ?: Priority.NORMAL
    val thread = (args.thread?.trim()?.ifEmpty { null }) ?: config.defaultThread.ifEmpty { null }
    val status = args.status?.trim()?.ifEmpty { null }?.let { Status.from(it) } ?: Status.OPEN
    val req = args.req?.trim()?.ifEmpty { null }
    val due = args.due?.let { normalizeDateTime(it) }
    val defer = args.defer?.let { normalizeDateTime(it) }
    val depends = args.depends.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    for (d in depends) {
        if (d !in known) throw TkError(ExitCode.VALIDATION, "depends references unknown id '$d'")
    }

    return Task(
        id = newId(taken),
        title = title,
        thread = thread,
        // Status is a first-class create field (ADR-018); creating straight into
        // a closed state stamps `closed` under the same rule as a later transition.
        status = status,
        waitingOn = req,
        depends = depends,
        due = due,
        defer = defer,
        priority = priority,
        created = nowUtc(),
        closed = if (status == Status.DONE || status == Status.DROPPED) nowUtc() else null,
        body = args.body?.stripLeadingBom()?.trim().orEmpty(),
    )
}

/**
 * Create a task (PRD §10.4). Runs under the write lock: title, priority,
 * datetimes and `depends` (dangling check vs. active + archive, ADR-014) are validated, a
 * collision-free id is minted, and the file is written via the temp→rename path.
 * A brand-new task has no dependents, so no cycle is possible. With [exportAfter]
 * the full export is computed before the lock releases (PRD §7.3).
 */
public fun Workspace.addTask(args: AddArgs, exportAfter: Boolean = false): MutationResult = withLock {
    val task = buildNewTask(args, known = activeIds() + idsInDir(archiveDir), taken = allKnownIds())
    writeFileAtomic(tasksDir / task.fileName(), task.toMarkdown())
    MutationResult(task, if (exportAfter) buildExport(includeBody = false, includeArchived = false) else null)
}

/**
 * `add --batch -` (t-zsh6) — create every task in [batch] **atomically**, returning them in
 * input order. The single-call path for scaffolding a milestone: N work tasks plus a gate
 * that depends on them, bodies included, without capturing each minted id and re-invoking
 * `add` per row.
 *
 * **Intra-batch wiring.** A record may carry a [BatchAddArgs.ref] — a local handle. Each
 * `depends` entry resolves to an existing task id, else to a ref declared by an EARLIER
 * record. Backward references only, which makes an intra-batch cycle *structurally*
 * impossible (an edge can only ever point at an already-built row) rather than merely
 * rejected — so this path needs no cycle check at all. A ref that shadows an existing id
 * loses to that id, per the resolution order above.
 *
 * **Atomicity is validate-all-then-write.** Pass 1 validates and mints every row; only once
 * ALL of them pass does pass 2 write anything. Any failure throws having written nothing, so
 * a rejected batch is always safe to fix and retry — a half-landed batch would instead leave
 * orphaned ids the caller cannot see and must not re-create. Both passes share ONE lock
 * acquisition: [addTask] cannot be reused per row here, because the lock is not reentrant
 * (see [withLock]) — a nested acquire would self-deadlock into an [ExitCode.LOCK] timeout on
 * Windows/JVM, and on POSIX the inner release would silently drop the outer lock mid-batch.
 *
 * Errors name the offending record by **0-based** index (its position in the caller's input)
 * and carry the underlying field-level complaint verbatim, so `add --batch`'s diagnostics are
 * the same ones `add` gives — with a row attached.
 */
public fun Workspace.addTasks(batch: List<BatchAddArgs>, exportAfter: Boolean = false): BatchResult = withLock {
    if (batch.isEmpty()) throw TkError(ExitCode.VALIDATION, "batch contains no records (nothing to create)")

    // Every ref in the batch, so an unresolvable dep can distinguish a FORWARD reference (a
    // real handle, declared too late — reorder the records) from a genuine typo. Two very
    // different fixes; one generic "unknown id" would hide which is which.
    val declaredRefs = batch.mapNotNull { it.ref?.trim()?.ifEmpty { null } }.toSet()

    val known = (activeIds() + idsInDir(archiveDir)).toMutableSet()
    val taken = allKnownIds().toMutableSet()
    val refToId = HashMap<String, String>()

    // --- Pass 1: validate + mint EVERY row. Writes nothing. ---
    val tasks = batch.mapIndexed { i, record ->
        val ref = record.ref?.trim()?.ifEmpty { null }
        if (ref != null && ref in refToId) {
            throw TkError(ExitCode.VALIDATION, "batch record $i: duplicate ref '$ref'")
        }
        val resolved = record.args.depends.map { it.trim() }.filter { it.isNotEmpty() }.distinct().map { d ->
            when {
                // Resolution order is decided: an existing id first, then a local handle.
                // `known` also holds the ids minted by earlier rows, which is what lets a
                // ref-resolved edge pass buildNewTask's dangling check below.
                d in known -> d
                d in refToId -> refToId.getValue(d)
                d == ref -> throw TkError(ExitCode.VALIDATION, "batch record $i: depends on its own ref '$d'")
                d in declaredRefs -> throw TkError(
                    ExitCode.VALIDATION,
                    "batch record $i: depends references ref '$d' declared by a LATER record — " +
                        "refs must be declared before they are referenced (move that record earlier)",
                )
                else -> throw TkError(ExitCode.VALIDATION, "batch record $i: depends references unknown id or ref '$d'")
            }
        }
        // Re-wrap field-level failures with the row: the caller sees one array, and
        // "invalid priority 'urgent'" alone does not say which of 12 records to fix.
        val task = try {
            buildNewTask(record.args.copy(depends = resolved), known, taken)
        } catch (e: TkError) {
            throw TkError(e.exit, "batch record $i: ${e.message}")
        }
        taken += task.id
        known += task.id
        if (ref != null) refToId[ref] = task.id
        task
    }

    // --- Pass 2: every row is valid — commit. ---
    for (task in tasks) writeFileAtomic(tasksDir / task.fileName(), task.toMarkdown())
    BatchResult(tasks, if (exportAfter) buildExport(includeBody = false, includeArchived = false) else null)
}

/** Mint `id_prefix` + 4 random `[a-z0-9]`, retrying on collision (PRD §8.1). */
public fun Workspace.newId(existing: Set<String>): String {
    repeat(1000) {
        val id = buildString {
            append(config.idPrefix)
            repeat(4) { append(ID_ALPHABET[Random.nextInt(ID_ALPHABET.length)]) }
        }
        if (id !in existing) return id
    }
    throw TkError(ExitCode.VALIDATION, "id space exhausted (could not mint a unique id)")
}
