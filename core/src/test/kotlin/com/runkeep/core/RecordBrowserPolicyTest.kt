package com.runkeep.core

import java.time.*
import kotlin.test.*

class RecordBrowserPolicyTest {
    private val zone = ZoneId.of("Asia/Seoul")
    private fun run(id: String, local: String, meters: Double = 1000.0, active: Long = 300000,
                    course: Course? = null) = RunSummary(id, LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli(),
        status = RunStatus.FINISHED, meters = meters, activeMs = active, course = course)
    @Test fun `cross month week includes Monday through Sunday with exclusive next Monday`() {
        val range = RecordBrowserPolicy.range(LocalDate.of(2026, 10, 1), RecordPeriod.WEEK, zone)
        assertEquals(LocalDate.of(2026, 9, 28), range.start)
        assertEquals(LocalDate.of(2026, 10, 5), range.endExclusive)
        val records = listOf(run("first", "2026-09-28T00:00:00"), run("last", "2026-10-04T23:59:59"), run("next", "2026-10-05T00:00:00"))
        assertEquals(setOf("first", "last"), RecordBrowserPolicy.records(records, range, RecordFilters(), emptySet(), RecordSort.NEWEST).map { it.id }.toSet())
        assertEquals(7, range.dates.size)
    }
    @Test fun `new year leap month and month movement use actual dates`() {
        val year = RecordBrowserPolicy.range(LocalDate.of(2027, 1, 1), RecordPeriod.WEEK, zone)
        assertEquals(LocalDate.of(2026, 12, 28), year.start)
        assertEquals(LocalDate.of(2027, 1, 4), year.endExclusive)
        val leap = RecordBrowserPolicy.range(LocalDate.of(2028, 2, 29), RecordPeriod.MONTH, zone)
        assertEquals(29, leap.dates.size)
        assertEquals(LocalDate.of(2026, 2, 28), RecordBrowserPolicy.move(LocalDate.of(2026, 1, 31), RecordPeriod.MONTH, 1))
    }
    @Test fun `DST calendar day boundaries neither duplicate nor lose records`() {
        val dstZone = ZoneId.of("America/New_York")
        val range = RecordBrowserPolicy.range(LocalDate.of(2026, 3, 8), RecordPeriod.WEEK, dstZone)
        assertEquals(167 * 3600000L, range.endEpoch - range.startEpoch)
        assertTrue(range.contains(range.startEpoch))
        assertTrue(range.contains(range.endEpoch - 1))
        assertFalse(range.contains(range.endEpoch))
        val adjacent = RecordBrowserPolicy.range(range.endExclusive, RecordPeriod.WEEK, dstZone)
        assertTrue(adjacent.contains(range.endEpoch))
        assertFalse(adjacent.contains(range.endEpoch - 1))
    }
    @Test fun `course filters match stable ID even when names are equal and heart filters use stored data`() {
        val a = Course(id = "a", name = "같은 이름", points = emptyList())
        val b = a.copy(id = "b")
        val records = listOf(run("a", "2026-10-01T10:00:00", course = a), run("b", "2026-10-01T11:00:00", course = b), run("none", "2026-10-02T10:00:00"))
        val range = RecordBrowserPolicy.range(LocalDate.of(2026, 10, 1), RecordPeriod.MONTH, zone)
        fun select(filters: RecordFilters) = RecordBrowserPolicy.records(records, range, filters, setOf("a"), RecordSort.NEWEST).map { it.id }
        assertEquals(listOf("a"), select(RecordFilters(courseId = "a", heart = RecordHeartFilter.PRESENT)))
        assertEquals(listOf("b"), select(RecordFilters(RecordCourseFilter.LINKED, heart = RecordHeartFilter.ABSENT)))
        assertEquals(listOf("none"), select(RecordFilters(RecordCourseFilter.UNLINKED)))
        assertEquals(3, select(RecordFilters()).size)
    }
    @Test fun `all sorts have deterministic ties and missing pace is last in either direction`() {
        val records = listOf(run("zero", "2026-10-01T09:00:00", 0.0), run("slow", "2026-10-01T10:00:00", active = 600000),
            run("fast", "2026-10-02T10:00:00", 2000.0, 600000), run("tie", "2026-10-01T08:00:00"))
        val range = RecordBrowserPolicy.range(LocalDate.of(2026, 10, 1), RecordPeriod.MONTH, zone)
        fun ids(sort: RecordSort) = RecordBrowserPolicy.records(records, range, RecordFilters(), emptySet(), sort).map { it.id }
        assertEquals(listOf("fast", "tie", "slow", "zero"), ids(RecordSort.FASTEST))
        assertEquals(listOf("slow", "fast", "tie", "zero"), ids(RecordSort.SLOWEST))
        assertEquals("fast", ids(RecordSort.LONGEST).first())
        assertEquals("zero", ids(RecordSort.SHORTEST).first())
        assertEquals("fast", ids(RecordSort.NEWEST).first())
        assertEquals("tie", ids(RecordSort.OLDEST).first())
    }
    @Test fun `selected calendar day is intersection and goal metrics keep prior scope`() {
        val source = run("goal", "2026-10-01T10:00:00", 1500.0, 500000).copy(goalMeters = 1000.0, goalActiveMs = 300000)
        val range = RecordBrowserPolicy.range(LocalDate.of(2026, 10, 1), RecordPeriod.MONTH, zone)
        assertEquals(1000.0, RecordBrowserPolicy.distance(source))
        assertEquals(300.0, RecordBrowserPolicy.pace(source))
        assertEquals(1500.0, source.meters)
        assertEquals(1, RecordBrowserPolicy.records(listOf(source), range, RecordFilters(), emptySet(), RecordSort.NEWEST, LocalDate.of(2026, 10, 1)).size)
        assertTrue(RecordBrowserPolicy.records(listOf(source), range, RecordFilters(), emptySet(), RecordSort.NEWEST, LocalDate.of(2026, 10, 2)).isEmpty())
        assertTrue(RecordBrowserPolicy.records(listOf(source.copy(status = RunStatus.RUNNING)), range, RecordFilters(), emptySet(), RecordSort.NEWEST).isEmpty())
    }
}
