package com.runkeep.core

import kotlin.test.*

class PaceFeedbackTest {
    private val prefs = Preferences(autoPause = false, paceAlarm = true)
    private fun PaceFeedback.sample(now: Long, pace: Double? = 330.0, target: Int? = 360, gpsAt: Long? = now,
                                    running: Boolean = true, settings: Preferences = prefs) =
        evaluate(now, running, pace, target, gpsAt, settings)

    @Test fun `missing old settings get compatible independent defaults`() {
        val p = codec.decodeFromString<Preferences>("""{"voice":false,"paceTolerance":15}""").normalized()
        assertFalse(p.voice); assertTrue(p.paceVoice); assertFalse(p.paceAlarm)
        assertEquals(30, p.paceRepeatSeconds); assertNull(p.cadenceTargetSpm)
        assertTrue(p.readPaceStatus); assertFalse(p.readCurrentPace); assertFalse(p.readTargetPace)
        assertEquals(p, codec.decodeFromString<Preferences>(codec.encodeToString(Preferences.serializer(), p)))
    }
    @Test fun `interval limits are preserved and invalid storage falls back`() {
        for (n in listOf(10, 30, 120)) assertEquals(n, prefs.copy(paceRepeatSeconds = n).normalized().paceRepeatSeconds)
        for (n in listOf(Int.MIN_VALUE, -1, 0, 5, 9, 121, Int.MAX_VALUE))
            assertEquals(30, prefs.copy(paceRepeatSeconds = n).normalized().paceRepeatSeconds)
        assertNull(prefs.copy(cadenceTargetSpm = 0).normalized().cadenceTargetSpm)
        assertEquals(.5f, prefs.copy(paceAlarmVolume = Float.NaN).normalized().paceAlarmVolume)
    }
    @Test fun `normal range inclusive boundaries stay silent`() {
        for (pace in listOf(350.0, 360.0, 370.0)) {
            val f = PaceFeedback(); f.sample(0, pace); val state = f.sample(30_000, pace)
            assertNull(state.alarm); assertFalse(state.voiceDue); assertNull(state.direction)
        }
    }
    @Test fun `alarm starts immediately and voice needs ten independent of GPS update frequency`() {
        val f = PaceFeedback()
        assertEquals(PaceDirection.FAST, f.sample(8000).alarm)
        assertEquals(PaceDirection.FAST, f.sample(9000, gpsAt = 8000).alarm)
        assertEquals(PaceDirection.FAST, f.sample(10_000, gpsAt = 8000).alarm)
        assertFalse(f.state.voiceDue)
        assertTrue(f.sample(18_000, gpsAt = 8000).voiceDue)
        assertNull(f.sample(18_001, gpsAt = 8000).direction)
    }
    @Test fun `repeat interval starts at spoken time and failed playback does not advance it`() {
        for (interval in listOf(10, 30, 120)) {
            val f = PaceFeedback(); val p = prefs.copy(paceRepeatSeconds = interval)
            f.sample(0, settings = p); assertTrue(f.sample(10_000, settings = p).voiceDue)
            assertTrue(f.sample(11_000, settings = p).voiceDue)
            f.spoken(12_000)
            assertFalse(f.sample(12_000 + interval * 1000L - 1, settings = p).voiceDue)
            assertTrue(f.sample(12_000 + interval * 1000L, settings = p).voiceDue)
        }
    }
    @Test fun `voice and alarm have four independent combinations`() {
        for (voice in listOf(false, true)) for (alarm in listOf(false, true)) {
            val f = PaceFeedback(); val p = prefs.copy(paceVoice = voice, paceAlarm = alarm)
            f.sample(0, settings = p); val s = f.sample(10_000, settings = p)
            assertEquals(voice, s.voiceDue); assertEquals(alarm, s.alarm != null)
        }
        val f = PaceFeedback(); f.sample(0, settings = prefs.copy(voice = false))
        assertEquals(PaceDirection.FAST, f.sample(10_000, settings = prefs.copy(voice = false)).alarm)
        assertFalse(f.state.voiceDue)
    }
    @Test fun `every out of tolerance value enters and inclusive recovery stops immediately`() {
        val f = PaceFeedback(); f.sample(0); assertNotNull(f.sample(2000).alarm)
        assertNull(f.sample(3000, 350.0).alarm)
        for (t in 4..15) assertEquals(PaceDirection.FAST, f.sample(t * 1000L, 349.0).alarm)
        f.sample(16_000, 347.0); assertEquals(PaceDirection.FAST, f.sample(18_000, 347.0).alarm)
    }
    @Test fun `direction reversal alarms immediately but voice renews sustained duration`() {
        val f = PaceFeedback(); f.sample(0); assertEquals(PaceDirection.FAST, f.sample(2000).alarm)
        assertEquals(PaceDirection.SLOW, f.sample(3000, 400.0).alarm)
        assertEquals(PaceDirection.SLOW, f.sample(5000, 400.0).alarm)
        assertFalse(f.sample(12_000, 400.0).voiceDue)
        assertTrue(f.sample(13_000, 400.0).voiceDue)
    }
    @Test fun `invalid pace GPS no target and stopped status immediately cancel`() {
        for (pace in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0, 3601.0)) {
            val f = PaceFeedback(); f.sample(0); f.sample(2000)
            assertNull(f.sample(3000, pace).alarm)
        }
        for (scenario in 0..6) {
            val f = PaceFeedback(); f.sample(0); f.sample(2000)
            val s = when (scenario) {
                0 -> f.sample(3000, target = null)
                1 -> f.sample(3000, gpsAt = null)
                2 -> f.sample(13_001, gpsAt = 3000)
                3 -> f.sample(3000, gpsAt = 4000)
                4 -> f.sample(3000, running = false)
                5 -> f.sample(3000, target = 119)
                else -> f.sample(3000, target = 1801)
            }
            assertNull(s.alarm); assertFalse(s.voiceDue)
        }
    }
    @Test fun `reset starts a new phase and settings apply immediately`() {
        val f = PaceFeedback(); f.sample(0); f.sample(10_000); f.spoken(10_000)
        f.reset(); assertFalse(f.sample(11_000).voiceDue)
        assertTrue(f.sample(21_000).voiceDue)
        assertNull(f.sample(22_000, settings = prefs.copy(paceAlarm = false)).alarm)
        assertFalse(f.sample(23_000, settings = prefs.copy(paceVoice = false)).voiceDue)
    }
    @Test fun `out of order evaluation cannot replay a previous state`() {
        val f = PaceFeedback(); f.sample(5000, 360.0)
        assertNull(f.sample(4000).direction)
    }
}
