package com.runkeep.core

import kotlin.test.*

class PartialKilometerRegressionTest {
    private fun point(meters: Double, activeMs: Long) =
        TrackPoint(Coordinate(meters / 111195.08, 0.0), activeMs, activeMs, meters, accuracy = 3f)

    private val points = listOf(point(0.0, 0), point(1000.0, 300000), point(1500.0, 450000))
    private val finished = RunSummary(startedAt = 0, meters = 1500.0, activeMs = 450000, status = RunStatus.FINISHED)

    @Test fun `finished free run closes its final actual interval`() {
        val rows = PaceProgress.kilometers(finished, points)
        assertEquals(listOf("1 km", "1.50 km"), rows.map { it.label })
        assertEquals(listOf(1000.0, 500.0), rows.map { it.plannedMeters })
        assertEquals(listOf(ProgressState.COMPLETE, ProgressState.COMPLETE), rows.map { it.state })
        val splits = Reports.splits(points, finished.meters, finished.activeMs)
        assertEquals(splits.map { it.meters }, rows.map { it.actualMeters })
        assertEquals(splits.map { it.activeMs }, rows.map { it.activeMs })
        assertEquals(300.0, rows.last().pace)
    }

    @Test fun `finished partial interval includes active time through manual finish`() {
        val run = finished.copy(activeMs = 480000)
        val row = PaceProgress.kilometers(run, points).last()
        assertEquals(180000L, row.activeMs)
        assertEquals(Reports.splits(points, run.meters, run.activeMs).last().pace, row.pace)
    }

    @Test fun `live paused and interrupted runs retain the next kilometer boundary`() {
        for (status in listOf(RunStatus.RUNNING, RunStatus.PAUSED, RunStatus.AUTO_PAUSED, RunStatus.INTERRUPTED)) {
            val row = PaceProgress.kilometers(finished.copy(status = status), points).last()
            assertEquals("2 km", row.label)
            assertEquals(1000.0, row.plannedMeters)
            assertEquals(500.0, row.actualMeters)
            assertEquals(ProgressState.ACTIVE, row.state)
        }
    }

    @Test fun `unfinished goal retains missed intervals after manual finish`() {
        val rows = PaceProgress.kilometers(finished.copy(goalMeters = 3500.0), points)
        assertEquals(listOf("1 km", "2 km", "3 km", "3.50 km"), rows.map { it.label })
        assertEquals(listOf(ProgressState.COMPLETE, ProgressState.INCOMPLETE, ProgressState.INCOMPLETE, ProgressState.INCOMPLETE), rows.map { it.state })
        assertEquals(1000.0, rows[1].plannedMeters)
        assertEquals(500.0, rows[1].actualMeters)
        assertNull(rows.last().actualMeters)
        assertEquals(500.0, rows.last().plannedMeters)
    }

    @Test fun `goal exceeded full view closes extra distance while goal view ends at goal`() {
        val run = finished.copy(goalMeters = 1200.0, goalActiveMs = 360000)
        val fullRows = PaceProgress.kilometers(run, points)
        val goalRows = PaceProgress.kilometers(run.copy(meters = 1200.0, activeMs = 360000), Reports.clip(points, 1200.0))
        assertEquals("1.50 km", fullRows.last().label)
        assertEquals(500.0, fullRows.last().actualMeters)
        assertEquals(ProgressState.COMPLETE, fullRows.last().state)
        assertEquals("1.20 km", goalRows.last().label)
        assertEquals(200.0, goalRows.last().plannedMeters)
        assertEquals(200.0, goalRows.last().actualMeters)
        assertEquals(60000L, goalRows.last().activeMs)
        assertEquals(ProgressState.COMPLETE, goalRows.last().state)
    }

    @Test fun `finished short free run closes first interval without an invented kilometer`() {
        val run = finished.copy(meters = 500.0, activeMs = 150000)
        val row = PaceProgress.kilometers(run, listOf(point(0.0, 0), point(500.0, 150000))).single()
        assertEquals("0.50 km", row.label)
        assertEquals(500.0, row.plannedMeters)
        assertEquals(500.0, row.actualMeters)
        assertEquals(ProgressState.COMPLETE, row.state)
    }

    @Test fun `exact kilometer finish does not add an empty interval`() {
        val run = finished.copy(meters = 1000.0, activeMs = 300000)
        val row = PaceProgress.kilometers(run, points.take(2)).single()
        assertEquals("1 km", row.label)
        assertEquals(1000.0, row.actualMeters)
        assertEquals(ProgressState.COMPLETE, row.state)
    }
}
