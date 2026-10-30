package com.runkeep.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BeatOutputPolicyTest {
    @Test fun `normal metronome persists without an alarm validity window`() {
        val output = BeatOutputPolicy()
        output.update(1000, null, 185, 0, metronome = true)
        assertTrue(output.hasOutput(1000))
        assertEquals(BeatSound.CLICK, output.poll(1000))
        assertEquals(1324L, output.nextAt)
        assertEquals(BeatSound.CLICK, output.poll(61_000))
        assertEquals(61_324L, output.nextAt)
        assertNull(output.poll(61_000))
    }

    @Test fun `alarm and metronome produce one sound and expiry restores the same click phase`() {
        val output = BeatOutputPolicy()
        output.update(0, null, 180, 0, metronome = true)
        assertEquals(BeatSound.CLICK, output.poll(0))
        output.update(100, PaceDirection.FAST, 180, 500, metronome = true)
        assertNull(output.poll(100))
        assertEquals(BeatSound.FAST, output.poll(333)); assertNull(output.poll(333))
        assertTrue(output.hasOutput(501)); assertEquals(667L, output.nextAt)
        assertNull(output.poll(501))
        assertEquals(BeatSound.CLICK, output.poll(667)); assertNull(output.poll(667))
        output.update(700, PaceDirection.SLOW, 180, 1100, metronome = true)
        assertEquals(BeatSound.SLOW, output.poll(1000)); assertNull(output.poll(1000))
        assertEquals(BeatSound.CLICK, output.poll(1333)); assertNull(output.poll(1333))
    }

    @Test fun `expired alarm only output stops without subsequent gps updates`() {
        val output = BeatOutputPolicy()
        output.update(0, PaceDirection.SLOW, 120, 500)
        assertEquals(BeatSound.SLOW, output.poll(0))
        assertEquals(BeatSound.SLOW, output.poll(500))
        assertFalse(output.hasOutput(501)); assertNull(output.nextAt)
        assertNull(output.poll(10_000))
        output.update(10_100, PaceDirection.FAST, 120, 10_000)
        assertFalse(output.hasOutput(10_100)); assertNull(output.poll(10_100))
    }

    @Test fun `disabling metronome keeps alarm only and disabling both clears the clock`() {
        val output = BeatOutputPolicy()
        output.update(0, PaceDirection.FAST, 185, 3000, metronome = true)
        assertEquals(BeatSound.FAST, output.poll(0))
        output.update(100, PaceDirection.FAST, 185, 3000, metronome = false)
        assertNull(output.poll(100)); assertEquals(BeatSound.FAST, output.poll(324))
        output.update(400, null, 185, 3000, metronome = false)
        assertFalse(output.hasOutput(400)); assertNull(output.nextAt); assertNull(output.poll(649))
    }

    @Test fun `pause and finish stop every pending output and restart uses a fresh anchor`() {
        val output = BeatOutputPolicy()
        output.update(0, PaceDirection.SLOW, 220, 10_000, metronome = true)
        assertEquals(BeatSound.SLOW, output.poll(0))
        output.stop()
        assertFalse(output.hasOutput(100)); assertNull(output.nextAt); assertNull(output.poll(10_000))
        output.update(20_000, null, null, 0, metronome = true)
        assertEquals(BeatSound.CLICK, output.poll(20_000)); assertEquals(20_333L, output.nextAt)
        output.stop(); assertNull(output.poll(20_333)); assertFalse(output.hasOutput(60_000))
    }

    @Test fun `transient focus loss resumes fresh state and excludes an alarm expired while silent`() {
        val output = BeatOutputPolicy()
        output.update(0, PaceDirection.FAST, 185, 500, metronome = true)
        assertEquals(BeatSound.FAST, output.poll(0))
        output.interrupt(permanent = false)
        assertTrue(output.isSuspended); assertNull(output.nextAt); assertNull(output.poll(324))
        assertTrue(output.hasOutput(1000)); assertNull(output.poll(1000))
        output.focusGained()
        assertFalse(output.isSuspended)
        assertEquals(BeatSound.CLICK, output.poll(2000)); assertEquals(2324L, output.nextAt)
        assertNull(output.poll(2000))
    }

    @Test fun `permanent loss and noisy route require explicit resume even after gain or updates`() {
        val output = BeatOutputPolicy()
        output.update(0, null, 180, 0, metronome = true)
        assertEquals(BeatSound.CLICK, output.poll(0))
        output.interrupt(permanent = true)
        output.focusGained()
        output.update(1000, PaceDirection.SLOW, 185, 2000, metronome = true)
        assertTrue(output.requiresResume); assertNull(output.poll(1000)); assertNull(output.nextAt)
        output.stop()
        output.update(3000, null, 185, 0, metronome = true)
        assertNull(output.poll(3000)); assertTrue(output.requiresResume)
        output.allowResume()
        assertFalse(output.isSuspended)
        assertEquals(BeatSound.CLICK, output.poll(4000)); assertEquals(4324L, output.nextAt)
    }
}
