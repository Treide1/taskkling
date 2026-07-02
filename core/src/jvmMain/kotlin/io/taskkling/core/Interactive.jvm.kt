package io.taskkling.core

/** JVM target is test-only (shipped binaries are native); `System.console()` is null when stdout isn't a terminal. */
internal actual fun stdoutIsInteractive(): Boolean = System.console() != null
