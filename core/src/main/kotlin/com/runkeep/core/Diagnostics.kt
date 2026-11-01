package com.runkeep.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable enum class DiagnosticKind { COLLECTION_START, COLLECTION_STOP, RECOVERY, INTERRUPTION, SETTINGS, LOCATION_RECEIVED, LOCATION_DECISION, GPS_GAP, GPS_RETURN, QUALITY_LOST, QUALITY_RETURN, SEGMENT, STATE, POSITION_SOURCE, MAP, CHECKPOINT }
@Serializable enum class DiagnosticReason {
    ACCEPTED, FORWARDED, BEFORE_RESUME, OUT_OF_ORDER, STALE, FUTURE_SAMPLE, INACTIVE, ACCURACY, POSITION_JUMP,
    AUTO_PAUSE, AUTO_PAUSE_WAIT, AUTO_RESUME, MANUAL_PAUSE, MANUAL_RESUME, FINISHED, INTERRUPTED,
    FIRST_FIX, GAP_OVER_15_SECONDS, NO_ACCURACY, SOURCE_INACTIVE, CONTROLLER_PASSED,
    ENABLED, DISABLED, SERVICE_RESTART, SERVICE_DESTROYED, NO_RECEIPT_10_SECONDS, RECEIPT_RETURNED,
    QUALITY_RECOVERED, PERMISSION, LOCATION_DISABLED, WAITING, SOURCE_ERROR, SOURCE_IDLE,
    MAP_NOT_OPENED, MAP_OPENED, MAP_CLOSED, SDK_NOT_READY, SDK_ERROR, SDK_DESTROYED,
    UPDATE_APPLIED, UPDATE_UNCHANGED, UPDATE_FAILED, PREVIEW_DRAWN
}
@Serializable data class DiagnosticSettings(val autoPause: Boolean, val turnLeadMeters: Int, val coursePaceEnabled: Boolean,
    val hasCourse: Boolean, val hasPlan: Boolean)
@Serializable data class DiagnosticEnvironment(val appVersion: String, val versionCode: Int, val buildType: String,
    val mapFlavor: String, val androidApi: Int, val settings: DiagnosticSettings)
@Serializable data class DiagnosticEvent(
    val sequence: Long = 0, val session: String = "", val atMs: Long = 0, val runOffsetMs: Long = 0,
    val kind: DiagnosticKind, val reason: DiagnosticReason? = null, val stage: String? = null,
    val measurementEpochOffsetMs: Long? = null, val measuredAtMs: Long? = null, val receivedAtMs: Long? = null, val delayMs: Long? = null,
    val processingDelayMs: Long? = null, val accuracyMeters: Float? = null, val speedMps: Float? = null,
    val speedAccuracyMps: Float? = null, val coordinate: Coordinate? = null,
    val pointIndex: Int? = null, val segment: Int? = null, val status: RunStatus? = null,
    val activeMs: Long? = null, val meters: Double? = null,
    val trackSegments: Int? = null, val trackPoints: Int? = null, val plannedPoints: Int? = null,
    val createdRoutes: Int? = null, val updatedRoutes: Int? = null, val removedRoutes: Int? = null,
    val mapSurface: String? = null, val environment: DiagnosticEnvironment? = null
)
@Serializable data class DiagnosticLoss(val queueDropped: Long = 0, val storageFailed: Boolean = false,
    val capacityReached: Boolean = false, val truncated: Boolean = false, val uncleanSession: Boolean = false) {
    val incomplete get() = queueDropped > 0 || storageFailed || capacityReached || truncated || uncleanSession
}
@Serializable data class DiagnosticFile(
    val format: String = "runkeep-diagnostic", val schemaVersion: Int = 1,
    val coordinatesIncluded: Boolean = false, val coordinateScope: String = "none",
    val coverage: String = "Observed collection sessions only; disabled and interrupted intervals are not reconstructed",
    val mapResultScope: String = "SDK calls applied or preview draw completed; physical display is not verified",
    val loss: DiagnosticLoss = DiagnosticLoss(), val events: List<DiagnosticEvent> = emptyList()
) {
    fun export(includeCoordinates: Boolean): String = diagnosticCodec.encodeToString(copy(
        coordinatesIncluded = includeCoordinates,
        coordinateScope = if (includeCoordinates) "received samples during collection only; no course or historical coordinates" else "none",
        events = events.map { if (includeCoordinates) it else it.copy(coordinate = null) }
    ))
}
val diagnosticCodec = Json { encodeDefaults = true; explicitNulls = false }

data class EngineDiagnostic(val kind: DiagnosticKind, val reason: DiagnosticReason, val atMs: Long,
    val fix: LocationFix? = null, val pointIndex: Int? = null, val segment: Int? = null, val status: RunStatus? = null, val activeMs: Long? = null, val meters: Double? = null)

data class MapDiagnostic(val reason: DiagnosticReason, val trackSegments: Int, val trackPoints: Int, val plannedPoints: Int,
    val createdRoutes: Int? = null, val updatedRoutes: Int? = null, val removedRoutes: Int? = null)

fun mapDiagnostic(reason: DiagnosticReason, planned: List<Coordinate>, tracks: List<List<Coordinate>>) =
    MapDiagnostic(reason, tracks.size, tracks.sumOf { it.size }, planned.size)

fun reportMapDiagnostic(callback: ((MapDiagnostic) -> Unit)?, result: MapDiagnostic) {
    runCatching { callback?.invoke(result) }
}

class DiagnosticSignalMonitor(private val beganAt: Long) {
    private var lastReceipt: Long? = null
    private var gap = false
    private var poor = false
    fun receipt(now: Long): DiagnosticReason? {
        lastReceipt = now
        return if (gap) { gap = false; DiagnosticReason.RECEIPT_RETURNED } else null
    }
    fun quality(accepted: Boolean, reason: DiagnosticReason = DiagnosticReason.ACCURACY): DiagnosticReason? {
        if (!accepted && !poor) { poor = true; return reason }
        if (accepted && poor) { poor = false; return DiagnosticReason.QUALITY_RECOVERED }
        return null
    }
    fun tick(now: Long): DiagnosticReason? {
        if (!gap && now - (lastReceipt ?: beganAt) > 10_000) { gap = true; return DiagnosticReason.NO_RECEIPT_10_SECONDS }
        return null
    }
}

fun serviceLocationExclusion(processedAtMs: Long, measuredNanos: Long, recordAfterNanos: Long): DiagnosticReason? = when {
    processedAtMs - measuredNanos / 1_000_000 > 10_000 -> DiagnosticReason.STALE
    measuredNanos < recordAfterNanos -> DiagnosticReason.BEFORE_RESUME
    else -> null
}
