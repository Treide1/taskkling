package io.taskkling.core

/**
 * Shared constants for the `:core` domain layer. Behaviour lives in the focused
 * files alongside this one: model ([Task]), task-file I/O ([parseTask] /
 * [Task.toMarkdown]), workspace resolution ([Workspace]), the locked write path
 * ([withLock] / [writeFileAtomic]), and read/compute ([buildExport]).
 */
public object Taskkling {
    /** Tool version. Bumped as milestones land. */
    public const val VERSION: String = "0.0.1-SNAPSHOT"
}
