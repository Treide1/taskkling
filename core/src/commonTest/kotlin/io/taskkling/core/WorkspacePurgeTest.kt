package io.taskkling.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import okio.Path.Companion.toPath

/**
 * Pure path logic of [Workspace.purgePlan] (t-qoyn): `uninstall --purge` must
 * cover the DEFAULT layout's root-level tasks dir (outside `.taskkling/`), must
 * not double-list a meta-nested tasks dir, and must refuse to follow a
 * `tasks_dir` that resolves to the workspace root itself or escapes it. The
 * actual recursive deletion is impure IO driven by the CLI — QA-gated, not
 * unit-tested here (mirrors [UpdateNotifierTest]'s split).
 */
class WorkspacePurgeTest {

    private val root = "/w/proj".toPath()
    private fun workspace(tasksDir: String) = Workspace(root, Config(tasksDir = tasksDir))

    @Test
    fun defaultLayoutPurgesMetaDirAndRootLevelTasksDir() {
        val plan = workspace("tasks").purgePlan()
        assertEquals(listOf("/w/proj/.taskkling".toPath(), "/w/proj/tasks".toPath()), plan.targets)
        assertTrue(plan.coversTasks)
    }

    @Test
    fun metaNestedTasksDirIsCoveredByMetaDirAlone() {
        // The dogfood layout: tasks_dir = ".taskkling/tasks" — one delete covers both.
        val plan = workspace(".taskkling/tasks").purgePlan()
        assertEquals(listOf("/w/proj/.taskkling".toPath()), plan.targets)
        assertTrue(plan.coversTasks)
    }

    @Test
    fun nestedTasksDirInsideRootIsPurged() {
        val plan = workspace("data/tasks").purgePlan()
        assertEquals(listOf("/w/proj/.taskkling".toPath(), "/w/proj/data/tasks".toPath()), plan.targets)
        assertTrue(plan.coversTasks)
    }

    @Test
    fun tasksDirResolvingToTheRootItselfIsNeverPurged() {
        // A hand-edited tasks_dir = "." must not turn --purge into rm -rf <root>.
        val plan = workspace(".").purgePlan()
        assertEquals(listOf("/w/proj/.taskkling".toPath()), plan.targets)
        assertFalse(plan.coversTasks)
    }

    @Test
    fun tasksDirEscapingTheRootIsNeverPurged() {
        val plan = workspace("../elsewhere").purgePlan()
        assertEquals(listOf("/w/proj/.taskkling".toPath()), plan.targets)
        assertFalse(plan.coversTasks)
    }

    @Test
    fun absoluteTasksDirOutsideTheRootIsNeverPurged() {
        val plan = workspace("/elsewhere/tasks").purgePlan()
        assertEquals(listOf("/w/proj/.taskkling".toPath()), plan.targets)
        assertFalse(plan.coversTasks)
    }
}
