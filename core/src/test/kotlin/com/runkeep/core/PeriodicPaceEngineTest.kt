package com.runkeep.core

import kotlin.test.*

class PeriodicPaceEngineTest {
    private val settings = Preferences(autoPause = false, kilometerVoice = false, periodicPaceVoice = true, periodicPaceSeconds = 10)
    private fun engine(prefs: Preferences = settings, plan: WorkoutPlan? = null) =
        RunEngine(RunSummary(startedAt = 0, plan = plan), prefs).also { it.tick(0, 0) }
    private fun feed(e: RunEngine, from: Int, to: Int, pace: Double = 360.0) {
        for (second in from..to) e.ingest(LocationFix(Coordinate(0.0, second * 1000.0 / pace / 111_195.0802335329),
            second * 1000L, second * 1000L, 3f, speed = 3f))
    }
    private fun current(e: RunEngine) = e.announcements.filter { it.kind == AnnouncementKind.CURRENT_PACE }
    private fun warning(e: RunEngine) = e.announcements.last { it.kind == AnnouncementKind.PACE_WARNING }
    private fun target() = WorkoutPlan(name = "target", steps = listOf(PlanStep(kind = StepKind.DISTANCE, amount = 10000.0, paceSeconds = 420)))

    @Test fun `normal and no target support an independent current announcement and only latest queued value`() {
        for (plan in listOf(null, WorkoutPlan(name = "normal", steps = listOf(PlanStep(kind = StepKind.DISTANCE, amount = 10000.0, paceSeconds = 360))))) {
            val e = engine(plan = plan)
            feed(e, 0, 17); assertTrue(current(e).isEmpty())
            feed(e, 18, 40); assertEquals(1, current(e).size)
            assertNull(e.feedback.direction)
            val speech = e.prepareSpeech(current(e).single(), 40_000)!!
            assertTrue(speech.event.speechText().contains("현재 페이스 6분"))
            assertTrue(e.isSpeechCurrent(speech.token, 40_000))
        }
    }

    @Test fun `only actual onStart credits periodic schedule and delayed value is refreshed`() {
        val e = engine()
        feed(e, 0, 18)
        val original = current(e).single()
        val failed = e.prepareSpeech(original, 18_000)!!
        for (second in 19..28) e.ingest(LocationFix(Coordinate(0.0,
            (18 * 1000.0 / 360 + (second - 18) * 1000.0 / 390) / 111_195.0802335329), second * 1000L, second * 1000L, 3f, speed = 3f))
        assertEquals(1, current(e).size) // prepare without accepted request gave no credit.
        val refreshed = e.prepareSpeech(current(e).single(), 28_000)!!
        val refreshedEvent = refreshed.event as Announcement.Pace
        assertTrue(refreshedEvent.secondsPerKilometer!! > (failed.event as Announcement.Pace).secondsPerKilometer!!)
        assertEquals(e.currentPace, refreshedEvent.secondsPerKilometer)
        e.speechRequested(refreshed.token)
        e.speechStarted(failed.token, 28_000) // Never requested, cannot credit another request.
        assertEquals(1, current(e).size)
        e.speechStarted(refreshed.token, 28_000)
        assertTrue(current(e).isEmpty())
        e.speechCompleted(refreshed.token)
        feed(e, 29, 37); assertTrue(current(e).isEmpty())
        feed(e, 38, 38); assertEquals(1, current(e).size)
    }

    @Test fun `reading choices remain independent in all eight combinations and only actual current earns credit`() {
        for (bits in 0..7) {
            val status = bits and 1 != 0; val readCurrent = bits and 2 != 0; val target = bits and 4 != 0
            val e = engine(settings.copy(readPaceStatus = status, readCurrentPace = readCurrent, readTargetPace = target), target())
            feed(e, 0, 18)
            val periodic = current(e).single()
            if (bits == 0) {
                assertTrue(e.announcements.none { it.kind == AnnouncementKind.PACE_WARNING })
                assertNotNull(e.prepareSpeech(periodic, 18_000))
            } else {
                val prepared = e.prepareSpeech(warning(e), 18_000)!!
                val text = prepared.event.speechText()
                assertEquals(status, text.contains("빠릅니다")); assertEquals(readCurrent, text.contains("현재 6분")); assertEquals(target, text.contains("목표 7분"))
                e.speechRequested(prepared.token); e.speechStarted(prepared.token, 18_000)
                assertEquals(!readCurrent, current(e).isNotEmpty())
                e.speechCompleted(prepared.token)
            }
        }
    }

