package com.runkeep.core

import kotlin.math.*

class RunEngine(initial: RunSummary, val preferences: Preferences, savedPoints: List<TrackPoint> = emptyList()) {
    var summary = initial; private set
    val points = savedPoints.toMutableList()
    private var tickAt: Long? = null
    private var resumeAt: Long = Long.MIN_VALUE
    private var lastFix: LocationFix? = null
    private val autoPause = AutoPauseDetector()
    val autoPauseDiagnostics: AutoPauseDiagnostics get() = autoPause.diagnostics
    var lastAutoPauseTransition: AutoPauseDiagnostics? = null; private set
    private val paceFeedback = PaceFeedback()
    private var feedbackPreferences = preferences.normalized()
    private val distancePace = DistancePaceTracker(initial.meters).apply {
        configure(feedbackPreferences.voice && feedbackPreferences.distancePaceVoice,
            feedbackPreferences.distancePaceMeters, initial.meters)
    }
    private var feedbackAt = Long.MIN_VALUE
    val feedback get() = paceFeedback.state
    val paceValidUntil get() = lastFix?.monotonicMs?.plus(PaceFeedbackPolicy.GPS_MAX_AGE_MS) ?: 0L
    private var lastStepCue = summary.planIndex
    private var lastKm = (summary.meters / 1000).toInt()
    private val paceWindow = ArrayDeque<Pair<Long, Double>>()
    private val providerPace = ProviderPaceWindow()
    private val periodicPace = PeriodicPace()
    private var speechGeneration = 0L
    private var speechSequence = 0L
    private var activeSpeech: RunSpeechToken? = null
    private var speechStarted = false
    private var currentWasValid = false
    private var lastCurrentSpokenAt: Long? = null
    private var lastCurrentSpokenRounded: Int? = null
    /** Diagnostics only; recorded distance and statistics use the original GPS path. */
    var providerPaceUsed = false; private set
    private val navigator = initial.course?.let { CourseNavigator(it.points, preferences.turnLeadMeters).apply { restore(savedPoints) } }
    private val waypointTracker = initial.course?.takeIf { initial.coursePaceEnabled }?.let { course ->
        OrderedWaypointTracker(course).apply { savedPoints.forEach(::observe); disconnect() }
    }
    private val courseDistance = initial.course?.takeIf { initial.coursePaceEnabled }?.let { CourseDistanceTracker(it, initial.courseRouteMeters) }
    private val courseBoundaries = initial.course?.points?.zipWithNext()?.map { (a, b) -> Geo.distance(a, b) }?.runningFold(0.0, Double::plus) ?: emptyList()
    private var lastCourseCue = initial.courseLegIndex to initial.coursePaceComplete
    val announcements = ArrayDeque<Announcement>()
    var currentPace: Double? = null; private set
    var gpsMessage = "GPS를 기다리는 중"; private set

    init {
        if (initial.coursePaceEnabled && initial.course?.paceTransition == CoursePaceTransition.DISTANCE && initial.courseLegIndex < 0)
            summary = summary.copy(courseLegIndex = 0)
    }

    fun tick(monotonicMs: Long, epochMs: Long) = updateClock(monotonicMs, epochMs, evaluateAutoPause = true)

    private fun updateClock(monotonicMs: Long, epochMs: Long, evaluateAutoPause: Boolean) {
        val last = tickAt
        if (last != null && monotonicMs <= last) return
        tickAt = monotonicMs
        autoPause.expire(monotonicMs)
        if (summary.status != RunStatus.RUNNING || last == null) return
        val delta = (monotonicMs - last).coerceAtLeast(0)
        summary = summary.copy(activeMs = summary.activeMs + delta)
        advancePlan()
        announceStep(monotonicMs)
        if (lastFix?.let { monotonicMs - it.monotonicMs > 10_000 } != false) {
            currentPace = null; autoPause.reset("gps_expired"); clearPaceWindow(); gpsMessage = "GPS 신호를 확인해주세요"
        }
        if (evaluateAutoPause && preferences.autoPause && autoPause.shouldPause(monotonicMs)) {
            pause(true); announcements += Announcement.Message(AnnouncementKind.STATE, "자동 일시정지", monotonicMs)
        }
        coach(monotonicMs)
    }

