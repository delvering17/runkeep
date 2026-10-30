package com.runkeep.core

import kotlin.test.*

class FileExportStateTest {
    @Test fun `picker and write keep the selected snapshot locked against another export`() {
        val original = FileExportRequest("whole.json", "original hearts and track")
        val waiting = FileExportState().start(original)
        assertTrue(waiting.busy)
        assertFalse(waiting.writing)
        assertSame(waiting, waiting.start(FileExportRequest("goal.gpx", "changed scope")))
        val writing = waiting.beginWriting()
        assertTrue(writing.writing)
        assertSame(original, writing.request)
        assertSame(writing, writing.start(FileExportRequest("other.json", "other record")))
        assertSame(writing, writing.beginWriting())
    }

    @Test fun `cancel completion or failure releases the old snapshot before reselecting`() {
        val first = FileExportRequest("course.gpx", "old draft")
        val next = FileExportRequest("course.json", "new draft")
        for (pending in listOf(FileExportState().start(first), FileExportState().start(first).beginWriting())) {
            val cleared = pending.finish()
            assertFalse(cleared.busy)
            assertFalse(cleared.writing)
            assertNull(cleared.request)
            assertEquals(next, cleared.start(next).request)
        }
    }

    @Test fun `a late result after reset cannot begin writing stale content`() {
        val cleared = FileExportState().start(FileExportRequest("run.gpx", "track")).finish()
        assertSame(cleared, cleared.beginWriting())
        assertNull(cleared.request)
    }
}
