package io.taskkling.ui

import io.taskkling.contract.ExportDto
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Locates the `taskkling` binary for the UI (PRD §6.3, §13). The UI is a pure CLI
 * client and never touches task files, so finding the one binary is its only
 * environment concern. Resolution order, first hit wins:
 *   1. the `TASKKLING_BINARY` environment variable (explicit override),
 *   2. an up-tree `.taskkling/bin/taskkling[.exe]` (a per-project self-install
 *      via `init --local-bin`; walked up from the start dir like the config step),
 *   3. `binary_path` in the workspace's `.taskkling/config.toml` (PRD §14),
 *   4. an entry named `taskkling`/`taskkling.exe` on `PATH`.
 */
public object CliDiscovery {
    private val exeNames = listOf("taskkling", "taskkling.exe")

    public fun locate(startDir: File = File(System.getProperty("user.dir"))): String? {
        System.getenv("TASKKLING_BINARY")?.takeIf { it.isNotBlank() }?.let { p ->
            if (File(p).canExecute()) return p
        }
        localBinFromTree(startDir)?.let { return it }
        binaryPathFromConfig(startDir)?.let { return it }
        return onPath()
    }

    /** Walk up from [startDir] for a self-installed `.taskkling/bin/taskkling[.exe]`. */
    private fun localBinFromTree(startDir: File): String? {
        var dir: File? = startDir.absoluteFile
        while (dir != null) {
            for (name in exeNames) {
                val f = File(dir, ".taskkling/bin/$name")
                if (f.isFile && f.canExecute()) return f.absolutePath
            }
            dir = dir.parentFile
        }
        return null
    }

    /** Walk up from [startDir] for `.taskkling/config.toml` and read its `binary_path`. */
    private fun binaryPathFromConfig(startDir: File): String? {
        var dir: File? = startDir.absoluteFile
        while (dir != null) {
            val cfg = File(dir, ".taskkling/config.toml")
            if (cfg.isFile) {
                val value = cfg.readLines()
                    .map { it.substringBefore('#').trim() }
                    .firstOrNull { it.startsWith("binary_path") && '=' in it }
                    ?.substringAfter('=')?.trim()?.trim('"')
                return value?.takeIf { it.isNotBlank() && File(it).canExecute() }
            }
            dir = dir.parentFile
        }
        return null
    }

    private fun onPath(): String? {
        val path = System.getenv("PATH") ?: return null
        for (dir in path.split(File.pathSeparatorChar)) {
            for (name in exeNames) {
                val f = File(dir, name)
                if (f.isFile && f.canExecute()) return f.absolutePath
            }
        }
        return null
    }
}

/** A failed CLI invocation, carrying the process exit code and the CLI's own reason. */
public class CliException(public val code: Int, message: String) : RuntimeException(message)

/** True when this JVM will hand argv to a child through a Windows command line ([encodeArg]). */
private val IS_WINDOWS: Boolean =
    System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/**
 * Quote one argument the way the Microsoft C runtime parses it back (t-ctbc).
 *
 * Windows has no argv: `CreateProcess` takes ONE command line string, and the child's CRT
 * re-splits it. Java can't opt out — `ProcessBuilder` joins our list into that string
 * itself, and its default "legacy" mode wraps an arg containing spaces in quotes without
 * escaping the quotes INSIDE it. The CRT then re-splits at the wrong places, which is where
 * the reported bug came from: a title like `Fix the "thing" now` silently stored as
 * `Fix the thing now`, and `quote " mid` failing with a bare `add: taskkling exited 127`
 * ("Too many arguments!" — the CRT had turned one title into two). Only `"` is affected;
 * the `.`, `,` and `-` in the report are innocent.
 *
 * So encode the argument ourselves, per the CRT's documented rules:
 *   - a `"` is escaped as `\"`,
 *   - backslashes are literal EXCEPT in a run directly before a `"`, where each doubles,
 *   - the whole thing is wrapped in quotes (unconditionally — harmless, and it is what
 *     makes the pass-through below fire).
 *
 * The pass-through: `ProcessImpl` appends an argument verbatim when it already starts and
 * ends with a quote (JDK 21 ProcessImpl.needsEscaping → `argIsQuoted`), so a fully-encoded
 * token reaches the child untouched instead of being re-quoted. That legacy path is the
 * default whenever there is no SecurityManager — always, on JDK 21+, where the
 * SecurityManager is permanently disabled. `CliClientTest` pins the whole contract by
 * decoding with a reference CRT splitter, so a JDK that changed this would fail loudly
 * rather than quietly corrupt titles again.
 *
 * Not an option: `-Djdk.lang.Process.allowAmbiguousCommands=false`. It fixes interior
 * quotes but still strips them from an argument that both starts AND ends with one
 * (`"fully quoted"`), and throws `IllegalArgumentException` on `"a" and "b"`.
 */
