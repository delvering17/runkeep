package com.runkeep.core

import kotlin.test.*

class PaceProgressTest {
    private fun coordinate(m: Double, east: Double = 0.0) = Coordinate(m / 111195.08, east / 111195.08)
    private fun point(m: Double, seconds: Long, segment: Int = 0, east: Double = 0.0) = TrackPoint(coordinate(m, east), seconds * 1000, seconds * 1000, m, accuracy = 3f, segment = segment)
    @Test fun `kilometer table separates completed active and pending intervals`() {
        val points = (0..150).map { point(it * 10.0, it * 3L) }
        val run = RunSummary(startedAt = 0, meters = 1500.0, activeMs = 450000, goalMeters = 3000.0)
        val rows = PaceProgress.kilometers(run, points)
        assertEquals(listOf(ProgressState.COMPLETE, ProgressState.ACTIVE, ProgressState.PENDING), rows.map { it.state })
        assertEquals(300.0, rows[0].pace!!, .001); assertEquals(500.0, rows[1].actualMeters!!, .001); assertNull(rows[2].pace)
    }
    @Test fun `waypoints use actual ordered crossings`() {
        val course = Course(name = "직선", points = listOf(coordinate(0.0), coordinate(100.0), coordinate(200.0)))
        val points = (0..21).map { point(it * 10.0, it * 3L) }
        val run = RunSummary(startedAt = 0, meters = 210.0, activeMs = 63000)
        val rows = PaceProgress.coursePassages(course, run, points)
        assertEquals(2, rows.size); assertTrue(rows.all { it.state == ProgressState.COMPLETE })
        assertEquals(300.0, rows[0].pace!!, .01)
    }
    @Test fun `deviation distance does not count as passing a coordinate`() {
        val course = Course(name = "직선", points = listOf(coordinate(0.0), coordinate(100.0)))
        val points = listOf(point(0.0, 0)) + (1..12).map { point(it * 10.0, it * 3L, east = 100.0) }
        val run = RunSummary(startedAt = 0, meters = 120.0, activeMs = 36000)
        assertEquals(ProgressState.ACTIVE, PaceProgress.coursePassages(course, run, points).single().state)
        assertEquals(ProgressState.COMPLETE, PaceProgress.courseDistances(course, run, points).single().state)
    }
    @Test fun `signal gaps cannot invent an intermediate waypoint crossing`() {
        val course = Course(name = "직선", points = listOf(coordinate(0.0), coordinate(50.0)))
        val points = listOf(point(0.0, 0), point(100.0, 30, segment = 1))
        assertEquals(listOf(0), PaceProgress.passes(course, points).map { it.index })
    }
    @Test fun `same start and finish coordinate does not finish a loop at the start`() {
        val course = Course(name = "왕복", points = listOf(coordinate(0.0), coordinate(100.0), coordinate(0.0)))
        assertEquals(listOf(0), PaceProgress.passes(course, listOf(point(0.0, 0), point(3.0, 1))).map { it.index })
    }
    @Test fun `goal shorter than one kilometer finishes its partial row`() {
        val run = RunSummary(startedAt = 0, meters = 100.0, activeMs = 30000, goalMeters = 100.0)
        val row = PaceProgress.kilometers(run, listOf(point(0.0, 0), point(100.0, 30))).single()
        assertEquals(ProgressState.COMPLETE, row.state); assertEquals(100.0, row.plannedMeters)
    }
}
