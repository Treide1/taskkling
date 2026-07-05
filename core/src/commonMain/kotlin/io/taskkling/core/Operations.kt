package io.taskkling.core

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.random.Random

private const val ID_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

/** Outcome of [initWorkspace]. */
public data class InitResult(val root: Path, val alreadyExisted: Boolean)

/**
 * Scaffold a workspace in [rootOverride] (or the cwd): `.taskkling/`
 * (config.toml, tmp/) plus `tasks/` with `archive/` and `trash/` (PRD §9, §10.7).
 * Idempotent — re-running never clobbers an existing `config.toml`.
 */
public fun initWorkspace(rootOverride: String?): InitResult {
    val fs = FileSystem.SYSTEM
    val root = if (rootOverride != null) {
        fs.createDirectories(rootOverride.toPath())
        fs.canonicalize(rootOverride.toPath())
    } else {
        fs.canonicalize(".".toPath())
    }
    val meta = root / ".taskkling"
    val existed = fs.exists(meta)
    val cfg = Config.DEFAULT

    fs.createDirectories(meta)
    fs.createDirectories(meta / "tmp")
    fs.createDirectories(root / cfg.tasksDir)
    fs.createDirectories(root / cfg.tasksDir / "archive")
    fs.createDirectories(root / cfg.tasksDir / "trash")

    val configFile = meta / "config.toml"
    if (!fs.exists(configFile)) fs.write(configFile) { writeUtf8(Config.defaultToml()) }

    return InitResult(root, existed)
}

/** User-supplied inputs for [addTask] (PRD §10.4). */
public data class AddArgs(
    val title: String,
    val thread: String? = null,
    val depends: List<String> = emptyList(),
    val due: String? = null,
    val defer: String? = null,
    val priority: String? = null,
    val body: String? = null,
)

/**
 * Create a task (PRD §10.4). Runs under the write lock: title, priority,
 * datetimes and `depends` (dangling check vs. the active set) are validated, a
 * collision-free id is minted, and the file is written via the temp→rename path.
 * A brand-new task has no dependents, so no cycle is possible. With [exportAfter]
 * the full export is computed before the lock releases (PRD §7.3).
 */
public fun Workspace.addTask(args: AddArgs, exportAfter: Boolean = false): MutationResult = withLock {
    val title = args.title.trim()
    if (title.isEmpty()) throw TkError(ExitCode.VALIDATION, "title must not be empty")

    val priority = args.priority?.let { Priority.from(it) } ?: Priority.NORMAL
    val thread = (args.thread?.trim()?.ifEmpty { null }) ?: config.defaultThread.ifEmpty { null }
    val due = args.due?.let { normalizeDateTime(it) }
    val defer = args.defer?.let { normalizeDateTime(it) }
    val depends = args.depends.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    val active = activeIds()
    for (d in depends) {
        if (d !in active) throw TkError(ExitCode.VALIDATION, "depends references unknown id '$d'")
    }

    val id = newId(allKnownIds())
    val task = Task(
        id = id,
        title = title,
        thread = thread,
        status = Status.OPEN,
        depends = depends,
        due = due,
        defer = defer,
        priority = priority,
        created = nowUtc(),
        body = args.body?.trim().orEmpty(),
    )
    writeFileAtomic(tasksDir / task.fileName(), task.toMarkdown())
    MutationResult(task, if (exportAfter) buildExport(includeBody = false, includeArchived = false) else null)
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
