package com.runkeep.core

import kotlin.test.*

class AutoPauseRegressionTest {
    private val origin = 1_700_000_000_000L
    private fun fix(s: Int, meters: Double = 0.0, speed: Float? = 0f, accuracy: Float = 3f) =
        LocationFix(Coordinate(meters / 111_195.0802335329, 127.0), origin + s * 1000, s * 1000L, accuracy, speed = speed)
    private fun paused(): RunEngine = RunEngine(RunSummary(startedAt = origin), Preferences(autoPause = true)).also {
        it.tick(0, origin)
        for (s in 0..9) it.ingest(fix(s))
        assertEquals(RunStatus.AUTO_PAUSED, it.summary.status)
        it.announcements.clear()
    }

    @Test fun `stationary speed spike must not resume or accumulate time and distance`() {
        val e = paused()
        val before = e.summary
        for (s in 10..60) e.ingest(fix(s, if (s % 10 == 0) 3.0 else 0.0, if (s % 10 == 0) 1.5f else 0f))
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        assertEquals(before.activeMs, e.summary.activeMs)
        assertEquals(before.meters, e.summary.meters)
        assertTrue(e.announcements.isEmpty())
    }

    @Test fun `missing speed coordinate drift must not resume`() {
        val e = paused()
        for (s in 10..60) e.ingest(fix(s, if (s % 2 == 0) 4.0 else -4.0, null, 6f))
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        assertEquals(0.0, e.summary.meters)
        assertTrue(e.announcements.isEmpty())
    }

    @Test fun `continuous walking resumes in three seconds without a paused bridge`() {
        val e = paused()
        val before = e.summary
        for (s in 10..11) {
            assertNull(e.ingest(fix(s, (s - 9) * 1.4, 1.4f)))
            assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        }
        val first = assertNotNull(e.ingest(fix(12, 4.2, 1.4f)))
        assertEquals(RunStatus.RUNNING, e.summary.status)
        val transition = assertNotNull(e.lastAutoPauseTransition)
        assertEquals("resume_confirmed", transition.reason)
        assertEquals(3, transition.movingLegs)
        assertEquals(3000, transition.elapsedMs)
        assertEquals(before.activeMs, first.activeMs)
        assertEquals(before.meters, first.meters)
        assertNotEquals(e.points[e.points.lastIndex - 1].segment, first.segment)
        e.ingest(fix(13, 5.6, 1.4f))
        assertEquals(1.4, e.summary.meters, 0.01)
        assertEquals(before.activeMs + 1000, e.summary.activeMs)
        assertEquals(1, e.announcements.count { it.speechText() == "달리기를 재개합니다" })
    }

    @Test fun `walking and running resume without speed or cadence`() {
        for (metersPerSecond in listOf(1.4, 3.0)) {
            val e = paused()
            for (s in 10..11) {
                e.ingest(fix(s, (s - 9) * metersPerSecond, null))
                assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
            }
            e.ingest(fix(12, 3 * metersPerSecond, null))
            assertEquals(RunStatus.RUNNING, e.summary.status)
            assertEquals(0.0, e.summary.meters)
            for (s in 13..40) e.ingest(fix(s, (s - 9) * metersPerSecond, null))
            assertEquals(RunStatus.RUNNING, e.summary.status)
            assertEquals(1, e.announcements.count { it.speechText() == "달리기를 재개합니다" })
        }
    }

    @Test fun `sustained reported speed with no coordinate progress is insufficient`() {
        val e = paused()
        for (s in 10..40) e.ingest(fix(s, 0.0, 3f))
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        assertEquals("insufficient_motion", e.autoPauseDiagnostics.reason)
    }

    @Test fun `direction reversing jitter with high speed stays paused`() {
        val e = paused()
        for (s in 10..40) e.ingest(fix(s, if (s % 2 == 0) 4.0 else -4.0, 2f))
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        assertEquals(0.0, e.summary.meters)
    }

    @Test fun `resume speed boundary jitter resets consecutive evidence`() {
        val e = paused()
        for (s in 10..40) e.ingest(fix(s, (s - 9) * 1.2, if (s % 3 == 0) 1.19f else 1.21f))
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        assertTrue(e.announcements.isEmpty())
    }

    @Test fun `single coordinate jump followed by stillness cannot confirm movement`() {
        val e = paused()
        e.ingest(fix(10, 10.0, null))
        for (s in 11..20) e.ingest(fix(s, 10.0, null))
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
    }

