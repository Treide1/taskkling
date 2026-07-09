@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package io.taskkling.cli

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.experimental.ExperimentalNativeApi
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import platform.posix.getenv
import platform.posix.system
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.random.Random
import kotlin.test.fail

/*
 * Black-box subprocess harness for the built `:cli` binary (t-hfwt). `:cli` is the single
 * read+write path, yet the pure helpers (CliHelpersTest) only cover argv/rendering in
 * isolation — they never touch the real dispatch, exit-code mapping or IO. These tests drive
 * the ACTUAL linked executable as a child process and assert its stdout / stderr / exit code:
 * the only thing that verifies the integration agents + the `:ui` client depend on.
 *
 * The binary under test is passed in via the `TASKKLING_TEST_BIN` env var, set per native
 * target by `cli/build.gradle.kts` (which also makes the `<target>Test` task depend on the
 * matching `linkDebugExecutable<Target>`). This compiles on every native target and runs via
 * the host `<target>Test` task, so CI exercises it on each matrix leg against that leg's own
 * freshly-built binary.
 */

/** The captured result of one CLI invocation. */
internal data class CliResult(val exit: Int, val stdout: String, val stderr: String)

private val fs = FileSystem.SYSTEM

/** Absolute path to the built binary, injected by Gradle. Fails loudly if unset (never a stub). */
private val binaryPath: String by lazy {
    getenv("TASKKLING_TEST_BIN")?.toKString()
        ?: fail(
            "TASKKLING_TEST_BIN is not set — the <target>Test task must inject the linked binary " +
                "path (see cli/build.gradle.kts). Refusing to run against a missing/stub binary.",
        )
}

/** OS temp base for scratch workspaces + captured stdio files. */
private val tmpBase: String by lazy {
    (getenv("TMPDIR") ?: getenv("TEMP") ?: getenv("TMP"))?.toKString() ?: "/tmp"
}

private var counter = 0

/** A fresh, unique scratch directory under the OS temp base. */
private fun uniqueDir(tag: String): Path {
    val p = tmpBase.toPath() / "tk-test-$tag-${counter++}-${Random.nextInt(0, Int.MAX_VALUE)}"
    fs.createDirectories(p)
    return p
}

/**
 * Run the built binary with [args], capturing stdout, stderr and the exit code. stdin is fed
 * from a temp file when [stdin] is non-null (drives the `-b -` convention). We shell out via
 * posix [system] with `> … 2> … [< …]` redirection — the one subprocess primitive available to
 * Kotlin/Native across every target — then read the captured files back with okio.
 *
 * Exit-code decoding is host-specific: on Windows `system` returns the child's code directly;
 * on POSIX it returns a wait-status whose exit code is the high byte (`WEXITSTATUS`).
 */
internal fun runCli(vararg args: String, stdin: String? = null): CliResult {
    val scratch = uniqueDir("io")
    val outPath = scratch / "stdout.txt"
    val errPath = scratch / "stderr.txt"
    val inPath = scratch / "stdin.txt"

    val cmd = buildString {
        // Binary path is unquoted: our build + temp trees never contain spaces, and leaving the
        // command's first token unquoted sidesteps cmd.exe's leading-quote-stripping rule. Each
        // arg is double-quoted so titles/paths with SPACES survive on both sh and cmd.exe — this
        // is not general metacharacter escaping (a `$`, backtick or `"` in an arg would still be
        // shell-special), which is fine: every test arg here is a controlled literal.
        append(binaryPath)
        for (a in args) append(" \"").append(a).append('"')
        append(" > \"").append(outPath.toString()).append('"')
        append(" 2> \"").append(errPath.toString()).append('"')
        if (stdin != null) {
            fs.write(inPath) { writeUtf8(stdin) }
            append(" < \"").append(inPath.toString()).append('"')
        }
    }

    val raw = system(cmd)
    val exit = if (Platform.osFamily == OsFamily.WINDOWS) raw else (raw shr 8) and 0xFF

    // Strip CR so golden comparisons are OS-neutral: the mingw binary's text-mode stdout
    // rewrites `\n` → `\r\n`, which CI's linux/macos legs never do.
    fun readOrEmpty(p: Path) = if (fs.exists(p)) fs.read(p) { readUtf8() }.replace("\r", "") else ""
    val result = CliResult(exit, readOrEmpty(outPath), readOrEmpty(errPath))
    fs.deleteRecursively(scratch, mustExist = false)
    return result
}

/**
 * Create a fresh workspace (its own `--root`) and return the root path to pass to later calls.
 * The directory is intentionally retained for the test's lifetime — a single test issues several
 * [runCli] calls against the same root — and is not cleaned per-call; it's a small tree under the
 * OS temp base, harmless on ephemeral CI.
 */
internal fun newWorkspace(): String {
    val root = uniqueDir("ws")
    val init = runCli("init", "--root", root.toString())
    if (init.exit != 0) fail("workspace init failed (exit ${init.exit}): ${init.stderr}")
    return root.toString()
}
