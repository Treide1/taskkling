package io.taskkling.core

import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

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
                    else -> c
                }
            }
            return c
        }

        /** The documented default `config.toml` written by `init` (PRD §14). */
        public fun defaultToml(): String =
            """
            tasks_dir       = "tasks"      # active-node directory (archive/ and trash/ are subdirs)
            id_prefix       = "t-"         # node id prefix
            granularity     = "minute"     # day | minute | second (display/working; deferred feature)
            default_thread  = ""           # applied by `add` when --thread omitted
            lock_timeout    = 30           # seconds before a dead-PID lock is reclaimable
            binary_path     = ""           # optional explicit path the UI uses to find the CLI
            # update_check  = true         # newer-version notifier, on by default (ADR-006); set false to disable, or leave unset to inherit the user-level config
            """.trimIndent() + "\n"
    }
}

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

    /** The `.md` file for node [id] in [dir] (`<id>--*.md`), or null if absent. */
    public fun fileFor(dir: Path, id: String): Path? {
        val fs = FileSystem.SYSTEM
        if (!fs.exists(dir)) return null
        return fs.list(dir).firstOrNull { it.name.endsWith(".md") && idOfFileName(it.name) == id }
    }

    /** Ids of the active set (top-level `tasks/`), the only valid `depends` targets. */
    public fun activeIds(): Set<String> = idsInDir(tasksDir)

    /** Every id the tool has issued (active + archive + trash), for collision-free generation. */
    public fun allKnownIds(): Set<String> =
        idsInDir(tasksDir) + idsInDir(archiveDir) + idsInDir(trashDir)

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
                return Workspace(root, Config.load(fs, root / ".taskkling" / "config.toml"))
            }
            var dir: Path? = fs.canonicalize(".".toPath())
            while (dir != null) {
                if (fs.exists(dir / ".taskkling")) {
                    return Workspace(dir, Config.load(fs, dir / ".taskkling" / "config.toml"))
                }
                dir = dir.parent
            }
            throw TkError(ExitCode.USAGE, "not inside a taskkling workspace (run 'taskkling init')")
        }
    }
}
