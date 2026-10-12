package com.runkeep.core

import kotlin.test.*

class DistancePaceEngineTest {
    private val prefs = Preferences(autoPause = false, kilometerVoice = false, paceVoice = false,
        distancePaceVoice = true, distancePaceMeters = 10)
    private fun engine(initialMeters: Double = 0.0, activeMs: Long = 0L, settings: Preferences = prefs) =
        RunEngine(RunSummary(startedAt = 0, meters = initialMeters, activeMs = activeMs), settings).also { it.tick(0, 0) }
    private fun fix(second: Int, meters: Double = second * 3.0, accuracy: Float = 3f) =
        LocationFix(Coordinate(meters / 111_195.0802335329, 127.0), second * 1000L, second * 1000L,
            accuracy, speed = 3f)
    private fun warm(e: RunEngine, until: Int = 10) { for (s in 0..until) e.ingest(fix(s)) }
    private fun distance(e: RunEngine) = e.announcements.filterIsInstance<Announcement.DistancePace>()

    @Test fun `current distance guidance does not require target or pace warning enabled`() {
        val e = engine(); warm(e)
        assertNull(e.summary.targetPace)
        assertNull(e.feedback.alarm)
        val event = distance(e).single()
        assertEquals(e.currentPace, event.currentSecondsPerKilometer)
        assertEquals("현재 페이스 5분 33초", event.speechText())
        assertFalse(e.announcements.any { it.kind == AnnouncementKind.PACE_WARNING })
    }

    @Test fun `a queued distance boundary reads latest valid sample without replaying each boundary`() {
        val e = engine(); warm(e)
        val event = distance(e).single()
        e.ingest(fix(11, 34.0))
        val refreshed = distance(e).single()
        assertEquals(event.occurredAtMs, refreshed.occurredAtMs)
        assertEquals(e.currentPace, refreshed.currentSecondsPerKilometer)
        assertNotEquals(event.currentSecondsPerKilometer, refreshed.currentSecondsPerKilometer)
        e.ingest(fix(16, 69.0))
        assertEquals(1, distance(e).size)
        assertEquals(69.0, distance(e).single().meters, 0.0001)
        val pending = AnnouncementQueue(e.announcements)
        e.announcements += Announcement.Message(AnnouncementKind.NAVIGATION, "우회전", 16_000)
        assertNull(pending.next(16_000, RunStatus.RUNNING, playbackBusy = true))
        assertEquals(AnnouncementKind.NAVIGATION, pending.next(16_000, RunStatus.RUNNING)?.kind)
    }

    @Test fun `kilometer crossing combines cumulative average and distinct recent current pace once`() {
        val e = engine(968.0, 600_000, prefs.copy(kilometerVoice = true, distancePaceMeters = 1000))
        warm(e, 11)
        val event = e.announcements.filterIsInstance<Announcement.Kilometer>().single()
        assertTrue(distance(e).isEmpty())
        assertEquals(e.currentPace, event.currentSecondsPerKilometer)
        assertNotEquals(event.averageSecondsPerKilometer, event.currentSecondsPerKilometer)
        assertTrue(event.speechText().contains("평균 페이스"))
        assertTrue(event.speechText().contains("현재 페이스"))
        e.invalidatePace(11_000)
        val averageOnly = e.announcements.filterIsInstance<Announcement.Kilometer>().single()
        assertNull(averageOnly.currentSecondsPerKilometer)
        assertEquals(event.averageSecondsPerKilometer, averageOnly.averageSecondsPerKilometer)
        assertFalse(averageOnly.speechText().contains("현재 페이스"))
    }

    @Test fun `GPS failure expiry and jumps remove queued current values`() {
        for (mode in 0..3) {
            val e = engine(); warm(e)
            assertEquals(1, distance(e).size)
            when (mode) {
                0 -> e.ingest(fix(11, accuracy = 90f))
                1 -> e.tick(20_001, 20_001)
                2 -> e.ingest(fix(11, 5000.0))
                else -> e.invalidatePace(11_000)
            }
            assertTrue(distance(e).isEmpty())
        }
    }

    @Test fun `settings changes cancel pending boundaries while unrelated settings preserve them`() {
        val e = engine(); warm(e)
        e.updateFeedbackPreferences(prefs.copy(metronome = true), 10_000)
        assertEquals(1, distance(e).size)
        e.updateFeedbackPreferences(prefs.copy(distancePaceMeters = 20), 10_000)
        assertTrue(distance(e).isEmpty())
        e.ingest(fix(11)); assertTrue(distance(e).isEmpty())
        e.updateFeedbackPreferences(prefs.copy(distancePaceVoice = false), 11_000)
        for (s in 12..20) e.ingest(fix(s))
        assertTrue(distance(e).isEmpty())
        e.updateFeedbackPreferences(prefs, 20_000)
        e.ingest(fix(21)); assertTrue(distance(e).isEmpty())
        e.ingest(fix(24)); assertEquals(1, distance(e).size)
    }

    @Test fun `paused drift and resume do not replay historical boundaries`() {
        val e = engine(); warm(e)
        val meters = e.summary.meters
        e.pause(); assertTrue(distance(e).isEmpty())
        assertNull(e.ingest(fix(11, 400.0)))
        assertEquals(meters, e.summary.meters)
        e.resume(20_000)
        for (s in 20..27) e.ingest(fix(s))
        assertTrue(distance(e).isEmpty())
        assertTrue(e.summary.meters < 60)
        e.ingest(fix(28)); e.ingest(fix(30))
        assertEquals(1, distance(e).size)
        e.finish(30_000); assertTrue(distance(e).isEmpty())
    }

    @Test fun `restored exercise begins at future boundary and cannot invent an old sample`() {
        val e = engine(105.0)
        e.updateFeedbackPreferences(prefs, 0)
        assertTrue(distance(e).isEmpty())
        warm(e)
        assertEquals(1, distance(e).size)
        assertTrue(distance(e).single().meters >= 130)
        e.interrupt(); assertTrue(distance(e).isEmpty())
    }

    @Test fun `app voice toggle consumes no old distance backlog and kilometer option stays independent`() {
        val e = engine(settings = prefs.copy(voice = false))
        warm(e, 20)
        assertTrue(distance(e).isEmpty())
        e.updateFeedbackPreferences(prefs, 20_000)
        e.ingest(fix(21)); assertTrue(distance(e).isEmpty())
        e.ingest(fix(24)); assertEquals(1, distance(e).size)
        val kilometer = engine(968.0, settings = prefs.copy(kilometerVoice = false, distancePaceMeters = 1000))
        warm(kilometer, 11)
        assertEquals(1, distance(kilometer).size)
        assertFalse(kilometer.announcements.any { it.kind == AnnouncementKind.KILOMETER })
    }
}
