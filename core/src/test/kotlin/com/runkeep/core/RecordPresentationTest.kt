package com.runkeep.core

import kotlin.test.*

class RecordPresentationTest {
    private val points = listOf(
        TrackPoint(Coordinate(37.0, 127.0), 1000, 0, 0.0),
        TrackPoint(Coordinate(37.001, 127.0), 301000, 300000, 1000.0),
        TrackPoint(Coordinate(37.002, 127.0), 421000, 420000, 1400.0)
    )
    private val source = RunSummary(startedAt = 1000, endedAt = 451000, status = RunStatus.FINISHED,
        meters = 1400.0, activeMs = 450000, goalMeters = 1100.0, goalActiveMs = 330000, goalEpochMs = 331000)

    @Test fun `goal scope clips the track and uses the saved goal time without changing source`() {
        val view = RecordPresentation.from(source, points, includeExtra = false)
        assertTrue(view.goalOnly)
        assertEquals(1100.0, view.run.meters)
        assertEquals(330000L, view.run.activeMs)
        assertEquals(331000L, view.heartUntil)
        assertEquals(1100.0, view.points.last().meters)
        assertEquals(330000L, view.points.last().activeMs)
        assertEquals(1400.0, points.last().meters)
        assertEquals(450000L, source.activeMs)
    }

    @Test fun `full scope removes goal overrides and includes finished partial split time`() {
        val view = RecordPresentation.from(source, points, includeExtra = true)
        assertFalse(view.goalOnly)
        assertSame(points, view.points)
        assertNull(view.run.goalMeters)
        assertNull(view.run.goalActiveMs)
        assertEquals(451000L, view.heartUntil)
        val last = PaceProgress.kilometers(view.run, view.points).last()
        assertEquals(400.0, last.actualMeters)
        assertEquals(150000L, last.activeMs)
        assertEquals(ProgressState.COMPLETE, last.state)
        assertEquals(1100.0, source.goalMeters)
    }

    @Test fun `missed goal keeps original unfinished planned boundary`() {
        val missed = source.copy(meters = 800.0, activeMs = 240000, goalActiveMs = null, goalEpochMs = null)
        val view = RecordPresentation.from(missed, points.take(1), includeExtra = false)
        assertSame(missed, view.run)
        assertFalse(view.goalOnly)
        assertEquals(1100.0, view.run.goalMeters)
        assertEquals(ProgressState.INCOMPLETE, PaceProgress.kilometers(view.run, view.points).last().state)
    }
}
