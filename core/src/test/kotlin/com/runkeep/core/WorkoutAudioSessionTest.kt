package com.runkeep.core

import kotlin.test.*

class WorkoutAudioSessionTest {
    @Test fun `new workout clears previous cue and rejects a late previous speech callback`() {
        val old = RunSummary(id = "old", startedAt = 0)
        val session = WorkoutAudioSession()
        session.attach(old.id, LiveRun(), newWorkout = true)
        session.show("자동 일시정지")
        val request = session.requested()
        assertTrue(session.accepts(request))
        session.attach("new", LiveRun(old.copy(status = RunStatus.FINISHED), cue = session.cue), newWorkout = true)
        assertNull(session.cue)
        assertFalse(session.accepts(request))
        val next = session.requested()
        assertTrue(session.accepts(next))
        assertFalse(session.accepts(request))
    }

    @Test fun `same workout recovery and screen reentry retain cue without retaining callbacks`() {
        val saved = RunSummary(id = "saved", startedAt = 0, status = RunStatus.INTERRUPTED)
        val session = WorkoutAudioSession()
        val live = LiveRun(saved, cue = "달리기를 재개합니다")
        session.attach(saved.id, live, newWorkout = false)
        assertEquals(live.cue, session.cue)
        val oldRequest = session.requested()
        session.attach(saved.id, live, newWorkout = false)
        assertEquals(live.cue, session.cue)
        assertFalse(session.accepts(oldRequest))
        session.completed()
        assertEquals(live.cue, session.cue)
    }

    @Test fun `unrelated recovery and explicit new start with reused id cannot inherit cue`() {
        val previous = LiveRun(RunSummary(id = "old", startedAt = 0), cue = "이전 안내")
        val session = WorkoutAudioSession()
        session.attach("other", previous, newWorkout = false)
        assertNull(session.cue)
        session.attach("old", previous, newWorkout = true)
        assertNull(session.cue)
    }

    @Test fun `pause completion and newer requests invalidate callbacks without erasing display`() {
        val session = WorkoutAudioSession()
        session.attach("run", LiveRun(), newWorkout = true)
        session.show("일시정지합니다")
        val first = session.requested()
        val second = session.requested()
        assertFalse(session.accepts(first)); assertTrue(session.accepts(second))
        session.completed()
        assertFalse(session.accepts(second)); assertEquals("일시정지합니다", session.cue)
    }
}
