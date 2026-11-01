package com.runkeep.app.diagnostics

import android.os.Build
import android.os.SystemClock
import com.runkeep.app.BuildConfig
import com.runkeep.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID

class DiagnosticRecorder(root: File, private val scope: CoroutineScope,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val epoch: () -> Long = System::currentTimeMillis,
    private val environmentFor: (RunSummary, Preferences) -> DiagnosticEnvironment = { summary, prefs ->
        DiagnosticEnvironment(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, BuildConfig.BUILD_TYPE,
            BuildConfig.FLAVOR, Build.VERSION.SDK_INT, DiagnosticSettings(prefs.autoPause, prefs.turnLeadMeters,
                summary.coursePaceEnabled, summary.course != null, summary.plan != null))
    }, queueCapacity: Int = 256,
    private val store: DiagnosticJournalStore = DiagnosticJournalStore(root)) {
    private val lock = Mutex()
    private sealed interface Pending {
        data class Write(val runId: String, val event: DiagnosticEvent, val existingOnly: Boolean = false) : Pending
        data class Flush(val done: CompletableDeferred<Unit>) : Pending
    }
    private val queue = Channel<Pending>(queueCapacity)
    private val losses = java.util.concurrent.ConcurrentHashMap<String, DiagnosticLoss>()
    private val loadedLosses = mutableSetOf<String>()
    private val deleted = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val attemptedCollections = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var runId: String? = null
    private var session = ""
    private var baseMs = 0L
    private var offsetMs = 0L
    private var startedEpochMs = 0L
    private var sequence = 0L
    private var signal = DiagnosticSignalMonitor(0)
    private var lastEnvironment: DiagnosticEnvironment? = null
    private var openLiveMap: String? = null
    @Volatile private var collecting = false
    val capture = MutableStateFlow<String?>(null)
    val status = MutableStateFlow("진단 꺼짐")
    init {
        scope.launch(Dispatchers.IO) {
            for (pending in queue) {
                if (pending is Pending.Flush) { pending.done.complete(Unit); continue }
                val write = pending as Pending.Write
                lock.withLock {
                if (write.runId in deleted || write.existingOnly && !store.exists(write.runId)) return@withLock
                try {
                    if (loadedLosses.add(write.runId)) {
                        val saved = store.loss(write.runId)
                        losses.compute(write.runId) { _, current ->
                            val pending = current ?: DiagnosticLoss()
                            mergeLoss(saved, pending).copy(queueDropped = saved.queueDropped + pending.queueDropped)
                        }
                    }
                    if (!store.append(write.runId, write.event)) changeLoss(write.runId) { it.copy(capacityReached = true) }
                    losses[write.runId]?.takeIf { it.incomplete }?.let { store.saveLoss(write.runId, it) }
                } catch (_: Exception) {
                    changeLoss(write.runId) { it.copy(storageFailed = true) }
                    runCatching { store.saveLoss(write.runId, losses.getValue(write.runId)) }
                }
                }
            }
        }
    }
    fun waiting(enabled: Boolean) {
        if (!collecting) status.value = if (enabled) "진단 켜짐 · 운동 시작 대기" else "진단 꺼짐"
    }
    fun configure(summary: RunSummary, prefs: Preferences, enginePrefs: Preferences, recovery: Boolean = false) {
        val environment = environmentFor(summary, enginePrefs)
        if (!prefs.diagnosticRecording) {
            if (collecting) stop(DiagnosticReason.DISABLED, summary)
            waiting(false)
            return
        }
        if (collecting && runId == summary.id) {
            if (lastEnvironment != environment) { lastEnvironment = environment; record(DiagnosticEvent(kind = DiagnosticKind.SETTINGS, environment = environment)) }
            return
        }
        if (collecting) stop(DiagnosticReason.INTERRUPTED)
        attemptedCollections.add(summary.id)
        collecting = true; runId = summary.id; baseMs = clock()
        offsetMs = (epoch() - summary.startedAt).coerceAtLeast(0)
        startedEpochMs = summary.startedAt; sequence = 0; session = UUID.randomUUID().toString(); capture.value = session
        signal = DiagnosticSignalMonitor(baseMs); lastEnvironment = environment
        status.value = if (losses[summary.id]?.capacityReached == true) "진단 저장 중단 · 용량 한도 · 일부 유실" else "진단 수집 중"
        record(DiagnosticEvent(kind = DiagnosticKind.COLLECTION_START, reason = DiagnosticReason.ENABLED,
            environment = environment, status = summary.status, activeMs = summary.activeMs, meters = summary.meters))
        if (recovery) record(DiagnosticEvent(kind = DiagnosticKind.RECOVERY, reason = DiagnosticReason.SERVICE_RESTART))
        record(DiagnosticEvent(kind = DiagnosticKind.MAP, reason = if (openLiveMap == summary.id) DiagnosticReason.MAP_OPENED else DiagnosticReason.MAP_NOT_OPENED, mapSurface = "live"))
    }
    fun record(event: DiagnosticEvent, at: Long = clock()) {
        val id = runId ?: return
        if (!collecting || id in deleted || losses[id]?.capacityReached == true) return
        val relative = at - baseMs
        val timed = event.copy(sequence = ++sequence, session = session, atMs = relative, runOffsetMs = offsetMs + relative)
        if (!queue.trySend(Pending.Write(id, timed)).isSuccess) changeLoss(id) { it.copy(queueDropped = it.queueDropped + 1) }
    }
    fun received(fix: android.location.Location, at: Long, reason: DiagnosticReason) {
        if (!collecting) return
        if (reason == DiagnosticReason.SOURCE_INACTIVE) return
        signal.receipt(at)?.let { record(DiagnosticEvent(kind = DiagnosticKind.GPS_RETURN, reason = it), at) }
        if (reason == DiagnosticReason.NO_ACCURACY) quality(false, reason)
        record(sample(fix, at).copy(kind = DiagnosticKind.LOCATION_RECEIVED, reason = reason, stage = "controller"), at)
    }
    fun sample(fix: android.location.Location, receivedAt: Long): DiagnosticEvent = DiagnosticEvent(
        kind = DiagnosticKind.LOCATION_DECISION,
        measurementEpochOffsetMs = fix.time.takeIf { it > 0 }?.minus(startedEpochMs),
        measuredAtMs = fix.elapsedRealtimeNanos / 1_000_000 - baseMs, receivedAtMs = receivedAt - baseMs,
        delayMs = receivedAt - fix.elapsedRealtimeNanos / 1_000_000,
        processingDelayMs = clock() - receivedAt,
        accuracyMeters = fix.accuracy.takeIf { fix.hasAccuracy() && it.isFinite() },
        speedMps = fix.speed.takeIf { fix.hasSpeed() && it.isFinite() },
        speedAccuracyMps = fix.speedAccuracyMetersPerSecond.takeIf { fix.hasSpeedAccuracy() && it.isFinite() },
        coordinate = runCatching { Coordinate(fix.latitude, fix.longitude) }.getOrNull())
    fun engine(event: EngineDiagnostic) {
        if (!collecting) return
        if (event.kind == DiagnosticKind.LOCATION_DECISION && event.reason in setOf(DiagnosticReason.ACCEPTED,
            DiagnosticReason.ACCURACY, DiagnosticReason.POSITION_JUMP, DiagnosticReason.STALE, DiagnosticReason.FUTURE_SAMPLE))
            quality(event.reason == DiagnosticReason.ACCEPTED, event.reason)
        val fix = event.fix
        record(DiagnosticEvent(kind = event.kind, reason = event.reason, stage = "engine", status = event.status,
            measuredAtMs = fix?.monotonicMs?.minus(baseMs), pointIndex = event.pointIndex, segment = event.segment, activeMs = event.activeMs, meters = event.meters), event.atMs)
    }
    fun quality(accepted: Boolean, reason: DiagnosticReason) {
        if (!collecting) return
        signal.quality(accepted, reason)?.let { transition ->
            record(DiagnosticEvent(kind = if (transition == DiagnosticReason.QUALITY_RECOVERED) DiagnosticKind.QUALITY_RETURN else DiagnosticKind.QUALITY_LOST, reason = transition))
        }
    }
    fun tick(summary: RunSummary, count: Int) {
        if (!collecting) return
        signal.tick(clock())?.let { record(DiagnosticEvent(kind = DiagnosticKind.GPS_GAP, reason = it)) }
        record(DiagnosticEvent(kind = DiagnosticKind.CHECKPOINT, status = summary.status, activeMs = summary.activeMs,
            meters = summary.meters, trackPoints = count, segment = summary.segment))
    }
    fun map(id: String, surface: String, result: MapDiagnostic) {
        if (surface == "live") openLiveMap = if (result.reason == DiagnosticReason.MAP_CLOSED) null else id
        if (id != runId) return
        record(DiagnosticEvent(kind = DiagnosticKind.MAP, reason = result.reason, mapSurface = surface,
            trackSegments = result.trackSegments, trackPoints = result.trackPoints, plannedPoints = result.plannedPoints, createdRoutes = result.createdRoutes, updatedRoutes = result.updatedRoutes,
            removedRoutes = result.removedRoutes))
    }
    fun stop(reason: DiagnosticReason, summary: RunSummary? = null) {
        if (!collecting) return
        record(DiagnosticEvent(kind = DiagnosticKind.COLLECTION_STOP, reason = reason, status = summary?.status,
            activeMs = summary?.activeMs, meters = summary?.meters))
        collecting = false; capture.value = null
        status.value = if (losses[runId]?.incomplete == true) "진단 종료 · 일부 유실" else "진단 수집 종료"
    }
    private fun changeLoss(id: String, transform: (DiagnosticLoss) -> DiagnosticLoss) {
        losses.compute(id) { _, value -> transform(value ?: DiagnosticLoss()) }
        if (id == runId) status.value = when {
            losses[id]?.capacityReached == true -> "진단 저장 중단 · 용량 한도 · 일부 유실"
            collecting -> "진단 저장 실패·지연 · 일부 유실 · 재시도 중"
            else -> "진단 종료 · 일부 유실"
        }
    }
    fun historyMap(id: String, runStartedAt: Long, result: MapDiagnostic) {
        val event = DiagnosticEvent(session = UUID.randomUUID().toString(), kind = DiagnosticKind.MAP,
            runOffsetMs = (epoch() - runStartedAt).coerceAtLeast(0), reason = result.reason,
            mapSurface = "history", trackSegments = result.trackSegments, trackPoints = result.trackPoints,
            plannedPoints = result.plannedPoints, createdRoutes = result.createdRoutes, updatedRoutes = result.updatedRoutes,
            removedRoutes = result.removedRoutes)
        if (!queue.trySend(Pending.Write(id, event, existingOnly = true)).isSuccess)
            changeLoss(id) { it.copy(queueDropped = it.queueDropped + 1) }
    }
    suspend fun snapshot(id: String): DiagnosticFile? = withContext(Dispatchers.IO) {
        val flushed = CompletableDeferred<Unit>()
        queue.send(Pending.Flush(flushed))
        flushed.await()
        lock.withLock {
            if (id in deleted) return@withLock null
            val data = store.read(id, session.takeIf { collecting && id == runId })
            val loss = mergeLoss(data?.loss ?: DiagnosticLoss(), losses[id] ?: DiagnosticLoss())
            if (data == null && (id !in attemptedCollections || !loss.incomplete)) null else (data ?: DiagnosticFile()).copy(loss = loss)
        }
    }
    suspend fun maintain(ids: Set<String>) = withContext(Dispatchers.IO) { lock.withLock { store.maintain(ids, runId.takeIf { collecting }) } }
    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        deleted.add(id)
        lock.withLock { store.delete(id); losses.remove(id); loadedLosses.remove(id) }
    }
    private fun mergeLoss(a: DiagnosticLoss, b: DiagnosticLoss) = DiagnosticLoss(maxOf(a.queueDropped, b.queueDropped),
        a.storageFailed || b.storageFailed, a.capacityReached || b.capacityReached, a.truncated || b.truncated, a.uncleanSession || b.uncleanSession)
}
