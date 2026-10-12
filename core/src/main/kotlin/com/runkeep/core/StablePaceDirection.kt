package com.runkeep.core

/** An explicit optional coaching policy. A timer evaluation is never a new GPS observation. */
internal class StablePaceDirection {
    private var candidate: PaceDirection? = null
    private var firstAt: Long? = null
    private var lastGpsAt: Long? = null
    private var observations = 0
    private var confirmed = false

    fun reset() { candidate = null; firstAt = null; lastGpsAt = null; observations = 0; confirmed = false }

    fun evaluate(direction: PaceDirection?, error: Double, tolerance: Int, gpsAt: Long,
                 targetChanged: Boolean): PaceDirection? {
        if (direction == null) { reset(); return null }
        if (targetChanged || kotlin.math.abs(error) >= maxOf(30, tolerance * 3)) {
            reset(); candidate = direction; firstAt = gpsAt; lastGpsAt = gpsAt; observations = 3
            confirmed = true
            return direction
        }
        if (candidate != direction) {
            candidate = direction; firstAt = gpsAt; lastGpsAt = gpsAt; observations = 1
            confirmed = false
            return null
        }
        if (lastGpsAt?.let { gpsAt > it } == true) { observations++; lastGpsAt = gpsAt }
        if (observations >= 3 && gpsAt - (firstAt ?: gpsAt) >= 2_000) confirmed = true
        return direction.takeIf { confirmed }
    }
}