internal fun encodeArg(arg: String): String {
    val sb = StringBuilder(arg.length + 8).append('"')
    var slashes = 0
    for (c in arg) {
        when (c) {
            '\\' -> slashes++
            '"' -> {
                // This run of backslashes precedes a quote, so it must be doubled, and
                // then one more escapes the quote itself.
                repeat(slashes * 2 + 1) { sb.append('\\') }
                slashes = 0
                sb.append('"')
            }
            else -> {
                repeat(slashes) { sb.append('\\') }
                slashes = 0
                sb.append(c)
            }
        }
    }
    // A trailing run abuts the closing quote, so it doubles for the same reason.
    repeat(slashes * 2) { sb.append('\\') }
    return sb.append('"').toString()
}

/**
 * The argv to hand [ProcessBuilder]. On POSIX this is identity: `execve` takes a real
 * string vector, nothing re-parses it, and quoting would embed literal quotes in the
 * values. Only Windows needs [encodeArg]. The binary path is left alone either way —
 * Java quotes the executable itself, and it holds no user text.
 */
internal fun commandLine(binary: String, args: List<String>, windows: Boolean = IS_WINDOWS): List<String> =
    listOf(binary) + if (windows) args.map(::encodeArg) else args

/** How much of a blank-stderr failure's stdout [cliFailureMessage] quotes. */
private const val EXCERPT_LINES = 3
private const val EXCERPT_CHARS = 300

/**
 * The message for a non-zero exit (t-eeze). The CLI's own errors go to stderr, so that
 * wins when present. But argument-level rejections never reach it: kotlinx-cli prints
 * "Unknown option --x" (and a usage dump) to STDOUT and exits 127 with stderr EMPTY, so
 * the old `stderr.ifBlank { "taskkling exited $code" }` fallback threw the only
 * explanation away — a UI built against new CLI flags with an older pinned binary showed
 * a bare "add: taskkling exited 127". When stderr is blank, quote stdout instead.
 *
 * Note kotlinx-cli puts the diagnosis FIRST and the usage dump after it, so this excerpts
 * the HEAD, not the tail, and stops at the usage block — that part is boilerplate `--help`
 * already prints, and it would bury the one line that matters.
 */
internal fun cliFailureMessage(code: Int, stdout: String, stderr: String): String {
    val reason = stderr.trim().ifEmpty { stdoutExcerpt(stdout) }
    return if (reason.isEmpty()) "taskkling exited $code" else "taskkling exited $code: $reason"
}

/** The leading, non-boilerplate lines of [raw], flattened to one bounded line. */
private fun stdoutExcerpt(raw: String): String {
    val lines = raw.replace("\r\n", "\n").replace("\r", "\n")
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .takeWhile { !it.startsWith("Usage:") }
        .take(EXCERPT_LINES)
        .toList()
    val text = lines.joinToString(" ")
    return if (text.length > EXCERPT_CHARS) text.take(EXCERPT_CHARS).trimEnd() + "…" else text
}

/** `--version`'s payload line: `taskkling 0.6.3`. Any notifier chatter around it is skipped. */
private val VERSION_LINE = Regex("""^taskkling\s+(\S+)$""")

/**
 * The version the binary reports (`taskkling <version>` on stdout), or null if it can't be
 * read — an ancient binary without the flag, or one that won't run. Null means "don't know",
 * and the caller stays silent rather than guessing at skew (t-eeze).
 */
internal fun parseVersionOutput(raw: String): String? = raw
    .replace("\r\n", "\n").replace("\r", "\n")
    .lineSequence()
    .mapNotNull { VERSION_LINE.find(it.trim())?.groupValues?.get(1) }
    .firstOrNull()

/**
 * Normalize the CLI's `get --body` stdout for display/editing. The native binary's stdout is
 * text-mode on Windows, so `println` translates every `\n` to `\r\n`; read back as raw bytes,
 * a trailing `\r` would otherwise survive [trimEnd]`('\n')` and render as a stray space at the
 * body's end (and internal lines would carry `\r`). Fold every `\r\n`/`\r`/`\n` variant to a
 * single `\n`, then drop trailing blank lines so the loaded text equals the CLI's own
 * fully-trimmed stored body.
 */
