package com.runkeep.core

import kotlin.math.floor
import kotlin.math.roundToLong

enum class BeatSound { CLICK, FAST, SLOW }

class BeatSchedule {
    private var anchor: Long? = null
    private var beat = 0L
    private var cadence = 0
    private var sound: BeatSound? = null
    var nextAt: Long? = null; private set

    fun configure(now: Long, alarm: PaceDirection?, cadenceSpm: Int?, metronome: Boolean = false) {
        val newSound = when (alarm) {
            PaceDirection.FAST -> BeatSound.FAST
            PaceDirection.SLOW -> BeatSound.SLOW
            null -> if (metronome) BeatSound.CLICK else null
        }
        val newCadence = cadenceSpm?.takeIf { it in PaceFeedbackPolicy.CADENCE_RANGE }
            ?: PaceFeedbackPolicy.DEFAULT_CADENCE_SPM
        if (newSound == null) { stop(); return }
        if (anchor == null || cadence != newCadence) {
            anchor = now; beat = 0; nextAt = now
        }
        cadence = newCadence; sound = newSound
    }

    fun poll(now: Long): BeatSound? {
        val origin = anchor ?: return null
        if (now < (nextAt ?: return null)) return null
        val result = sound
        val period = 60_000.0 / cadence
        beat = maxOf(beat + 1, floor((now - origin) / period).toLong() + 1)
        nextAt = origin + (beat * period).roundToLong()
        if (nextAt!! <= now) {
            beat++
            nextAt = origin + (beat * period).roundToLong()
        }
        return result
    }

    fun stop() { anchor = null; nextAt = null; beat = 0; cadence = 0; sound = null }
}
