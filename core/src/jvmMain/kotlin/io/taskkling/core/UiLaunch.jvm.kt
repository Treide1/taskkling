package io.taskkling.core

import okio.Path

/** Best-effort; the JVM target is test-only (the shipped binaries are native). */
internal actual fun readEnvVar(name: String): String? = System.getenv(name)

public actual fun extractArchiveWithSystemTar(archive: Path, destDir: Path): Boolean =
    try {
        ProcessBuilder("tar", "-xf", archive.toString(), "-C", destDir.toString())
            .redirectErrorStream(true)
            .start()
            .let { proc ->
                proc.inputStream.readBytes() // drain so tar can't block on a full pipe
                proc.waitFor() == 0
            }
    } catch (_: Exception) {
        false
    }

public actual fun spawnDetachedProcess(argv: List<String>, logFile: Path): Boolean =
    try {
        ProcessBuilder(argv)
            .redirectErrorStream(true)
            .redirectOutput(logFile.toFile())
            .start()
        true
    } catch (_: Exception) {
        false
    }