    @Test fun `pending choice change all off and stopped old tokens do not credit new generations`() {
        for (next in listOf(settings.copy(readPaceStatus = false, readCurrentPace = false, readTargetPace = false),
            settings.copy(readTargetPace = true), settings.copy(readPaceStatus = false), settings.copy(paceTolerance = 60),
            settings.copy(paceCoachingMode = PaceCoachingMode.STABLE))) {
            val e = engine(settings, target()); feed(e, 0, 18)
            val old = e.prepareSpeech(warning(e), 18_000)!!
            e.speechRequested(old.token); e.updateFeedbackPreferences(next, 18_001)
            assertFalse(e.isSpeechCurrent(old.token, 18_001))
            e.speechStarted(old.token, 18_001)
            e.speechCompleted(old.token)
            assertTrue(current(e).isEmpty())
            feed(e, 19, 28); assertEquals(1, current(e).size)
        }
        val e = engine(); feed(e, 0, 18)
        val old = e.prepareSpeech(current(e).single(), 18_000)!!
        e.speechRequested(old.token); e.pause(); e.resume(20_000)
        feed(e, 20, 38)
        val fresh = e.prepareSpeech(current(e).single(), 38_000)!!
        e.speechRequested(fresh.token)
        e.speechStarted(old.token, 38_000); e.speechCompleted(old.token)
        assertEquals(1, current(e).size)
        e.speechStarted(fresh.token, 38_000); assertTrue(current(e).isEmpty())
    }

    @Test fun `GPS invalid rejects unstarted speech but preserves actual sentence until option off or pause`() {
        val e = engine(); feed(e, 0, 18)
        val waiting = e.prepareSpeech(current(e).single(), 18_000)!!
        e.speechRequested(waiting.token); e.invalidatePace(18_001)
        assertFalse(e.isSpeechCurrent(waiting.token, 18_001)); e.speechStarted(waiting.token, 18_001)
        feed(e, 19, 37)
        val started = e.prepareSpeech(current(e).single(), 37_000)!!
        e.speechRequested(started.token); e.speechStarted(started.token, 37_000)
        e.invalidatePace(37_001)
        assertTrue(e.isSpeechCurrent(started.token, 37_001))
        assertTrue(current(e).isEmpty())
        e.updateFeedbackPreferences(settings.copy(periodicPaceVoice = false), 37_002)
        assertFalse(e.isSpeechCurrent(started.token, 37_002))
        e.pause(); assertFalse(e.isSpeechCurrent(started.token, 37_003))
    }

    @Test fun `warning started sentence survives normal and target change but waiting sentence is rejected`() {
        val plan = WorkoutPlan(name = "transition", steps = listOf(
            PlanStep(kind = StepKind.TIME, amount = 20.0, paceSeconds = 420),
            PlanStep(kind = StepKind.TIME, amount = 100.0, paceSeconds = 360)))
        for (started in listOf(false, true)) {
            val e = engine(plan = plan); feed(e, 0, 18)
            val speech = e.prepareSpeech(warning(e), 18_000)!!
            e.speechRequested(speech.token)
            if (started) e.speechStarted(speech.token, 18_000)
            feed(e, 19, 20)
            assertNull(e.feedback.direction)
            assertEquals(started, e.isSpeechCurrent(speech.token, 20_000))
        }
    }

    @Test fun `kilometer combines periodic current once and retains average when pending current becomes invalid`() {
        val e = engine(); feed(e, 0, 18)
        val kilometer = Announcement.Kilometer(1, 365.0, 18_000)
        e.announcements += kilometer
        val speech = e.prepareSpeech(kilometer, 18_000)!!
        assertTrue(speech.event.speechText().contains("평균 페이스 6분 5초, 현재 페이스 6분"))
        AnnouncementQueue(e.announcements).acknowledge(kilometer)
        e.speechRequested(speech.token); e.invalidatePace(18_001)
        assertFalse(e.isSpeechCurrent(speech.token, 18_001))
        e.speechCompleted(speech.token)
        val restored = e.announcements.filterIsInstance<Announcement.Kilometer>().single()
        assertNull(restored.currentSecondsPerKilometer)
        assertEquals(365.0, restored.averageSecondsPerKilometer)
        assertNotNull(e.prepareSpeech(restored, 18_001))
    }

    @Test fun `current onStart coalesces distance and kilometer numeric phrases without losing milestones`() {
        val e = engine(settings.copy(distancePaceVoice = true)); feed(e, 0, 18)
        val speech = e.prepareSpeech(current(e).single(), 18_000)!!
        e.announcements += Announcement.DistancePace(100.0, 360.0, 18_000)
        e.announcements += Announcement.Kilometer(1, 365.0, 18_000, 360.0)
        e.speechRequested(speech.token); e.speechStarted(speech.token, 18_000)
        assertTrue(e.announcements.none { it.kind == AnnouncementKind.CURRENT_PACE || it.kind == AnnouncementKind.DISTANCE_PACE })
        assertNull(e.announcements.filterIsInstance<Announcement.Kilometer>().single().currentSecondsPerKilometer)
    }

