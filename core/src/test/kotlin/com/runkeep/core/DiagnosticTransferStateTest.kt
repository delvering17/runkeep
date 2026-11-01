package com.runkeep.core

import kotlin.test.*

class DiagnosticTransferStateTest {
    @Test fun `double click and duplicate result cannot start a second write`() {
        val preparing = DiagnosticTransferState().start()
        assertEquals(preparing, preparing.start())
        val picker = preparing.prepared(false).launched()
        val writing = picker.documentResult(true, true)
        assertEquals(DiagnosticTransferPhase.WRITING, writing.phase)
        assertEquals(writing, writing.start())
        assertEquals(writing, writing.documentResult(true, true))
        assertFalse(writing.finish().busy)
    }
    @Test fun `save cancellation missing content and failed launch permit retry`() {
        val picker = DiagnosticTransferState().start().prepared(false).launched()
        assertFalse(picker.documentResult(false, true).busy)
        assertFalse(picker.documentResult(true, false).busy)
        assertFalse(picker.finish().busy)
        assertTrue(picker.finish().start().busy)
    }
    @Test fun `share result is separate and pending state survives a screen instance`() {
        val ready = DiagnosticTransferState().start().prepared(true)
        val recreated = ready.copy()
        assertEquals(DiagnosticTransferPhase.SHARE_READY, recreated.phase)
        val launched = recreated.launched()
        assertEquals(DiagnosticTransferPhase.SHARE_PICKER, launched.phase)
        assertEquals(launched, launched.documentResult(true, true))
        assertFalse(launched.finish().busy)
        assertEquals(DiagnosticTransferPhase.IDLE, DiagnosticTransferState().documentResult(true, false).phase)
    }
}
