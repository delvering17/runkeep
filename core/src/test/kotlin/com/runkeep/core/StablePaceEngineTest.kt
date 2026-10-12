package com.runkeep.core

import kotlin.math.*
import kotlin.test.*

class StablePaceEngineTest {
    private val prefs = Preferences(autoPause = false, paceAlarm = true, paceTolerance = 5)
    private val defaultPlan = WorkoutPlan(id = "stable-target", name = "target", steps =
        listOf(PlanStep(kind = StepKind.DISTANCE, amount = 10000.0, paceSeconds = 360)))
    private fun engine(mode: PaceCoachingMode, plan: WorkoutPlan = defaultPlan) =
        RunEngine(RunSummary(id = "stable", startedAt = 0, plan = plan), prefs.copy(paceCoachingMode = mode)).also { it.tick(0, 0) }
    private fun fix(second: Int, meters: Double) = LocationFix(Coordinate(0.0, meters / 111_195.0802335329),
        second * 1000L, second * 1000L, 3f, speed = 3f)

    @Test fun `same actual engine boundary noise reduces starts without changing pace or recording`() {
        val immediate = engine(PaceCoachingMode.IMMEDIATE)
        val stable = engine(PaceCoachingMode.STABLE)
        var previousImmediate: PaceDirection? = null; var previousStable: PaceDirection? = null
        var immediateStarts = 0; var stableStarts = 0
        for (second in 0..240) {
            val sample = fix(second, second * 1000.0 / 360 + .6 * sin(second * 1.7))
            immediate.ingest(sample); stable.ingest(sample)
            assertEquals(immediate.currentPace, stable.currentPace)
            assertEquals(immediate.summary, stable.summary)
            if (second >= 30) {
                if (immediate.feedback.alarm != null && immediate.feedback.alarm != previousImmediate) immediateStarts++
                if (stable.feedback.alarm != null && stable.feedback.alarm != previousStable) stableStarts++
            }
            previousImmediate = immediate.feedback.alarm; previousStable = stable.feedback.alarm
        }
        assertTrue(immediateStarts > 20, "$immediateStarts")
        assertTrue(stableStarts < immediateStarts / 2, "$stableStarts vs $immediateStarts")
        println("STABLE_PACE_COMPARE=boundaryNoise,immediateStarts=$immediateStarts,stableStarts=$stableStarts")
    }

    @Test fun `timer evaluation alone cannot confirm entry and normal recovery is immediate`() {
        val f = PaceFeedback()
        val stablePrefs = prefs.copy(paceCoachingMode = PaceCoachingMode.STABLE)
        assertNull(f.evaluate(0, true, 366.0, 360, 0, stablePrefs).alarm)
        for (now in 1L..3000L step 100) assertNull(f.evaluate(now, true, 366.0, 360, 0, stablePrefs).alarm)
        assertNull(f.evaluate(4000, true, 366.0, 360, 4000, stablePrefs).alarm)
        assertEquals(PaceDirection.SLOW, f.evaluate(5000, true, 366.0, 360, 5000, stablePrefs).alarm)
        assertNull(f.evaluate(5001, true, 365.0, 360, 5000, stablePrefs).alarm)
        assertNull(f.evaluate(6000, true, 366.0, 360, 6000, stablePrefs).alarm)
    }

    @Test fun `large actual deviation and target changes bypass optional confirmation`() {
        val f = PaceFeedback()
        val settings = prefs.copy(paceCoachingMode = PaceCoachingMode.STABLE)
        assertEquals(PaceDirection.FAST, f.evaluate(0, true, 300.0, 360, 0, settings).alarm)
        assertEquals(PaceDirection.SLOW, f.evaluate(1, true, 367.0, 350, 1, settings).alarm)
        val plan = WorkoutPlan(name = "short", steps = listOf(
            PlanStep(kind = StepKind.TIME, amount = 20.0, paceSeconds = 360),
            PlanStep(kind = StepKind.TIME, amount = 10.0, paceSeconds = 350)))
        val e = engine(PaceCoachingMode.STABLE, plan)
        for (second in 0..20) e.ingest(fix(second, second * 1000.0 / 360))
        assertEquals(350, e.feedback.targetPace)
        assertEquals(PaceDirection.SLOW, e.feedback.alarm)
    }

    @Test fun `same short acceleration and slowing input reports bounded optional confirmation latency`() {
        val immediate = engine(PaceCoachingMode.IMMEDIATE); val stable = engine(PaceCoachingMode.STABLE)
        val oldAt = mutableListOf<Int>(); val stableAt = mutableListOf<Int>()
        var distance = 0.0; var before: PaceDirection? = null; var beforeStable: PaceDirection? = null
        for (second in 0..120) {
            val pace = when (second) { in 40..49 -> 330; in 50..59 -> 390; in 80..99 -> 300; else -> 360 }
            if (second > 0) distance += 1000.0 / pace
            val sample = fix(second, distance)
            immediate.ingest(sample); stable.ingest(sample)
            if (immediate.feedback.alarm != null && before != immediate.feedback.alarm) oldAt += second
            if (stable.feedback.alarm != null && beforeStable != stable.feedback.alarm) stableAt += second
            before = immediate.feedback.alarm; beforeStable = stable.feedback.alarm
        }
        assertTrue(oldAt.isNotEmpty()); assertTrue(stableAt.isNotEmpty())
        for (at in stableAt) assertTrue(oldAt.any { at - it in 0..2 }, "$stableAt vs $oldAt")
        println("STABLE_PACE_COMPARE=shortIntervals,immediate=$oldAt,stable=$stableAt")
    }
}