internal fun normalizeBodyText(raw: String): String = raw
    .replace("\r\n", "\n")
    .replace("\r", "\n")
    .trimEnd('\n')

/**
 * The UI's entire dependency on the outside world (PRD §6.3): five blocking calls
 * against a task store. [CliClient] is the real adapter — a `taskkling` subprocess;
 * tests supply an in-memory one. Every method blocks, so callers ([AppStore]) hop to
 * an IO dispatcher.
 *
 * This lives in `:ui`, not `:contract` — the contract stays DTO-only (ADR-008), and
 * this interface describes how *this* client talks, not what crosses the wire.
 */
public interface TaskklingClient {
    /** The current export; [includeBody] asks for each task's markdown body too. */
    public fun export(includeBody: Boolean = false): ExportDto

    /** Run a mutation verb with its arguments; returns the post-mutation export. */
    public fun mutate(args: List<String>): ExportDto

    /** One task's body text. */
    public fun body(id: String): String

    /** Replace one task's body. */
    public fun writeBody(id: String, text: String)

    /** The backing tool's version, or null when it can't be determined (t-eeze). */
    public fun version(): String?
}

/**
 * Thin subprocess wrapper around the `taskkling` [binary] (PRD §6.3). Reads run
 * `export`; mutations append `--export-on-success` so the post-mutation export
 * comes back in the same call (a TOCTOU-free read-after-write, PRD §7.3) and the
 * UI refreshes from it. The UI holds no parallel model and never writes files.
 */
public class CliClient(
    private val binary: String,
    private val workdir: File = File(System.getProperty("user.dir")),
) : TaskklingClient {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }

    /** `taskkling export [--include-body]` → parsed [ExportDto]. */
    override fun export(includeBody: Boolean): ExportDto {
        val args = buildList { add("export"); if (includeBody) add("--include-body") }
        return json.decodeFromString(ExportDto.serializer(), run(args).stdout)
    }

    /**
     * Run a mutation verb (e.g. `done`, `set`) with `--export-on-success` and
     * return the refreshed export. Caller passes the verb and its arguments.
     */
    override fun mutate(args: List<String>): ExportDto {
        val full = args + "--export-on-success"
        return json.decodeFromString(ExportDto.serializer(), run(full).stdout)
    }

    /** `taskkling get <id> --body` → the task's body text, newline-normalized ([normalizeBodyText]). */
    override fun body(id: String): String = normalizeBodyText(run(listOf("get", id, "--body")).stdout)

    /**
     * `taskkling write <id> -` — replace the task's body, feeding [text] on stdin
     * rather than as an argv value. Arbitrary body text (newlines, non-ASCII) round-trips
     * cleanly through the pipe, sidestepping the platform's argv quoting/encoding (the
     * umlaut-argv class of bugs). Body edits don't touch the graph, so this deliberately
     * skips `--export-on-success`: the panel already holds the text it just saved.
     */
    override fun writeBody(id: String, text: String) {
        val proc = ProcessBuilder(commandLine(binary, listOf("write", id, "-")))
            .directory(workdir)
            .redirectErrorStream(false)
            .start()
        // The CLI reads stdin to EOF before it emits anything, so write-all-then-close
        // ahead of any read is deadlock-free (the tiny id echo can't fill the pipe).
        proc.outputStream.bufferedWriter().use { it.write(text) }
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) throw CliException(code, cliFailureMessage(code, out, err))
    }

    /**
     * `taskkling --version` → the binary's version, or null when it can't be determined
     * (flag absent, binary unrunnable). Deliberately best-effort: a failed probe must never
     * break launch, so this swallows rather than throws (t-eeze). The version notifier the
     * flag can print is TTY-gated, so this subprocess never triggers its network call.
     */
    override fun version(): String? =
        runCatching { parseVersionOutput(run(listOf("--version")).stdout) }.getOrNull()

    private data class Output(val stdout: String, val stderr: String)

    private fun run(args: List<String>): Output {
        val proc = ProcessBuilder(commandLine(binary, args))
            .directory(workdir)
            .redirectErrorStream(false)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) throw CliException(code, cliFailureMessage(code, out, err))
        return Output(out, err)
    }
}
