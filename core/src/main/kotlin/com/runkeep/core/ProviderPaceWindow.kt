package com.runkeep.core

/** Runtime estimate only. These samples never contribute to recorded distance. */
internal class ProviderPaceWindow {
    private data class Sample(val activeMs: Long, val speed: Double)
    private val samples = ArrayDeque<Sample>()

    fun clear() = samples.clear()

    fun observe(fix: LocationFix, activeMs: Long) {
        val speed = fix.speed?.toDouble()
        val uncertainty = fix.speedAccuracy?.toDouble()
        // Application gates, not an Android accuracy guarantee. Unknown uncertainty falls back.
        val usable = speed != null && speed.isFinite() && speed in 0.0..12.0 &&
            uncertainty != null && uncertainty.isFinite() && uncertainty in 0.0..0.3f.toDouble() &&
            (speed == 0.0 || uncertainty <= speed * .1)
        if (!usable) { clear(); return }
        if (samples.lastOrNull()?.let { activeMs <= it.activeMs || activeMs - it.activeMs > 15_000 } == true) clear()
        samples += Sample(activeMs, speed!!)
        while (samples.size > 1 && activeMs - samples.first().activeMs > 20_000) samples.removeFirst()
    }

    val ready: Boolean get() = samples.size > 1 && samples.last().activeMs - samples.first().activeMs >= 8_000

    fun pace(): Double? {
        if (!ready) return null
        val duration = samples.last().activeMs - samples.first().activeMs
        val meters = samples.zipWithNext().sumOf { (a, b) -> (a.speed + b.speed) * .5 * (b.activeMs - a.activeMs) / 1000 }
        return if (meters >= 8) duration / meters else null
    }
}
