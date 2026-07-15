package io.taskkling.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [windowTitle] (t-tlk0): what the taskbar and alt-tab show. The header label is
 * composed, but this is a pure string and the two must stay in step — both fall
 * back to the bare wordmark when the name is empty rather than leaving a dangling
 * separator.
 */
class WindowTitleTest {

    @Test
    fun aNamedWorkspaceIsAppendedToTheWordmark() {
        assertEquals("taskkling · my-repo", windowTitle("my-repo"))
    }

    @Test
    fun anEmptyNameLeavesTheBareWordmarkWithNoDanglingSeparator() {
        // Two paths reach here: the moment before the first export lands, and an
        // export from a CLI predating workspaceName.
        assertEquals("taskkling", windowTitle(""))
    }

    @Test
    fun aWorkspaceNamedAfterTheToolIsNotSpecialCased() {
        // Same dumb rule as the header and Workspace.displayName: no suppression.
        assertEquals("taskkling · taskkling", windowTitle("taskkling"))
    }

    @Test
    fun aLongNameIsNotTruncatedInTheTitle() {
        // Unlike the header, the title has no ladder to protect and the window
        // manager truncates for us — so nothing here should pre-empt it.
        val long = "acme-platform-migration-2026-q3-workstream"
        assertEquals("taskkling · $long", windowTitle(long))
    }
}
