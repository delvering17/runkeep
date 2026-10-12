package com.runkeep.core

import kotlinx.serialization.encodeToString
import kotlin.test.*

class CoursePaceEngineTest {
    @Test fun `distance policy completes a short return loop without selecting its future overlapping edge`() {
        val value = Course(name = "거리 왕복", points = listOf(coordinate(0.0), coordinate(5.0), coordinate(0.0)),
            paceTransition = CoursePaceTransition.DISTANCE).withPace(0, 360).withPace(1, 345)
        val e = engine(value)
        e.ingest(fix(0.0, 0)); e.ingest(fix(1.0, 1)); e.ingest(fix(0.0, 2))
        assertEquals(0, e.summary.courseLegIndex); assertEquals(1.0, e.summary.courseProgressMeters, .001)
        e.ingest(fix(5.0, 4))
        assertEquals(1, e.summary.courseLegIndex); assertEquals(5.0, e.summary.courseProgressMeters, .001)
        e.ingest(fix(0.0, 6))
        assertTrue(e.summary.coursePaceComplete); assertEquals(10.0, e.summary.courseProgressMeters, .001)
    }

    @Test fun `distance jitter followed by forward travel counts only the newly covered path`() {
        val e = engine(course(CoursePaceTransition.DISTANCE))
        e.ingest(fix(0.0, 0)); e.ingest(fix(2.0, 1)); e.ingest(fix(0.0, 2)); e.ingest(fix(5.0, 4))
        assertEquals(5.0, e.summary.courseProgressMeters, .001)
        assertEquals(0, e.summary.courseLegIndex)
    }

    @Test fun `a continuous fix pair can cross the start without an endpoint inside its radius`() {
        val e = engine(course())
        e.ingest(fix(-15.0, 0))
        assertEquals(-1, e.summary.courseLegIndex)
        e.ingest(fix(15.0, 4))
        assertEquals(0, e.summary.courseLegIndex)
        assertEquals(360, e.summary.targetPace)
        e.ingest(fix(40.0, 8))
        assertEquals(1, e.summary.courseLegIndex)
        assertEquals(345, e.summary.targetPace)
    }

    private fun coordinate(north: Double, east: Double = 0.0) = Coordinate(north / 111195.08, east / 111195.08)
    private fun fix(north: Double, seconds: Long, east: Double = 0.0, accuracy: Float = 3f) =
        LocationFix(coordinate(north, east), seconds * 1000, seconds * 1000, accuracy, speed = 3f)
    private fun course(mode: CoursePaceTransition = CoursePaceTransition.WAYPOINT) =
        Course(name = "지점 계획", points = listOf(coordinate(0.0), coordinate(30.0), coordinate(60.0)), paceTransition = mode)
            .withPace(0, 360).withPace(1, 345)
    private fun engine(course: Course, prefs: Preferences = Preferences(autoPause = false)) =
        RunEngine(RunSummary(startedAt = 0, course = course, coursePaceEnabled = true), prefs).apply { tick(0, 0) }

    @Test fun `actual passage changes edge goal voice and alarm together`() {
        val e = engine(course(), Preferences(autoPause = false, paceAlarm = true))
        assertNull(e.summary.targetPace)
        for (index in 0..5) e.ingest(fix(index * 5.0, index * 2L))
        assertEquals(360, e.summary.targetPace); assertEquals(PaceDirection.SLOW, e.feedback.alarm)
        e.ingest(fix(30.0, 12))
        assertEquals(1, e.summary.courseLegIndex); assertEquals(345, e.summary.targetPace)
        assertEquals(345, e.feedback.targetPace); assertEquals(PaceDirection.SLOW, e.feedback.alarm)
        val step = e.announcements.filterIsInstance<Announcement.Step>().last()
        assertEquals(345.0, step.targetSecondsPerKilometer)
        assertTrue(step.speechText().contains("5분 45초"))
        for (index in 7..10) e.ingest(fix(index * 5.0, index * 2L))
        assertTrue(e.announcements.none { it.kind == AnnouncementKind.PACE_WARNING })
    }

