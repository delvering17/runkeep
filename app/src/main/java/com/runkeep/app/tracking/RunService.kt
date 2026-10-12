package com.runkeep.app.tracking

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.hardware.*
import android.location.*
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.runkeep.app.*
import com.runkeep.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.encodeToString
import java.io.Closeable
import java.io.File
import com.runkeep.app.data.PrivatePayloadStore

class RunService : Service(), SensorEventListener {
    private val app get() = application as RunkeepApp
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val events = Channel<Event>(Channel.UNLIMITED)
    private var engine: RunEngine? = null
    private var audioFocus: RunAudioFocus? = null
    private var speech: SpeechPlayer? = null
    private var alarmPlayer: PaceAlarmPlayer? = null
    private var activePaceWarning: PaceFeedbackState? = null
    private val audioSession = WorkoutAudioSession()
    private var previousAlarmEnabled = false
    private var previousMetronomeEnabled = false
    private var locationLease: Closeable? = null
    private var lastLocationNanos = -1L
    private var recordAfterNanos = 0L
    private var lastCheckpoint = 0L
    private var cadence: Double? = null
    private val steps = ArrayDeque<Long>()
    private var registered = false
    private val startPolicy = RunStartPolicy()
    private val finishPolicy = FinishSpeechPolicy()
    private var preparationJob: Job? = null
    private var locationJob: Job? = null
    private var finishText: String? = null
    private var latestStartId = 0
    private var processedStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastWakeRenewed = 0L
    private val sensors by lazy { getSystemService(SensorManager::class.java) }
    sealed interface Event {
        data class Command(val action: String?, val payload: String?, val startId: Int, val runId: String?) : Event
        data class Position(val state: PositionState) : Event
        data class Settings(val value: Preferences) : Event
        data class SpeechStarted(val source: RunEngine, val request: WorkoutSpeechRequest, val atMs: Long) : Event
        data class PreparationTick(val requestId: String) : Event
        data class SpeechFinished(val ticket: FinishSpeechTicket) : Event
        data object Tick : Event
    }
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL, "진행 중인 달리기", NotificationManager.IMPORTANCE_LOW))
        startForeground(7, notification("기록을 준비하고 있습니다"))
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Runkeep:Workout").apply {
            setReferenceCounted(false); acquire(10 * 60_000L)
        }
        lastWakeRenewed = SystemClock.elapsedRealtime()
        previousAlarmEnabled = app.settings.value.paceAlarm
        previousMetronomeEnabled = app.settings.value.metronome
        scope.launch { app.settings.collect { events.send(Event.Settings(it)) } }
        scope.launch {
            for (event in events) {
                try {
                    when (event) {
                        is Event.Command -> command(event)
                        is Event.Position -> {
                            val fix = event.state.fix
                            if (event.state.phase == PositionPhase.RECEIVING && fix != null && fix.elapsedRealtimeNanos > lastLocationNanos) {
                                lastLocationNanos = fix.elapsedRealtimeNanos; process(fix)
                            }
                            if (event.state.phase != PositionPhase.RECEIVING) engine?.invalidatePace(SystemClock.elapsedRealtime())
                            publish(); syncAudio(); speak()
                        }
                        is Event.Settings -> {
                            engine?.updateFeedbackPreferences(event.value, SystemClock.elapsedRealtime())
                            if (event.value.paceAlarm && !previousAlarmEnabled || event.value.metronome && !previousMetronomeEnabled)
                                alarmPlayer?.allowResume()
                            previousAlarmEnabled = event.value.paceAlarm
                            previousMetronomeEnabled = event.value.metronome
                            syncAudio(); speak(); drainFinish()
                        }
                        is Event.SpeechStarted -> {
                            if (engine === event.source && activePaceWarning != null && audioSession.accepts(event.request))
                                event.source.paceWarningSpoken(event.atMs)
                        }
                        is Event.PreparationTick -> advancePreparation(event.requestId)
                        is Event.SpeechFinished -> if (finishPolicy.complete(event.ticket)) {
                            finishText = null; closeAudioSession(); stopWhenIdle()
                        }
                        Event.Tick -> tick()
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    app.error.value = "기록 처리 오류: ${e.message ?: "권한과 저장 공간을 확인해주세요"}"
                    cancelPreparation()
                    finishPolicy.cancel(); finishText = null
                    speech?.stop(); activePaceWarning = null; audioSession.completed()
                    engine?.interrupt()
                    syncAudio(); publish(); stopWhenIdle()
                }
            }
        }
        scope.launch { while (isActive) { delay(1000); events.send(Event.Tick) } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        events.trySend(Event.Command(intent?.action, intent?.getStringExtra("payload"), startId, intent?.getStringExtra("runId")))
        return START_STICKY
    }
    private suspend fun command(event: Event.Command) {
        processedStartId = event.startId
        val startPayload = if (event.action == START) event.payload?.let {
            PrivatePayloadStore(File(cacheDir, "course-payloads")).read(it, consume = true)
        } else null
        val now = SystemClock.elapsedRealtime()
        if (event.action == CANCEL_START) {
            if (!workoutCommandMatches(event.runId, startPolicy.preparation(now)?.requestId)) { stopWhenIdle(); return }
            cancelPreparation(); stopWhenIdle(); return
        }
        if (event.action == FINISH && startPolicy.preparation(now) != null) {
            if (!workoutCommandMatches(event.runId, startPolicy.preparation(now)?.requestId)) { stopWhenIdle(); return }
            cancelPreparation(); stopWhenIdle(); return
        }
        if (event.action == START && engine == null) {
            if (startPolicy.preparation(now) != null) return
            val previous = app.db.dao().unfinished()
            if (previous == null) {
                val request = codec.decodeFromString<RunSummary>(requireNotNull(startPayload) { "출발 설정 사본을 다시 선택해주세요" })
                if (app.db.dao().run(request.id) != null) { stopWhenIdle(); return }
                check(RunStartPolicy.validRequest(request)) { "출발 설정을 확인해주세요" }
                checkStartPermission()
                finishPolicy.cancel(); finishText = null; closeAudioSession()
                if (startPolicy.prepare(request, SystemClock.elapsedRealtime(), app.settings.value.startDelaySeconds, canStart = true))
                    advancePreparation(request.id)
                else stopWhenIdle()
                return
            } else { finishPolicy.cancel(); finishText = null; recover(previous.payload); register() }
        } else if (engine == null) {
            if (startPolicy.preparation(now) != null || finishPolicy.ticket != null) return
            app.db.dao().unfinished()?.let { recover(it.payload); register() }
        }
        val e = engine ?: run { stopWhenIdle(); return }
        if (!workoutCommandMatches(event.runId, e.summary.id)) { stopWhenIdle(); return }
        e.tick(now, System.currentTimeMillis())
        when (event.action) {
            PAUSE -> {
                if (e.summary.status == RunStatus.RUNNING) {
                    speech?.stop(); activePaceWarning = null; audioSession.completed()
                    e.pause(); e.announcements += Announcement.Message(AnnouncementKind.STATE, "일시정지합니다", now)
                }
            }
            RESUME -> if (e.summary.status != RunStatus.RUNNING && e.summary.status != RunStatus.FINISHED) {
                alarmPlayer?.allowResume(); recordAfterNanos = now * 1_000_000
                steps.clear(); cadence = null
                e.resume(now); e.announcements += Announcement.Message(AnnouncementKind.STATE, "달리기를 재개합니다", now)
                app.location.restart()
            }
            FINISH -> {
                alarmPlayer?.stop(); speech?.stop(); activePaceWarning = null; audioSession.completed()
                e.announcements.clear()
                e.finish(System.currentTimeMillis())
                app.db.dao().checkpoint(e.summary)
                releaseTracking()
                finishText = finishSpeechText(e.summary, e.points)
                finishPolicy.begin(e.summary.id, SystemClock.elapsedRealtime())
                audioSession.show(requireNotNull(finishText))
                publish(); engine = null
                drainFinish(); return
            }
        }
        syncAudio()
        app.db.dao().checkpoint(e.summary); publish(); speak()
    }
    private fun checkStartPermission() {
        check(app.location.permitted) { "정확한 위치 권한이 필요합니다" }
        check(app.location.enabled || app.location.simulation.enabled) { "휴대폰 위치 기능을 켜주세요" }
    }
    private suspend fun advancePreparation(requestId: String) {
        val now = SystemClock.elapsedRealtime()
        val preparing = startPolicy.preparation(now) ?: return
        if (preparing.requestId != requestId) return
        checkStartPermission()
        if (preparing.remainingSeconds > 0) {
            app.live.value = app.live.value.copy(preparation = preparing)
            getSystemService(NotificationManager::class.java).notify(7, notification("출발까지 ${preparing.remainingSeconds}초"))
            preparationJob?.cancel()
            preparationJob = scope.launch {
                delay(startPolicy.untilNextUpdate(SystemClock.elapsedRealtime()).coerceAtLeast(1))
                events.send(Event.PreparationTick(requestId))
            }
            return
        }
        val previous = app.db.dao().unfinished()
        if (previous != null) {
            cancelPreparation(); recover(previous.payload); register()
            publish(); return
        }
        val startedAtMs = SystemClock.elapsedRealtime()
        val summary = startPolicy.takeReady(requestId, startedAtMs, System.currentTimeMillis(), canStart = true) ?: return
        preparationJob?.cancel(); preparationJob = null
        openAudioSession(); alarmPlayer?.allowResume()
        audioSession.attach(summary.id, app.live.value, newWorkout = true)
        steps.clear(); cadence = null
        recordAfterNanos = SystemClock.elapsedRealtimeNanos()
        engine = RunEngine(summary, app.settings.value).also {
            it.tick(startedAtMs, summary.startedAt)
            it.announcements += workoutStartAnnouncement(startedAtMs)
        }
        register()
        app.db.dao().checkpoint(requireNotNull(engine).summary)
        getSystemService(NotificationManager::class.java).notify(7, notification("달리기 기록 중"))
        publish(); syncAudio(); speak()
    }
    private fun cancelPreparation() {
        startPolicy.cancel(); preparationJob?.cancel(); preparationJob = null
        app.live.value = app.live.value.copy(preparation = null)
    }
    private fun releaseTracking() {
        locationJob?.cancel(); locationJob = null
        locationLease?.close(); locationLease = null
        sensors.unregisterListener(this); registered = false
        lastLocationNanos = -1L; steps.clear(); cadence = null
    }
    private fun closeAudioSession() {
        activePaceWarning = null; audioSession.completed()
        speech?.close(); speech = null
        alarmPlayer?.close(); alarmPlayer = null
        audioFocus?.close(); audioFocus = null
    }
    private fun stopWhenIdle() {
        if (engine != null || startPolicy.preparation(SystemClock.elapsedRealtime()) != null || finishPolicy.ticket != null ||
            latestStartId != processedStartId) return
        closeAudioSession(); releaseTracking()
        wakeLock?.let { if (it.isHeld) it.release() }
        stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(processedStartId)
    }
    private fun drainFinish() {
        val ticket = finishPolicy.ticket ?: return
        val player = speech
        val now = SystemClock.elapsedRealtime()
        if (!app.settings.value.voice || app.settings.value.voiceVolume <= 0 || player == null ||
            player.playback.value in listOf(SpeechPlayback.ERROR, SpeechPlayback.CLOSED) || finishPolicy.expired(now)) {
            finishPolicy.complete(ticket); finishText = null; closeAudioSession(); stopWhenIdle(); return
        }
        if (finishPolicy.requested) return
        val text = finishText ?: return
        if (player.speak(text, app.settings.value, onFinished = { events.trySend(Event.SpeechFinished(ticket)) })) {
            if (!finishPolicy.accepted(ticket, SystemClock.elapsedRealtime())) {
                finishPolicy.complete(ticket); finishText = null; closeAudioSession(); stopWhenIdle()
            }
        } else if (player.playback.value == SpeechPlayback.ERROR) {
            finishPolicy.complete(ticket); finishText = null; closeAudioSession(); stopWhenIdle()
        }
    }
    private suspend fun recover(payload: String) {
        val saved = codec.decodeFromString<RunSummary>(payload)
        openAudioSession()
        audioSession.attach(saved.id, app.live.value, newWorkout = false)
        val points = app.db.dao().points(saved.id).map { codec.decodeFromString<TrackPoint>(it.payload) }
        engine = RunEngine(saved.copy(status = RunStatus.INTERRUPTED), app.settings.value, points)
        app.error.value = "중단된 기록을 복구했습니다. 재개하거나 종료해주세요. 중단 시간은 운동 시간에 포함하지 않습니다."
    }
    private fun openAudioSession() {
        closeAudioSession()
        audioFocus = RunAudioFocus(this)
        speech = SpeechPlayer(this, audioFocus)
        alarmPlayer = PaceAlarmPlayer(this, audioFocus)
    }
    private fun register() {
        if (registered) return
        check(ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "정확한 위치 권한이 필요합니다" }
        locationLease = app.location.acquire()
        locationJob = scope.launch { app.location.state.collect { events.send(Event.Position(it)) } }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED) {
            sensors.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        registered = true
    }
    private suspend fun process(location: Location) {
        val e = engine ?: return
        val mono = location.elapsedRealtimeNanos / 1_000_000
        if (SystemClock.elapsedRealtime() - mono > 10_000 || location.elapsedRealtimeNanos < recordAfterNanos) return
        val point = e.ingest(LocationFix(Coordinate(location.latitude, location.longitude), location.time, mono,
            location.accuracy, if (location.hasAltitude()) location.altitude else null,
            if (location.hasSpeed()) location.speed else null), cadence, receivedAtMs = SystemClock.elapsedRealtime())
        syncAudio()
        app.db.dao().checkpoint(e.summary, point, e.points.lastIndex)
        publish(); syncAudio(); speak()
    }
    private suspend fun tick() {
        drainFinish()
        val e = engine ?: return
        val now = SystemClock.elapsedRealtime()
        e.tick(now, System.currentTimeMillis())
        syncAudio()
        while (steps.isNotEmpty() && now - steps.first() > 20_000) steps.removeFirst()
        cadence = if (steps.size >= 6 && now > steps.first()) (steps.size - 1) * 60_000.0 / (now - steps.first()) else null
        if (now - lastCheckpoint > 5000) {
            app.db.dao().checkpoint(e.summary); lastCheckpoint = now
            getSystemService(NotificationManager::class.java).notify(7, notification("%.2f km · %s".format(e.summary.meters / 1000, durationText(e.summary.activeMs))))
        }
        publish(); syncAudio(); speak()
    }
    private fun publish() {
        val e = engine ?: return
        val now = SystemClock.elapsedRealtime()
        wakeLock?.let { lock ->
            if (e.summary.status in listOf(RunStatus.RUNNING, RunStatus.AUTO_PAUSED) || finishPolicy.ticket != null) {
                if (!lock.isHeld || now - lastWakeRenewed >= 5 * 60_000) { lock.acquire(10 * 60_000L); lastWakeRenewed = now }
            } else if (lock.isHeld) lock.release()
        }
        val position = app.location.state.value
        val fix = position.recentFix()
        val message = if (position.phase == PositionPhase.RECEIVING && (position.ageMs() ?: Long.MAX_VALUE) <= 10_000 && e.gpsMessage.contains("위치 튐")) e.gpsMessage else position.message()
        app.live.value = LiveRun(e.summary, e.points.toList(), e.currentPace, cadence, message, audioSession.cue,
            fix?.let { Coordinate(it.latitude, it.longitude) }, fix?.accuracy)
    }
    private fun syncAudio() {
        val e = engine ?: run { alarmPlayer?.stop(); return }
        val prefs = app.settings.value
        val active = activePaceWarning
        if (active != null && (speech?.isSpeaking != true || !prefs.voice || !prefs.paceVoice ||
                e.summary.status != RunStatus.RUNNING)) {
            if (speech?.isSpeaking == true) speech?.stop()
            activePaceWarning = null; audioSession.completed(); e.paceWarningCompleted()
        }
        if (!prefs.voice && speech?.isSpeaking == true) speech?.stop()
        alarmPlayer?.update(e.feedback.alarm.takeIf { prefs.paceAlarm }, prefs.cadenceTargetSpm,
            prefs.paceAlarmVolume, e.paceValidUntil, metronome = prefs.metronome && e.summary.status == RunStatus.RUNNING)
    }
    private fun speak() {
        val e = engine ?: return
        val now = SystemClock.elapsedRealtime()
        e.updateFeedbackPreferences(app.settings.value, now)
        if (e.announcements.isEmpty()) return
        val player = speech ?: return
        val queue = AnnouncementQueue(e.announcements)
        val event = queue.next(now, e.summary.status, playbackBusy = player.isSpeaking) ?: return
        val text = event.speechText()
        if (text.isBlank()) { queue.acknowledge(event); return }
        audioSession.show(text)
        app.live.value = app.live.value.copy(cue = text)
        if (!app.settings.value.voice) { queue.acknowledge(event); return }
        val paceState = e.feedback.takeIf { event.kind == AnnouncementKind.PACE_WARNING }
        val request = audioSession.requested()
        if (player.speak(text, app.settings.value) {
                if (paceState != null) events.trySend(Event.SpeechStarted(e, request, SystemClock.elapsedRealtime()))
            }) {
            queue.acknowledge(event)
            if (paceState != null) { activePaceWarning = paceState; e.paceWarningRequested() }
        } else audioSession.completed()
    }
    override fun onSensorChanged(event: SensorEvent) {
        if (engine?.summary?.status != RunStatus.RUNNING || event.timestamp < recordAfterNanos) return
        val now = event.timestamp / 1_000_000
        steps += now
        while (steps.isNotEmpty() && now - steps.first() > 20_000) steps.removeFirst()
        cadence = if (steps.size >= 6 && now > steps.first()) (steps.size - 1) * 60_000.0 / (now - steps.first()) else null
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_run_notification).setContentTitle("런킵 · 달리기 기록 중")
            .setContentText(text).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).build()
    }
    override fun onDestroy() {
        cancelPreparation(); finishPolicy.cancel(); finishText = null
        releaseTracking(); closeAudioSession()
        wakeLock?.let { if (it.isHeld) it.release() }
        scope.cancel(); super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    companion object {
        const val CHANNEL = "runkeep-running"
        const val CANCEL_START = "runkeep.CANCEL_START"
        const val START = "runkeep.START"; const val PAUSE = "runkeep.PAUSE"; const val RESUME = "runkeep.RESUME"; const val FINISH = "runkeep.FINISH"
        fun forRun(action: String, runId: String) = "$action/$runId"
        fun send(context: Context, action: String, summary: RunSummary? = null) {
            val intent = Intent(context, RunService::class.java).setAction(action.substringBefore('/'))
            action.substringAfter('/', "").takeIf { it.isNotEmpty() }?.let { intent.putExtra("runId", it) }
            if (summary != null) {
                val payload = runCatching { PrivatePayloadStore(File(context.cacheDir, "course-payloads")).save(codec.encodeToString(summary)) }
                    .getOrElse { error ->
                        (context.applicationContext as RunkeepApp).error.value = "출발 설정 보관 실패: ${error.message}"
                        return
                    }
                intent.putExtra("payload", payload)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
