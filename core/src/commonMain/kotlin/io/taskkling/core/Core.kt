package io.taskkling.core

/**
 * Shared constants for the `:core` domain layer. Behaviour lives in the focused
 * files alongside this one: model ([Task]), task-file I/O ([parseTask] /
 * [Task.toMarkdown]), workspace resolution ([Workspace]), the locked write path
 * ([withLock] / [writeFileAtomic]), and read/compute ([buildExport]).
 */
public object Taskkling {
    /**
     * Tool version. Single-sourced at compile time from the Gradle `version`
     * property (gradle.properties) via the generated [BUILD_VERSION] constant —
     * see the `generateVersionFile` task in core/build.gradle.kts. Bumped by
     * editing that one property; never hardcode the literal here again.
     */
    public const val VERSION: String = BUILD_VERSION
}
