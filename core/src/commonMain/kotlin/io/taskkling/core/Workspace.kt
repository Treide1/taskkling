package io.taskkling.core

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.SYSTEM

/**
 * The handful of `config.toml` keys the tool reads (PRD §14). Parsed leniently;
 * unknown keys are ignored so the file can carry documented-but-unused settings.
 *
 * [updateCheck] is deliberately nullable — `null` means "the key is absent from
 * THIS file", not "off" — so [resolveUpdateCheckEnabled] can implement ADR-005's
 * precedence (a workspace `.taskkling/config.toml` overrides a user-level one;
 * the flag defaults to `false` only once BOTH are absent). This same [load]/
 * [Config] pair is reused to parse the user-level `config.toml` ([loadUserConfig]) —
 * its other fields (`tasks_dir` etc.) are simply irrelevant there.
 */
public data class Config(
    val tasksDir: String = "tasks",
    val idPrefix: String = "t-",
    val defaultThread: String = "",
    val lockTimeout: Int = 30,
    val updateCheck: Boolean? = null,
    /**
     * The explicit binary the UI's resolution chain falls back to (PRD §14, step
     * 3 of `CliDiscovery`'s order). Blank — the template's own value — means
     * "not configured", i.e. the chain moves on to `PATH`. Nothing in `:core`
     * *acts* on it; `doctor` reads it to report which rule of that chain
     * resolves a binary from here (t-8der), which is why the key is parsed at
     * all rather than tolerated as an unknown one.
     */
    val binaryPath: String = "",
) {
    public companion object {
        public val DEFAULT: Config = Config()

        public fun load(fs: FileSystem, path: Path): Config {
            if (!fs.exists(path)) return DEFAULT
            var c = DEFAULT
            val text = fs.read(path) { readUtf8() }
            for (raw in text.lines()) {
                val line = raw.substringBefore('#').trim()
                if (line.isEmpty() || '=' !in line) continue
                val key = line.substringBefore('=').trim()
                val value = line.substringAfter('=').trim().trim('"')
                c = when (key) {
                    "tasks_dir" -> c.copy(tasksDir = value)
                    "id_prefix" -> c.copy(idPrefix = value)
                    "default_thread" -> c.copy(defaultThread = value)
                    "lock_timeout" -> c.copy(lockTimeout = value.toIntOrNull() ?: c.lockTimeout)
                    "update_check" -> c.copy(updateCheck = value.toBooleanStrictOrNull() ?: c.updateCheck)
                    "binary_path" -> c.copy(binaryPath = value)
                    else -> c
                }
            }
            return c
        }

        /**
         * The documented default `config.toml` written by `init` (PRD §14).
         * [tasksDir] deviates from the default only for `init --demo-mode`
         * (ADR-017), which keeps the sandbox tasks inside the meta dir.
         */
        public fun defaultToml(tasksDir: String = "tasks"): String =
            """
            tasks_dir       = "$tasksDir"      # active-task directory (archive/ and trash/ are subdirs)
            id_prefix       = "t-"         # task id prefix
            granularity     = "minute"     # day | minute | second (display/working; deferred feature)
            default_thread  = ""           # applied by `add` when --thread omitted
            lock_timeout    = 30           # seconds before a dead-PID lock is reclaimable
            binary_path     = ""           # optional explicit path the UI uses to find the CLI
            # update_check  = true         # newer-version notifier, on by default (ADR-006); set false to disable, or leave unset to inherit the user-level config
            """.trimIndent() + "\n"
    }
}

/**
 * The directories `uninstall --purge` deletes recursively, and whether they
 * cover the workspace's authored task files ([Workspace.purgePlan]). When
 * [coversTasks] is false the CLI must not claim task deletion in its
 * confirmation prompts — only config/caches (the meta dir) are erased.
 */
public data class PurgePlan(val targets: List<Path>, val coversTasks: Boolean)

/**
 * A resolved workspace: the root directory plus the derived paths every command
 * works against (PRD §9). Construct via [discover].
 */
