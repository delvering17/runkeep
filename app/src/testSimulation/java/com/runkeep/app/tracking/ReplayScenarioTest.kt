package com.runkeep.app.tracking

import com.runkeep.core.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ReplayScenarioTest {
    @Test fun `all scenarios are deterministic ordered one second samples`() {
        ReplayScenario.entries.forEach { scenario ->
            val frames = scenario.frames()
            assertEquals(frames, scenario.frames())
            assertEquals((0..scenario.seconds).toList(), frames.map { it.second })
            assertTrue(frames.all { it.speed >= 0 && it.accuracy > 0 })
        }
    }

    @Test fun `stationary jitter does not simulate real displacement despite speed errors`() {
        val frames = ReplayScenario.STATIONARY.frames()
        assertTrue(frames.all { Geo.distance(frames.first().coordinate, it.coordinate) < 2.01 })
        assertTrue(frames.any { it.speed > 1.2f })
        assertTrue(frames.zipWithNext().all { (a, b) -> a.speed == 0f || b.speed == 0f })
    }

    @Test fun `stopping and returning produces enough sustained movement to test resume`() {
        val frames = ReplayScenario.STOP_AND_GO.frames()
        assertTrue(frames.slice(40..69).all { Geo.distance(frames[40].coordinate, it.coordinate) < 4.01 })
        assertTrue(Geo.distance(frames[70].coordinate, frames[73].coordinate) > 8)
        assertTrue(frames.slice(70..119).all { it.speed > 1.2f })
    }

    @Test fun `pace stages exceed the estimation window and announcement thresholds`() {
        val frames = ReplayScenario.PACE_CHANGES.frames()
        assertEquals(365.0, 1000.0 / frames[20].speed, 0.001)
        assertEquals(420.0, 1000.0 / frames[70].speed, 0.001)
        assertEquals(300.0, 1000.0 / frames[150].speed, 0.001)
        assertEquals(365.0, 1000.0 / frames[240].speed, 0.001)
        frames.zipWithNext().forEach { (a, b) ->
            val distance = Geo.distance(a.coordinate, b.coordinate)
            if (a.coordinate.lat == b.coordinate.lat || a.coordinate.lon == b.coordinate.lon)
                assertEquals(b.speed.toDouble(), distance, 0.015)
            else assertTrue(distance > 0 && distance <= b.speed + 0.015)
        }
    }

    @Test fun `GPS recovery distinguishes absent data from inaccurate data`() {
        val frames = ReplayScenario.GPS_RECOVERY.frames()
        assertEquals((30..51).toList(), frames.filterNot { it.emit }.map { it.second })
        assertEquals((52..81).toList(), frames.filter { it.accuracy == 60f }.map { it.second })
        assertTrue(frames.last().emit && frames.last().accuracy == 5f)
    }

    @Test fun `kilometer replay feeds real engine at correct pace past kilometer boundary`() {
        val plan = WorkoutPlan(name = "test", steps = listOf(PlanStep(kind = StepKind.DISTANCE, amount = 10_000.0, paceSeconds = 365)))
        val engine = RunEngine(RunSummary(startedAt = 1_000_000, plan = plan), Preferences(autoPause = false))
        val announcements = mutableListOf<Any>()
        ReplayScenario.KILOMETER.frames().forEach { frame ->
            val mono = 10_000L + frame.second * 1000
            engine.tick(mono, 1_000_000L + frame.second * 1000)
            engine.ingest(LocationFix(frame.coordinate, 1_000_000L + frame.second * 1000, mono, frame.accuracy, speed = frame.speed))
            while (engine.announcements.isNotEmpty()) announcements += engine.announcements.removeFirst()
        }
        assertTrue(engine.summary.meters > 1200)
        assertEquals(365.0, engine.currentPace!!, 0.1)
        assertTrue(announcements.isNotEmpty())
    }

    @Test fun `GPS gap does not bridge missing or rejected positions into distance`() {
        val engine = RunEngine(RunSummary(startedAt = 1_000_000), Preferences(autoPause = false))
        ReplayScenario.GPS_RECOVERY.frames().forEach { frame ->
            val mono = 10_000L + frame.second * 1000
            engine.tick(mono, 1_000_000L + frame.second * 1000)
            if (frame.emit) engine.ingest(LocationFix(frame.coordinate, 1_000_000L + frame.second * 1000, mono, frame.accuracy, speed = frame.speed))
        }
        assertTrue(engine.summary.meters in 190.0..220.0)
        assertTrue(engine.points.map { it.segment }.distinct().size >= 2)
    }
}
