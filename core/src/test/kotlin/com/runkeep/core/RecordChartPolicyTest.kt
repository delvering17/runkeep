package com.runkeep.core

import kotlin.test.*

class RecordChartPolicyTest {
    private val origin = 1790812800000L
    private fun point(seconds: Long, meters: Double, segment: Int = 0, activeSeconds: Long = seconds) =
        TrackPoint(Coordinate(37.0, 127.0), origin + seconds * 1000, activeSeconds * 1000, meters, segment = segment)
    private fun sample(seconds: Long, value: Double? = 120.0, segment: Int? = null) = RecordChartSample(origin + seconds * 1000, value, segment)
    @Test fun `epoch heart samples map only inside adjacent valid GPS brackets`() {
        val gps = listOf(point(0, 0.0), point(10, 100.0), point(20, 200.0))
        assertEquals(50.0 to 0, RecordChartPolicy.distanceAt(origin + 5000, gps))
        assertEquals(100.0 to 0, RecordChartPolicy.distanceAt(origin + 10000, gps))
        assertNull(RecordChartPolicy.distanceAt(origin - 1, gps))
        assertNull(RecordChartPolicy.distanceAt(origin + 20001, gps))
        val distance = RecordChartPolicy.series(listOf(sample(5), sample(15), sample(25)), gps, origin, RecordChartAxis.DISTANCE)
        assertEquals(listOf(.05, .15), distance.points.map { it.x })
        assertEquals(2, distance.mappedCount)
        assertEquals(3, distance.sourceCount)
        assertEquals(RecordChartAxis.DISTANCE, distance.axis)
    }
    @Test fun `elapsed wall time includes pause and differs from active time`() {
        val gps = listOf(point(0, 0.0), point(10, 100.0), point(60, 100.0, 1, 10), point(70, 200.0, 1, 20))
        val time = RecordChartPolicy.series(listOf(sample(10), sample(60), sample(70)), gps, origin, RecordChartAxis.TIME)
        assertEquals(listOf(10.0, 60.0, 70.0), time.points.map { it.x })
        assertEquals(listOf(1, 2), time.parts.map { it.size })
        assertNull(RecordChartPolicy.distanceAt(origin + 30000, gps))
    }
    @Test fun `known GPS gap and inactive pair never draw a bridge even with close heart times`() {
        for (gps in listOf(listOf(point(0, 0.0), point(20, 200.0)), listOf(point(0, 0.0), point(10, 0.0, activeSeconds = 0)))) {
            val series = RecordChartPolicy.series(listOf(sample(2), sample(5), sample(8)), gps, origin, RecordChartAxis.TIME)
            assertEquals(3, series.parts.size)
            assertFalse(series.distanceAvailable)
        }
        val endpoints = RecordChartPolicy.series(listOf(sample(0), sample(20)), listOf(point(0, 0.0), point(20, 200.0)), origin, RecordChartAxis.TIME)
        assertEquals(2, endpoints.parts.size)
    }
    @Test fun `missing invalid and long sample gaps separate segments in either axis`() {
        val samples = listOf(sample(0, 100.0, 0), sample(5, null, 0), sample(10, 150.0, 0), sample(15, Double.NaN, 0), sample(20, 120.0, 0), sample(60, 180.0, 0))
        val series = RecordChartPolicy.series(samples, emptyList(), origin, RecordChartAxis.TIME)
        assertEquals(4, series.parts.size)
        assertEquals(listOf(100.0, 150.0, 120.0, 180.0), series.points.map { it.value })
        val segments = RecordChartPolicy.series(listOf(sample(0, segment = 0), sample(1, segment = 1)), emptyList(), origin, RecordChartAxis.TIME)
        assertEquals(2, segments.parts.size)
    }
    @Test fun `no GPS and partial overlap fall back without inventing distance`() {
        val samples = listOf(sample(0), sample(10), sample(20))
        val noGps = RecordChartPolicy.series(samples, emptyList(), origin, RecordChartAxis.DISTANCE)
        assertEquals(RecordChartAxis.TIME, noGps.axis)
        assertFalse(noGps.distanceAvailable)
        assertEquals(listOf(0.0, 10.0, 20.0), noGps.points.map { it.x })
        val noOverlap = RecordChartPolicy.series(samples, listOf(point(30, 0.0), point(40, 100.0)), origin, RecordChartAxis.DISTANCE)
        assertEquals(RecordChartAxis.TIME, noOverlap.axis)
        val partial = RecordChartPolicy.series(listOf(sample(0), sample(5), sample(10), sample(15), sample(20)), listOf(point(5, 50.0), point(10, 100.0), point(15, 150.0)), origin, RecordChartAxis.DISTANCE)
        assertEquals(3, partial.points.size)
        assertEquals(listOf(.05, .1, .15), partial.points.map { it.x })
    }
    @Test fun `single sample and constant values have a nonzero display domain`() {
        val single = RecordChartPolicy.series(listOf(sample(0)), emptyList(), origin, RecordChartAxis.TIME)
        assertEquals(1, single.parts.single().size)
        assertTrue(RecordChartPolicy.ticks(120.0, 120.0).zipWithNext().all { (a, b) -> b > a })
        assertTrue(RecordChartPolicy.series(emptyList(), emptyList(), origin, RecordChartAxis.TIME).points.isEmpty())
    }
    @Test fun `long series reduction preserves extrema endpoints order and original samples`() {
        val values = (0 until 10000).map { index -> RecordChartPoint(index * 1000L, index.toDouble(),
            when (index) { 4301 -> -100.0; 8755 -> 10000.0; else -> index % 180 + 100.0 }, 0) }
        val reduced = RecordChartPolicy.downsample(values, 240)
        assertTrue(reduced.size <= 240)
        assertSame(values.first(), reduced.first())
        assertSame(values.last(), reduced.last())
        assertTrue(reduced.any { it.value == -100.0 })
        assertTrue(reduced.any { it.value == 10000.0 })
        assertTrue(reduced.zipWithNext().all { (a, b) -> a.epochMs < b.epochMs })
        assertEquals(10000, values.size)
    }
    @Test fun `out of order and duplicate epochs are deterministic while descending distance breaks`() {
        val series = RecordChartPolicy.series(listOf(sample(10, 140.0), sample(0, 100.0), sample(10, 150.0)), emptyList(), origin, RecordChartAxis.TIME)
        assertEquals(listOf(100.0, 140.0), series.points.map { it.value })
        val bad = listOf(point(0, 100.0), point(10, 50.0))
        assertNull(RecordChartPolicy.distanceAt(origin + 5000, bad))
        assertEquals(2, RecordChartPolicy.series(listOf(sample(0), sample(10)), bad, origin, RecordChartAxis.DISTANCE).parts.size)
    }
    @Test fun `bad accuracy nonfinite distance and inconsistent clocks have no valid distance bridge`() {
        val start = point(0, 0.0)
        for (bad in listOf(point(10, 100.0).copy(accuracy = 26f), point(10, Double.NaN), point(10, -1.0), point(10, 100.0, activeSeconds = 20))) {
            val gps = listOf(start, bad)
            assertNull(RecordChartPolicy.distanceAt(origin + 5000, gps))
            assertEquals(2, RecordChartPolicy.series(listOf(sample(0), sample(10)), gps, origin, RecordChartAxis.TIME).parts.size)
        }
    }

}
