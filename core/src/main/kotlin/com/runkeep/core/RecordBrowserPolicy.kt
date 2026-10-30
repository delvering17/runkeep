package com.runkeep.core

import java.time.*
import java.time.temporal.TemporalAdjusters

enum class RecordPeriod { WEEK, MONTH }
enum class RecordView { LIST, CALENDAR }
enum class RecordSort { NEWEST, OLDEST, LONGEST, SHORTEST, FASTEST, SLOWEST }
enum class RecordCourseFilter { ALL, LINKED, UNLINKED }
enum class RecordHeartFilter { ALL, PRESENT, ABSENT }

data class RecordDateRange(val start: LocalDate, val endExclusive: LocalDate, val zone: ZoneId) {
    val startEpoch get() = start.atStartOfDay(zone).toInstant().toEpochMilli()
    val endEpoch get() = endExclusive.atStartOfDay(zone).toInstant().toEpochMilli()
    fun contains(epoch: Long) = epoch >= startEpoch && epoch < endEpoch
    val dates: List<LocalDate> get() = generateSequence(start) { it.plusDays(1) }.takeWhile { it < endExclusive }.toList()
}

data class RecordFilters(val course: RecordCourseFilter = RecordCourseFilter.ALL, val courseId: String? = null,
                         val heart: RecordHeartFilter = RecordHeartFilter.ALL)

/** Local dates in the current device zone; weeks always start on Monday, including cross-month weeks. */
object RecordBrowserPolicy {
    fun range(anchor: LocalDate, period: RecordPeriod, zone: ZoneId): RecordDateRange {
        val start = when (period) {
            RecordPeriod.WEEK -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            RecordPeriod.MONTH -> anchor.withDayOfMonth(1)
        }
        return RecordDateRange(start, if (period == RecordPeriod.WEEK) start.plusWeeks(1) else start.plusMonths(1), zone)
    }
    fun move(anchor: LocalDate, period: RecordPeriod, direction: Long): LocalDate =
        if (period == RecordPeriod.WEEK) anchor.plusWeeks(direction) else anchor.plusMonths(direction)
    fun distance(run: RunSummary) = if (run.goalActiveMs != null) run.goalMeters ?: run.meters else run.meters
    fun activeMs(run: RunSummary) = run.goalActiveMs ?: run.activeMs
    fun pace(run: RunSummary): Double? = distance(run).takeIf { it.isFinite() && it > 0 }
        ?.let { activeMs(run) / it }?.takeIf { it.isFinite() && it > 0 }
    fun records(runs: List<RunSummary>, range: RecordDateRange, filters: RecordFilters,
                heartRunIds: Set<String>, sort: RecordSort, selectedDay: LocalDate? = null): List<RunSummary> {
        val filtered = runs.filter { run ->
            run.status == RunStatus.FINISHED && range.contains(run.startedAt) &&
                (selectedDay == null || Instant.ofEpochMilli(run.startedAt).atZone(range.zone).toLocalDate() == selectedDay) &&
                when (filters.course) { RecordCourseFilter.ALL -> true; RecordCourseFilter.LINKED -> run.course != null; RecordCourseFilter.UNLINKED -> run.course == null } &&
                (filters.courseId == null || run.course?.id == filters.courseId) &&
                when (filters.heart) { RecordHeartFilter.ALL -> true; RecordHeartFilter.PRESENT -> run.id in heartRunIds; RecordHeartFilter.ABSENT -> run.id !in heartRunIds }
        }
        val primary = when (sort) {
            RecordSort.NEWEST -> compareByDescending<RunSummary> { it.startedAt }
            RecordSort.OLDEST -> compareBy<RunSummary> { it.startedAt }
            RecordSort.LONGEST -> compareByDescending<RunSummary> { distance(it) }
            RecordSort.SHORTEST -> compareBy<RunSummary> { distance(it) }
            RecordSort.FASTEST -> compareBy<RunSummary> { pace(it) == null }.thenBy { pace(it) }
            RecordSort.SLOWEST -> compareBy<RunSummary> { pace(it) == null }.thenByDescending { pace(it) }
        }
        return filtered.sortedWith(primary.thenByDescending { it.startedAt }.thenBy { it.id })
    }
}
