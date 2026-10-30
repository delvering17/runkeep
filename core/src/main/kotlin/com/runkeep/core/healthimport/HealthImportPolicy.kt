package com.runkeep.core.healthimport

import java.time.Instant

const val SAMSUNG_HEALTH_ORIGIN = "com.sec.android.app.shealth"

data class HealthWindow(val start: Instant, val end: Instant) {
    init { require(start < end) }
    fun contains(time: Instant) = time >= start && time < end
    fun overlaps(other: HealthWindow) = start < other.end && other.start < end
    fun intersection(other: HealthWindow): HealthWindow? {
        val from = maxOf(start, other.start)
        val until = minOf(end, other.end)
        return if (from < until) HealthWindow(from, until) else null
    }
}

data class HealthSession(val id: String, val origin: String, val window: HealthWindow, val title: String? = null)
data class HealthReading(val time: Instant, val bpm: Long)
data class HealthSeries(val id: String, val origin: String, val start: Instant, val end: Instant, val samples: List<HealthReading>) {
    init { require(start <= end) }
    fun overlaps(window: HealthWindow) = start < window.end && end >= window.start
    fun contains(time: Instant) = time >= start && time <= end
}
data class ImportedHeart(val sourceId: String, val epochMs: Long, val bpm: Long)
data class HealthPage<T>(val records: List<T>, val nextToken: String?)
enum class HealthAvailability { AVAILABLE, UPDATE_REQUIRED, UNSUPPORTED }
data class HealthReadAccess(val heart: Boolean, val exercise: Boolean) {
    val permitted get() = heart && exercise
}
enum class HealthStage { AVAILABILITY, PERMISSION, TIME_RANGE, SESSION_READ, HEART_READ, SOURCE_COMPARE }
enum class HealthFailure { UPDATE_REQUIRED, UNSUPPORTED, PERMISSION_REQUIRED, ACCESS_RESTRICTED, INVALID_TIME, NO_OVERLAP, WRONG_SOURCE, PAGE_CYCLE, READ_FAILED, TIMEOUT }
class HealthReadException(val reason: HealthFailure) : Exception(reason.name)
sealed interface HealthResult<out T> {
    data class Success<T>(val value: T) : HealthResult<T>
    data class Failure(val reason: HealthFailure, val stage: HealthStage) : HealthResult<Nothing>
}

class HealthPageTokens {
    private val seen = mutableSetOf<String>()
    fun next(token: String?): String? {
        if (token.isNullOrEmpty()) return null
        if (!seen.add(token)) throw HealthReadException(HealthFailure.PAGE_CYCLE)
        return token
    }
}

data class HealthSourceCounts(
    val records: Int = 0,
    val overlappingRecords: Int = 0,
    val samplesInRun: Int = 0,
    val samplesInSelection: Int = 0,
    val rejectedSamples: Int = 0,
)
data class HealthDiagnostics(
    val pages: Int,
    val samsung: HealthSourceCounts,
    val other: HealthSourceCounts = HealthSourceCounts(),
    val duplicateSamples: Int = 0,
    val duplicateRecords: Int = 0,
)
enum class HealthDiagnosticReason { SELECTED_SAMSUNG_SAMPLES, SAMSUNG_OUTSIDE_SELECTION, OTHER_SOURCE_ONLY, SAMSUNG_INTERVAL_WITHOUT_SAMPLES, NO_SAMSUNG_RECORDS }
fun HealthDiagnostics.reason(): HealthDiagnosticReason = when {
    samsung.samplesInSelection > 0 -> HealthDiagnosticReason.SELECTED_SAMSUNG_SAMPLES
    samsung.samplesInRun > 0 -> HealthDiagnosticReason.SAMSUNG_OUTSIDE_SELECTION
    other.samplesInSelection > 0 -> HealthDiagnosticReason.OTHER_SOURCE_ONLY
    samsung.records > 0 -> HealthDiagnosticReason.SAMSUNG_INTERVAL_WITHOUT_SAMPLES
    else -> HealthDiagnosticReason.NO_SAMSUNG_RECORDS
}
data class HealthCandidates(val sessions: List<HealthSession>, val pages: Int)
data class HealthImportData(val samples: List<ImportedHeart>, val diagnostics: HealthDiagnostics)

interface HealthImportSource {
    fun availability(): HealthAvailability
    suspend fun access(): HealthReadAccess
    suspend fun sessions(window: HealthWindow, token: String?): HealthPage<HealthSession>
    suspend fun hearts(window: HealthWindow, samsungOnly: Boolean, token: String?): HealthPage<HealthSeries>
}

class HealthImportReader(private val source: HealthImportSource) {
    private class ReadScope { var stage = HealthStage.AVAILABILITY }

    private suspend fun ReadScope.checkAccess(): HealthResult.Failure? {
        stage = HealthStage.AVAILABILITY
        when (source.availability()) {
            HealthAvailability.UNSUPPORTED -> return HealthResult.Failure(HealthFailure.UNSUPPORTED, stage)
            HealthAvailability.UPDATE_REQUIRED -> return HealthResult.Failure(HealthFailure.UPDATE_REQUIRED, stage)
            HealthAvailability.AVAILABLE -> Unit
        }
        stage = HealthStage.PERMISSION
        return if (source.access().permitted) null else HealthResult.Failure(HealthFailure.PERMISSION_REQUIRED, stage)
    }

