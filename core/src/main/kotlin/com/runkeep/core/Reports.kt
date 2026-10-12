package com.runkeep.core

import java.time.Instant

data class Split(val kilometer: Int, val meters: Double, val activeMs: Long) { val pace get() = activeMs / meters }
object Reports {
    /** Keep the exact goal boundary, without mutating the original track. */
    fun clip(points: List<TrackPoint>, meters: Double): List<TrackPoint> {
        val index = points.indexOfFirst { it.meters > meters }
        if (index < 0) return points
        if (index == 0) return emptyList()
        val a = points[index - 1]; val b = points[index]
        if (a.meters == meters || b.meters <= a.meters) return points.take(index)
        val f = (meters - a.meters) / (b.meters - a.meters)
        val boundary = b.copy(coordinate = Coordinate(a.coordinate.lat + (b.coordinate.lat - a.coordinate.lat) * f,
            a.coordinate.lon + (b.coordinate.lon - a.coordinate.lon) * f), meters = meters,
            activeMs = a.activeMs + ((b.activeMs - a.activeMs) * f).toLong(),
            epochMs = a.epochMs + ((b.epochMs - a.epochMs) * f).toLong())
        return points.take(index) + boundary
    }
    fun paceSamples(points: List<TrackPoint>): List<Pair<Long, Double>> {
        val result = mutableListOf<Pair<Long, Double>>()
        val window = ArrayDeque<TrackPoint>()
        for (point in points) {
            if (window.lastOrNull()?.segment != point.segment) window.clear()
            window += point
            while (window.size > 1 && point.activeMs - window.first().activeMs > 20_000) window.removeFirst()
            val start = window.first(); val distance = point.meters - start.meters; val ms = point.activeMs - start.activeMs
            if (ms >= 8000 && distance >= 8) result += point.epochMs to ms / distance
        }
        return result
    }
    fun ascent(points: List<TrackPoint>): Double? {
        var anchor: TrackPoint? = null; var total = 0.0
        for (point in points) {
            val height = point.altitude ?: continue
            val a = anchor
            if (a == null || a.segment != point.segment) { anchor = point; continue }
            val delta = height - a.altitude!!
            if (kotlin.math.abs(delta) >= 3) { if (delta > 0) total += delta; anchor = point }
        }
        return total.takeIf { anchor != null }
    }
    fun activeAtDistance(points: List<TrackPoint>, meters: Double): Long? {
        if (meters <= 0) return 0
        val index = points.indexOfFirst { it.meters >= meters }
        if (index < 0) return null
        val b = points[index]; val a = points.getOrNull(index - 1)
        if (a == null || b.meters <= a.meters) return b.activeMs
        return a.activeMs + ((b.activeMs - a.activeMs) * (meters - a.meters) / (b.meters - a.meters)).toLong()
    }
    fun splits(points: List<TrackPoint>, totalMeters: Double, activeMs: Long): List<Split> {
        val out = mutableListOf<Split>(); var previous = 0L; var km = 1
        while (km * 1000 <= totalMeters) {
            val elapsed = activeAtDistance(points, km * 1000.0) ?: break
            out += Split(km, 1000.0, elapsed - previous); previous = elapsed; km++
        }
        val remaining = totalMeters - (km - 1) * 1000
        if (remaining > 1) out += Split(km, remaining, (activeMs - previous).coerceAtLeast(0))
        return out
    }
    fun gpx(name: String, points: List<Coordinate>): String = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Runkeep" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>${escape(name)}</name><trkseg>${points.joinToString("") { "<trkpt lat=\"${it.lat}\" lon=\"${it.lon}\"/>" }}</trkseg></trk></gpx>"""
    fun runGpx(name: String, points: List<TrackPoint>): String = """<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="Runkeep" xmlns="http://www.topografix.com/GPX/1/1"><trk><name>${escape(name)}</name>${points.groupBy { it.segment }.values.joinToString("") { part -> "<trkseg>" + part.joinToString("") { "<trkpt lat=\"${it.coordinate.lat}\" lon=\"${it.coordinate.lon}\">" + (it.altitude?.let { a -> "<ele>$a</ele>" } ?: "") + "<time>${Instant.ofEpochMilli(it.epochMs)}</time></trkpt>" } + "</trkseg>" }}</trk></gpx>"""
    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
