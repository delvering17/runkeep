package com.runkeep.core

import kotlin.test.*

class FinishSpeechPolicyTest {
    @Test fun `late finish or cancel for an old run cannot affect the next run`() {
        assertFalse(workoutCommandMatches("old", "new"))
        assertFalse(workoutCommandMatches("old", null))
        assertTrue(workoutCommandMatches("new", "new"))
        assertTrue(workoutCommandMatches(null, "new"))
    }

    @Test fun `accepted playback owns a finite completion deadline instead of immediate teardown`() {
        val policy = FinishSpeechPolicy()
        val ticket = assertNotNull(policy.begin("saved", 100))
        assertFalse(policy.expired(5099))
        assertTrue(policy.accepted(ticket, 5099))
        assertFalse(policy.expired(35_098))
        assertTrue(policy.expired(35_099))
        assertTrue(policy.complete(ticket))
        assertNull(policy.ticket)
        assertFalse(policy.complete(ticket))
    }

    @Test fun `TTS initialization or focus refusal cannot hold the service forever`() {
        val policy = FinishSpeechPolicy()
        val ticket = assertNotNull(policy.begin("saved", 100))
        assertFalse(policy.expired(5099))
        assertTrue(policy.expired(5100))
        assertFalse(policy.accepted(ticket, 5100))
        assertTrue(policy.complete(ticket))
        assertFalse(policy.expired(60_000))
    }

    @Test fun `completion and failure cleanup are idempotent and do not permit replay`() {
        for (accepted in listOf(false, true)) {
            val policy = FinishSpeechPolicy()
            val ticket = assertNotNull(policy.begin("saved", 0))
            if (accepted) assertTrue(policy.accepted(ticket, 0))
            assertTrue(policy.complete(ticket))
            assertFalse(policy.complete(ticket))
            assertNull(policy.begin("saved", 1))
            assertNull(policy.ticket)
        }
    }

    @Test fun `duplicate finish cannot extend deadlines or request the speech twice`() {
        val policy = FinishSpeechPolicy()
        val ticket = assertNotNull(policy.begin("saved", 0))
        assertEquals(ticket, policy.begin("saved", 4999))
        assertTrue(policy.expired(5000))
        policy.cancel()
        val next = assertNotNull(policy.begin("next", 6000))
        assertTrue(policy.accepted(next, 6000))
        assertFalse(policy.accepted(next, 7000))
        assertTrue(policy.expired(36_000))
    }

    @Test fun `focus loss and new workout invalidate late callbacks without closing the new session`() {
        val finish = FinishSpeechPolicy()
        val old = assertNotNull(finish.begin("old", 0))
        assertTrue(finish.accepted(old, 0))
        finish.cancel()
        val audio = WorkoutAudioSession()
        audio.attach("new", LiveRun(RunSummary(id = "old", startedAt = 0, status = RunStatus.FINISHED), cue = "이전 종료 안내"), true)
        assertNull(audio.cue)
        val request = audio.requested()
        assertFalse(finish.complete(old))
        assertTrue(audio.accepts(request))
        val next = assertNotNull(finish.begin("new", 1000))
        assertFalse(finish.complete(old))
        assertEquals(next, finish.ticket)
        assertTrue(finish.complete(next))
    }

    @Test fun `process restart does not replay a completed saved summary`() {
        val before = FinishSpeechPolicy()
        assertNotNull(before.begin("saved", 0))
        val restarted = FinishSpeechPolicy()
        assertNull(restarted.ticket)
        assertFalse(restarted.expired(60_000))
    }
}