    private suspend fun <T> read(operation: suspend ReadScope.() -> HealthResult<T>): HealthResult<T> {
        val scope = ReadScope()
        return try {
            scope.operation()
        } catch (failure: HealthReadException) {
            HealthResult.Failure(failure.reason, scope.stage)
        }
    }

    suspend fun candidates(window: HealthWindow): HealthResult<HealthCandidates> = read {
        checkAccess()?.let { return@read it }
        stage = HealthStage.SESSION_READ
        val sessions = linkedMapOf<String, HealthSession>()
        val tokens = HealthPageTokens()
        var token: String? = null
        var pages = 0
        do {
            val page = source.sessions(window, token)
            pages++
            page.records.filter { it.origin == SAMSUNG_HEALTH_ORIGIN && it.window.overlaps(window) && it.id.isNotEmpty() }
                .forEach { sessions[it.id] = it }
            token = tokens.next(page.nextToken)
        } while (token != null)
        HealthResult.Success(HealthCandidates(sessions.values.sortedWith(compareBy({ it.window.start }, { it.id })), pages))
    }

    suspend fun samples(run: HealthWindow, session: HealthSession?): HealthResult<HealthImportData> = read {
        checkAccess()?.let { return@read it }
        stage = HealthStage.TIME_RANGE
        if (session != null && session.origin != SAMSUNG_HEALTH_ORIGIN) {
            return@read HealthResult.Failure(HealthFailure.WRONG_SOURCE, stage)
        }
        val selected = if (session == null) run else run.intersection(session.window)
            ?: return@read HealthResult.Failure(HealthFailure.NO_OVERLAP, stage)
        stage = HealthStage.HEART_READ
        val scan = scan(selected, selected, samsungOnly = true)
        HealthResult.Success(HealthImportData(scan.samples, scan.diagnostics))
    }

    suspend fun diagnose(run: HealthWindow, session: HealthSession?): HealthResult<HealthDiagnostics> = read {
        checkAccess()?.let { return@read it }
        stage = HealthStage.TIME_RANGE
        if (session != null && session.origin != SAMSUNG_HEALTH_ORIGIN) {
            return@read HealthResult.Failure(HealthFailure.WRONG_SOURCE, stage)
        }
        val selected = if (session == null) run else run.intersection(session.window)
            ?: return@read HealthResult.Failure(HealthFailure.NO_OVERLAP, stage)
        stage = HealthStage.SOURCE_COMPARE
        HealthResult.Success(scan(run, selected, samsungOnly = false).diagnostics)
    }

    private data class Scan(val samples: List<ImportedHeart>, val diagnostics: HealthDiagnostics)

    private suspend fun scan(query: HealthWindow, selected: HealthWindow, samsungOnly: Boolean): Scan {
        val tokens = HealthPageTokens()
        val records = mutableSetOf<Pair<String, String>>()
        val sampleKeys = mutableSetOf<Triple<String, String, Long>>()
        val imported = linkedMapOf<Pair<String, Long>, ImportedHeart>()
        var samsung = HealthSourceCounts()
        var other = HealthSourceCounts()
        var duplicates = 0
        var duplicateRecords = 0
        var pages = 0
        var token: String? = null
        do {
            val page = source.hearts(query, samsungOnly, token)
            pages++
            for (record in page.records) {
                if (record.id.isEmpty()) continue
                val isSamsung = record.origin == SAMSUNG_HEALTH_ORIGIN
                if (samsungOnly && !isSamsung) continue
                if (!records.add(record.origin to record.id)) {
                    duplicateRecords++
                    continue
                }
                var counts = if (isSamsung) samsung else other
                counts = counts.copy(records = counts.records + 1,
                    overlappingRecords = counts.overlappingRecords + if (record.overlaps(selected)) 1 else 0)
                for (sample in record.samples) {
                    if (!record.contains(sample.time) || sample.bpm !in 1..300) {
                        counts = counts.copy(rejectedSamples = counts.rejectedSamples + 1)
                        continue
                    }
                    if (!query.contains(sample.time)) continue
                    val at = sample.time.toEpochMilli()
                    if (!sampleKeys.add(Triple(record.origin, record.id, at))) {
                        duplicates++
                        continue
                    }
                    counts = counts.copy(samplesInRun = counts.samplesInRun + 1)
                    if (selected.contains(sample.time)) {
                        counts = counts.copy(samplesInSelection = counts.samplesInSelection + 1)
                        if (isSamsung && samsungOnly) imported[record.id to at] = ImportedHeart(record.id, at, sample.bpm)
                    }
                }
                if (isSamsung) samsung = counts else other = counts
            }
            token = tokens.next(page.nextToken)
        } while (token != null)
        return Scan(imported.values.sortedWith(compareBy({ it.epochMs }, { it.sourceId })),
            HealthDiagnostics(pages, samsung, other, duplicates, duplicateRecords))
    }
}
