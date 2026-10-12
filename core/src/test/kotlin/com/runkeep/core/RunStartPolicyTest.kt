package com.runkeep.core

import kotlin.test.*

class RunStartPolicyTest {
    private fun request(id: String = "synthetic") = RunSummary(id = id, startedAt = 0, goalMeters = 1000.0,
        plan = WorkoutPlan(name = "합성 계획", steps = listOf(PlanStep(kind = StepKind.DISTANCE, amount = 1000.0, paceSeconds = 360))))

    @Test fun `zero starts immediately and one and sixty start only at the monotonic deadline`() {
        for (seconds in listOf(0, 1, 60)) {
            val policy = RunStartPolicy()
            val input = request()
            assertTrue(policy.prepare(input, 10_000, seconds, canStart = true))
            assertEquals(seconds, policy.preparation(10_000)?.remainingSeconds)
            if (seconds > 0) {
                assertNull(policy.takeReady(input.id, 10_000 + seconds * 1000L - 1, 1, true))
                assertEquals(1, policy.preparation(10_000 + seconds * 1000L - 1)?.remainingSeconds)
            }
            val started = assertNotNull(policy.takeReady(input.id, 10_000 + seconds * 1000L, 200_000, true))
            assertEquals(200_000L, started.startedAt)
            assertEquals(input.id, started.id)
            assertEquals(input.plan, started.plan)
            assertNull(policy.preparation(100_000))
            assertNull(policy.takeReady(input.id, 100_000, 300_000, true))
        }
    }

    @Test fun `delay boundary normalization and legacy and saved preferences remain compatible`() {
        assertEquals(0, codec.decodeFromString<Preferences>("{}").normalized().startDelaySeconds)
        for (seconds in 0..60) {
            val saved = codec.decodeFromString<Preferences>(codec.encodeToString(Preferences.serializer(),
                Preferences(startDelaySeconds = seconds))).normalized()
            assertEquals(seconds, saved.startDelaySeconds)
        }
        for (seconds in listOf(Int.MIN_VALUE, -1, 61, Int.MAX_VALUE)) {
            assertEquals(0, Preferences(startDelaySeconds = seconds).normalized().startDelaySeconds)
        }
        assertEquals(1, RunStartPolicy.parseDelay("1"))
        assertEquals(60, RunStartPolicy.parseDelay("60"))
        for (text in listOf("", "0", "61", "-1", "1.0", "1:00", "1e1", "1초", " 5", "2147483648"))
            assertNull(RunStartPolicy.parseDelay(text))
    }

    @Test fun `duplicate taps cannot replace a pending start or renew its deadline`() {
        val policy = RunStartPolicy()
        assertTrue(policy.prepare(request(), 100, 5, true))
        assertFalse(policy.prepare(request(), 4000, 60, true))
        assertFalse(policy.prepare(request("other"), 4000, 60, true))
        assertEquals(1, policy.preparation(4100)?.remainingSeconds)
        assertNotNull(policy.takeReady("synthetic", 5100, 10_000, true))
        assertFalse(policy.prepare(request(), 10_000, 5, true))
    }

    @Test fun `cancel and late old callbacks cannot start a record or consume the next preparation`() {
        val policy = RunStartPolicy()
        assertTrue(policy.prepare(request("old"), 0, 1, true))
        assertTrue(policy.cancel())
        assertFalse(policy.cancel())
        assertNull(policy.takeReady("old", 60_000, 1, true))
        assertFalse(policy.prepare(request("old"), 60_000, 1, true))
        assertTrue(policy.prepare(request("new"), 60_000, 1, true))
        assertNull(policy.takeReady("old", 61_000, 1, true))
        assertNotNull(policy.takeReady("new", 61_000, 1, true))
    }

    @Test fun `missing permission before preparation and lost permission at the deadline create no run`() {
        val policy = RunStartPolicy()
        assertFalse(policy.prepare(request(), 0, 5, false))
        assertNull(policy.preparation(5000))
        assertTrue(policy.prepare(request(), 0, 5, true))
        assertNull(policy.takeReady("synthetic", 5000, 10_000, false))
        assertNull(policy.preparation(5000))
        assertNull(policy.takeReady("synthetic", 5000, 10_000, true))
    }

    @Test fun `repeated observations and background delay preserve the deadline without catch up records`() {
        val policy = RunStartPolicy()
        assertTrue(policy.prepare(request(), 10_000, 60, true))
        repeat(10) { assertEquals(RunPreparation("synthetic", 59), policy.preparation(11_001)) }
        assertEquals(999L, policy.untilNextUpdate(11_001))
        assertEquals(1000L, policy.untilNextUpdate(12_000))
        assertEquals(0L, policy.untilNextUpdate(80_000))
        val started = assertNotNull(policy.takeReady("synthetic", 80_000, 900_000, true))
        assertEquals(900_000L, started.startedAt)
        assertEquals(0L, started.activeMs)
        assertEquals(0.0, started.meters)
        assertNull(policy.takeReady("synthetic", 81_000, 901_000, true))
    }

    @Test fun `process recovery has no persisted preparation or ghost workout`() {
        val before = RunStartPolicy()
        assertTrue(before.prepare(request(), 0, 60, true))
        val recovered = RunStartPolicy()
        assertNull(recovered.preparation(61_000))
        assertNull(recovered.takeReady("synthetic", 61_000, 1, true))
    }

    @Test fun `actual engine clock and plan start after waiting and recovery does not create a start cue`() {
        val policy = RunStartPolicy()
        assertTrue(policy.prepare(request(), 1000, 60, true))
        val run = assertNotNull(policy.takeReady("synthetic", 61_000, 1_000_000, true))
        val engine = RunEngine(run, Preferences(autoPause = false, startDelaySeconds = 60))
        engine.tick(61_000, 1_000_000)
        assertEquals(0L, engine.summary.activeMs)
        assertEquals(0, engine.summary.planIndex)
        assertEquals(emptyList(), engine.points)
        engine.tick(62_000, 1_001_000)
        assertEquals(1000L, engine.summary.activeMs)
        engine.pause()
        engine.resume(80_000)
        engine.tick(81_000, 1_020_000)
        assertEquals(2000L, engine.summary.activeMs)
        engine.interrupt()
        val restored = RunEngine(engine.summary, Preferences(startDelaySeconds = 60), engine.points)
        restored.tick(200_000, 1_200_000)
        restored.resume(200_000)
        restored.tick(201_000, 1_201_000)
        assertEquals(3000L, restored.summary.activeMs)
        assertTrue(restored.announcements.none { it.kind == AnnouncementKind.WORKOUT_START })
    }

    @Test fun `new summary cannot inherit draft measurement or finished status`() {
        val policy = RunStartPolicy()
        val dirty = request().copy(startedAt = 1, endedAt = 2, activeMs = 60_000, meters = 3000.0,
            status = RunStatus.FINISHED, goalActiveMs = 10_000, planIndex = 1, segment = 9)
        assertTrue(policy.prepare(dirty, 0, 0, true))
        val started = assertNotNull(policy.takeReady(dirty.id, 0, 1000, true))
        assertEquals(RunStatus.RUNNING, started.status)
        assertNull(started.endedAt); assertNull(started.goalActiveMs)
        assertEquals(0L, started.activeMs); assertEquals(0.0, started.meters)
        assertEquals(0, started.planIndex); assertEquals(0, started.segment)
        assertFalse(RunStartPolicy.validRequest(request().copy(goalMeters = Double.NaN)))
        assertFalse(RunStartPolicy.validRequest(request().copy(plan = WorkoutPlan(name = "빈 계획", steps = emptyList()))))
    }
}
