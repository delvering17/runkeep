package com.runkeep.core

class BeatOutputPolicy {
    private val schedule = BeatSchedule()
    private var alarm: PaceDirection? = null
    private var cadence: Int? = null
    private var metronome = false
    private var validUntil = 0L
    var requiresResume = false; private set
    var transientLoss = false; private set
    val isSuspended get() = requiresResume || transientLoss
    val nextAt get() = schedule.nextAt

    fun update(now: Long, direction: PaceDirection?, cadenceSpm: Int?, expiresAt: Long, metronome: Boolean = false) {
        alarm = direction
        cadence = cadenceSpm
        validUntil = expiresAt
        this.metronome = metronome
        refresh(now)
    }

    fun hasOutput(now: Long): Boolean {
        refresh(now)
        return alarm != null || metronome
    }

    fun poll(now: Long): BeatSound? {
        refresh(now)
        return schedule.poll(now)
    }

    private fun refresh(now: Long) {
        if (now > validUntil) alarm = null
        if (isSuspended) schedule.stop() else schedule.configure(now, alarm, cadence, metronome)
    }

    fun interrupt(permanent: Boolean) {
        if (permanent) {
            requiresResume = true
            transientLoss = false
        } else transientLoss = true
        resetPhase()
    }

    fun focusGained() { transientLoss = false }

    fun allowResume() {
        requiresResume = false
        transientLoss = false
        resetPhase()
    }

    fun resetPhase() { schedule.stop() }

    fun stop() {
        alarm = null
        cadence = null
        metronome = false
        validUntil = 0L
        transientLoss = false
        resetPhase()
    }
}
