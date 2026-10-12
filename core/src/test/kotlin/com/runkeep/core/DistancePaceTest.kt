package com.runkeep.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DistancePaceTest {
    private fun tracker(interval: Int = 100, meters: Double = 0.0) = DistancePaceTracker(meters).apply {
        configure(enabled = true, intervalMeters = interval, meters = meters)
    }

    @Test fun `policy accepts range endpoints and presets and defaults invalid intervals`() {
        assertEquals(10..10_000, DistancePacePolicy.RANGE)
        assertEquals(100, DistancePacePolicy.DEFAULT_INTERVAL_METERS)
        for (interval in listOf(10, 20, 100, 200, 1000, 10_000)) {
            assertEquals(interval, DistancePacePolicy.normalizeInterval(interval))
        }
        for (interval in listOf(Int.MIN_VALUE, -1, 0, 9, 10_001, Int.MAX_VALUE)) {
            assertEquals(100, DistancePacePolicy.normalizeInterval(interval))
        }
    }

    @Test fun `distance pace is disabled by default`() {
        val tracker = DistancePaceTracker()
        assertFalse(tracker.observe(100.0, valid = true, running = true))
        assertFalse(tracker.observe(1000.0, valid = true, running = true))
    }

    @Test fun `presets and maximum trigger exactly at each boundary once`() {
        for (interval in listOf(10, 20, 100, 200, 1000, 10_000)) {
            val tracker = tracker(interval)
            assertFalse(tracker.observe(interval - 0.001, valid = true, running = true))
            assertTrue(tracker.observe(interval.toDouble(), valid = true, running = true))
            assertFalse(tracker.observe(interval.toDouble(), valid = true, running = true))
            assertFalse(tracker.observe(interval * 2.0 - 0.001, valid = true, running = true))
            assertTrue(tracker.observe(interval * 2.0, valid = true, running = true))
        }
    }

    @Test fun `one observation crossing many boundaries returns one event and skips backlog`() {
        val tracker = tracker(10)
        assertTrue(tracker.observe(105.0, valid = true, running = true))
        assertFalse(tracker.observe(105.0, valid = true, running = true))
        assertFalse(tracker.observe(109.0, valid = true, running = true))
        assertTrue(tracker.observe(110.0, valid = true, running = true))
    }

    @Test fun `unchanged configuration preserves the pending boundary`() {
        val tracker = tracker()
        assertFalse(tracker.observe(99.0, valid = true, running = true))
        tracker.configure(enabled = true, intervalMeters = 100, meters = 100.0)
        assertTrue(tracker.observe(100.0, valid = true, running = true))
        tracker.configure(enabled = true, intervalMeters = Int.MAX_VALUE, meters = 200.0)
        assertTrue(tracker.observe(200.0, valid = true, running = true))
    }

    @Test fun `enable and interval changes start at strictly future multiples`() {
        val tracker = DistancePaceTracker()
        tracker.configure(enabled = true, intervalMeters = 100, meters = 350.0)
        assertFalse(tracker.observe(350.0, valid = true, running = true))
        assertTrue(tracker.observe(400.0, valid = true, running = true))
        tracker.configure(enabled = true, intervalMeters = 20, meters = 400.0)
        assertFalse(tracker.observe(400.0, valid = true, running = true))
        assertFalse(tracker.observe(419.0, valid = true, running = true))
        assertTrue(tracker.observe(420.0, valid = true, running = true))
        tracker.configure(enabled = false, intervalMeters = 20, meters = 420.0)
        assertFalse(tracker.observe(600.0, valid = true, running = true))
        tracker.configure(enabled = true, intervalMeters = 20, meters = 600.0)
        assertFalse(tracker.observe(600.0, valid = true, running = true))
        assertTrue(tracker.observe(620.0, valid = true, running = true))
    }

    @Test fun `invalid pace crossing consumes boundaries without recovery catchup`() {
        val tracker = tracker()
        assertFalse(tracker.observe(250.0, valid = false, running = true))
        assertFalse(tracker.observe(250.0, valid = true, running = true))
        assertFalse(tracker.observe(299.0, valid = true, running = true))
        assertTrue(tracker.observe(300.0, valid = true, running = true))
    }

    @Test fun `invalid pace before a boundary still allows a fresh valid crossing`() {
        val tracker = tracker()
        assertFalse(tracker.observe(99.0, valid = false, running = true))
        assertTrue(tracker.observe(100.0, valid = true, running = true))
    }

    @Test fun `paused crossings and resume drift are skipped`() {
        val tracker = tracker()
        assertFalse(tracker.observe(90.0, valid = true, running = true))
        assertFalse(tracker.observe(250.0, valid = true, running = false))
        assertFalse(tracker.observe(250.0, valid = true, running = true))
        tracker.resetBoundary(300.0)
        assertFalse(tracker.observe(300.0, valid = true, running = true))
        assertTrue(tracker.observe(400.0, valid = true, running = true))
    }

    @Test fun `explicit reset and distance regression reanchor without announcements`() {
        val tracker = tracker(meters = 100.0)
        assertFalse(tracker.observe(100.0, valid = true, running = true))
        assertTrue(tracker.observe(200.0, valid = true, running = true))
        assertFalse(tracker.observe(10.0, valid = true, running = true))
        assertTrue(tracker.observe(100.0, valid = true, running = true))
        tracker.resetBoundary(0.0)
        assertFalse(tracker.observe(0.0, valid = true, running = true))
        assertTrue(tracker.observe(100.0, valid = true, running = true))
    }

    @Test fun `unusable distances suppress announcements and reanchor on recovery`() {
        for (meters in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1.0)) {
            val tracker = tracker()
            assertFalse(tracker.observe(meters, valid = true, running = true))
            assertFalse(tracker.observe(150.0, valid = true, running = true))
            assertTrue(tracker.observe(200.0, valid = true, running = true))
            tracker.resetBoundary(meters)
            assertFalse(tracker.observe(300.0, valid = true, running = true))
            assertTrue(tracker.observe(400.0, valid = true, running = true))
            val initial = DistancePaceTracker(meters)
            initial.configure(enabled = true, intervalMeters = 100, meters = meters)
            assertFalse(initial.observe(100.0, valid = true, running = true))
            assertTrue(initial.observe(200.0, valid = true, running = true))
        }
    }

    @Test fun `large distances have no integer overflow or repeated boundary events`() {
        val tracker = tracker(10)
        val beyondInteger = Int.MAX_VALUE.toDouble() * 10 + 5
        assertTrue(tracker.observe(beyondInteger, valid = true, running = true))
        assertFalse(tracker.observe(beyondInteger, valid = true, running = true))
        assertTrue(tracker.observe(beyondInteger + 5, valid = true, running = true))
        val enormous = 1e300
        tracker.resetBoundary(enormous)
        assertFalse(tracker.observe(enormous, valid = true, running = true))
        assertTrue(tracker.observe(Math.nextUp(enormous), valid = true, running = true))
        assertFalse(tracker.observe(Math.nextUp(enormous), valid = true, running = true))
        tracker.resetBoundary(Double.MAX_VALUE)
        assertFalse(tracker.observe(Double.MAX_VALUE, valid = true, running = true))
    }

    @Test fun `rounding cannot announce before an unrepresentable future multiple`() {
        val meters = 9_007_199_254_740_992.0
        val tracker = tracker(15, meters)
        assertFalse(tracker.observe(meters + 12, valid = true, running = true))
        assertTrue(tracker.observe(meters + 14, valid = true, running = true))
        assertFalse(tracker.observe(meters + 14, valid = true, running = true))
    }
}
