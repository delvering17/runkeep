package com.runkeep.core

import kotlin.math.*
import kotlin.test.*

/** Deterministic geometry; no phone/GNSS accuracy claim. Both runs ingest identical positions. */
class ProviderPaceEngineTest {
    private val speed = 1000.0 / 360
    private val radiusEarth = 111_195.0802335329
    private fun coordinate(x: Double, y: Double = 0.0) = Coordinate(y / radiusEarth, x / radiusEarth)
    private fun engine(goal: Double? = null, autoPause: Boolean = false) =
        RunEngine(RunSummary(id = "comparison", startedAt = 0, goalMeters = goal), Preferences(autoPause = autoPause)).also { it.tick(0, 0) }
    private fun fix(at: Long, point: Coordinate, uncertainty: Float? = .05f, v: Float = speed.toFloat(), accuracy: Float = 3f) =
        LocationFix(point, at, at, accuracy, speed = v, speedAccuracy = uncertainty)

    @Test fun `straight corners and curves improve only runtime pace and preserve every stored field`() {
        val paths = linkedMapOf<String, (Double) -> Coordinate>(
            "straight" to { t -> coordinate(speed * t) },
            "corner90" to { t -> coordinate(speed * min(t, 60.5), speed * max(0.0, t - 60.5)) },
            "turn180" to { t -> coordinate(if (t <= 60.5) speed * t else speed * (121 - t)) }
        )
        for (radius in listOf(2.0, 5.0, 20.0)) paths["curve$radius"] = { t ->
            val angle = speed * t / radius
            coordinate(radius * sin(angle), radius * (1 - cos(angle)))
        }
        println("PROVIDER_PACE_COMPARE=path,periodMs,truthMeters,storedMeters,baselineMaxError,newMaxError,baselineValid,candidateValid")
        for ((name, path) in paths) for (interval in listOf(500L, 1000L, 2000L, 4000L)) {
            val baseline = engine(goal = 100.0)
            val candidate = engine(goal = 100.0)
            var oldError = 0.0; var newError = 0.0; var oldValid = 0; var newValid = 0
            for (at in 0L..120_000L step interval) {
                val sample = fix(at, path(at / 1000.0))
                baseline.ingest(sample.copy(speedAccuracy = null))
                candidate.ingest(sample)
                assertEquals(baseline.summary, candidate.summary, "$name at $at")
                assertEquals(baseline.points, candidate.points, "$name at $at")
                if (at >= 20_000) {
                    baseline.currentPace?.let { oldError = max(oldError, abs(it - 360)); oldValid++ }
                    candidate.currentPace?.let { newError = max(newError, abs(it - 360)); newValid++ }
                }
            }
            if (baseline.currentPace == null) {
                assertNull(candidate.currentPace, "Coordinate movement preparation is preserved")
                assertFalse(candidate.providerPaceUsed)
            } else {
                assertTrue(candidate.providerPaceUsed, "$name at interval $interval")
                assertTrue(newError < .001, "$name: $newError")
                if (name.startsWith("curve")) assertTrue(oldError > newError + .001)
            }
            println("PROVIDER_PACE_COMPARE=$name,$interval,${speed * 120},${candidate.summary.meters},${oldError.takeIf { oldValid > 0 } ?: "N/A"},${newError.takeIf { newValid > 0 } ?: "N/A"},$oldValid,$newValid")
        }
    }

    @Test fun `unknown negative nonfinite and poor uncertainty retain exact coordinate fallback`() {
        for (uncertainty in listOf(null, -.1f, Float.NaN, Float.POSITIVE_INFINITY, .31f, .29f)) {
            val baseline = engine(); val candidate = engine()
            for (second in 0..40) {
                // 1m/s makes .29m/s fail the relative 10 percent gate.
                val sample = fix(second * 1000L, coordinate(second.toDouble()), uncertainty, 1f)
                baseline.ingest(sample.copy(speedAccuracy = null)); candidate.ingest(sample)
                assertEquals(baseline.currentPace, candidate.currentPace)
                assertEquals(baseline.summary, candidate.summary)
            }
            assertFalse(candidate.providerPaceUsed)
        }
    }

    @Test fun `weighted integration handles irregular periods and an actual sustained slowdown`() {
        val e = engine()
        var at = 0L; var x = 0.0
        val periods = listOf(500L, 1500L, 4000L, 1000L, 3000L)
        var previousSpeed = speed
        repeat(60) { index ->
            val currentSpeed = if (index < 30) speed else 2.0
            if (index > 0) { val elapsed = periods[(index - 1) % periods.size]; at += elapsed; x += (previousSpeed + currentSpeed) * .5 * elapsed / 1000 }
            e.ingest(fix(at, coordinate(x), v = currentSpeed.toFloat()))
            if (index == 29) assertEquals(360.0, e.currentPace!!, .001)
            previousSpeed = currentSpeed
        }
        assertEquals(500.0, e.currentPace!!, .001)
        assertTrue(e.providerPaceUsed)
    }

