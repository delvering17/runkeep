package com.runkeep.core

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

enum class ProgressState { COMPLETE, ACTIVE, PENDING, INCOMPLETE }
data class PaceProgressRow(val label: String, val plannedMeters: Double, val actualMeters: Double?, val activeMs: Long?, val state: ProgressState) {
    val pace get() = actualMeters?.takeIf { it > .1 }?.let { distance -> activeMs?.div(distance) }
}
data class WaypointPass(val index: Int, val meters: Double, val activeMs: Long)

object PaceProgress {
    fun kilometers(run: RunSummary, points: List<TrackPoint>): List<PaceProgressRow> {
        val recordedEnd = run.status == RunStatus.FINISHED && run.meters > 0 && (run.goalMeters == null || run.meters >= run.goalMeters)
        val total = if (recordedEnd) run.meters else max(run.meters, run.goalMeters ?: 1000.0).coerceAtLeast(1.0)
        val count = ceil(total / 1000).toInt().coerceAtMost(1000)
        return (0 until count).map { index ->
            val start = index * 1000.0
            val end = if (index == count - 1 && (recordedEnd || (run.goalMeters != null && run.meters <= run.goalMeters))) min(total, (index + 1) * 1000.0) else (index + 1) * 1000.0
            val label = if (end - start < 999.9) "%.2f km".format(java.util.Locale.ROOT, end / 1000) else "${index + 1} km"
            distanceRow(label, start, end, run, points, useRunEndTime = recordedEnd && end == run.meters && end < (index + 1) * 1000.0)
        }
    }

    fun courseDistances(course: Course, run: RunSummary, points: List<TrackPoint>): List<PaceProgressRow> {
        val cumulative = cumulative(course)
        return course.points.zipWithNext().indices.map { index -> distanceRow("${index + 1} → ${index + 2}", cumulative[index], cumulative[index + 1], run, points) }
    }

    private fun distanceRow(label: String, start: Double, end: Double, run: RunSummary, points: List<TrackPoint>, useRunEndTime: Boolean = false): PaceProgressRow {
        val started = run.meters > start
        val completed = run.meters >= end && end > start
        val fromTime = if (start == 0.0) 0L else Reports.activeAtDistance(points, start)
        val toTime = if (completed && !useRunEndTime) Reports.activeAtDistance(points, end) else run.activeMs
        val distance = if (started) min(run.meters, end) - start else null
        val ms = if (started && fromTime != null && toTime != null) (toTime - fromTime).coerceAtLeast(0) else null
        val state = when { completed -> ProgressState.COMPLETE; run.status == RunStatus.FINISHED -> ProgressState.INCOMPLETE; run.meters >= start -> ProgressState.ACTIVE; else -> ProgressState.PENDING }
        return PaceProgressRow(label, (end - start).coerceAtLeast(0.0), distance, ms, state)
    }

    fun passes(course: Course, points: List<TrackPoint>): List<WaypointPass> {
        val tracker = OrderedWaypointTracker(course)
        points.forEach(tracker::observe)
        return tracker.passed
    }

    fun coursePassages(course: Course, run: RunSummary, points: List<TrackPoint>): List<PaceProgressRow> {
        val passages = passes(course, points).associateBy { it.index }
        val cumulative = cumulative(course)
        return course.points.zipWithNext().indices.map { index ->
            val a = passages[index]; val b = passages[index + 1]
            val distance = a?.let { ((b?.meters ?: run.meters) - it.meters).coerceAtLeast(0.0) }
            val ms = a?.let { ((b?.activeMs ?: run.activeMs) - it.activeMs).coerceAtLeast(0L) }
            val state = when { b != null -> ProgressState.COMPLETE; run.status == RunStatus.FINISHED -> ProgressState.INCOMPLETE; a != null -> ProgressState.ACTIVE; else -> ProgressState.PENDING }
            PaceProgressRow("${index + 1} → ${index + 2}", cumulative[index + 1] - cumulative[index], distance, ms, state)
        }
    }
    private fun cumulative(course: Course) = course.points.zipWithNext().map { (a, b) -> Geo.distance(a, b) }.runningFold(0.0, Double::plus)
}
