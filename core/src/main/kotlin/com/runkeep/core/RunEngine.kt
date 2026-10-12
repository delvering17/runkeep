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
            currentPace = null; autoPause.reset("gps_expired"); paceWindow.clear(); gpsMessage = "GPS 신호를 확인해주세요"
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
            currentPace = null; paceWindow.clear(); gpsMessage = "GPS 신호를 확인해주세요"
            coach(receivedAtMs)
            return null
        }
        updateClock(fix.monotonicMs, fix.epochMs, evaluateAutoPause = false)
        if (summary.status == RunStatus.PAUSED || summary.status == RunStatus.INTERRUPTED || summary.status == RunStatus.FINISHED) return null
        if (!fix.accuracy.isFinite() || fix.accuracy < 0 || fix.accuracy > 25) {
            gpsMessage = "GPS 정확도가 낮습니다"; currentPace = null; autoPause.reset("accuracy"); lastFix = null; paceWindow.clear(); coach(fix.monotonicMs); return null
        }
        val previous = lastFix
        val seconds = previous?.let { (fix.monotonicMs - it.monotonicMs) / 1000.0 } ?: 0.0
        val delta = previous?.let { Geo.distance(it.coordinate, fix.coordinate) } ?: 0.0
        if (previous != null && seconds < 15 && delta / seconds > 12) {
            gpsMessage = "GPS 위치 튐을 제외했습니다"; currentPace = null; autoPause.reset("position_jump"); coach(fix.monotonicMs); return null
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
        if (previous == null || seconds > 15) { segment++; paceWindow.clear() }
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
                lastCourseCue = marker; paceFeedback.reset(); clearPaceWarnings()
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
            lastStepCue = summary.planIndex; paceFeedback.reset(); clearPaceWarnings()
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
    }
    fun paceWarningRequested() { paceFeedback.requested(); clearPaceWarnings() }
    fun paceWarningSpoken(now: Long) { paceFeedback.spoken(now) }
    fun paceWarningCompleted() { paceFeedback.completed() }
    fun invalidatePace(now: Long) {
        currentPace = null; paceWindow.clear(); waypointTracker?.disconnect(); courseDistance?.disconnect(); coach(now)
    }
    fun pause(automatic: Boolean = false) {
        if (summary.status != RunStatus.RUNNING && (automatic || summary.status != RunStatus.AUTO_PAUSED)) return
        if (automatic) lastAutoPauseTransition = autoPause.diagnostics.copy(reason = "pause_confirmed")
        summary = summary.copy(status = if (automatic) RunStatus.AUTO_PAUSED else RunStatus.PAUSED)
        currentPace = null; paceWindow.clear(); paceFeedback.reset(); clearPaceWarnings()
        waypointTracker?.disconnect(); courseDistance?.disconnect()
        resetDistancePace()
        autoPause.reset(if (automatic) "auto_paused" else "manual_paused")
        if (!automatic) lastFix = null
    }
    fun resume(now: Long) {
        if (summary.status == RunStatus.AUTO_PAUSED) lastAutoPauseTransition = autoPause.diagnostics.copy(reason = "resume_confirmed")
        summary = summary.copy(status = RunStatus.RUNNING, segment = summary.segment + 1)
        paceFeedback.reset(); clearPaceWarnings()
        tickAt = now; resumeAt = now; lastFix = null; autoPause.reset("resumed"); paceWindow.clear(); currentPace = null
        waypointTracker?.disconnect(); courseDistance?.disconnect()
        resetDistancePace()
    }
    fun finish(epochMs: Long) { summary = summary.copy(status = RunStatus.FINISHED, endedAt = epochMs); paceFeedback.reset(); clearPaceWarnings(); resetDistancePace() }
    fun interrupt() { summary = summary.copy(status = RunStatus.INTERRUPTED); tickAt = null; lastFix = null; autoPause.reset("interrupted"); currentPace = null; paceFeedback.reset(); clearPaceWarnings(); resetDistancePace() }
}