    fun ingest(fix: LocationFix, cadence: Double? = null, receivedAtMs: Long = maxOf(fix.monotonicMs, tickAt ?: fix.monotonicMs)): TrackPoint? {
        if (fix.monotonicMs < resumeAt || lastFix?.let { fix.monotonicMs <= it.monotonicMs } == true) return null
        if (receivedAtMs - fix.monotonicMs !in 0..AutoPauseDetector.MAX_GAP_MS) {
            autoPause.reset("stale_fix"); lastFix = null
            currentPace = null; clearPaceWindow(); gpsMessage = "GPS 신호를 확인해주세요"
            coach(receivedAtMs)
            return null
        }
        updateClock(fix.monotonicMs, fix.epochMs, evaluateAutoPause = false)
        if (summary.status == RunStatus.PAUSED || summary.status == RunStatus.INTERRUPTED || summary.status == RunStatus.FINISHED) return null
        if (!fix.accuracy.isFinite() || fix.accuracy < 0 || fix.accuracy > 25) {
            gpsMessage = "GPS 정확도가 낮습니다"; currentPace = null; autoPause.reset("accuracy"); lastFix = null; clearPaceWindow(); coach(fix.monotonicMs); return null
        }
        val previous = lastFix
        val seconds = previous?.let { (fix.monotonicMs - it.monotonicMs) / 1000.0 } ?: 0.0
        val delta = previous?.let { Geo.distance(it.coordinate, fix.coordinate) } ?: 0.0
        if (previous != null && seconds < 15 && delta / seconds > 12) {
            gpsMessage = "GPS 위치 튐을 제외했습니다"; currentPace = null; providerPace.clear(); providerPaceUsed = false; autoPause.reset("position_jump"); coach(fix.monotonicMs); return null
        }
        if (summary.status == RunStatus.RUNNING && preferences.autoPause) {
            autoPause.observeRunning(fix, previous)
            if (autoPause.shouldPause(fix.monotonicMs)) {
                lastFix = fix
                pause(true); announcements += Announcement.Message(AnnouncementKind.STATE, "자동 일시정지", fix.monotonicMs)
                return null
            }
        }
        val moving = fix.speed?.takeIf { it.isFinite() && it >= 0 }?.let { it >= 0.7f } ?: (delta > max(2.5, fix.accuracy / 2.0))
        val wasAutoPaused = summary.status == RunStatus.AUTO_PAUSED
        if (summary.status == RunStatus.AUTO_PAUSED) {
            lastFix = fix
            if (!autoPause.shouldResume(fix, previous, delta)) return null
            val resumedAt = maxOf(receivedAtMs, tickAt ?: receivedAtMs)
            resume(resumedAt); announcements += Announcement.Message(AnnouncementKind.STATE, "달리기를 재개합니다", resumedAt)
        }
        if (wasAutoPaused) autoPause.observeRunning(fix, null)
        val validLeg = previous != null && !wasAutoPaused && seconds in 0.001..15.0 && summary.status == RunStatus.RUNNING
        val added = if (validLeg && moving && delta >= 0.8) delta else 0.0
        val before = summary
        var segment = summary.segment
        if (previous == null || seconds > 15) { segment++; clearPaceWindow() }
        summary = summary.copy(meters = summary.meters + added, segment = segment)
        lastFix = fix
        gpsMessage = "GPS ±${fix.accuracy.roundToInt()}m"
        if (summary.goalMeters != null && summary.goalActiveMs == null && summary.meters >= summary.goalMeters!!) {
            val lastPoint = points.lastOrNull()
            val f = if (added > 0) ((summary.goalMeters!! - before.meters) / added).coerceIn(0.0, 1.0) else 1.0
            val active = lastPoint?.let { it.activeMs + ((summary.activeMs - it.activeMs) * f).toLong() } ?: summary.activeMs
            val epoch = previous?.let { it.epochMs + ((fix.epochMs - it.epochMs) * f).toLong() } ?: fix.epochMs
            summary = summary.copy(goalActiveMs = active, goalEpochMs = epoch)
            announcements += Announcement.Message(AnnouncementKind.GOAL, "목표 거리를 달성했습니다. 기록은 계속됩니다", fix.monotonicMs)
        }
        val point = TrackPoint(fix.coordinate, fix.epochMs, summary.activeMs, summary.meters, fix.altitude, fix.accuracy, segment, cadence)
        points += point
        advanceCourse(point)
        paceWindow += summary.activeMs to summary.meters
        while (paceWindow.size > 1 && summary.activeMs - paceWindow.first().first > 20_000) paceWindow.removeFirst()
        val oldest = paceWindow.first()
        val windowMs = summary.activeMs - oldest.first; val windowMeters = summary.meters - oldest.second
        currentPace = if (windowMs >= 8000 && windowMeters >= 8) windowMs / windowMeters else null
        providerPace.observe(fix, summary.activeMs)
        providerPaceUsed = currentPace != null && providerPace.ready
        if (providerPaceUsed) currentPace = providerPace.pace()
        advancePlan(points.getOrNull(points.lastIndex - 1))
        coach(fix.monotonicMs)
        navigator?.update(fix.coordinate, fix.epochMs)?.let { announcements += Announcement.Message(AnnouncementKind.NAVIGATION, it, fix.monotonicMs) }
        val km = (summary.meters / 1000).toInt()
        val distanceDue = distancePace.observe(summary.meters, validDistancePace(maxOf(fix.monotonicMs, receivedAtMs)) != null,
            summary.status == RunStatus.RUNNING)
        if (km > lastKm) {
            lastKm = km
            if (feedbackPreferences.kilometerVoice) {
                if (distanceDue) announcements.removeAll { it.kind == AnnouncementKind.DISTANCE_PACE }
                announcements += Announcement.Kilometer(km, summary.averagePace, fix.monotonicMs,
                    currentPace.takeIf { distanceDue })
            } else if (distanceDue) enqueueDistancePace(fix.monotonicMs)
        } else if (distanceDue) {
            enqueueDistancePace(fix.monotonicMs)
        }
        return point
    }