    @Test fun `unassigned middle edge disables coaching even when end coaching continues`() {
        val value = Course(name = "빈 구간", points = listOf(0.0, 30.0, 60.0, 90.0).map { coordinate(it) })
            .withPace(0, 360).withPace(2, 420)
        val e = engine(value, Preferences(autoPause = false, paceAlarm = true, stopCoachAtEnd = false))
        for (index in 0..8) e.ingest(fix(index * 5.0, index * 2L))
        assertEquals(1, e.summary.courseLegIndex); assertNull(e.summary.targetPace); assertNull(e.feedback.alarm)
        assertTrue(e.announcements.filterIsInstance<Announcement.Message>().any { it.text.contains("목표 페이스 미설정") })
        for (index in 9..12) e.ingest(fix(index * 5.0, index * 2L))
        assertEquals(420, e.summary.targetPace)
    }

    @Test fun `short return loop never finishes at its overlapping start`() {
        val value = Course(name = "왕복", points = listOf(coordinate(0.0), coordinate(5.0), coordinate(0.0)))
            .withPace(0, 360).withPace(1, 345)
        val e = engine(value)
        e.ingest(fix(0.0, 0)); e.ingest(fix(1.0, 1)); e.ingest(fix(0.0, 2))
        assertEquals(0, e.summary.courseLegIndex); assertFalse(e.summary.coursePaceComplete)
        e.ingest(fix(5.0, 4)); assertEquals(345, e.summary.targetPace); assertFalse(e.summary.coursePaceComplete)
        e.ingest(fix(0.0, 6)); assertTrue(e.summary.coursePaceComplete); assertNull(e.summary.targetPace)
        assertEquals(RunStatus.RUNNING, e.summary.status)
    }

    @Test fun `future loop intersection cannot skip the next waypoint`() {
        val value = Course(name = "교차", points = listOf(coordinate(0.0), coordinate(100.0), coordinate(0.0), coordinate(200.0)))
            .withAllPaces(360)
        val e = engine(value)
        e.ingest(fix(0.0, 0)); e.ingest(fix(3.0, 1)); e.ingest(fix(0.0, 2))
        assertEquals(0, e.summary.courseLegIndex); assertFalse(e.summary.coursePaceComplete)
    }

    @Test fun `GPS gap and rejected accuracy do not fabricate a passage`() {
        val e = engine(course())
        e.ingest(fix(0.0, 0)); e.ingest(fix(10.0, 4))
        e.ingest(fix(30.0, 5, accuracy = 100f))
        e.ingest(fix(40.0, 40)); e.ingest(fix(45.0, 42))
        assertEquals(0, e.summary.courseLegIndex); assertEquals(360, e.summary.targetPace)
    }

    @Test fun `route deviation with more running distance does not complete future waypoints`() {
        val value = Course(name = "이탈", points = listOf(coordinate(0.0), coordinate(100.0), coordinate(200.0))).withAllPaces(360)
        val e = engine(value)
        e.ingest(fix(0.0, 0))
        for (index in 1..20) e.ingest(fix(index * 10.0, index * 5L, east = 100.0))
        assertTrue(e.summary.meters > 100); assertEquals(0, e.summary.courseLegIndex)
    }

    @Test fun `distance policy uses accepted on course meters and does not advance on timer`() {
        val e = engine(course(CoursePaceTransition.DISTANCE))
        assertEquals(360, e.summary.targetPace)
        e.ingest(fix(0.0, 0)); e.ingest(fix(15.0, 5)); e.tick(9000, 9000)
        assertEquals(0, e.summary.courseLegIndex)
        e.ingest(fix(30.0, 10)); assertEquals(345, e.summary.targetPace)
        assertEquals(30.0, e.summary.courseProgressMeters, .001)
        e.ingest(fix(45.0, 15)); e.ingest(fix(60.0, 20))
        assertTrue(e.summary.coursePaceComplete); assertNull(e.summary.targetPace)
    }

    @Test fun `distance policy rejects deviation and catch up after gap`() {
        val value = Course(name = "거리", points = listOf(coordinate(0.0), coordinate(100.0), coordinate(200.0)),
            paceTransition = CoursePaceTransition.DISTANCE).withAllPaces(360)
        val e = engine(value)
        e.ingest(fix(0.0, 0)); e.ingest(fix(10.0, 4))
        for (index in 1..8) e.ingest(fix(10.0 + index * 10, 4 + index * 5L, east = 100.0))
        assertEquals(0, e.summary.courseLegIndex); assertEquals(10.0, e.summary.courseProgressMeters, .001)
        e.ingest(fix(100.0, 100)); e.ingest(fix(110.0, 104))
        assertEquals(0, e.summary.courseLegIndex); assertEquals(10.0, e.summary.courseProgressMeters, .001)
    }

