package com.runkeep.core

internal class PeriodicPace {
    private var enabled = false
    private var intervalMs = 30_000L
    private var nextAt: Long? = null

    fun configure(enabled: Boolean, seconds: Int) {
        val interval = seconds.takeIf { it in 10..300 }?.times(1000L) ?: 30_000L
        if (this.enabled != enabled || intervalMs != interval) reset()
        this.enabled = enabled; intervalMs = interval
    }
    fun reset() { nextAt = null }
    fun observe(activeMs: Long, valid: Boolean): Boolean {
        if (!enabled || !valid) { reset(); return false }
        val boundary = nextAt ?: (activeMs + intervalMs).also { nextAt = it }
        if (activeMs < boundary) return false
        nextAt = activeMs + intervalMs // No backlog after an invalid signal or a delayed evaluation.
        return true
    }
    fun spoken(activeMs: Long) { if (enabled) nextAt = activeMs + intervalMs }
}

/** Service passes the exact token through onStart/completion, after its own engine/request check. */
class RunSpeechToken internal constructor(
    internal val owner: RunEngine,
    internal val generation: Long,
    internal val sequence: Long,
    internal val kind: AnnouncementKind,
    internal val readsCurrent: Boolean,
    internal val currentSeconds: Double?,
    internal val warningDirection: PaceDirection?,
    internal val warningTarget: Int?,
    internal val kilometer: Announcement.Kilometer?
)

data class PreparedRunSpeech(val event: Announcement, val token: RunSpeechToken)
