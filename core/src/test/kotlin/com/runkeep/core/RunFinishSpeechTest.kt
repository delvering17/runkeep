package com.runkeep.core

import kotlin.test.*

class RunFinishSpeechTest {
    private fun point(cadence: Double?) = TrackPoint(Coordinate(0.0, 0.0), 0, 0, 0.0, cadence = cadence)

    @Test fun `GPS engine goal and extra recording produce the same saved whole finish speech`() {
        val engine = RunEngine(RunSummary(id = "synthetic-gps", startedAt = 1_000_000, goalMeters = 100.0),
            Preferences(autoPause = false, cadenceTargetSpm = 220))
        engine.tick(10_000, 1_000_000)
        for (second in 0..60) {
            engine.ingest(LocationFix(Coordinate(second * 6.0 / 111_195.0802335329, 0.0),
                1_000_000L + second * 1000, 10_000L + second * 1000, 3f, speed = 6f),
                cadence = if (second <= 30) 160.0 else 200.0)
        }
        engine.tick(72_000, 1_062_000)
        engine.finish(1_062_000)
        val saved = codec.decodeFromString<RunSummary>(codec.encodeToString(RunSummary.serializer(), engine.summary))
        assertEquals(RunStatus.FINISHED, saved.status)
        assertEquals(62_000L, saved.activeMs)
        assertTrue(assertNotNull(saved.goalActiveMs) < saved.activeMs)
        assertTrue(saved.meters > assertNotNull(saved.goalMeters))
        assertEquals("달리기를 종료합니다. 총 0.36킬로미터. 평균 페이스 2분 52초. 평균 케이던스 180.",
            finishSpeechText(saved, engine.points))
    }

    @Test fun `summary uses the entire saved run after the goal with measured cadence`() {
        val run = RunSummary(startedAt = 0, endedAt = 2_190_000, activeMs = 2_190_000, meters = 6000.0,
            status = RunStatus.FINISHED, goalMeters = 5000.0, goalActiveMs = 1_800_000, goalEpochMs = 1_800_000)
        val points = listOf(point(170.0), point(null), point(180.0))
        assertEquals("달리기를 종료합니다. 총 6킬로미터. 평균 페이스 6분 5초. 평균 케이던스 175.", finishSpeechText(run, points))
        assertEquals(175.0, measuredAverageCadence(points))
        assertEquals(5000.0, run.goalMeters)
        assertEquals(6000.0, run.meters)
    }

    @Test fun `missing and invalid measurements are omitted and target cadence is never substituted`() {
        val invalid = listOf(null, 0.0, -10.0, Double.NaN, Double.POSITIVE_INFINITY).map(::point)
        assertEquals("달리기를 종료합니다.", finishSpeechText(RunSummary(startedAt = 0), invalid))
        assertNull(measuredAverageCadence(invalid))
        for (meters in listOf(-1.0, Double.NaN, Double.POSITIVE_INFINITY))
            assertEquals("달리기를 종료합니다.", finishSpeechText(RunSummary(startedAt = 0, meters = meters), emptyList()))
        assertEquals("달리기를 종료합니다. 총 1킬로미터.",
            finishSpeechText(RunSummary(startedAt = 0, meters = 1000.0), invalid))
        val preferences = Preferences(cadenceTargetSpm = 220, metronome = true)
        assertEquals(220, preferences.cadenceTargetSpm)
        assertFalse(finishSpeechText(RunSummary(startedAt = 0), emptyList()).contains("220"))
    }

    @Test fun `pace speech keeps units and filters zero rounded and excessive pace`() {
        for (active in listOf(0L, -1L, 1L, 3_600_001L)) {
            val text = finishSpeechText(RunSummary(startedAt = 0, meters = 1000.0, activeMs = active), emptyList())
            assertFalse(text.contains("평균 페이스"))
            assertFalse(text.contains("측정 불가"))
        }
        val text = finishSpeechText(RunSummary(startedAt = 0, meters = 1000.0, activeMs = 360_000), emptyList())
        assertTrue(text.contains("평균 페이스 6분."))
        assertFalse(text.contains(":"))
    }

    @Test fun `distance and cadence round like recorded display without inventing zero distance`() {
        val run = RunSummary(startedAt = 0, meters = 1234.0, activeMs = 0)
        assertEquals("달리기를 종료합니다. 총 1.23킬로미터. 평균 케이던스 181.",
            finishSpeechText(run, listOf(point(180.0), point(181.0))))
        assertEquals("달리기를 종료합니다. 총 1미터.", finishSpeechText(run.copy(meters = 1.0), emptyList()))
    }

    @Test fun `start is one queue event independent of pace selections and retained while preparing speech`() {
        for (status in listOf(false, true)) for (current in listOf(false, true)) for (target in listOf(false, true)) {
            val prefs = Preferences(paceVoice = false, readPaceStatus = status, readCurrentPace = current, readTargetPace = target)
            val engine = RunEngine(RunSummary(startedAt = 0), prefs)
            val start = workoutStartAnnouncement(0)
            engine.announcements += start
            val queue = AnnouncementQueue(engine.announcements)
            engine.updateFeedbackPreferences(prefs, 1000)
            assertEquals("달리기를 시작합니다", assertNotNull(queue.next(1000, RunStatus.RUNNING)).speechText())
            assertNull(queue.next(1000, RunStatus.RUNNING, playbackBusy = true))
            assertSame(start, queue.next(2000, RunStatus.RUNNING))
            queue.acknowledge(start)
            assertNull(queue.next(3000, RunStatus.RUNNING))
        }
    }

    @Test fun `whole engine finishes normally with no points before any speech outcome`() {
        val engine = RunEngine(RunSummary(id = "instant", startedAt = 1000), Preferences())
        engine.tick(100, 1000)
        engine.finish(1000)
        val saved = codec.decodeFromString<RunSummary>(codec.encodeToString(RunSummary.serializer(), engine.summary))
        assertEquals(RunStatus.FINISHED, saved.status)
        assertEquals(0L, saved.activeMs)
        assertEquals("달리기를 종료합니다.", finishSpeechText(saved, engine.points))
        val policy = FinishSpeechPolicy()
        val ticket = assertNotNull(policy.begin(saved.id, 100))
        assertTrue(policy.complete(ticket))
        assertEquals(saved, engine.summary)
    }
}