    @Test fun `settings interval changes and invalid recovery never replay backlog or leak owner tokens`() {
        val e = engine(); feed(e, 0, 18)
        val token = e.prepareSpeech(current(e).single(), 18_000)!!.token
        val other = engine(); feed(other, 0, 18)
        other.speechRequested(token); other.speechStarted(token, 18_000)
        assertEquals(1, current(other).size)
        e.updateFeedbackPreferences(settings.copy(periodicPaceSeconds = 30), 18_001)
        assertTrue(current(e).isEmpty())
        feed(e, 19, 47); assertTrue(current(e).isEmpty())
        feed(e, 48, 48); assertEquals(1, current(e).size)
        e.tick(60_001, 60_001); assertTrue(current(e).isEmpty())
        feed(e, 70, 107); assertTrue(current(e).isEmpty())
        feed(e, 108, 108); assertEquals(1, current(e).size)
        e.finish(108_001); assertTrue(current(e).isEmpty())
    }
    @Test fun `only identical spoken numeric current is coalesced and captured value is not replaced at onStart`() {
        val e = engine(settings.copy(readCurrentPace = true), target())
        feed(e, 0, 18)
        val prepared = e.prepareSpeech(current(e).single(), 18_000)!!
        e.speechRequested(prepared.token)
        // A new coordinate sample after prepare changes the engine value before actual onStart.
        e.ingest(LocationFix(Coordinate(0.0, (18 * 1000.0 / 360 + 1.0) / 111_195.0802335329), 19_000, 19_000, 3f, speed = 3f))
        e.speechStarted(prepared.token, 19_000); e.speechCompleted(prepared.token)
        val changed = e.prepareSpeech(warning(e), 19_000)!!
        val numericPhrase = "현재 ${paceSpeechText(e.currentPace)}"
        assertTrue(changed.event.speechText().contains(numericPhrase)) // Captured speech was 360; now it differs.
        val currentOnly = e.prepareSpeech(Announcement.Pace(AnnouncementKind.CURRENT_PACE, e.currentPace, 19_000), 19_000)!!
        e.speechRequested(currentOnly.token); e.speechStarted(currentOnly.token, 19_000); e.speechCompleted(currentOnly.token)
        e.updateFeedbackPreferences(settings.copy(readCurrentPace = true), 19_001)
        val same = e.prepareSpeech(warning(e), 19_001)!!
        assertFalse(same.event.speechText().contains(numericPhrase))
        assertTrue(same.event.speechText().contains("빠릅니다"))
    }

    @Test fun `different latest queued current values survive an older captured utterance`() {
        val e = engine(settings.copy(distancePaceVoice = true)); feed(e, 0, 18)
        val speech = e.prepareSpeech(current(e).single(), 18_000)!!
        e.announcements += Announcement.DistancePace(100.0, 375.0, 18_000)
        e.announcements += Announcement.Kilometer(1, 365.0, 18_000, 375.0)
        e.speechRequested(speech.token); e.speechStarted(speech.token, 18_000)
        assertEquals(1, e.announcements.filterIsInstance<Announcement.DistancePace>().size)
        assertEquals(375.0, e.announcements.filterIsInstance<Announcement.Kilometer>().single().currentSecondsPerKilometer)
    }

    @Test fun `delayed onStart validates against processing time and credits the captured numeric value`() {
        val e = engine(settings.copy(readCurrentPace = true), target()); feed(e, 0, 18)
        val speech = e.prepareSpeech(current(e).single(), 18_000)!!
        e.speechRequested(speech.token)
        e.ingest(LocationFix(Coordinate(0.0, (18 * 1000.0 / 360 + 1.0) / 111_195.0802335329),
            19_000, 19_000, 3f, speed = 3f))
        assertFalse(e.isSpeechCurrent(speech.token, 18_100)) // Callback predates the latest processed fix.
        e.speechStarted(speech.token, 18_100, validatedAtMs = 19_100)
        assertTrue(e.isSpeechCurrent(speech.token, 18_100)) // Accepted actual sentence is preserved.
        assertEquals(360.0, speech.token.currentSeconds!!, .001)
        val changed = e.prepareSpeech(warning(e), 19_100)!!
        assertTrue(changed.event.speechText().contains("현재 ${paceSpeechText(e.currentPace)}"))
        e.speechCompleted(speech.token)
        e.announcements.clear()
        feed(e, 20, 28); assertTrue(current(e).isEmpty()) // Credit anchors to active time at processing.
        feed(e, 29, 29); assertEquals(1, current(e).size)
    }

    @Test fun `delayed onStart keeps actual callback credit time and rejects expired processing time`() {
        val e = engine(settings.copy(readCurrentPace = true), target()); feed(e, 0, 18)
        val speech = e.prepareSpeech(current(e).single(), 18_000)!!
        e.speechRequested(speech.token); feed(e, 19, 19)
        e.speechStarted(speech.token, 18_100, validatedAtMs = 19_100)
        e.speechCompleted(speech.token)
        val afterActualWindow = e.prepareSpeech(warning(e), 23_101)!!
        assertTrue(afterActualWindow.event.speechText().contains("현재 ${paceSpeechText(e.currentPace)}"))

        val expired = engine(); feed(expired, 0, 18)
        val waiting = expired.prepareSpeech(current(expired).single(), 18_000)!!
        expired.speechRequested(waiting.token); feed(expired, 19, 19)
        expired.speechStarted(waiting.token, 18_100, validatedAtMs = 29_001)
        expired.tick(29_001, 29_001)
        assertFalse(expired.isSpeechCurrent(waiting.token, 29_001)) // No started-sentence preservation after rejection.
    }

}
