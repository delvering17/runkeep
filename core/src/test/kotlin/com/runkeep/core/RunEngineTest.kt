package com.runkeep.core

import kotlin.test.*

class RunEngineTest {
    private val origin = 1_700_000_000_000L
    private fun coordinate(meters: Double) = Coordinate(meters / 111_195.0802335329, 127.0)
    private fun fix(seconds: Int, meters: Double, accuracy: Float = 3f, speed: Float = 3f) =
        LocationFix(coordinate(meters), origin + seconds * 1000, seconds * 1000L, accuracy, speed = speed)
    private fun engine(goal: Double? = null, plan: WorkoutPlan? = null, prefs: Preferences = Preferences(autoPause = false)) =
        RunEngine(RunSummary(startedAt = origin, goalMeters = goal, plan = plan), prefs).also { it.tick(0, origin) }

    @Test fun `weighted pace uses total time and distance`() {
        val plan = WorkoutPlan(name = "기본", steps = listOf(PlanStep(kind = StepKind.DISTANCE, amount = 1000.0, paceSeconds = 420), PlanStep(kind = StepKind.DISTANCE, amount = 2000.0, paceSeconds = 385)))
        assertEquals(1190.0 / 3, plan.averagePace!!, .001)
    }
    @Test fun `goal crossing interpolates and keeps recording`() {
        val e = engine(goal = 100.0)
        for (s in 0..40) e.ingest(fix(s, s * 3.0))
        assertEquals(120.0, e.summary.meters, .01)
        assertTrue(kotlin.math.abs(33_333 - e.summary.goalActiveMs!!) <= 2)
        assertEquals(RunStatus.RUNNING, e.summary.status)
        val clipped = Reports.clip(e.points, 100.0)
        assertEquals(100.0, clipped.last().meters, .001)
        assertTrue(kotlin.math.abs(33_333 - clipped.last().activeMs) <= 2)
        assertTrue(clipped.size < e.points.size)
    }
    @Test fun `manual pause does not join movement or add paused time`() {
        val e = engine(); e.ingest(fix(0, 0.0)); e.ingest(fix(10, 30.0)); e.pause()
        e.tick(60_000, origin + 60_000); e.ingest(fix(61, 500.0)); e.resume(62_000)
        e.ingest(fix(61, 500.0)); e.ingest(fix(62, 500.0)); e.ingest(fix(72, 530.0))
        assertEquals(60.0, e.summary.meters, .01); assertEquals(20_000, e.summary.activeMs)
        assertNotEquals(e.points[1].segment, e.points[2].segment)
    }
    @Test fun `automatic pause resumes without counting the paused bridge`() {
        val e = engine(prefs = Preferences(autoPause = true))
        e.ingest(fix(0, 0.0, speed = 0f))
        for (s in 1..9) e.ingest(fix(s, 0.0, speed = 0f))
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        e.ingest(fix(10, 3.0)); e.ingest(fix(11, 6.0)); e.ingest(fix(12, 9.0)); e.ingest(fix(13, 12.0))
        assertEquals(RunStatus.RUNNING, e.summary.status)
        assertEquals(3.0, e.summary.meters, .01)
    }
    @Test fun `out of order timestamps do not double count active time`() {
        val e = engine(); e.ingest(fix(0, 0.0)); e.ingest(fix(10, 30.0)); e.ingest(fix(5, 15.0)); e.ingest(fix(11, 33.0))
        assertEquals(11_000, e.summary.activeMs); assertEquals(33.0, e.summary.meters, .01)
        e.tick(10_000, origin + 10_000); e.tick(12_000, origin + 12_000)
        assertEquals(12_000, e.summary.activeMs)
    }
    @Test fun `bad accuracy jump and long gap do not create distance`() {
        val e = engine(); e.ingest(fix(0, 0.0)); e.ingest(fix(1, 1000.0)); e.ingest(fix(2, 6.0))
        assertEquals(6.0, e.summary.meters, .01)
        e.ingest(fix(3, 50.0, accuracy = 90f)); e.ingest(fix(4, 12.0)); e.ingest(fix(30, 90.0))
        assertEquals(6.0, e.summary.meters, .01); assertNull(e.currentPace)
        e.ingest(fix(31, 93.0)); assertEquals(9.0, e.summary.meters, .01)
    }
    @Test fun `distance phase boundary uses the crossing leg instead of average pace`() {
        val plan = WorkoutPlan(name = "거리 후 시간", steps = listOf(PlanStep(kind = StepKind.DISTANCE, amount = 100.0, paceSeconds = 300), PlanStep(kind = StepKind.TIME, amount = 60.0, paceSeconds = 420)))
        val e = engine(plan = plan)
        for (s in 0..30) e.ingest(fix(s, s * 3.0))
        e.ingest(fix(40, 110.0))
        assertEquals(1, e.summary.planIndex); assertTrue(kotlin.math.abs(35_000 - e.summary.stepStartActiveMs) <= 2)
    }
    @Test fun `time phases stop coaching after completion by default`() {
        val plan = WorkoutPlan(name = "시간", steps = listOf(PlanStep(kind = StepKind.TIME, amount = 10.0, paceSeconds = 420)))
        val e = engine(plan = plan)
        e.tick(10_000, origin + 10_000)
        assertEquals(1, e.summary.planIndex); assertNull(e.summary.targetPace)
        assertTrue(e.announcements.any { it.speechText() == "페이스 계획을 완료했습니다" })
        e.announcements.clear()
        for (s in 11..100) e.ingest(fix(s, s * 3.0))
        assertTrue(e.announcements.none { it.speechText().contains("빠릅니다") || it.speechText().contains("느립니다") })
    }
    @Test fun `pace warnings require sustained error and have a cooldown`() {
        val plan = WorkoutPlan(name = "느리게", steps = listOf(PlanStep(kind = StepKind.DISTANCE, amount = 10000.0, paceSeconds = 600)))
        val e = engine(plan = plan, prefs = Preferences(autoPause = false, paceRepeatSeconds = 45))
        for (s in 0..17) e.ingest(fix(s, s * 3.0))
        assertTrue(e.announcements.none { it.speechText().contains("빠릅니다") })
        e.ingest(fix(18, 54.0))
        e.paceWarningRequested(); e.paceWarningSpoken(18_000); e.paceWarningCompleted()
        for (s in 19..62) e.ingest(fix(s, s * 3.0))
        assertEquals(0, e.announcements.count { it.speechText().contains("빠릅니다") })
        for (s in 63..66) e.ingest(fix(s, s * 3.0))
        assertEquals(1, e.announcements.count { it.speechText().contains("빠릅니다") })
    }
    @Test fun `recovered workout excludes process death gap`() {
        val original = engine(); original.ingest(fix(0, 0.0)); original.ingest(fix(10, 30.0))
        val recovered = RunEngine(original.summary.copy(status = RunStatus.INTERRUPTED), Preferences(autoPause = false), original.points)
        recovered.tick(100_000, origin + 100_000); recovered.resume(101_000)
        recovered.ingest(fix(101, 300.0)); recovered.ingest(fix(111, 330.0))
        assertEquals(20_000, recovered.summary.activeMs); assertEquals(60.0, recovered.summary.meters, .01)
    }
    @Test fun `gpx escapes names and keeps pause segments separate`() {
        val points = listOf(TrackPoint(coordinate(0.0), origin, 0, 0.0, segment = 0), TrackPoint(coordinate(20.0), origin + 2000, 2000, 20.0, segment = 1))
        val xml = Reports.runGpx("A&B <course>", points)
        assertTrue(xml.contains("A&amp;B &lt;course&gt;"))
        assertEquals(2, "<trkseg>".toRegex().findAll(xml).count())
    }
}
