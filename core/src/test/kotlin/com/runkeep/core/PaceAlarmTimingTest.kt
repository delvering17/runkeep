package com.runkeep.core

import kotlin.math.abs
import kotlin.test.*

class PaceAlarmTimingTest {
    private val prefs = Preferences(paceAlarm = true)

    private class PreviousAlarmPolicy {
        private var direction: PaceDirection? = null
        private var since: Long? = null

        fun evaluate(now: Long, pace: Double, tolerance: Int = 10): PaceDirection? {
            val error = pace - 360
            val candidate = when {
                error < -tolerance -> PaceDirection.FAST
                error > tolerance -> PaceDirection.SLOW
                else -> null
            }
            val next = candidate?.takeIf { it == direction || abs(error) > tolerance + 2 }
            if (next != direction || since == null) since = next?.let { now }
            direction = next
            return next?.takeIf { since?.let { now - it >= 2_000 } == true }
        }
    }

    private fun firstAlarm(paces: List<Double>, previous: Boolean): Long? {
        val old = PreviousAlarmPolicy()
        val current = PaceFeedback()
        return paces.withIndex().firstOrNull { (second, pace) ->
            val now = second * 1000L
            val alarm = if (previous) old.evaluate(now, pace) else
                current.evaluate(now, true, pace, 360, now, prefs).alarm
            alarm != null
        }?.index?.times(1000L)
    }

    @Test fun `same sustained deviation alarms at zero instead of two seconds`() {
        val samples = List(11) { 330.0 }
        assertEquals(2_000L, firstAlarm(samples, previous = true))
        assertEquals(0L, firstAlarm(samples, previous = false))
    }

    @Test fun `same one second per kilometer boundary deviation now alarms immediately`() {
        val samples = List(11) { 349.0 }
        assertNull(firstAlarm(samples, previous = true))
        assertEquals(0L, firstAlarm(samples, previous = false))
    }

    @Test fun `same reversal changes alarm immediately instead of two seconds later`() {
        val old = PreviousAlarmPolicy()
        val current = PaceFeedback()
        val samples = listOf(330.0, 330.0, 330.0, 400.0, 400.0, 400.0)
        val before = samples.mapIndexed { second, pace -> old.evaluate(second * 1000L, pace) }
        val after = samples.mapIndexed { second, pace ->
            val now = second * 1000L
            current.evaluate(now, true, pace, 360, now, prefs).alarm
        }
        assertEquals(listOf(null, null, PaceDirection.FAST, null, null, PaceDirection.SLOW), before)
        assertEquals(listOf(PaceDirection.FAST, PaceDirection.FAST, PaceDirection.FAST,
            PaceDirection.SLOW, PaceDirection.SLOW, PaceDirection.SLOW), after)
        assertFalse(current.state.voiceDue)
        assertFalse(current.evaluate(12_999, true, 400.0, 360, 12_999, prefs).voiceDue)
        assertTrue(current.evaluate(13_000, true, 400.0, 360, 13_000, prefs).voiceDue)
    }

    @Test fun `inclusive boundaries and live tolerance stop and restart alarms on the same evaluation`() {
        for ((outside, boundary, direction) in listOf(
            Triple(349.999, 350.0, PaceDirection.FAST), Triple(370.001, 370.0, PaceDirection.SLOW))) {
            val f = PaceFeedback()
            assertEquals(direction, f.evaluate(0, true, outside, 360, 0, prefs).alarm)
            assertNull(f.evaluate(1, true, boundary, 360, 1, prefs).alarm)
            assertEquals(direction, f.evaluate(2, true, outside, 360, 2, prefs).alarm)
            assertFalse(f.evaluate(10_001, true, outside, 360, 10_001, prefs).voiceDue)
            assertTrue(f.evaluate(10_002, true, outside, 360, 10_002, prefs).voiceDue)
            assertNull(f.evaluate(10_003, true, outside, 360, 10_003, prefs.copy(paceTolerance = 11)).alarm)
            assertEquals(direction, f.evaluate(10_004, true, outside, 360, 10_004, prefs).alarm)
            assertFalse(f.state.voiceDue)
        }
    }
}
