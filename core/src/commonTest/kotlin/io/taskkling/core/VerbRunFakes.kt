package io.taskkling.core

import okio.FileSystem
import okio.ForwardingFileSystem
import okio.IOException
import okio.Path

/**
 * Test doubles for the distribution verbs' effect bundles ([UpdateEffects],
 * [UiEffects], [UninstallEffects]). Together with okio's `FakeFileSystem` they
 * let the runner tests drive the scenarios manual release QA cannot reliably
 * produce — a truncated download, a corrupt cache, a locked cache dir — and
 * assert the user-facing wording, which IS the behavior.
 */

/** Collects a runner's output instead of printing it. */
internal class RecordingOutput : CliOutput {
    val stdout: MutableList<String> = mutableListOf()
    val stderr: MutableList<String> = mutableListOf()
    override fun out(line: String) { stdout += line }
    override fun err(line: String) { stderr += line }
}

/**
 * A scripted network: each URL either has a canned response or 404s, and any
 * URL in [offline] throws the way a dead transport does. [calls] records every
 * request in order, so a test can assert both what was fetched and — for the
 * ordering guarantees (t-6ouc) — that nothing was fetched at all.
 */
internal class FakeNet(
    private val texts: Map<String, Pair<Int, String>> = emptyMap(),
    private val bytes: Map<String, Pair<Int, ByteArray>> = emptyMap(),
    private val offline: Set<String> = emptySet(),
) : NetEffects {
    val calls: MutableList<String> = mutableListOf()

    override fun getText(url: String, userAgent: String): Pair<Int, String> {
        calls += url
        if (url in offline) throw RuntimeException("simulated transport failure")
        return texts[url] ?: (404 to "not found")
    }

    override fun getBytes(url: String, userAgent: String): Pair<Int, ByteArray> {
        calls += url
        if (url in offline) throw RuntimeException("simulated transport failure")
        return bytes[url] ?: (404 to ByteArray(0))
    }
}

/** Wraps [delegate] so deleting exactly [locked] throws — a running UI's file lock, deterministically. */
internal class LockedFileFs(delegate: FileSystem, private val locked: Path) : ForwardingFileSystem(delegate) {
    override fun delete(path: Path, mustExist: Boolean) {
        if (path == locked) throw IOException("locked: $path")
        super.delete(path, mustExist)
    }
}

/** A `SHA256SUMS` body that verifies each of [assets] (`name to bytes`). */
internal fun sha256SumsFor(vararg assets: Pair<String, ByteArray>): String =
    assets.joinToString("\n") { (name, body) -> "${Sha256.hashHex(body)}  $name" } + "\n"

/** A [WorkspaceInfo] with no filesystem behind it. */
internal fun fakeWorkspace(
    root: Path,
    tasksDirSetting: String = "tasks",
    taskCount: Int = 0,
    purge: PurgePlan = PurgePlan(listOf(root / ".taskkling", root / "tasks"), coversTasks = true),
): WorkspaceInfo = WorkspaceInfo(
    root = root,
    metaDir = root / ".taskkling",
    tasksDirSetting = tasksDirSetting,
    taskCount = { taskCount },
    purge = { purge },
)
