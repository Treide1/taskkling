@file:OptIn(ExperimentalForeignApi::class)

package io.taskkling.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import okio.Path
import platform.posix.X_OK
import platform.posix.access
import platform.posix.getenv
import platform.posix.system

internal actual fun readEnvVar(name: String): String? = getenv(name)?.toKString()

/**
 * `system` is the one subprocess primitive Kotlin/Native offers portably (the
 * same one the CLI's own black-box test harness builds on); every argument
 * goes through [shellSingleQuote] so paths with spaces/metacharacters — e.g.
 * macOS's `Application Support` — survive `sh -c` intact.
 */
public actual fun extractArchiveWithSystemTar(archive: Path, destDir: Path): Boolean =
    system("tar -xf ${shellSingleQuote(archive.toString())} -C ${shellSingleQuote(destDir.toString())}") == 0

/**
 * Detach via the shell: `nohup … &` backgrounds the child and the `sh -c`
 * wrapper exits immediately, reparenting the UI to init — no terminal is
 * held, and a later terminal close can't HUP it. `system` only reports the
 * (instantly-exiting) shell's status, so spawn failure is pre-checked the one
 * way POSIX allows here: the launcher must exist and be executable.
 */
public actual fun spawnDetachedProcess(argv: List<String>, logFile: Path): Boolean {
    if (access(argv.first(), X_OK) != 0) return false
    val cmd = "nohup " + argv.joinToString(" ") { shellSingleQuote(it) } +
        " > ${shellSingleQuote(logFile.toString())} 2>&1 &"
    return system(cmd) == 0
}
