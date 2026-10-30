package com.runkeep.core

/** Pace is elapsed seconds per kilometer, never a time of day. */
fun paceSpeechText(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds <= 0 || seconds > 3600) return "측정 불가"
    val rounded = kotlin.math.round(seconds).toInt()
    if (rounded == 0) return "측정 불가"
    return listOfNotNull((rounded / 60).takeIf { it > 0 }?.let { "${it}분" },
        (rounded % 60).takeIf { it > 0 }?.let { "${it}초" }).joinToString(" ")
}

fun durationSpeechText(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return listOfNotNull((seconds / 3600).takeIf { it > 0 }?.let { "${it}시간" },
        (seconds / 60 % 60).takeIf { it > 0 }?.let { "${it}분" },
        (seconds % 60).takeIf { it > 0 }?.let { "${it}초" }).joinToString(" ").ifEmpty { "0초" }
}

enum class AnnouncementKind(val priority: Int, val lifetimeMs: Long?) {
    WORKOUT_START(4, null), STATE(3, null), NAVIGATION(2, 15_000), STEP(1, null), GOAL(1, null), KILOMETER(1, null),
    PACE_WARNING(0, 15_000), DISTANCE_PACE(0, 15_000), CURRENT_PACE(0, 15_000), TARGET_PACE(0, 15_000)
}

/** Timestamp uses the same monotonic clock as RunEngine, independent of wall time. */
sealed interface Announcement {
    val kind: AnnouncementKind
    val occurredAtMs: Long
    val priority get() = kind.priority

    data class Pace(override val kind: AnnouncementKind, val secondsPerKilometer: Double?,
                    override val occurredAtMs: Long) : Announcement {
        init { require(kind == AnnouncementKind.CURRENT_PACE || kind == AnnouncementKind.TARGET_PACE) }
    }
    data class Kilometer(val kilometers: Int, val averageSecondsPerKilometer: Double?,
                         override val occurredAtMs: Long,
                         val currentSecondsPerKilometer: Double? = null) : Announcement { override val kind = AnnouncementKind.KILOMETER }
    data class DistancePace(val meters: Double, val currentSecondsPerKilometer: Double,
                            override val occurredAtMs: Long) : Announcement { override val kind = AnnouncementKind.DISTANCE_PACE }
    data class Step(val name: String, val targetSecondsPerKilometer: Double,
                    override val occurredAtMs: Long) : Announcement { override val kind = AnnouncementKind.STEP }
    data class Message(override val kind: AnnouncementKind, val text: String,
                       override val occurredAtMs: Long) : Announcement
}

fun Announcement.speechText(): String = when (this) {
    is Announcement.Pace -> "${if (kind == AnnouncementKind.CURRENT_PACE) "현재" else "목표"} 페이스 ${paceSpeechText(secondsPerKilometer)}"
    is Announcement.Kilometer -> "${kilometers}킬로미터, 평균 페이스 ${paceSpeechText(averageSecondsPerKilometer)}" +
        (currentSecondsPerKilometer?.let { ", 현재 페이스 ${paceSpeechText(it)}" } ?: "")
    is Announcement.DistancePace -> "현재 페이스 ${paceSpeechText(currentSecondsPerKilometer)}"
    is Announcement.Step -> "$name, 목표 페이스 ${paceSpeechText(targetSecondsPerKilometer)}"
    is Announcement.Message -> text
}

/** Select without consuming: failed focus/TTS requests can retry on the next service tick. */
class AnnouncementQueue(private val pending: ArrayDeque<Announcement>) {
    fun next(now: Long, status: RunStatus, playbackBusy: Boolean = false): Announcement? {
        pending.removeAll { event ->
            val expired = event.kind.lifetimeMs?.let { now - event.occurredAtMs > it } == true
            expired || (status != RunStatus.RUNNING && (event.priority == 0 || event.kind == AnnouncementKind.NAVIGATION))
        }
        // Only the latest sampled pace/warning and state remain relevant after waiting.
        val latest = pending.filter { it.priority == 0 || it.kind == AnnouncementKind.STATE || it.kind == AnnouncementKind.STEP }.groupBy { it.kind }.mapValues { it.value.last() }
        pending.removeAll { it.kind in latest && latest[it.kind] !== it }
        // 우선순위는 다음 발화를 고를 때만 적용한다. 현재 문장은 끝까지 재생한다.
        if (playbackBusy) return null
        return pending.maxByOrNull { it.priority }
    }
    fun acknowledge(event: Announcement) { pending.remove(event) }
}
