package com.runkeep.core

import kotlin.math.max

/** GPS-only hysteresis. No paused samples are committed to the track. */
internal class AutoPauseDetector {
    private var stationarySince: Long? = null
    private var stationaryAnchor: LocationFix? = null
    private var latestAt: Long? = null
    private var anchor: LocationFix? = null
    private var movingLegs = 0
    private var pathMeters = 0.0
    var diagnostics = AutoPauseDiagnostics("waiting"); private set

    fun reset(reason: String) {
        stationarySince = null; stationaryAnchor = null; latestAt = null; anchor = null; movingLegs = 0; pathMeters = 0.0
        diagnostics = AutoPauseDiagnostics(reason)
    }

    fun expire(now: Long) {
        if (latestAt?.let { now - it > MAX_GAP_MS } == true) reset("gps_gap")
    }

    fun observeRunning(fix: LocationFix, previous: LocationFix?) {
        if (fix.accuracy > MAX_MOTION_ACCURACY) { reset("accuracy"); return }
        expire(fix.monotonicMs)
        latestAt = fix.monotonicMs
        val speed = fix.validSpeed()
        // A sequence of short walking legs must not be mistaken for eight seconds of stillness.
        val reference = stationaryAnchor ?: previous ?: fix
        val stopped = speed?.let { it <= STOP_SPEED }
            ?: (Geo.distance(reference.coordinate, fix.coordinate) <= max(2.5, max(reference.accuracy, fix.accuracy) / 2.0))
        if (stopped) {
            if (stationarySince == null) { stationarySince = fix.monotonicMs; stationaryAnchor = reference }
            diagnostics = AutoPauseDiagnostics("stationary", elapsedMs = fix.monotonicMs - stationarySince!!)
        } else {
            stationarySince = null; stationaryAnchor = null
            diagnostics = AutoPauseDiagnostics("moving_or_uncertain")
        }
    }

    fun shouldPause(now: Long): Boolean = stationarySince?.let {
        now - it >= STOP_MS && latestAt?.let { at -> now - at in 0..MAX_GAP_MS } == true
    } == true

    fun shouldResume(fix: LocationFix, previous: LocationFix?, delta: Double): Boolean {
        expire(fix.monotonicMs)
        val gap = previous?.let { fix.monotonicMs - it.monotonicMs }
        if (fix.accuracy > MAX_MOTION_ACCURACY || previous == null || previous.accuracy > MAX_MOTION_ACCURACY) {
            reset(if (fix.accuracy > MAX_MOTION_ACCURACY) "accuracy" else "new_anchor")
            latestAt = fix.monotonicMs
            return false
        }
        if (gap == null || gap !in 1..MAX_GAP_MS) {
            reset("gps_gap"); latestAt = fix.monotonicMs; return false
        }
        if (fix.accuracy > max(5f, previous.accuracy * 2)) {
            reset("accuracy_changed"); latestAt = fix.monotonicMs; return false
        }
        latestAt = fix.monotonicMs
        val seconds = gap / 1000.0
        // Speed alone can spike on a stationary phone. Require coordinate progress as well.
        val moving = fix.validSpeed()?.let { it >= RESUME_SPEED && delta / seconds >= 0.5 }
            ?: (delta / seconds >= 1.0)
        if (!moving) { reset("insufficient_motion"); latestAt = fix.monotonicMs; return false }
        if (anchor == null) anchor = previous
        movingLegs++
        pathMeters += delta
        val start = anchor!!
        val net = Geo.distance(start.coordinate, fix.coordinate)
        val elapsed = fix.monotonicMs - start.monotonicMs
        val required = max(4.0, (start.accuracy + fix.accuracy) / 2.0)
        // Back-and-forth jitter has a long path but little net progress.
        if (net < pathMeters * 0.75) {
            reset("direction_changed"); latestAt = fix.monotonicMs; return false
        }
        val confirmed = movingLegs >= 3 && elapsed >= 2000 && net >= required
        diagnostics = AutoPauseDiagnostics(if (confirmed) "resume_confirmed" else "confirming_motion", movingLegs, elapsed, net, required)
        return confirmed
    }

    companion object {
        const val MAX_GAP_MS = 3000L
        private const val STOP_MS = 8000L
        private const val STOP_SPEED = 0.5f
        private const val RESUME_SPEED = 1.2f
        private const val MAX_MOTION_ACCURACY = 15f
    }
}

/** Inspectable in a debugger; no coordinates or personal data, and never persisted. */
data class AutoPauseDiagnostics(
    val reason: String,
    val movingLegs: Int = 0,
    val elapsedMs: Long = 0,
    val netMeters: Double = 0.0,
    val requiredMeters: Double = 0.0,
)

private fun LocationFix.validSpeed(): Float? = speed?.takeIf { it.isFinite() && it >= 0 }
