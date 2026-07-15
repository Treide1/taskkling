package io.taskkling.core

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * [Workspace.displayName] (PRD §14) and its trip through [buildExport]: what the
 * UI header calls this workspace. The fallback is resolved writer-side because
 * only the writer knows [Workspace.root] — see [io.taskkling.contract.ExportDto.workspaceName].
 */
class WorkspaceDisplayNameTest {

    private val fs = FileSystem.SYSTEM

    /**
     * A workspace in a directory literally named [dirName] (the temp root gets a
     * random suffix, so the name under test has to come from a nested dir), with
     * `workspace_name` set to [configured] — empty meaning "key present but blank",
     * which is what `init` writes.
     */
    private fun workspaceNamed(dirName: String, configured: String = ""): Workspace {
        val parent = Files.createTempDirectory("tk-display-name").toAbsolutePath().toString().toPath()
        val root = parent / dirName
        fs.createDirectories(root)
        initWorkspace(root.toString())
        if (configured.isNotEmpty()) {
            val cfg = root / ".taskkling" / "config.toml"
            fs.write(cfg) { writeUtf8(fs.read(cfg) { readUtf8() } + "\nworkspace_name = \"$configured\"\n") }
        }
        return Workspace.discover(root.toString())
    }

    @Test
    fun blankConfigFallsBackToTheWorkspaceDirectoryName() {
        assertEquals("my-repo", workspaceNamed("my-repo").displayName)
    }

    @Test
    fun anAbsentKeyFallsBackTheSameWayAsABlankOne() {
        // init writes `workspace_name = ""`, but a hand-trimmed or pre-existing
        // config may omit it entirely. Absent and blank must not diverge.
        val ws = workspaceNamed("trimmed")
        val cfg = ws.root / ".taskkling" / "config.toml"
        val stripped = fs.read(cfg) { readUtf8() }.lines().filterNot { it.startsWith("workspace_name") }
        fs.write(cfg) { writeUtf8(stripped.joinToString("\n")) }
        assertEquals("trimmed", Workspace.discover(ws.root.toString()).displayName)
    }

    @Test
    fun anExplicitNameOverridesTheDirectoryName() {
        assertEquals("Acme Tasks", workspaceNamed("boring-dir", configured = "Acme Tasks").displayName)
    }

    @Test
    fun aWorkspaceNamedAfterTheToolIsNotSpecialCased() {
        // The rule stays dumb: no suppression when the name duplicates the wordmark,
        // so this repo's own header reads "taskkling · taskkling". Changing that is a
        // config edit, not a code path.
        assertEquals("taskkling", workspaceNamed("taskkling").displayName)
    }

    @Test
    fun theExportCarriesTheResolvedNameNotTheRawConfigValue() {
        // The DTO is the UI's only channel — a raw "" would strand the reader, which
        // can't derive the fallback itself.
        val export = workspaceNamed("shipped").buildExport(includeBody = false, includeArchived = false)
        assertEquals("shipped", export.workspaceName)
    }
}
