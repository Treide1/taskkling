package io.taskkling.ui

import io.taskkling.contract.ExportDto
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Locates the `taskkling` binary for the UI (PRD §6.3, §13). The UI is a pure CLI
 * client and never touches task files, so finding the one binary is its only
 * environment concern. Resolution order, first hit wins:
 *   1. the `TASKKLING_BINARY` environment variable (explicit override),
 *   2. `binary_path` in the workspace's `.taskkling/config.toml` (PRD §14),
 *   3. an entry named `taskkling`/`taskkling.exe` on `PATH`.
 */
public object CliDiscovery {
    private val exeNames = listOf("taskkling", "taskkling.exe")

    public fun locate(startDir: File = File(System.getProperty("user.dir"))): String? {
        System.getenv("TASKKLING_BINARY")?.takeIf { it.isNotBlank() }?.let { p ->
            if (File(p).canExecute()) return p
        }
        binaryPathFromConfig(startDir)?.let { return it }
        return onPath()
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

/** A failed CLI invocation, carrying the process exit code and captured stderr. */
public class CliException(public val code: Int, message: String) : RuntimeException(message)

/**
 * Thin subprocess wrapper around the `taskkling` [binary] (PRD §6.3). Reads run
 * `export`; mutations append `--export-on-success` so the post-mutation export
 * comes back in the same call (a TOCTOU-free read-after-write, PRD §7.3) and the
 * UI refreshes from it. The UI holds no parallel model and never writes files.
 */
public class CliClient(
    private val binary: String,
    private val workdir: File = File(System.getProperty("user.dir")),
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = true }

    /** `taskkling export [--include-body]` → parsed [ExportDto]. */
    public fun export(includeBody: Boolean = false): ExportDto {
        val args = buildList { add("export"); if (includeBody) add("--include-body") }
        return json.decodeFromString(ExportDto.serializer(), run(args).stdout)
    }

    /**
     * Run a mutation verb (e.g. `done`, `set`) with `--export-on-success` and
     * return the refreshed export. Caller passes the verb and its arguments.
     */
    public fun mutate(args: List<String>): ExportDto {
        val full = args + "--export-on-success"
        return json.decodeFromString(ExportDto.serializer(), run(full).stdout)
    }

    /** `taskkling read <id>` → the node's body text. */
    public fun body(id: String): String = run(listOf("read", id)).stdout.trimEnd('\n')

    private data class Output(val stdout: String, val stderr: String)

    private fun run(args: List<String>): Output {
        val proc = ProcessBuilder(listOf(binary) + args)
            .directory(workdir)
            .redirectErrorStream(false)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        val err = proc.errorStream.bufferedReader().readText()
        val code = proc.waitFor()
        if (code != 0) throw CliException(code, err.ifBlank { "taskkling exited $code" }.trim())
        return Output(out, err)
    }
}
