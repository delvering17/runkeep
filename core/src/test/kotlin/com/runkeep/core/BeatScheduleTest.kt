package com.runkeep.core

import kotlin.test.*
import kotlin.math.roundToLong

class BeatScheduleTest {
    @Test fun `all metronome alarm cadence combinations share one beat`() {
        for (alarm in listOf(null, PaceDirection.FAST, PaceDirection.SLOW))
            for (metronome in listOf(false, true)) for (cadence in listOf(null, 120, 180, 185, 220)) {
                val clock = BeatSchedule(); clock.configure(0, alarm, cadence, metronome)
                val expected = when (alarm) {
                    PaceDirection.FAST -> BeatSound.FAST
                    PaceDirection.SLOW -> BeatSound.SLOW
                    null -> if (metronome) BeatSound.CLICK else null
                }
                assertEquals(expected, clock.poll(0))
                assertNull(clock.poll(0))
                assertEquals(expected != null, clock.nextAt != null)
                if (expected != null) {
                    val next = (60_000.0 / (cadence ?: 180)).roundToLong()
                    assertEquals(next, clock.nextAt)
                    assertNull(clock.poll(next - 1)); assertEquals(expected, clock.poll(next)); assertNull(clock.poll(next))
                }
            }
    }
    @Test fun `fractional beat period stays anchored over a minute`() {
        for (cadence in listOf(180, 185)) {
            val clock = BeatSchedule(); clock.configure(0, null, cadence, metronome = true)
            var count = 0
            for (ms in 0L until 60_000L) if (clock.poll(ms) != null) count++
            assertEquals(cadence, count); assertEquals(60_000L, clock.nextAt)
        }
    }
    @Test fun `late callbacks skip missed beats without bursts`() {
        val clock = BeatSchedule(); clock.configure(0, PaceDirection.SLOW, 120)
        assertNotNull(clock.poll(0)); assertNotNull(clock.poll(2600))
        assertEquals(3000L, clock.nextAt); assertNull(clock.poll(2600)); assertNull(clock.poll(2999))
    }
    @Test fun `alarm replaces click without resetting phase and stopping cancels immediately`() {
        val clock = BeatSchedule(); clock.configure(0, null, 180, metronome = true); clock.poll(0)
        clock.configure(100, PaceDirection.FAST, 180, metronome = true)
        assertNull(clock.poll(100)); assertEquals(BeatSound.FAST, clock.poll(333))
        clock.configure(400, PaceDirection.SLOW, 180, metronome = true)
        assertNull(clock.poll(400)); assertEquals(BeatSound.SLOW, clock.poll(667))
        clock.configure(700, null, 180, metronome = true)
        assertNull(clock.poll(700)); assertEquals(BeatSound.CLICK, clock.poll(1000))
        clock.configure(1100, null, 180, metronome = false); assertNull(clock.poll(1333)); assertNull(clock.nextAt)
    }
    @Test fun `cadence changes reanchor and invalid cadence uses default`() {
        val clock = BeatSchedule(); clock.configure(0, PaceDirection.FAST, 120); clock.poll(0)
        clock.configure(100, PaceDirection.FAST, 220); assertNotNull(clock.poll(100)); assertEquals(373L, clock.nextAt)
        clock.configure(200, PaceDirection.FAST, -1); assertNotNull(clock.poll(200)); assertEquals(533L, clock.nextAt)
    }

    @Test fun `185 cadence rounds anchored deadlines without accumulating a rounded period`() {
        val clock = BeatSchedule(); clock.configure(9000, null, 185, metronome = true)
        assertEquals(BeatSound.CLICK, clock.poll(9000))
        for (deadline in listOf(9324L, 9649L, 9973L, 10297L, 10622L)) {
            assertEquals(deadline, clock.nextAt)
            assertNull(clock.poll(deadline - 1))
            assertEquals(BeatSound.CLICK, clock.poll(deadline))
            assertNull(clock.poll(deadline))
        }
    }

    @Test fun `delayed callback on a rounded down beat never repeats that beat`() {
        val clock = BeatSchedule(); clock.configure(0, null, 185, metronome = true)
        assertEquals(BeatSound.CLICK, clock.poll(0))
        assertEquals(BeatSound.CLICK, clock.poll(1297))
        assertEquals(1622L, clock.nextAt)
        assertNull(clock.poll(1297))
    }

    @Test fun `long jitter and callback stalls preserve the original phase without bursts`() {
        for (cadence in listOf(120, 180, 185, 220)) {
            val clock = BeatSchedule(); val origin = 42_123L
            clock.configure(origin, null, cadence, metronome = true)
            var now = origin
            val delays = listOf(1L, 31L, 337L, 3L, 2000L, 10_003L, 47L)
            repeat(10_000) { step ->
                clock.poll(now)
                assertNull(clock.poll(now))
                val next = assertNotNull(clock.nextAt)
                assertTrue(next > now)
                assertTrue(next - now <= (60_000.0 / cadence).roundToLong() + 1)
                val beatNumber = ((next - origin) * cadence / 60_000.0).roundToLong()
                assertEquals(origin + (beatNumber * 60_000.0 / cadence).roundToLong(), next)
                now += delays[step % delays.size]
            }
        }
    }

    @Test fun `invalid cadence falls back and stop starts a fresh monotonic anchor`() {
        for (cadence in listOf(null, 119, 221, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val clock = BeatSchedule(); clock.configure(1000, null, cadence, metronome = true)
            assertEquals(BeatSound.CLICK, clock.poll(1000)); assertEquals(1333L, clock.nextAt)
            clock.stop(); assertNull(clock.nextAt); assertNull(clock.poll(50_000))
            clock.configure(60_000, null, 185, metronome = true)
            assertEquals(BeatSound.CLICK, clock.poll(60_000)); assertEquals(60_324L, clock.nextAt)
        }
    }
}