    @Test fun `invalid fix expiry gap and pause need fresh preparation and never bridge distance`() {
        val e = engine()
        for (second in 0..20) e.ingest(fix(second * 1000L, coordinate(second * speed)))
        assertTrue(e.providerPaceUsed)
        e.ingest(fix(21_000, coordinate(21 * speed), accuracy = 26f))
        assertNull(e.currentPace)
        for (second in 22..29) e.ingest(fix(second * 1000L, coordinate(second * speed)))
        assertFalse(e.providerPaceUsed)
        e.ingest(fix(30_000, coordinate(30 * speed))); assertTrue(e.providerPaceUsed)
        val beforeGap = e.summary.meters
        e.tick(40_001, 40_001); assertNull(e.currentPace)
        e.ingest(fix(46_000, coordinate(46 * speed)))
        assertEquals(beforeGap, e.summary.meters)
        assertNull(e.currentPace)
        for (second in 47..54) e.ingest(fix(second * 1000L, coordinate(second * speed)))
        assertTrue(e.providerPaceUsed)
        e.pause(); val pausedMeters = e.summary.meters
        e.resume(60_000); e.ingest(fix(60_000, coordinate(600.0)))
        assertEquals(pausedMeters, e.summary.meters); assertNull(e.currentPace)
        e.ingest(fix(61_000, coordinate(603.0)), receivedAtMs = 64_001)
        assertNull(e.currentPace); assertFalse(e.providerPaceUsed)
    }

    @Test fun `stationary sensor spike does not create distance or bypass movement preparation`() {
        val e = engine(autoPause = true)
        for (second in 0..20) e.ingest(fix(second * 1000L, coordinate(0.0), v = if (second == 10) 3f else 0f))
        assertEquals(0.0, e.summary.meters)
        assertNull(e.currentPace)
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
    }
    @Test fun `quality dropout returns to coordinate and requires eight seconds before provider reentry`() {
        val e = engine()
        for (second in 0..20) e.ingest(fix(second * 1000L, coordinate(second * speed)))
        e.ingest(fix(21_000, coordinate(21 * speed), uncertainty = null))
        assertFalse(e.providerPaceUsed); assertEquals(360.0, e.currentPace!!, .001)
        for (second in 22..29) {
            e.ingest(fix(second * 1000L, coordinate(second * speed)))
            assertFalse(e.providerPaceUsed)
        }
        e.ingest(fix(30_000, coordinate(30 * speed)))
        assertTrue(e.providerPaceUsed)
        e.ingest(fix(31_000, coordinate(1000.0)))
        assertNull(e.currentPace)
        assertFalse(e.providerPaceUsed)
        for (second in 32..39) {
            e.ingest(fix(second * 1000L, coordinate(second * speed)))
            assertFalse(e.providerPaceUsed)
        }
        e.ingest(fix(40_000, coordinate(40 * speed))); assertTrue(e.providerPaceUsed)
    }

    @Test fun `invalid speeds fall back and even a certified biased speed cannot change recorded truth`() {
        for (v in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f, 0f, 12.01f)) {
            val old = engine(); val e = engine()
            for (second in 0..30) {
                val sample = fix(second * 1000L, coordinate(second * speed), v = v)
                old.ingest(sample.copy(speedAccuracy = null)); e.ingest(sample)
                assertEquals(old.summary, e.summary); assertEquals(old.points, e.points)
                assertEquals(old.currentPace, e.currentPace)
            }
            assertFalse(e.providerPaceUsed)
        }
        val old = engine(); val biased = engine()
        for (second in 0..40) {
            val sample = fix(second * 1000L, coordinate(second * speed), v = 3.5f)
            old.ingest(sample.copy(speedAccuracy = null)); biased.ingest(sample)
        }
        assertEquals(old.summary, biased.summary); assertEquals(old.points, biased.points)
        assertEquals(1000.0 / 3.5, biased.currentPace!!, .001)
        assertTrue(abs(biased.currentPace!! - 360) > 70) // The API's uncertainty is not a guarantee.
    }

    @Test fun `received age and sample gap boundaries retain the original distance rules`() {
        for (age in listOf(2999L, 3000L, 3001L)) {
            val e = engine()
            val point = e.ingest(fix(10_000, coordinate(0.0)), receivedAtMs = 10_000 + age)
            if (age <= 3000) assertNotNull(point) else assertNull(point)
        }
        for (gap in listOf(10_000L, 10_001L, 15_000L, 15_001L)) {
            val old = engine(); val e = engine()
            for (second in 0..20) {
                val sample = fix(second * 1000L, coordinate(second * speed))
                old.ingest(sample.copy(speedAccuracy = null)); e.ingest(sample)
            }
            val before = e.summary.meters
            val at = 20_000 + gap
            val sample = fix(at, coordinate(at / 1000.0 * speed))
            old.ingest(sample.copy(speedAccuracy = null)); e.ingest(sample)
            assertEquals(old.summary, e.summary); assertEquals(old.points, e.points)
            if (gap <= 15_000) assertTrue(e.summary.meters > before) else assertEquals(before, e.summary.meters)
            if (gap > 10_000) { assertNull(e.currentPace); assertFalse(e.providerPaceUsed) }
        }
    }

    @Test fun `quality speed preserves outward return waypoint and distance progress plus goal boundary`() {
        for (mode in CoursePaceTransition.entries) {
            val course = Course(id = "course", name = "return", points = listOf(coordinate(0.0), coordinate(50.0), coordinate(0.0)), paceTransition = mode)
                .withPace(0, 360).withPace(1, 345)
            val summary = RunSummary(id = "course-run", startedAt = 0, course = course, coursePaceEnabled = true, goalMeters = 80.0)
            val old = RunEngine(summary, Preferences(autoPause = false)).also { it.tick(0, 0) }
            val e = RunEngine(summary, Preferences(autoPause = false)).also { it.tick(0, 0) }
            for (second in 0..40) {
                val meters = if (second <= 20) second * 2.5 else (40 - second) * 2.5
                val sample = fix(second * 1000L, coordinate(meters), v = 2.5f)
                old.ingest(sample.copy(speedAccuracy = null)); e.ingest(sample)
                assertEquals(old.summary, e.summary); assertEquals(old.points, e.points)
            }
            assertTrue(e.summary.coursePaceComplete)
            assertNotNull(e.summary.goalEpochMs)
        }
    }

}