    @Test fun `poor accuracy interrupts confirmation and recovery needs fresh legs`() {
        for (accuracy in listOf(20f, 90f, Float.NaN, -1f)) {
            val e = paused()
            e.ingest(fix(10, 3.0, 3f)); e.ingest(fix(11, 6.0, 3f))
            e.ingest(fix(12, 9.0, 3f, accuracy))
            assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
            for (s in 13..15) {
                e.ingest(fix(s, (s - 9) * 3.0, 3f))
                assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
            }
            e.ingest(fix(16, 21.0, 3f))
            assertEquals(RunStatus.RUNNING, e.summary.status)
            assertEquals(0.0, e.summary.meters)
        }
    }

    @Test fun `accuracy deterioration within usable range resets movement evidence`() {
        val e = paused()
        e.ingest(fix(10, 3.0, 3f)); e.ingest(fix(11, 6.0, 3f))
        e.ingest(fix(12, 9.0, 3f, 10f))
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        assertEquals("accuracy_changed", e.autoPauseDiagnostics.reason)
        for (s in 13..15) e.ingest(fix(s, (s - 9) * 3.0, 3f, 10f))
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status) // 9m is still inside the 10m accuracy radius.
        e.ingest(fix(16, 21.0, 3f, 10f))
        assertEquals(RunStatus.RUNNING, e.summary.status)
    }

    @Test fun `GPS gap resets confirmation including after a timer expires`() {
        for (expireWithTimer in listOf(false, true)) {
            val e = paused()
            e.ingest(fix(10, 3.0, 3f)); e.ingest(fix(11, 6.0, 3f))
            if (expireWithTimer) e.tick(16_000, origin + 16_000)
            e.ingest(fix(17, 24.0, 3f))
            assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
            for (s in 18..19) {
                e.ingest(fix(s, (s - 9) * 3.0, 3f))
                assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
            }
            e.ingest(fix(20, 33.0, 3f))
            assertEquals(RunStatus.RUNNING, e.summary.status)
            assertEquals(8000, e.summary.activeMs)
            assertEquals(0.0, e.summary.meters)
        }
    }

    @Test fun `old cached moving fixes cannot resume and recovery starts a new anchor`() {
        val e = paused()
        e.ingest(fix(10, 3.0, 3f)); e.ingest(fix(11, 6.0, 3f))
        e.ingest(fix(12, 9.0, 3f), receivedAtMs = 20_000)
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        assertEquals("stale_fix", e.autoPauseDiagnostics.reason)
        for (s in 20..22) {
            e.ingest(fix(s, (s - 9) * 3.0, 3f), receivedAtMs = s * 1000L)
            assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        }
        e.ingest(fix(23, 42.0, 3f), receivedAtMs = 23_000)
        assertEquals(RunStatus.RUNNING, e.summary.status)
        assertEquals(0.0, e.summary.meters)
    }

    @Test fun `stale fix cannot confirm movement after a later timer`() {
        val e = paused()
        e.tick(20_000, origin + 20_000)
        for (s in 10..12) e.ingest(fix(s, (s - 9) * 3.0, 3f))
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        assertEquals(8000, e.summary.activeMs)
    }

    @Test fun `GPS loss while running is not an automatic stop`() {
        val e = RunEngine(RunSummary(startedAt = origin), Preferences(autoPause = true))
        e.tick(0, origin); e.ingest(fix(0))
        for (s in 1..3) e.ingest(fix(s))
        for (s in 4..30) e.tick(s * 1000L, origin + s * 1000)
        assertEquals(RunStatus.RUNNING, e.summary.status)
        for (s in 31..38) e.ingest(fix(s))
        assertEquals(RunStatus.RUNNING, e.summary.status)
        e.ingest(fix(39))
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
    }

    @Test fun `degraded accuracy at stop boundary must not cause an automatic stop`() {
        val e = RunEngine(RunSummary(startedAt = origin), Preferences(autoPause = true))
        e.tick(0, origin)
        for (s in 0..7) e.ingest(fix(s))
        e.ingest(fix(8, accuracy = 20f))
        assertEquals(RunStatus.RUNNING, e.summary.status)
        for (s in 9..16) e.ingest(fix(s))
        assertEquals(RunStatus.RUNNING, e.summary.status)
        e.ingest(fix(17))
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
    }

    @Test fun `movement at stop boundary is evaluated before prior stationary evidence`() {
        val e = RunEngine(RunSummary(startedAt = origin), Preferences(autoPause = true))
        e.tick(0, origin)
        for (s in 0..7) e.ingest(fix(s))
        e.ingest(fix(8, 3.0, 3f))
        assertEquals(RunStatus.RUNNING, e.summary.status)
        assertTrue(e.announcements.isEmpty())
    }

    @Test fun `stop speed boundary jitter never counts eight continuous stationary seconds`() {
        val e = RunEngine(RunSummary(startedAt = origin), Preferences(autoPause = true))
        e.tick(0, origin)
        for (s in 0..30) e.ingest(fix(s, speed = if (s % 4 == 0) 0.51f else 0.49f))
        assertEquals(RunStatus.RUNNING, e.summary.status)
    }

    @Test fun `missing speed can automatically stop with bounded coordinate noise`() {
        val e = RunEngine(RunSummary(startedAt = origin), Preferences(autoPause = true))
        e.tick(0, origin)
        for (s in 0..9) e.ingest(fix(s, if (s % 2 == 0) 1.0 else -1.0, null))
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        assertEquals(0.0, e.summary.meters)
    }

    @Test fun `manual pause while auto paused must remain manual despite sustained motion`() {
        val e = paused()
        e.pause()
        for (s in 10..30) e.ingest(fix(s, (s - 9) * 3.0, 3f))
        assertEquals(RunStatus.PAUSED, e.summary.status)
        assertEquals(8000, e.summary.activeMs)
        assertEquals(0.0, e.summary.meters)
        assertTrue(e.announcements.isEmpty())
    }

    @Test fun `jump rejection breaks confirmation and later real movement can recover`() {
        val e = paused()
        e.ingest(fix(10, 3.0, 3f)); e.ingest(fix(11, 6.0, 3f))
        e.ingest(fix(12, 1000.0, 3f))
        assertEquals("position_jump", e.autoPauseDiagnostics.reason)
        for (s in 13..14) {
            e.ingest(fix(s, (s - 9) * 3.0, 3f))
            assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        }
        e.ingest(fix(15, 18.0, 3f))
        assertEquals(RunStatus.RUNNING, e.summary.status)
    }

    @Test fun `invalid speeds use coordinate fallback`() {
        for (speed in listOf(Float.NaN, -1f, Float.POSITIVE_INFINITY)) {
            val e = paused()
            for (s in 10..12) e.ingest(fix(s, (s - 9) * 3.0, speed))
            assertEquals(RunStatus.RUNNING, e.summary.status)
        }
    }

    @Test fun `motion diagnosis explains pending samples and spatial threshold`() {
        val e = paused()
        e.ingest(fix(10, 3.0, 3f))
        val d = e.autoPauseDiagnostics
        assertEquals("confirming_motion", d.reason)
        assertEquals(1, d.movingLegs)
        assertEquals(1000, d.elapsedMs)
        assertEquals(3.0, d.netMeters, 0.01)
        assertEquals(4.0, d.requiredMeters, 0.01)
    }

    @Test fun `subsecond samples still require two seconds before resuming`() {
        val e = paused()
        for (index in 1..3) {
            val at = 9000L + index * 500
            e.ingest(LocationFix(Coordinate(index * 1.5 / 111_195.0802335329, 127.0), origin + at, at, 3f, speed = 3f))
            assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        }
        e.ingest(LocationFix(Coordinate(6.0 / 111_195.0802335329, 127.0), origin + 11_000, 11_000, 3f, speed = 3f))
        assertEquals(RunStatus.RUNNING, e.summary.status)
        assertEquals(2000, e.lastAutoPauseTransition?.elapsedMs)
    }

    @Test fun `out of order paused fixes do not add evidence or trigger resume`() {
        val e = paused()
        e.ingest(fix(10, 3.0, 3f)); e.ingest(fix(11, 6.0, 3f))
        e.ingest(fix(10, 30.0, 3f))
        e.ingest(fix(11, 30.0, 3f))
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        assertEquals(2, e.autoPauseDiagnostics.movingLegs)
        e.ingest(fix(12, 9.0, 3f))
        assertEquals(RunStatus.RUNNING, e.summary.status)
        assertEquals(0.0, e.summary.meters)
    }

    @Test fun `delayed fresh resume fix never rewinds the active clock`() {
        val e = paused()
        e.ingest(fix(10, 3.0, 3f)); e.ingest(fix(11, 6.0, 3f))
        e.tick(13_000, origin + 13_000)
        e.ingest(fix(12, 9.0, 3f), receivedAtMs = 13_000)
        assertEquals(RunStatus.RUNNING, e.summary.status)
        assertEquals(8000, e.summary.activeMs)
        e.tick(14_000, origin + 14_000)
        assertEquals(9000, e.summary.activeMs)
    }
}
