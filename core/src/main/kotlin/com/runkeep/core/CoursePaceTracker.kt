package com.runkeep.core

import kotlin.math.abs
import kotlin.math.max

class OrderedWaypointTracker(private val course: Course) {
    private val passages = mutableListOf<WaypointPass>()
    private var previous: TrackPoint? = null
    val passed get() = passages.toList()
    val nextIndex get() = passages.size

    fun disconnect() { previous = null }

    fun observe(point: TrackPoint) {
        if (!point.accuracy.isFinite() || point.accuracy !in 0f..25f) { disconnect(); return }
        val a = previous
        val connected = a != null && a.segment == point.segment && point.epochMs - a.epochMs in 1..15_000 &&
            point.activeMs > a.activeMs && point.meters > a.meters
        val tolerance = point.accuracy.toDouble().coerceIn(8.0, 20.0)
        var lastFraction = 0.0
        while (nextIndex < course.points.size) {
            val index = nextIndex
            val target = course.points[index]
            if (index == 0 && Geo.distance(target, point.coordinate) <= tolerance) {
                passages += WaypointPass(0, point.meters, point.activeMs)
                continue
            }
            if (!connected) break
            if (index == 0) {
                val outgoing = course.points.getOrNull(1) ?: break
                val crossing = Geo.project(target, a!!.coordinate, point.coordinate, clamp = false)
                val before = Geo.project(a.coordinate, target, outgoing, clamp = false)
                val after = Geo.project(point.coordinate, target, outgoing, clamp = false)
                if (crossing.fraction !in 0.0..1.0 || crossing.away > tolerance || after.fraction <= before.fraction) break
                passages += WaypointPass(0, a.meters + (point.meters - a.meters) * crossing.fraction,
                    a.activeMs + ((point.activeMs - a.activeMs) * crossing.fraction).toLong())
                lastFraction = crossing.fraction
                continue
            }
            val incoming = course.points[index - 1]
            if (Geo.distance(incoming, target) <= .01) {
                val last = passages.last()
                passages += WaypointPass(index, last.meters, last.activeMs)
                continue
            }
            val projection = Geo.project(target, a!!.coordinate, point.coordinate, clamp = false)
            val before = Geo.project(a.coordinate, incoming, target, clamp = false)
            val after = Geo.project(point.coordinate, incoming, target, clamp = false)
            if (projection.fraction !in lastFraction..1.0 || projection.away > tolerance ||
                after.fraction <= before.fraction || Geo.project(a.coordinate, incoming, target).away > 35) break
            val meters = a.meters + (point.meters - a.meters) * projection.fraction
            if (meters <= passages.last().meters) break
            passages += WaypointPass(index, meters, a.activeMs + ((point.activeMs - a.activeMs) * projection.fraction).toLong())
            lastFraction = projection.fraction
        }
        previous = point
    }
}

class CourseDistanceTracker(private val course: Course, initialRouteMeters: Double = 0.0) {
    private val lengths = course.points.zipWithNext().map { (a, b) -> Geo.distance(a, b) }
    private val cumulative = lengths.runningFold(0.0, Double::plus)
    private var previous: TrackPoint? = null
    var routeMeters = initialRouteMeters; private set
    fun disconnect() { previous = null }

    fun observe(point: TrackPoint, progressMeters: Double): Double {
        val a = previous
        previous = point
        if (!point.accuracy.isFinite() || point.accuracy !in 0f..25f) { disconnect(); return 0.0 }
        if (a == null || a.segment != point.segment || point.epochMs - a.epochMs !in 1..15_000 || point.meters <= a.meters) return 0.0
        val moved = point.meters - a.meters
        val currentIndex = cumulative.drop(1).indexOfFirst { routeMeters + .000001 < it }.let { if (it < 0) lengths.lastIndex else it }
        fun match(coordinate: Coordinate, forwardOnly: Boolean = false): Pair<Double, Double>? = lengths.indices.mapNotNull { index ->
            if (forwardOnly) {
                val before = Geo.project(a.coordinate, course.points[index], course.points[index + 1], clamp = false)
                val after = Geo.project(point.coordinate, course.points[index], course.points[index + 1], clamp = false)
                if (after.fraction <= before.fraction) return@mapNotNull null
            }
            var lastFraction = 0.0
            for (boundary in (currentIndex + 1)..index) {
                val crossing = Geo.project(course.points[boundary], a.coordinate, point.coordinate, clamp = false)
                if (crossing.fraction !in lastFraction..1.0 || crossing.away > 35) return@mapNotNull null
                lastFraction = crossing.fraction
            }
            val projection = Geo.project(coordinate, course.points[index], course.points[index + 1])
            val along = cumulative[index] + projection.fraction * lengths[index]
            if (along !in max(0.0, routeMeters - 20)..(routeMeters + moved + 35) || projection.away > 35) null
            else along to projection.away
        }.minByOrNull { it.second + abs(it.first - routeMeters) * .05 }
        val start = match(a.coordinate)
        val end = match(point.coordinate, forwardOnly = true) ?: return 0.0
        if (start == null) { routeMeters = max(routeMeters, end.first); return 0.0 }
        val forward = end.first - max(routeMeters, start.first)
        routeMeters = max(routeMeters, end.first)
        if (forward < .8) return 0.0
        return moved.coerceAtMost(forward).coerceAtMost((course.meters - progressMeters).coerceAtLeast(0.0))
    }
}
