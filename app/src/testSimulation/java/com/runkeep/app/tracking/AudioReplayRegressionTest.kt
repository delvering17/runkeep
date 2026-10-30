package com.runkeep.app.tracking

import com.runkeep.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AudioReplayRegressionTest {
    private val settings = Preferences(autoPause = false, paceAlarm = true, metronome = true,
        cadenceTargetSpm = 185, readPaceStatus = false, readCurrentPace = true, readTargetPace = true, distancePaceVoice = true,
        distancePaceMeters = 10, kilometerVoice = false)
    private fun engine(target: Int = 365) = RunEngine(RunSummary(startedAt = 1_000_000,
        plan = WorkoutPlan(name = "audio", steps = listOf(
            PlanStep(kind = StepKind.DISTANCE, amount = 10_000.0, paceSeconds = target)))), settings)

    @Test fun `pace replay uses one beat output and keeps distance speech separate from warning selection`() {
        val engine = engine()
        val output = BeatOutputPolicy()
        val sounds = mutableMapOf<Int, BeatSound?>()
        val warnings = mutableListOf<Announcement>()
        val distance = mutableListOf<Announcement.DistancePace>()
        ReplayScenario.PACE_CHANGES.frames().forEach { frame ->
            val mono = 10_000L + frame.second * 1000
            engine.tick(mono, 1_000_000L + frame.second * 1000)
            engine.ingest(LocationFix(frame.coordinate, 1_000_000L + frame.second * 1000,
                mono, frame.accuracy, speed = frame.speed))
            output.update(mono, engine.feedback.alarm, 185, engine.paceValidUntil, metronome = true)
            sounds[frame.second] = output.poll(mono)
            assertNull(output.poll(mono))
            while (engine.announcements.isNotEmpty()) {
                val event = engine.announcements.removeFirst()
                if (event.kind == AnnouncementKind.PACE_WARNING) {
                    warnings += event
                    engine.paceWarningRequested()
                    engine.paceWarningSpoken(mono)
                    engine.paceWarningCompleted()
                }
                if (event is Announcement.DistancePace) distance += event
            }
        }
        assertTrue((0..25).all { sounds[it] == BeatSound.CLICK })
        assertTrue((55..100).any { sounds[it] == BeatSound.SLOW })
        assertTrue((135..180).any { sounds[it] == BeatSound.FAST })
        assertTrue((220..250).all { sounds[it] == BeatSound.CLICK })
        assertTrue(warnings.isNotEmpty())
        assertTrue(warnings.all { it.speechText().contains("현재") && it.speechText().contains("목표") })
        assertTrue(warnings.none { it.speechText().contains("빠릅니다") || it.speechText().contains("느립니다") })
        assertTrue(distance.any { it.occurredAtMs < 40_000 })
        assertTrue(distance.any { it.occurredAtMs > 230_000 })
        engine.pause()
        output.stop()
        assertNull(output.poll(300_000))
        engine.finish(300_000)
        assertTrue(engine.announcements.none { it.kind == AnnouncementKind.DISTANCE_PACE })
    }

    @Test fun `GPS recovery replay expires coaching while target metronome continues without distance backlog`() {
        val engine = engine(300)
        val output = BeatOutputPolicy()
        val distanceSeconds = mutableListOf<Int>()
        val sounds = mutableMapOf<Int, BeatSound?>()
        ReplayScenario.GPS_RECOVERY.frames().forEach { frame ->
            val mono = 10_000L + frame.second * 1000
            engine.tick(mono, 1_000_000L + frame.second * 1000)
            if (frame.emit) engine.ingest(LocationFix(frame.coordinate,
                1_000_000L + frame.second * 1000, mono, frame.accuracy, speed = frame.speed))
            output.update(mono, engine.feedback.alarm, 185, engine.paceValidUntil, metronome = true)
            sounds[frame.second] = output.poll(mono)
            if (frame.second in 40..89) assertEquals(BeatSound.CLICK, sounds[frame.second])
            assertNull(output.poll(mono))
            val pending = engine.announcements.filterIsInstance<Announcement.DistancePace>()
            assertTrue(pending.size <= 1)
            if (pending.isNotEmpty()) distanceSeconds += frame.second
            engine.announcements.clear()
            if (frame.second in 40..81) assertNull(engine.feedback.direction)
        }
        assertTrue(distanceSeconds.any { it < 30 })
        assertTrue(distanceSeconds.none { it in 30..89 })
        assertTrue(distanceSeconds.any { it >= 90 })
        assertEquals(BeatSound.SLOW, sounds[20])
        assertEquals(BeatSound.SLOW, sounds[120])
        output.stop()
        assertFalse(output.hasOutput(150_000))
        assertNull(output.poll(150_000))
    }
}
