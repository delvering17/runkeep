package com.runkeep.core

import kotlin.math.*

object Geo {
    private const val R = 6371008.8
    fun distance(a: Coordinate, b: Coordinate): Double {
        val p = Math.toRadians(a.lat); val q = Math.toRadians(b.lat)
        val d = Math.toRadians(b.lat - a.lat); val l = Math.toRadians(b.lon - a.lon)
        val h = sin(d / 2).pow(2) + cos(p) * cos(q) * sin(l / 2).pow(2)
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    fun bearing(a: Coordinate, b: Coordinate): Double {
        val p = Math.toRadians(a.lat); val q = Math.toRadians(b.lat); val l = Math.toRadians(b.lon - a.lon)
        return Math.toDegrees(atan2(sin(l) * cos(q), cos(p) * sin(q) - sin(p) * cos(q) * cos(l)))
    }
    fun turn(a: Coordinate, b: Coordinate, c: Coordinate) = (bearing(b, c) - bearing(a, b) + 540) % 360 - 180
    data class Projection(val fraction: Double, val away: Double)
    fun project(p: Coordinate, a: Coordinate, b: Coordinate, clamp: Boolean = true): Projection {
        val scale = cos(Math.toRadians(a.lat))
        val bx = Math.toRadians(b.lon - a.lon) * R * scale; val by = Math.toRadians(b.lat - a.lat) * R
        val px = Math.toRadians(p.lon - a.lon) * R * scale; val py = Math.toRadians(p.lat - a.lat) * R
        val d = bx * bx + by * by
        val raw = if (d < 0.001) 0.0 else (px * bx + py * by) / d
        val f = if (clamp) raw.coerceIn(0.0, 1.0) else raw
        return Projection(f, hypot(px - f * bx, py - f * by))
    }
}

/** Progress is bounded by physical travel, so crossing an old/future segment cannot skip a loop. */
class CourseNavigator(private val points: List<Coordinate>, private val lead: Int) {
    private val lengths = points.zipWithNext().map { (a, b) -> Geo.distance(a, b) }
    private val cumulative = lengths.runningFold(0.0) { a, b -> a + b }
    private var progress = 0.0
    private var previous: Coordinate? = null
    private var lastOffRoute = Long.MIN_VALUE / 2
    private var lastTurnAt = -1000.0
    private val spoken = mutableSetOf<Int>()
    fun update(p: Coordinate, now: Long): String? {
        if (points.size < 2) return null
        val moved = previous?.let { Geo.distance(it, p) } ?: 0.0
        previous = p
        val candidates = lengths.indices.map { i ->
            val projection = Geo.project(p, points[i], points[i + 1])
            Triple(i, projection, cumulative[i] + projection.fraction * lengths[i])
        }.filter { it.third in max(0.0, progress - 20)..(progress + moved.coerceAtMost(50.0) + 35) }
        val match = candidates.minByOrNull { it.second.away + abs(it.third - progress) * 0.05 }
        if (match == null || match.second.away > 35) {
            if (now - lastOffRoute > 60_000) { lastOffRoute = now; return "설정한 경로를 벗어났습니다" }
            return null
        }
        progress = max(progress, match.third)
        for (i in 1 until points.lastIndex) {
            if (i in spoken) continue
            val remaining = cumulative[i] - progress
            if (remaining < -8) { spoken += i; continue }
            if (remaining > lead || remaining < -5) continue
            if (lengths[i - 1] < 8 || lengths[i] < 8) continue
            val angle = Geo.turn(points[i - 1], points[i], points[i + 1])
            if (abs(angle) < 35 || cumulative[i] - lastTurnAt < 15) continue
            spoken += i; lastTurnAt = cumulative[i]
            return when {
                abs(angle) > 150 -> "앞에서 돌아가세요"
                angle > 0 -> "앞에서 오른쪽으로 이동하세요"
                else -> "앞에서 왼쪽으로 이동하세요"
            }
        }
        return null
    }
    fun restore(points: List<TrackPoint>) { points.forEach { update(it.coordinate, it.epochMs) } }
}
