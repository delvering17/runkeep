package com.runkeep.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.util.UUID

val codec = Json { ignoreUnknownKeys = true; encodeDefaults = true }
fun newId(): String = UUID.randomUUID().toString()

@Serializable data class Coordinate(val lat: Double, val lon: Double) {
    init { require(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) }
}
@Serializable data class Course(val id: String = newId(), val name: String, val points: List<Coordinate>, val version: Int = 1,
    val pointIds: List<String> = points.indices.map { UUID.nameUUIDFromBytes("$id:point:$it".toByteArray(Charsets.UTF_8)).toString() },
    val paceTargets: List<CoursePaceTarget> = emptyList(), val paceTransition: CoursePaceTransition = CoursePaceTransition.WAYPOINT,
    val usePaceTargets: Boolean = paceTargets.isNotEmpty()) {
    init {
        require(pointIds.size == points.size && pointIds.all { it.isNotBlank() && it.length <= 128 } && pointIds.distinct().size == pointIds.size)
        val adjacent = pointIds.zipWithNext().toSet()
        require(paceTargets.all { it.fromPointId to it.toPointId in adjacent })
        require(paceTargets.map { it.fromPointId to it.toPointId }.distinct().size == paceTargets.size)
    }
    @Transient internal val paceByStart = paceTargets.associateBy { it.fromPointId }
    val meters get() = points.zipWithNext().sumOf { (a, b) -> Geo.distance(a, b) }
}
@Serializable enum class StepKind { DISTANCE, TIME }
@Serializable enum class PaceCoachingMode { IMMEDIATE, STABLE }
@Serializable enum class MusicFocusPolicy { DUCK, PAUSE }
@Serializable data class PlanStep(val name: String = "달리기", val kind: StepKind, val amount: Double, val paceSeconds: Int) {
    init { require(amount.isFinite() && amount > 0); require(paceSeconds in 120..1800) }
    val expectedMeters get() = if (kind == StepKind.DISTANCE) amount else amount / paceSeconds * 1000
    val expectedSeconds get() = if (kind == StepKind.TIME) amount else amount / 1000 * paceSeconds
}
@Serializable data class WorkoutPlan(val id: String = newId(), val name: String, val steps: List<PlanStep>) {
    val meters get() = steps.sumOf { it.expectedMeters }
    val seconds get() = steps.sumOf { it.expectedSeconds }
    val averagePace get() = if (meters > 0) seconds / meters * 1000 else null
}
@Serializable data class Preferences(
    val paceTolerance: Int = 10, val turnLeadMeters: Int = 20,
    val stopCoachAtEnd: Boolean = true, val autoPause: Boolean = true,
    val kilometerVoice: Boolean = true, val voice: Boolean = true,
    val weightKg: Double? = null, val darkTheme: Boolean = false,
    val voiceVolume: Float = 1f, val voiceSpeed: Float = 1f, val voicePitch: Float = 1f,
    val paceVoice: Boolean = true, val paceAlarm: Boolean = false,
    val paceRepeatSeconds: Int = PaceFeedbackPolicy.DEFAULT_REPEAT_SECONDS,
    val readPaceStatus: Boolean = true,
    val readCurrentPace: Boolean = false, val readTargetPace: Boolean = false,
    val cadenceTargetSpm: Int? = null, val paceAlarmVolume: Float = .5f,
    val metronome: Boolean = false,
    val distancePaceVoice: Boolean = false, val distancePaceMeters: Int = DistancePacePolicy.DEFAULT_INTERVAL_METERS,
    val startDelaySeconds: Int = 0,
    val startDelayPresetSeconds: Int = 5, val cadencePresetSpm: Int = PaceFeedbackPolicy.DEFAULT_CADENCE_SPM,
    val paceCoachingMode: PaceCoachingMode = PaceCoachingMode.IMMEDIATE,
    val periodicPaceVoice: Boolean = false, val periodicPaceSeconds: Int = 30,
    val musicFocusPolicy: MusicFocusPolicy = MusicFocusPolicy.DUCK
) {
    fun normalized(): Preferences = copy(
        paceTolerance = paceTolerance.coerceIn(5, 60),
        paceRepeatSeconds = paceRepeatSeconds.takeIf { it in PaceFeedbackPolicy.REPEAT_RANGE }
            ?: PaceFeedbackPolicy.DEFAULT_REPEAT_SECONDS,
        cadenceTargetSpm = cadenceTargetSpm?.takeIf { it in PaceFeedbackPolicy.CADENCE_RANGE },
        distancePaceMeters = DistancePacePolicy.normalizeInterval(distancePaceMeters),
        periodicPaceSeconds = periodicPaceSeconds.takeIf { it in 10..300 } ?: 30,
        startDelaySeconds = RunStartPolicy.normalizeDelay(startDelaySeconds),
        startDelayPresetSeconds = startDelayPresetSeconds.takeIf { it in 1..60 } ?: 5,
        cadencePresetSpm = cadencePresetSpm.takeIf { it in PaceFeedbackPolicy.CADENCE_RANGE } ?: PaceFeedbackPolicy.DEFAULT_CADENCE_SPM,
        paceAlarmVolume = paceAlarmVolume.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .5f
    )
}
@Serializable enum class RunStatus { RUNNING, PAUSED, AUTO_PAUSED, INTERRUPTED, FINISHED }
@Serializable data class TrackPoint(
    val coordinate: Coordinate, val epochMs: Long, val activeMs: Long,
    val meters: Double, val altitude: Double? = null, val accuracy: Float = 0f,
    val segment: Int = 0, val cadence: Double? = null
)
@Serializable data class HeartSample(val epochMs: Long, val bpm: Long, val sourceId: String)
@Serializable data class RunSummary(
    val id: String = newId(), val startedAt: Long, val endedAt: Long? = null,
    val activeMs: Long = 0, val meters: Double = 0.0,
    val status: RunStatus = RunStatus.RUNNING, val goalMeters: Double? = null,
    val goalActiveMs: Long? = null, val goalEpochMs: Long? = null,
    val course: Course? = null, val plan: WorkoutPlan? = null,
    val planIndex: Int = 0, val stepStartMeters: Double = 0.0, val stepStartActiveMs: Long = 0,
    val segment: Int = 0, val weightKg: Double? = null,
    val coursePaceEnabled: Boolean = false, val courseLegIndex: Int = -1,
    val courseProgressMeters: Double = 0.0, val coursePaceComplete: Boolean = false, val courseRouteMeters: Double = 0.0
) {
    val averagePace get() = if (meters > 0) activeMs / meters else null
    // Milliseconds / meters = seconds / kilometer.
    val targetPace get() = if (coursePaceEnabled) course?.takeUnless { coursePaceComplete }?.paceAt(courseLegIndex)
        else plan?.steps?.getOrNull(planIndex)?.paceSeconds
}
data class LocationFix(val coordinate: Coordinate, val epochMs: Long, val monotonicMs: Long,
                       val accuracy: Float, val altitude: Double? = null, val speed: Float? = null,
                       val speedAccuracy: Float? = null)
data class LiveRun(val summary: RunSummary? = null, val points: List<TrackPoint> = emptyList(),
                   val pace: Double? = null, val cadence: Double? = null, val gpsMessage: String = "GPS를 기다리는 중", val cue: String? = null,
                   val position: Coordinate? = null, val accuracy: Float? = null,
                   val preparation: RunPreparation? = null)
@Serializable data class CourseFile(val schemaVersion: Int = 2, val course: Course)
@Serializable data class RunFile(val schemaVersion: Int = 2, val run: RunSummary, val points: List<TrackPoint>, val hearts: List<HeartSample>)

fun paceText(seconds: Double?): String {
    if (seconds == null || !seconds.isFinite() || seconds <= 0 || seconds > 3600) return "—:—"
    val n = kotlin.math.round(seconds).toInt()
    return "%d:%02d".format(n / 60, n % 60)
}
fun durationText(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%02d:%02d".format(s / 60, s % 60)
}