public class Workspace(
    public val root: Path,
    public val config: Config,
) {
    public val tasksDir: Path get() = root / config.tasksDir
    public val archiveDir: Path get() = tasksDir / "archive"
    public val trashDir: Path get() = tasksDir / "trash"
    public val metaDir: Path get() = root / ".taskkling"
    public val configFile: Path get() = metaDir / "config.toml"
    public val lockFile: Path get() = metaDir / "lock"
    public val tmpDir: Path get() = metaDir / "tmp"

    /** Ids parsed from the `.md` filenames in [dir] (the `<id>` before `--`). */
    public fun idsInDir(dir: Path): Set<String> {
        val fs = FileSystem.SYSTEM
        if (!fs.exists(dir)) return emptySet()
        return fs.list(dir)
            .filter { it.name.endsWith(".md") }
            .map { idOfFileName(it.name) }
            .toSet()
    }

    /** The `.md` file for task [id] in [dir] (`<id>--*.md`), or null if absent. */
    public fun fileFor(dir: Path, id: String): Path? {
        val fs = FileSystem.SYSTEM
        if (!fs.exists(dir)) return null
        return fs.list(dir).firstOrNull { it.name.endsWith(".md") && idOfFileName(it.name) == id }
    }

    /** Ids of the active set (top-level `tasks/`). Valid `depends` targets are these plus [archiveDir] ids (ADR-014 graph-neutral archive). */
    public fun activeIds(): Set<String> = idsInDir(tasksDir)

    /** Every id the tool has issued (active + archive + trash), for collision-free generation. */
    public fun allKnownIds(): Set<String> =
        idsInDir(tasksDir) + idsInDir(archiveDir) + idsInDir(trashDir)

    /**
     * What `uninstall --purge` erases (ADR-004; t-qoyn): the meta dir plus the
     * resolved [tasksDir] when that lives elsewhere. The DEFAULT layout
     * (`tasks_dir = "tasks"`) keeps authored tasks OUTSIDE `.taskkling/`, so
     * purging the meta dir alone strands them; `archive/` and `trash/` are
     * subdirs of [tasksDir], so covering it covers every authored task.
     * Guarded lexically: a `tasks_dir` that resolves to the workspace root
     * itself, or escapes it, is never purged ([coversTasks] = false) — a
     * hand-edited `tasks_dir = "."` must not turn `--purge` into `rm -rf <root>`.
     */
    public fun purgePlan(): PurgePlan {
        val rootN = root.normalized()
        val meta = metaDir.normalized()
        val tasks = tasksDir.normalized()
        fun strictlyInside(p: Path, dir: Path): Boolean =
            generateSequence(p.parent) { it.parent }.any { it == dir }
        val coveredByMeta = tasks == meta || strictlyInside(tasks, meta)
        return when {
            coveredByMeta -> PurgePlan(listOf(meta), coversTasks = true)
            strictlyInside(tasks, rootN) -> PurgePlan(listOf(meta, tasks), coversTasks = true)
            else -> PurgePlan(listOf(meta), coversTasks = false)
        }
    }

    public companion object {
        /**
         * Resolve the workspace. With [rootOverride] (`--root`) the directory is
         * used as-is and must already be initialized; otherwise discovery walks
         * up from the cwd for `.taskkling/` (git-style, PRD §9).
         */
        public fun discover(rootOverride: String?): Workspace {
            val fs = FileSystem.SYSTEM
            if (rootOverride != null) {
                val root = fs.canonicalize(rootOverride.toPath())
                if (!fs.exists(root / ".taskkling")) {
                    throw TkError(ExitCode.USAGE, "no taskkling workspace at --root $root")
                }
                return openAt(fs, root)
            }
            val root = findWorkspaceRoot(fs, fs.canonicalize(".".toPath()))
                ?: throw TkError(ExitCode.USAGE, "not inside a taskkling workspace (run 'taskkling init')")
            return openAt(fs, root)
        }

        /** Build the [Workspace] rooted at [root], loading its `.taskkling/config.toml`. */
        private fun openAt(fs: FileSystem, root: Path): Workspace =
            Workspace(root, Config.load(fs, root / ".taskkling" / "config.toml"))

        /**
         * Walk up from [start] to the nearest ancestor directory that holds a
         * `.taskkling/` (git-style discovery, PRD §9), or null if none is found
         * before the filesystem root. Extracted from [discover] so the walk-up is
         * unit-testable without mutating the process working directory (the JVM
         * has no portable `chdir`, and okio's `canonicalize(".")` reads a cached
         * cwd, so a nested cwd cannot be simulated at runtime).
         */
        internal fun findWorkspaceRoot(fs: FileSystem, start: Path): Path? {
            var dir: Path? = start
            while (dir != null) {
                if (fs.exists(dir / ".taskkling")) return dir
                dir = dir.parent
            }
            return null
        }
    }
}
