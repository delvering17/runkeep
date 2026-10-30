package com.runkeep.core

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.roundToInt

data class RunPreparation(val requestId: String, val remainingSeconds: Int)

fun workoutCommandMatches(requestedId: String?, activeId: String?) = requestedId == null || requestedId == activeId

class RunStartPolicy {
    private var draft: RunSummary? = null
    private var deadlineMs = 0L
    private val handled = mutableSetOf<String>()

    fun prepare(request: RunSummary, nowMs: Long, delaySeconds: Int, canStart: Boolean): Boolean {
        if (!canStart || draft != null || request.id in handled) return false
        draft = request
        deadlineMs = nowMs + normalizeDelay(delaySeconds) * 1000L
        return true
    }

    fun preparation(nowMs: Long): RunPreparation? = draft?.let {
        RunPreparation(it.id, kotlin.math.ceil((deadlineMs - nowMs).coerceAtLeast(0) / 1000.0).toInt())
    }

    fun untilNextUpdate(nowMs: Long): Long {
        val remaining = (deadlineMs - nowMs).coerceAtLeast(0)
        return if (remaining == 0L) 0 else ((remaining - 1) % 1000) + 1
    }

    fun takeReady(requestId: String, nowMs: Long, epochMs: Long, canStart: Boolean): RunSummary? {
        val request = draft ?: return null
        if (request.id != requestId) return null
        if (!canStart) { cancel(); return null }
        if (nowMs < deadlineMs) return null
        draft = null
        handled += request.id
        return RunSummary(id = request.id, startedAt = epochMs, goalMeters = request.goalMeters,
            course = request.course, plan = request.plan, weightKg = request.weightKg, coursePaceEnabled = request.coursePaceEnabled)
    }

    fun cancel(): Boolean {
        val request = draft ?: return false
        handled += request.id
        draft = null
        return true
    }

    companion object {
        fun normalizeDelay(seconds: Int) = seconds.takeIf { it in 0..60 } ?: 0
        fun parseDelay(text: String): Int? = text.takeIf { it.matches(Regex("[0-9]+")) }
            ?.toIntOrNull()?.takeIf { it in 1..60 }
        fun validRequest(request: RunSummary) =
            (request.goalMeters == null || MeasurementLimits.distance(request.goalMeters, 100.0)) &&
                (request.plan == null || request.plan.steps.isNotEmpty() && request.plan.steps.all(MeasurementLimits::step)) &&
                (!request.coursePaceEnabled || request.course?.let { it.points.size in 2..10_000 && it.meters > .01 } == true)
    }
}

fun finishSpeechText(run: RunSummary, points: List<TrackPoint>): String {
    val parts = mutableListOf("달리기를 종료합니다")
    if (run.meters.isFinite() && run.meters > 0) {
        val distance = BigDecimal.valueOf(run.meters).movePointLeft(3).setScale(2, RoundingMode.HALF_UP)
        if (distance.signum() > 0) parts += "총 ${distance.stripTrailingZeros().toPlainString()}킬로미터"
        else parts += "총 ${BigDecimal.valueOf(run.meters).stripTrailingZeros().toPlainString()}미터"
    }
    run.averagePace?.takeIf { run.activeMs > 0 && it.isFinite() && it > 0 && it <= 3600 }
        ?.let { paceSpeechText(it) }?.takeUnless { it == "측정 불가" }?.let { parts += "평균 페이스 $it" }
    measuredAverageCadence(points)?.let { parts += "평균 케이던스 ${it.roundToInt()}" }
    return parts.joinToString(". ", postfix = ".")
}

fun measuredAverageCadence(points: List<TrackPoint>): Double? = points.mapNotNull { point ->
    point.cadence?.takeIf { it.isFinite() && it > 0 && it <= Int.MAX_VALUE }
}.takeIf { it.isNotEmpty() }?.average()?.takeIf { it.isFinite() && it >= .5 }

fun workoutStartAnnouncement(nowMs: Long) =
    Announcement.Message(AnnouncementKind.WORKOUT_START, "달리기를 시작합니다", nowMs)

data class FinishSpeechTicket(val sequence: Long, val runId: String)

class FinishSpeechPolicy {
    var ticket: FinishSpeechTicket? = null; private set
    var requested = false; private set
    private var sequence = 0L
    private var deadlineMs = 0L
    private val handled = mutableSetOf<String>()

    fun begin(runId: String, nowMs: Long): FinishSpeechTicket? {
        if (runId in handled) return ticket?.takeIf { it.runId == runId }
        handled += runId
        return FinishSpeechTicket(++sequence, runId).also {
            ticket = it; requested = false; deadlineMs = nowMs + PREPARATION_LIMIT_MS
        }
    }

    fun accepted(value: FinishSpeechTicket, nowMs: Long): Boolean {
        if (ticket != value || requested || nowMs >= deadlineMs) return false
        requested = true; deadlineMs = nowMs + PLAYBACK_LIMIT_MS
        return true
    }

    fun complete(value: FinishSpeechTicket): Boolean {
        if (ticket != value) return false
        cancel()
        return true
    }

    fun expired(nowMs: Long) = ticket != null && nowMs >= deadlineMs
    fun cancel() { ticket = null; requested = false }

    companion object {
        const val PREPARATION_LIMIT_MS = 5000L
        const val PLAYBACK_LIMIT_MS = 30_000L
    }
}
