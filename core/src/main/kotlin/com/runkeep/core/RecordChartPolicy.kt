package com.runkeep.core

import kotlin.math.*

enum class RecordChartAxis { TIME, DISTANCE }
data class RecordChartSample(val epochMs: Long, val value: Double?, val segment: Int? = null)
data class RecordChartPoint(val epochMs: Long, val x: Double, val value: Double, val segment: Int?)
data class RecordChartSeries(val axis: RecordChartAxis, val parts: List<List<RecordChartPoint>>, val sourceCount: Int,
                             val mappedCount: Int, val distanceAvailable: Boolean) {
    val points get() = parts.flatten()
}

/** Display-only mapping. Wall-clock epoch differences are elapsed time, never active workout time. */
object RecordChartPolicy {
    const val GPS_GAP_MS = 15_000L
    private fun valid(point: TrackPoint) = point.meters.isFinite() && point.meters >= 0 && point.activeMs >= 0 &&
        point.accuracy.isFinite() && point.accuracy in 0f..25f
    private fun continuous(a: TrackPoint, b: TrackPoint): Boolean {
        val elapsed = b.epochMs - a.epochMs
        val active = b.activeMs - a.activeMs
        return valid(a) && valid(b) && a.segment == b.segment && elapsed in 1..GPS_GAP_MS &&
            active > 0 && active <= elapsed + 1000 && b.meters >= a.meters
    }
    fun distanceAt(epoch: Long, gps: List<TrackPoint>): Pair<Double, Int>? {
        val index = gps.binarySearchBy(epoch) { it.epochMs }
        if (index >= 0) return gps[index].takeIf(::valid)?.let { it.meters to it.segment }
        val insertion = -index - 1
        val a = gps.getOrNull(insertion - 1) ?: return null
        val b = gps.getOrNull(insertion) ?: return null
        if (!continuous(a, b)) return null
        val fraction = (epoch - a.epochMs).toDouble() / (b.epochMs - a.epochMs)
        return (a.meters + (b.meters - a.meters) * fraction) to a.segment
    }

    fun series(samples: List<RecordChartSample>, points: List<TrackPoint>, startedAt: Long,
               requestedAxis: RecordChartAxis, maxGapMs: Long = 30_000, limitPerPart: Int = 240): RecordChartSeries {
        val gps = points.filter { it.epochMs >= startedAt }.sortedBy { it.epochMs }.distinctBy { it.epochMs }
        val invalidPrefix = IntArray(gps.size)
        gps.zipWithNext().forEachIndexed { index, (a, b) ->
            invalidPrefix[index + 1] = invalidPrefix[index] + if (!continuous(a, b)) 1 else 0
        }
        fun lowerBound(epoch: Long, inclusive: Boolean): Int {
            var lo = 0; var hi = gps.size
            while (lo < hi) {
                val middle = (lo + hi) / 2
                if (gps[middle].epochMs < epoch || (inclusive && gps[middle].epochMs == epoch)) lo = middle + 1 else hi = middle
            }
            return lo
        }
        fun crossesGpsGap(from: Long, to: Long): Boolean {
            if (gps.size < 2) return false
            val first = (lowerBound(from, true) - 1).coerceAtLeast(0)
            val last = (lowerBound(to, false) - 1).coerceAtMost(gps.lastIndex - 1)
            return first <= last && invalidPrefix[last + 1] > invalidPrefix[first]
        }
        val ordered = samples.filter { it.epochMs >= startedAt }.sortedBy { it.epochMs }.distinctBy { it.epochMs }
        val mapped = ordered.map { sample -> sample to distanceAt(sample.epochMs, gps) }
        val available = mapped.any { (sample, mapping) -> sample.value?.isFinite() == true && mapping != null }
        val axis = if (requestedAxis == RecordChartAxis.DISTANCE && available) requestedAxis else RecordChartAxis.TIME
        val parts = mutableListOf<MutableList<RecordChartPoint>>()
        var previous: RecordChartPoint? = null
        var gap = true
        for ((sample, mapping) in mapped) {
            val value = sample.value
            if (value == null || !value.isFinite() || (axis == RecordChartAxis.DISTANCE && mapping == null)) {
                gap = true; previous = null; continue
            }
            // HR can remain readable in time during missing GPS, but must not bridge a known pause/gap.
            val segment = sample.segment ?: mapping?.second
            val point = RecordChartPoint(sample.epochMs,
                if (axis == RecordChartAxis.TIME) (sample.epochMs - startedAt) / 1000.0 else requireNotNull(mapping).first / 1000,
                value, segment)
            val old = previous
            if (gap || old == null || point.epochMs - old.epochMs > maxGapMs || point.segment != old.segment || point.x < old.x ||
                crossesGpsGap(old.epochMs, point.epochMs)) {
                parts += mutableListOf(point)
            } else parts.last() += point
            gap = false; previous = point
        }
        return RecordChartSeries(axis, parts.map { downsample(it, limitPerPart) }, ordered.size,
            mapped.count { it.first.value?.isFinite() == true && it.second != null }, available)
    }

    /** Per bucket preserve both extrema, in original time order, plus the first and last sample. */
    fun downsample(points: List<RecordChartPoint>, limit: Int): List<RecordChartPoint> {
        require(limit >= 4)
        if (points.size <= limit) return points
        val buckets = (limit - 2) / 2
        val selected = sortedSetOf(0, points.lastIndex)
        for (bucket in 0 until buckets) {
            val start = 1 + bucket * (points.size - 2) / buckets
            val end = 1 + (bucket + 1) * (points.size - 2) / buckets
            val indices = start until end
            indices.minByOrNull { points[it].value }?.let(selected::add)
            indices.maxByOrNull { points[it].value }?.let(selected::add)
        }
        return selected.map(points::get)
    }
    fun ticks(minimum: Double, maximum: Double, count: Int = 4): List<Double> {
        if (!minimum.isFinite() || !maximum.isFinite()) return emptyList()
        val low = if (minimum == maximum) minimum - max(1.0, abs(minimum) * .05) else minimum
        val high = if (minimum == maximum) maximum + max(1.0, abs(maximum) * .05) else maximum
        return (0 until count).map { low + (high - low) * it / (count - 1) }
    }
}
