package com.runkeep.core

import kotlin.test.*

class PaceFeedbackEngineTest {
    private val origin = 1_700_000_000_000L
    private val prefs = Preferences(autoPause = false, paceAlarm = true, kilometerVoice = false)
    private fun plan(seconds: Double = 1000.0, target: Int = 360) =
        WorkoutPlan(name = "계획", steps = listOf(PlanStep(kind = StepKind.TIME, amount = seconds, paceSeconds = target)))
    private fun engine(plan: WorkoutPlan? = plan(), settings: Preferences = prefs) =
        RunEngine(RunSummary(startedAt = origin, plan = plan), settings).also { it.tick(0, origin) }
    private fun fix(second: Int, pace: Double = 330.0, accuracy: Float = 3f) =
        LocationFix(Coordinate(second * 1000.0 / pace / 111_195.0802335329, 127.0),
            origin + second * 1000L, second * 1000L, accuracy, speed = 3f)
    private fun warm(e: RunEngine, until: Int = 10) { for (s in 0..until) e.ingest(fix(s)) }
    private fun warnings(e: RunEngine) = e.announcements.filter { it.kind == AnnouncementKind.PACE_WARNING }

    @Test fun `one second ticks evaluate unchanged valid pace without manufacturing measurements`() {
        val e = engine(); warm(e, 8)
        assertEquals(PaceDirection.FAST, e.feedback.alarm)
        val measured = e.currentPace; val meters = e.summary.meters; val points = e.points.size
        e.tick(9000, origin + 9000); assertEquals(PaceDirection.FAST, e.feedback.alarm)
        e.tick(10_000, origin + 10_000); assertEquals(PaceDirection.FAST, e.feedback.alarm)
        e.tick(18_000, origin + 18_000); assertEquals(1, warnings(e).size)
        assertEquals(measured, e.currentPace); assertEquals(meters, e.summary.meters); assertEquals(points, e.points.size)
        e.tick(19_000, origin + 19_000); assertNull(e.feedback.alarm); assertTrue(warnings(e).isEmpty()); assertNull(e.currentPace)
    }
    @Test fun `pending warning is refreshed and recovery deletes it before playback`() {
        val e = engine(); warm(e, 18)
        val first = warnings(e).single(); e.ingest(fix(19))
        assertEquals(1, warnings(e).size); assertTrue(warnings(e).single().occurredAtMs > first.occurredAtMs)
        e.updateFeedbackPreferences(prefs.copy(paceTolerance = 60), 19_000)
        assertNull(e.feedback.alarm); assertTrue(warnings(e).isEmpty())
    }
    @Test fun `actual playback request start completion separate pending warning and repeat timer`() {
        val e = engine(); warm(e, 18)
        assertEquals(1, warnings(e).size)
        e.paceWarningRequested()
        for (s in 19..23) e.ingest(fix(s))
        assertTrue(warnings(e).isEmpty())
        e.paceWarningSpoken(23_000); e.paceWarningCompleted()
        for (s in 24..52) e.ingest(fix(s))
        assertTrue(warnings(e).isEmpty())
        e.ingest(fix(53)); assertEquals(1, warnings(e).size)
    }
    @Test fun `failed request can retry but GPS invalidation discards queued values`() {
        val e = engine(); warm(e, 18)
        e.tick(19_000, origin + 19_000); assertEquals(1, warnings(e).size)
        e.invalidatePace(19_000); assertNull(e.feedback.alarm); assertTrue(warnings(e).isEmpty())
    }
    @Test fun `bad accuracy and rejected jump stop alarms immediately`() {
        val e = engine(); warm(e)
        assertNotNull(e.feedback.alarm)
        e.ingest(fix(11, accuracy = 90f)); assertNull(e.feedback.alarm)
        val other = engine(); warm(other)
        other.ingest(LocationFix(Coordinate(1.0, 127.0), origin + 11_000, 11_000, 3f, speed = 3f))
        assertNull(other.feedback.alarm); assertTrue(warnings(other).isEmpty())
    }
    @Test fun `delayed location clears the alarm and pending warning immediately`() {
        val e = engine(); warm(e, 18)
        assertNotNull(e.feedback.alarm); assertEquals(1, warnings(e).size)
        assertNull(e.ingest(fix(19), receivedAtMs = 23_001))
        assertNull(e.currentPace); assertNull(e.feedback.alarm); assertTrue(warnings(e).isEmpty())
        assertEquals("stale_fix", e.autoPauseDiagnostics.reason)
    }
    @Test fun `detected auto pause and confirmed resume discard previous pace feedback`() {
        val e = engine(settings = prefs.copy(autoPause = true)); warm(e, 18)
        assertNotNull(e.feedback.alarm); assertEquals(1, warnings(e).size)
        val stopped = fix(18)
        for (s in 19..28) e.ingest(stopped.copy(epochMs = origin + s * 1000L, monotonicMs = s * 1000L, speed = 0f))
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        assertNull(e.currentPace); assertNull(e.feedback.alarm); assertTrue(warnings(e).isEmpty())
        for (s in 29..31) e.ingest(fix(s).copy(coordinate = fix(18 + s - 28).coordinate))
        assertEquals(RunStatus.RUNNING, e.summary.status)
        assertNull(e.currentPace); assertNull(e.feedback.alarm); assertTrue(warnings(e).isEmpty())
        assertEquals(1, e.announcements.count { it.speechText() == "달리기를 재개합니다" })
    }
    @Test fun `no plan and default completed plan remain silent while continuation uses last target`() {
        val none = engine(plan = null); warm(none, 30)
        assertNull(none.feedback.alarm); assertTrue(warnings(none).isEmpty())
        val completed = engine(plan(15.0)); warm(completed, 30)
        assertNull(completed.feedback.alarm); assertTrue(warnings(completed).isEmpty())
        completed.updateFeedbackPreferences(prefs.copy(stopCoachAtEnd = false), 30_000)
        assertEquals(PaceDirection.FAST, completed.feedback.direction)
        assertEquals(PaceDirection.FAST, completed.feedback.alarm)
        completed.tick(32_000, origin + 32_000); assertEquals(PaceDirection.FAST, completed.feedback.alarm)
    }
    @Test fun `same target step boundary resets sustain and repeat instead of inheriting the old phase`() {
        val steps = WorkoutPlan(name = "반복", steps = listOf(
            PlanStep(kind = StepKind.TIME, amount = 20.0, paceSeconds = 360),
            PlanStep(kind = StepKind.TIME, amount = 100.0, paceSeconds = 360)))
        val e = engine(steps); warm(e, 19); assertNotNull(e.feedback.alarm)
        e.paceWarningRequested(); e.paceWarningSpoken(19_000)
        e.ingest(fix(20)); assertEquals(1, e.summary.planIndex); assertEquals(PaceDirection.FAST, e.feedback.alarm); assertTrue(warnings(e).isEmpty())
        e.ingest(fix(21)); assertEquals(PaceDirection.FAST, e.feedback.alarm)
        e.ingest(fix(22)); assertNotNull(e.feedback.alarm)
        for (s in 23..29) e.ingest(fix(s))
        assertTrue(warnings(e).isEmpty()); e.ingest(fix(30)); assertEquals(1, warnings(e).size)
    }
    @Test fun `pause automatic pause finish and interruption cancel warnings and alarm`() {
        for (action in 0..3) {
            val e = engine(); warm(e, 18); assertNotNull(e.feedback.alarm)
            when (action) { 0 -> e.pause(); 1 -> e.pause(true); 2 -> e.finish(origin + 18_000); else -> e.interrupt() }
            assertNull(e.feedback.alarm); assertTrue(warnings(e).isEmpty())
        }
        val e = engine(); warm(e, 18); e.pause(); e.resume(20_000)
        assertNull(e.currentPace); assertNull(e.feedback.alarm)
        for (s in 20..27) e.ingest(fix(s))
        assertNull(e.feedback.alarm); e.ingest(fix(28)); assertNotNull(e.feedback.alarm)
    }
    @Test fun `all four value selections reuse the speech formatter`() {
        for (current in listOf(false, true)) for (target in listOf(false, true)) {
            val state = PaceFeedbackState(PaceDirection.SLOW, currentPace = 380.0, targetPace = 360)
            val text = paceWarningAnnouncement(state, prefs.copy(readCurrentPace = current, readTargetPace = target), 1000)!!.speechText()
            assertTrue(text.startsWith("현재 페이스가 느립니다"))
            assertEquals(current, text.contains("현재 6분 20초")); assertEquals(target, text.contains("목표 6분"))
            assertFalse(text.contains(":"))
        }
        val text = paceWarningAnnouncement(PaceFeedbackState(PaceDirection.SLOW, currentPace = Double.NaN, targetPace = 360),
            prefs.copy(readCurrentPace = true, readTargetPace = true), 1000)!!.speechText()
        assertFalse(text.contains("측정 불가")); assertTrue(text.contains("목표 6분"))
    }
    @Test fun `all off live settings remove pending warning without consuming voice timers`() {
        val e = engine(); warm(e, 18)
        assertEquals(1, warnings(e).size)
        val off = prefs.copy(readPaceStatus = false)
        e.updateFeedbackPreferences(off, 18_000)
        assertEquals(PaceDirection.FAST, e.feedback.alarm)
        assertFalse(e.feedback.voiceDue); assertTrue(warnings(e).isEmpty())
        e.paceWarningRequested(); e.paceWarningSpoken(18_000); e.paceWarningCompleted()
        e.updateFeedbackPreferences(off.copy(readCurrentPace = true), 18_001)
        assertEquals(1, warnings(e).size)
        assertTrue(warnings(e).single().speechText().startsWith("현재 5분 30초"))
        assertFalse(warnings(e).single().speechText().contains("빠릅니다"))
    }
    @Test fun `numeric only queued voice starts after sustained deviation and uses live selection`() {
        val numeric = prefs.copy(readPaceStatus = false, readTargetPace = true)
        val e = engine(settings = numeric); warm(e, 17)
        assertEquals(PaceDirection.FAST, e.feedback.alarm); assertTrue(warnings(e).isEmpty())
        e.ingest(fix(18)); assertEquals("목표 6분입니다.", warnings(e).single().speechText())
        e.updateFeedbackPreferences(numeric.copy(readTargetPace = false, readCurrentPace = true), 18_000)
        assertEquals("현재 5분 30초입니다.", warnings(e).single().speechText())
        e.updateFeedbackPreferences(numeric.copy(paceVoice = false), 18_000)
        assertTrue(warnings(e).isEmpty()); assertEquals(PaceDirection.FAST, e.feedback.alarm)
    }
    @Test fun `navigation wins but keeps only latest pace warning until acknowledged`() {
        val e = engine(); warm(e, 18)
        e.announcements += Announcement.Message(AnnouncementKind.NAVIGATION, "좌회전", 18_000)
        val queue = AnnouncementQueue(e.announcements)
        val nav = queue.next(18_000, RunStatus.RUNNING)!!
        assertEquals(AnnouncementKind.NAVIGATION, nav.kind)
        e.ingest(fix(19)); queue.acknowledge(nav)
        assertEquals(19_000, queue.next(19_000, RunStatus.RUNNING)!!.occurredAtMs)
    }
}
