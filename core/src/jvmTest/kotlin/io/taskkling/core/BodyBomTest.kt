package io.taskkling.core

import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Write-side BOM hygiene (t-3hmw): Windows PowerShell 5.1 pipes prepend a
 * UTF-8 BOM to each piped payload, so `write`/`append` must strip one leading
 * U+FEFF per incoming chunk before persisting — an appended chunk's BOM lands
 * mid-body where the parse-side strip (t-7j9q) can't reach it. A FEFF anywhere
 * else in the payload is content and must survive.
 */
class BodyBomTest {

    private fun rawOnDisk(ws: Workspace, id: String): String =
        FileSystem.SYSTEM.read(ws.findActiveFile(id)!!) { readUtf8() }

    @Test
    fun writeStripsLeadingBom() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "write bom"))
        ws.writeBody(id, "﻿piped from PowerShell")
        assertEquals("piped from PowerShell", ws.loadTask(id)!!.body)
        // Assert on raw bytes: the parse-side strip would hide a leading BOM.
        assertFalse(rawOnDisk(ws, id).contains('﻿'), "no FEFF may be persisted")
    }

    @Test
    fun appendStripsLeadingBomPerChunk() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "append bom"))
        ws.writeBody(id, "first")
        ws.appendBody(id, "﻿second")
        ws.appendBody(id, "﻿third")
        assertEquals("first\nsecond\nthird", ws.loadTask(id)!!.body)
        assertFalse(rawOnDisk(ws, id).contains('﻿'), "no FEFF may be persisted")
    }

    @Test
    fun bomOnlyChunkIsTreatedAsEmpty() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "bom only"))
        ws.writeBody(id, "existing")
        ws.appendBody(id, "﻿")
        assertEquals("existing", ws.loadTask(id)!!.body)
        assertFalse(rawOnDisk(ws, id).contains('﻿'), "no FEFF may be persisted")
        ws.writeBody(id, "﻿")
        assertEquals("", ws.loadTask(id)!!.body)
    }

    @Test
    fun interiorFeffIsContentAndSurvives() {
        val ws = tempWorkspace()
        val id = ws.addReturningId(AddArgs(title = "interior feff"))
        ws.writeBody(id, "zero﻿width")
        ws.appendBody(id, "more﻿text")
        assertEquals("zero﻿width\nmore﻿text", ws.loadTask(id)!!.body)
        assertTrue(rawOnDisk(ws, id).contains('﻿'), "interior FEFF is content")
    }
}
