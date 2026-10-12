package com.runkeep.core

object PaceFeedbackPolicy {
    const val DEFAULT_REPEAT_SECONDS = 30
    val REPEAT_RANGE = 10..120
    val CADENCE_RANGE = 120..220
    const val DEFAULT_CADENCE_SPM = 180
    const val GPS_MAX_AGE_MS = 10_000L
    const val VOICE_SUSTAIN_MS = 10_000L
}

enum class PaceDirection { FAST, SLOW }

data class PaceFeedbackState(
    val direction: PaceDirection? = null,
    val alarm: PaceDirection? = null,
    val voiceDue: Boolean = false,
    val currentPace: Double? = null,
    val targetPace: Int? = null
)

class PaceFeedback {
    var state = PaceFeedbackState(); private set
    private var since: Long? = null
    private var lastSpoken: Long? = null
    private var lastEvaluation: Long? = null
    private var voiceInFlight = false
    private var voiceAvailable = false

    fun reset() {
        state = PaceFeedbackState(); since = null; lastSpoken = null; lastEvaluation = null; voiceInFlight = false; voiceAvailable = false
    }

    fun evaluate(now: Long, running: Boolean, pace: Double?, target: Int?, gpsAt: Long?, prefs: Preferences): PaceFeedbackState {
        if (lastEvaluation?.let { now < it } == true) return state
        lastEvaluation = now
        val settings = prefs.normalized()
        if (!running || gpsAt == null || now - gpsAt !in 0..PaceFeedbackPolicy.GPS_MAX_AGE_MS ||
            pace == null || !pace.isFinite() || pace <= 0 || pace > 3600 || target == null || target !in 120..1800) {
            state = PaceFeedbackState(); since = null; voiceAvailable = false
            return state
        }
        val error = pace - target
        val tolerance = settings.paceTolerance
        val direction = when {
            error < -tolerance -> PaceDirection.FAST
            error > tolerance -> PaceDirection.SLOW
            else -> null
        }
        if (direction != state.direction || since == null) since = direction?.let { now }
        val elapsed = since?.let { now - it } ?: 0L
        val current = PaceFeedbackState(direction, currentPace = pace, targetPace = target)
        voiceAvailable = paceWarningAnnouncement(current, settings, now) != null
        val voiceDue = !voiceInFlight && voiceAvailable &&
            elapsed >= PaceFeedbackPolicy.VOICE_SUSTAIN_MS &&
            (lastSpoken?.let { now - it >= settings.paceRepeatSeconds * 1000L } != false)
        state = PaceFeedbackState(direction,
            direction.takeIf { settings.paceAlarm },
            voiceDue, pace, target)
        return state
    }

    fun requested() {
        if (!voiceAvailable || !state.voiceDue) return
        voiceInFlight = true
        state = state.copy(voiceDue = false)
    }
    fun completed() { voiceInFlight = false }
    fun spoken(now: Long) {
        if (!voiceAvailable || (!voiceInFlight && !state.voiceDue)) return
        lastSpoken = now
        state = state.copy(voiceDue = false)
    }
}

fun paceWarningAnnouncement(state: PaceFeedbackState, settings: Preferences, now: Long): Announcement.Message? {
    val direction = state.direction ?: return null
    if (!settings.voice || !settings.paceVoice) return null
    val warning = if (!settings.readPaceStatus) null else when (direction) {
        PaceDirection.FAST -> "현재 페이스가 빠릅니다"
        PaceDirection.SLOW -> "현재 페이스가 느립니다"
    }
    val values = listOfNotNull(
        state.currentPace?.takeIf { settings.readCurrentPace && it.isFinite() && it > 0 && it <= 3600 && kotlin.math.round(it) > 0 }
            ?.let { "현재 ${paceSpeechText(it)}" },
        state.targetPace?.takeIf { settings.readTargetPace && it in 120..1800 }
            ?.let { "목표 ${paceSpeechText(it.toDouble())}" }
    )
    val numbers = values.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { "${it}입니다." }
    val text = listOfNotNull(warning, numbers).takeIf { it.isNotEmpty() }?.joinToString(". ") ?: return null
    return Announcement.Message(AnnouncementKind.PACE_WARNING, text, now)
}