    private fun advancePlan(anchor: TrackPoint? = null) {
        if (summary.coursePaceEnabled) return
        val steps = summary.plan?.steps ?: return
        while (summary.planIndex < steps.size) {
            val step = steps[summary.planIndex]
            val distance = summary.meters - summary.stepStartMeters
            val time = summary.activeMs - summary.stepStartActiveMs
            when (step.kind) {
                StepKind.DISTANCE -> {
                    if (distance < step.amount) return
                    val boundary = summary.stepStartMeters + step.amount
                    val fromMeters = max(summary.stepStartMeters, anchor?.meters ?: summary.stepStartMeters)
                    val fromMs = max(summary.stepStartActiveMs, anchor?.activeMs ?: summary.stepStartActiveMs)
                    val fraction = if (summary.meters > fromMeters) ((boundary - fromMeters) / (summary.meters - fromMeters)).coerceIn(0.0, 1.0) else 1.0
                    summary = summary.copy(planIndex = summary.planIndex + 1, stepStartMeters = boundary,
                        stepStartActiveMs = fromMs + ((summary.activeMs - fromMs) * fraction).toLong())
                }
                StepKind.TIME -> {
                    if (time < step.amount * 1000) return
                    val boundary = summary.stepStartActiveMs + (step.amount * 1000).toLong()
                    val fromMs = max(summary.stepStartActiveMs, anchor?.activeMs ?: summary.activeMs)
                    val fromMeters = max(summary.stepStartMeters, anchor?.meters ?: summary.meters)
                    val fraction = if (summary.activeMs > fromMs) ((boundary - fromMs).toDouble() / (summary.activeMs - fromMs)).coerceIn(0.0, 1.0) else 1.0
                    summary = summary.copy(planIndex = summary.planIndex + 1, stepStartActiveMs = boundary,
                        stepStartMeters = fromMeters + (summary.meters - fromMeters) * fraction)
                }
            }
        }
    }
    private fun announceStep(now: Long) {
        if (summary.coursePaceEnabled) {
            val marker = summary.courseLegIndex to summary.coursePaceComplete
            if (marker != lastCourseCue) {
                lastCourseCue = marker; paceFeedback.reset(targetChanged = true); clearPaceWarnings()
                val index = summary.courseLegIndex
                if (summary.coursePaceComplete) announcements += Announcement.Message(AnnouncementKind.STEP, "코스 페이스 계획을 완료했습니다", now)
                else if (index >= 0) {
                    val label = "${index + 1}번에서 ${index + 2}번 구간"
                    announcements += summary.targetPace?.let { Announcement.Step(label, it.toDouble(), now) }
                        ?: Announcement.Message(AnnouncementKind.STEP, "$label, 목표 페이스 미설정", now)
                }
            }
            return
        }
        if (summary.planIndex != lastStepCue) {
            lastStepCue = summary.planIndex; paceFeedback.reset(targetChanged = true); clearPaceWarnings()
            announcements += summary.plan?.steps?.getOrNull(lastStepCue)?.let { Announcement.Step(it.name, it.paceSeconds.toDouble(), now) }
                ?: Announcement.Message(AnnouncementKind.STEP, "페이스 계획을 완료했습니다", now)
        }
    }
    private fun advanceCourse(point: TrackPoint) {
        if (!summary.coursePaceEnabled || summary.coursePaceComplete) return
        val course = summary.course ?: return
        if (course.paceTransition == CoursePaceTransition.WAYPOINT) {
            waypointTracker?.observe(point)
            val passed = (waypointTracker?.nextIndex ?: 0) - 1
            if (passed >= 0) summary = summary.copy(courseLegIndex = passed.coerceAtMost(course.points.lastIndex - 1),
                coursePaceComplete = passed == course.points.lastIndex)
        } else {
            val tracker = courseDistance ?: return
            val progress = summary.courseProgressMeters + tracker.observe(point, summary.courseProgressMeters)
            val index = courseBoundaries.drop(1).indexOfFirst { progress + .000001 < it }
            summary = summary.copy(courseLegIndex = if (index < 0) course.points.lastIndex - 1 else index,
                courseProgressMeters = progress, courseRouteMeters = tracker.routeMeters, coursePaceComplete = index < 0)
        }
    }
    fun updateFeedbackPreferences(value: Preferences, now: Long) {
        val next = value.normalized()
        if (next.voice != feedbackPreferences.voice || next.distancePaceVoice != feedbackPreferences.distancePaceVoice ||
            next.distancePaceMeters != feedbackPreferences.distancePaceMeters) clearDistancePace()
        if (next.voice != feedbackPreferences.voice || next.periodicPaceVoice != feedbackPreferences.periodicPaceVoice ||
            next.periodicPaceSeconds != feedbackPreferences.periodicPaceSeconds || next.distancePaceVoice != feedbackPreferences.distancePaceVoice ||
            next.paceVoice != feedbackPreferences.paceVoice || next.readCurrentPace != feedbackPreferences.readCurrentPace ||
            next.readPaceStatus != feedbackPreferences.readPaceStatus || next.readTargetPace != feedbackPreferences.readTargetPace ||
            next.paceTolerance != feedbackPreferences.paceTolerance || next.paceCoachingMode != feedbackPreferences.paceCoachingMode) {
            speechGeneration++
            announcements.removeAll { it.kind == AnnouncementKind.CURRENT_PACE }
        }
        feedbackPreferences = next
        distancePace.configure(feedbackPreferences.voice && feedbackPreferences.distancePaceVoice,
            feedbackPreferences.distancePaceMeters, summary.meters)
        coach(now)
    }
    private fun validDistancePace(now: Long): Double? = currentPace?.takeIf {
        summary.status == RunStatus.RUNNING && lastFix?.let { fix -> now - fix.monotonicMs in 0..PaceFeedbackPolicy.GPS_MAX_AGE_MS } == true &&
            it.isFinite() && it > .5 && it <= 3600
    }
    private fun enqueueDistancePace(now: Long) {
        val pace = validDistancePace(now) ?: return
        announcements.removeAll { it.kind == AnnouncementKind.DISTANCE_PACE }
        announcements += Announcement.DistancePace(summary.meters, pace, now)
    }
    private fun refreshDistancePace(now: Long) {
        val pace = validDistancePace(now).takeIf { feedbackPreferences.voice && feedbackPreferences.distancePaceVoice }
        val refreshed = announcements.mapNotNull { event ->
            when (event) {
                is Announcement.DistancePace -> pace?.let { event.copy(currentSecondsPerKilometer = it) }
                is Announcement.Kilometer -> event.copy(currentSecondsPerKilometer = pace.takeIf { event.currentSecondsPerKilometer != null })
                else -> event
            }
        }
        announcements.clear(); announcements.addAll(refreshed)
    }
    private fun resetDistancePace() {
        distancePace.resetBoundary(summary.meters)
        clearDistancePace()
    }
    private fun clearDistancePace() {
        val kept = announcements.filterNot { it.kind == AnnouncementKind.DISTANCE_PACE }.map {
            if (it is Announcement.Kilometer) it.copy(currentSecondsPerKilometer = null) else it
        }
        announcements.clear(); announcements.addAll(kept)
    }
    private fun clearPaceWarnings() { announcements.removeAll { it.kind == AnnouncementKind.PACE_WARNING } }
    private fun coach(sampleTime: Long) {
        val now = maxOf(sampleTime, feedbackAt)
        feedbackAt = now
        announceStep(now)
        val target = summary.targetPace ?: if (!feedbackPreferences.stopCoachAtEnd) {
            if (summary.coursePaceEnabled) summary.course?.takeIf { summary.coursePaceComplete }?.let { it.paceAt(it.points.lastIndex - 1) }
            else summary.plan?.steps?.lastOrNull()?.paceSeconds
        } else null
        val state = paceFeedback.evaluate(now, summary.status == RunStatus.RUNNING, currentPace, target, lastFix?.monotonicMs, feedbackPreferences)
        // 대기한 숫자를 재생하지 않는다. 발화 직전까지 최신 값으로 하나만 교체한다.
        clearPaceWarnings()
        if (state.voiceDue) paceWarningAnnouncement(state, feedbackPreferences, now)?.let { announcements += it }
        refreshDistancePace(now)
        val validCurrent = validDistancePace(now) != null
        if (currentWasValid && !validCurrent) speechGeneration++
        currentWasValid = validCurrent
        periodicPace.configure(feedbackPreferences.voice && feedbackPreferences.periodicPaceVoice, feedbackPreferences.periodicPaceSeconds)
        if (periodicPace.observe(summary.activeMs, validCurrent)) {
            announcements.removeAll { it.kind == AnnouncementKind.CURRENT_PACE }
            announcements += Announcement.Pace(AnnouncementKind.CURRENT_PACE, currentPace, now)
        }
        refreshPeriodicPace(now)
    }
    private fun clearPaceWindow() {
        paceWindow.clear(); providerPace.clear(); providerPaceUsed = false
    }
    private fun refreshPeriodicPace(now: Long) {
        val pace = validDistancePace(now).takeIf { feedbackPreferences.voice && feedbackPreferences.periodicPaceVoice }
        val refreshed = announcements.mapNotNull { event ->
            if (event is Announcement.Pace && event.kind == AnnouncementKind.CURRENT_PACE)
                pace?.let { event.copy(secondsPerKilometer = it) } else event
        }
        announcements.clear(); announcements.addAll(refreshed)
    }
    /** Prepare immediately before calling TTS. A failed request does not consume the original cue. */
    fun prepareSpeech(selected: Announcement, now: Long): PreparedRunSpeech? {
        if (!feedbackPreferences.voice) return null
        val pace = validDistancePace(now)
        val event = when (selected) {
            is Announcement.Pace -> if (selected.kind == AnnouncementKind.CURRENT_PACE) {
                if (!feedbackPreferences.periodicPaceVoice || pace == null) return null
                selected.copy(secondsPerKilometer = pace)
            } else selected
            is Announcement.DistancePace -> {
                if (!feedbackPreferences.distancePaceVoice || pace == null) return null
                selected.copy(currentSecondsPerKilometer = pace)
            }
            is Announcement.Kilometer -> selected.copy(currentSecondsPerKilometer = pace.takeIf {
                (feedbackPreferences.distancePaceVoice && selected.currentSecondsPerKilometer != null) ||
                    (feedbackPreferences.periodicPaceVoice && announcements.any { it.kind == AnnouncementKind.CURRENT_PACE })
            })
            is Announcement.Message -> if (selected.kind == AnnouncementKind.PACE_WARNING) {
                if (pace == null || !feedback.voiceDue) return null
                // The current numeric phrase is already fulfilled by a nearby real utterance.
                // Keep warning/target independently; never infer this from a requested or failed TTS.
                val prefs = feedbackPreferences.copy(readCurrentPace = feedbackPreferences.readCurrentPace &&
                    (!feedbackPreferences.periodicPaceVoice || !sameSpokenCurrent(now, pace)))
                paceWarningAnnouncement(feedback, prefs, now) ?: return null
            } else selected
            else -> selected
        }
        val readsCurrent = when (event) {
            is Announcement.Pace -> event.kind == AnnouncementKind.CURRENT_PACE
            is Announcement.DistancePace -> true
            is Announcement.Kilometer -> event.currentSecondsPerKilometer != null
            is Announcement.Message -> event.kind == AnnouncementKind.PACE_WARNING && feedbackPreferences.readCurrentPace &&
                (!feedbackPreferences.periodicPaceVoice || !sameSpokenCurrent(now, pace))
            else -> false
        }
        val spokenCurrent = if (!readsCurrent) null else when (event) {
            is Announcement.Pace -> event.secondsPerKilometer
            is Announcement.DistancePace -> event.currentSecondsPerKilometer
            is Announcement.Kilometer -> event.currentSecondsPerKilometer
            else -> feedback.currentPace
        }
        val token = RunSpeechToken(this, speechGeneration, ++speechSequence, event.kind, readsCurrent, spokenCurrent,
            feedback.direction.takeIf { event.kind == AnnouncementKind.PACE_WARNING },
            feedback.targetPace.takeIf { event.kind == AnnouncementKind.PACE_WARNING }, event as? Announcement.Kilometer)
        return PreparedRunSpeech(event, token)
    }
    private fun sameSpokenCurrent(now: Long, pace: Double?): Boolean = pace != null &&
        lastCurrentSpokenAt?.let { now - it in 0..5_000 } == true && round(pace).toInt() == lastCurrentSpokenRounded
    /** Does not interrupt a sentence merely because the sampled numeric pace changes. */
    fun isSpeechCurrent(token: RunSpeechToken, now: Long): Boolean {
        if (token.owner !== this || !feedbackPreferences.voice) return false
        val sampled = token.kind == AnnouncementKind.CURRENT_PACE || token.kind == AnnouncementKind.DISTANCE_PACE ||
            token.kind == AnnouncementKind.PACE_WARNING
        // An unstarted kilometer current phrase must still be fresh. Completion requeues its average.
        if (!sampled) return !token.readsCurrent || (activeSpeech === token && speechStarted) ||
            (token.generation == speechGeneration && validDistancePace(now) != null &&
                (feedbackPreferences.distancePaceVoice || feedbackPreferences.periodicPaceVoice))
        // Preserve an actual sentence through normal recovery, target changes and GPS expiry.
        if (activeSpeech === token && speechStarted) return summary.status == RunStatus.RUNNING && when (token.kind) {
            AnnouncementKind.CURRENT_PACE -> feedbackPreferences.periodicPaceVoice
            AnnouncementKind.DISTANCE_PACE -> feedbackPreferences.distancePaceVoice
            AnnouncementKind.PACE_WARNING -> feedbackPreferences.paceVoice
            else -> true
        }
        if (token.generation != speechGeneration || validDistancePace(now) == null) return false
        return when (token.kind) {
            AnnouncementKind.CURRENT_PACE -> feedbackPreferences.periodicPaceVoice
            AnnouncementKind.DISTANCE_PACE -> feedbackPreferences.distancePaceVoice
            AnnouncementKind.PACE_WARNING -> feedbackPreferences.paceVoice && feedback.direction == token.warningDirection &&
                feedback.targetPace == token.warningTarget
            else -> true
        }
    }
    fun speechRequested(token: RunSpeechToken) {
        if (token.owner !== this || token.generation != speechGeneration) return
        activeSpeech = token; speechStarted = false
        if (token.kind == AnnouncementKind.PACE_WARNING) paceWarningRequested()
    }
    /** Validate at processing time; retain the actual callback time for spoken credit. */
    fun speechStarted(token: RunSpeechToken, now: Long, validatedAtMs: Long = now) {
        if (activeSpeech !== token || speechStarted || !isSpeechCurrent(token, validatedAtMs)) return
        speechStarted = true
        if (token.kind == AnnouncementKind.PACE_WARNING) paceWarningSpoken(now)
        if (token.readsCurrent && token.generation == speechGeneration && validDistancePace(validatedAtMs) != null) {
            if (feedbackPreferences.periodicPaceVoice) {
                lastCurrentSpokenAt = now
                lastCurrentSpokenRounded = token.currentSeconds?.let { round(it).toInt() }
            }
            periodicPace.spoken(summary.activeMs)
            val spoken = token.currentSeconds?.let { round(it).toInt() }
            fun fulfilled(value: Double?) = value != null && round(value).toInt() == spoken
            announcements.removeAll {
                (it is Announcement.Pace && it.kind == AnnouncementKind.CURRENT_PACE && fulfilled(it.secondsPerKilometer)) ||
                    (it is Announcement.DistancePace && fulfilled(it.currentSecondsPerKilometer))
            }
            val kept = announcements.map {
                if (it is Announcement.Kilometer && fulfilled(it.currentSecondsPerKilometer)) it.copy(currentSecondsPerKilometer = null) else it
            }
            announcements.clear(); announcements.addAll(kept)
        }
    }
    fun speechCompleted(token: RunSpeechToken) {
        if (activeSpeech !== token) return
        if (!speechStarted && token.readsCurrent) token.kilometer?.let {
            announcements += it.copy(currentSecondsPerKilometer = null)
        }
        activeSpeech = null
        if (token.kind == AnnouncementKind.PACE_WARNING) paceWarningCompleted()
    }
    private fun resetPeriodicPace() {
        speechGeneration++; currentWasValid = false; periodicPace.reset()
        lastCurrentSpokenAt = null
        lastCurrentSpokenRounded = null
        announcements.removeAll { it.kind == AnnouncementKind.CURRENT_PACE }
    }
    fun paceWarningRequested() { paceFeedback.requested(); clearPaceWarnings() }
    fun paceWarningSpoken(now: Long) { paceFeedback.spoken(now) }
    fun paceWarningCompleted() { paceFeedback.completed() }
    fun invalidatePace(now: Long) {
        currentPace = null; clearPaceWindow(); waypointTracker?.disconnect(); courseDistance?.disconnect(); coach(now)
    }
    fun pause(automatic: Boolean = false) {
        if (summary.status != RunStatus.RUNNING && (automatic || summary.status != RunStatus.AUTO_PAUSED)) return
        if (automatic) lastAutoPauseTransition = autoPause.diagnostics.copy(reason = "pause_confirmed")
        summary = summary.copy(status = if (automatic) RunStatus.AUTO_PAUSED else RunStatus.PAUSED)
        currentPace = null; clearPaceWindow(); paceFeedback.reset(); clearPaceWarnings()
        waypointTracker?.disconnect(); courseDistance?.disconnect()
        resetDistancePace(); resetPeriodicPace()
        autoPause.reset(if (automatic) "auto_paused" else "manual_paused")
        if (!automatic) lastFix = null
    }
    fun resume(now: Long) {
        if (summary.status == RunStatus.AUTO_PAUSED) lastAutoPauseTransition = autoPause.diagnostics.copy(reason = "resume_confirmed")
        summary = summary.copy(status = RunStatus.RUNNING, segment = summary.segment + 1)
        paceFeedback.reset(); clearPaceWarnings()
        tickAt = now; resumeAt = now; lastFix = null; autoPause.reset("resumed"); clearPaceWindow(); currentPace = null
        waypointTracker?.disconnect(); courseDistance?.disconnect()
        resetDistancePace(); resetPeriodicPace()
    }
    fun finish(epochMs: Long) { summary = summary.copy(status = RunStatus.FINISHED, endedAt = epochMs); paceFeedback.reset(); clearPaceWarnings(); resetDistancePace(); resetPeriodicPace() }
    fun interrupt() { summary = summary.copy(status = RunStatus.INTERRUPTED); tickAt = null; lastFix = null; autoPause.reset("interrupted"); currentPace = null; paceFeedback.reset(); clearPaceWarnings(); resetDistancePace(); resetPeriodicPace() }
}
