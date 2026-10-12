package com.runkeep.core

object DistancePacePolicy {
    val RANGE = 10..10_000
    const val DEFAULT_INTERVAL_METERS = 100

    fun normalizeInterval(value: Int): Int = value.takeIf { it in RANGE } ?: DEFAULT_INTERVAL_METERS
}

class DistancePaceTracker(initialMeters: Double = 0.0) {
    private var enabled = false
    private var intervalMeters = DistancePacePolicy.DEFAULT_INTERVAL_METERS
    private var lastMeters = initialMeters.takeIf(::usableDistance) ?: 0.0
    private var nextBoundary = futureBoundary(lastMeters)
    private var needsReanchor = !usableDistance(initialMeters)

    fun configure(enabled: Boolean, intervalMeters: Int, meters: Double) {
        val normalizedInterval = DistancePacePolicy.normalizeInterval(intervalMeters)
        if (this.enabled == enabled && this.intervalMeters == normalizedInterval) return
        this.enabled = enabled
        this.intervalMeters = normalizedInterval
        resetBoundary(meters)
    }

    fun resetBoundary(meters: Double) {
        if (!usableDistance(meters)) {
            needsReanchor = true
            return
        }
        lastMeters = meters
        nextBoundary = futureBoundary(meters)
        needsReanchor = false
    }

    fun observe(meters: Double, valid: Boolean, running: Boolean): Boolean {
        if (!usableDistance(meters)) {
            needsReanchor = true
            return false
        }
        if (needsReanchor || meters < lastMeters) {
            resetBoundary(meters)
            return false
        }
        lastMeters = meters
        if (!enabled || !running) {
            nextBoundary = futureBoundary(meters)
            return false
        }
        val crossed = meters >= nextBoundary
        if (crossed) nextBoundary = futureBoundary(meters)
        return crossed && valid
    }

    private fun futureBoundary(meters: Double): Double {
        val interval = intervalMeters.toDouble()
        val remaining = interval - meters % interval
        val boundary = meters + remaining
        return if (boundary <= meters || boundary - meters < remaining) Math.nextUp(boundary) else boundary
    }

    private fun usableDistance(meters: Double): Boolean = meters.isFinite() && meters >= 0.0
}
