package io.taskkling.core

import okio.FileSystem
import java.nio.file.Files

/** A fresh, initialized workspace under a unique JVM temp dir (PRD §9). */
internal fun tempWorkspace(): Workspace {
    val dir = Files.createTempDirectory("tk-test").toAbsolutePath().toString()
    initWorkspace(dir)
    return Workspace.discover(dir)
}

/** Add a task and return its minted id. */
internal fun Workspace.addReturningId(args: AddArgs): String = addTask(args).task.id

/** Overwrite the on-disk file for [task] directly (simulates a concurrent/hand edit). */
internal fun Workspace.overwriteOnDisk(task: Task) {
    val fs = FileSystem.SYSTEM
    val existing = findActiveFile(task.id)
    if (existing != null && existing.name != task.fileName()) fs.delete(existing, mustExist = false)
    fs.write(tasksDir / task.fileName()) { writeUtf8(task.toMarkdown()) }
}