    @Test fun `distance jitter cannot accumulate repeated forward and backward travel`() {
        val e = engine(course(CoursePaceTransition.DISTANCE))
        e.ingest(fix(0.0, 0))
        for (index in 1..50) e.ingest(fix(if (index % 2 == 0) 0.0 else 2.0, index.toLong()))
        assertTrue(e.summary.meters > 30); assertEquals(0, e.summary.courseLegIndex)
        assertEquals(2.0, e.summary.courseProgressMeters, .001)
    }

    @Test fun `pause and recovery do not connect unrecorded movement`() {
        val e = engine(course())
        e.ingest(fix(0.0, 0)); e.ingest(fix(15.0, 5)); e.pause()
        assertNull(e.ingest(fix(30.0, 10)))
        val saved = codec.decodeFromString<RunSummary>(codec.encodeToString(e.summary))
        val restored = RunEngine(saved.copy(status = RunStatus.INTERRUPTED), Preferences(autoPause = false), e.points)
        restored.resume(30000); restored.ingest(fix(40.0, 30)); restored.ingest(fix(45.0, 32))
        assertEquals(0, restored.summary.courseLegIndex); assertEquals(360, restored.summary.targetPace)
        assertFalse(restored.summary.coursePaceComplete)
    }

    @Test fun `distance recovery preserves progress and disconnects its first sample`() {
        val e = engine(course(CoursePaceTransition.DISTANCE))
        e.ingest(fix(0.0, 0)); e.ingest(fix(15.0, 5))
        val restored = RunEngine(codec.decodeFromString<RunSummary>(codec.encodeToString(e.summary)), Preferences(autoPause = false), e.points)
        restored.resume(30000); restored.ingest(fix(25.0, 30))
        assertEquals(15.0, restored.summary.courseProgressMeters, .001)
        assertEquals(0, restored.summary.courseLegIndex)
    }

    @Test fun `end coaching follows the final edge and never carries across missing goals`() {
        for (stop in listOf(true, false)) {
            val e = engine(course(), Preferences(autoPause = false, paceAlarm = true, stopCoachAtEnd = stop))
            for (index in 0..12) e.ingest(fix(index * 5.0, index * 2L))
            assertTrue(e.summary.coursePaceComplete)
            assertEquals(if (stop) null else 345, e.feedback.targetPace)
        }
        val e = engine(course().withPace(1, null), Preferences(autoPause = false, paceAlarm = true, stopCoachAtEnd = false))
        for (index in 0..12) e.ingest(fix(index * 5.0, index * 2L))
        assertNull(e.feedback.targetPace); assertNull(e.feedback.alarm)
    }

    @Test fun `old independent course and plan run does not activate new edge goals`() {
        val independent = WorkoutPlan(name = "기존", steps = listOf(PlanStep(kind = StepKind.DISTANCE, amount = 15.0, paceSeconds = 420),
            PlanStep(kind = StepKind.DISTANCE, amount = 100.0, paceSeconds = 390)))
        val e = RunEngine(RunSummary(startedAt = 0, course = course(), plan = independent), Preferences(autoPause = false))
        e.tick(0, 0); e.ingest(fix(0.0, 0)); e.ingest(fix(15.0, 5))
        assertEquals(1, e.summary.planIndex); assertEquals(390, e.summary.targetPace)
        assertFalse(e.summary.coursePaceEnabled)
    }

    @Test fun `countdown retains the immutable combined snapshot and resets progress`() {
        val source = course()
        val request = RunSummary(startedAt = 0, course = source, coursePaceEnabled = true, courseLegIndex = 1,
            coursePaceComplete = true, courseProgressMeters = 60.0)
        val policy = RunStartPolicy()
        assertTrue(RunStartPolicy.validRequest(request)); assertTrue(policy.prepare(request, 0, 3, true))
        assertNull(policy.takeReady(request.id, 2000, 2000, true))
        val started = policy.takeReady(request.id, 3000, 3000, true)!!
        assertTrue(started.coursePaceEnabled); assertEquals(source, started.course)
        assertEquals(-1, started.courseLegIndex); assertFalse(started.coursePaceComplete); assertEquals(0.0, started.courseProgressMeters)
        assertNotEquals(source.withPace(0, 420), started.course)
    }

    @Test fun `combined request without a valid course is rejected`() {
        assertFalse(RunStartPolicy.validRequest(RunSummary(startedAt = 0, coursePaceEnabled = true)))
        assertFalse(RunStartPolicy.validRequest(RunSummary(startedAt = 0, course = Course(name = "빈 코스", points = emptyList()), coursePaceEnabled = true)))
    }
}
