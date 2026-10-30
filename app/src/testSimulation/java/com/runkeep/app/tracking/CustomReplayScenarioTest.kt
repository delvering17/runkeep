package com.runkeep.app.tracking

import com.runkeep.core.*
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class CustomReplayScenarioTest {
    private fun scenario(vararg stages: ReplayStage) = CustomReplayScenario(stages = stages.toList())

    @Test fun `each stage contributes exactly its duration and ordered intervals`() {
        val plan = scenario(ReplayStage(seconds = 3, paceSeconds = 365), ReplayStage(seconds = 2, paceSeconds = 420))
        val frames = plan.frames()
        assertEquals((0..5).toList(), frames.map { it.second })
        assertEquals(frames, plan.frames())
        assertTrue(frames.slice(0..3).all { it.phase.startsWith("1/2") })
        assertTrue(frames.slice(4..5).all { it.phase.startsWith("2/2") })
        frames.zipWithNext().forEach { (previous, next) ->
            assertEquals(next.speed.toDouble(), Geo.distance(previous.coordinate, next.coordinate), .015)
        }
        assertEquals(3 * 1000.0 / 365 + 2 * 1000.0 / 420, plan.inputMeters, .001)
    }

    @Test fun `stop has no actual route progress despite jitter and reported speed errors`() {
        val plan = scenario(ReplayStage(seconds = 10), ReplayStage(kind = ReplayStageKind.STOP, seconds = 25, gpsJitter = true), ReplayStage(seconds = 10))
        val frames = plan.frames()
        val stop = frames.slice(11..35)
        assertTrue(stop.all { Geo.distance(frames[10].coordinate, it.coordinate) < 2.01 })
        assertTrue(stop.any { it.speed > 1.2f })
        assertEquals(20 * 1000.0 / 365, plan.inputMeters, .001)
        assertTrue(Geo.distance(frames[35].coordinate, frames[40].coordinate) > 10)
    }

    @Test fun `ordinary stop emits exact stationary coordinates and zero speed`() {
        val frames = scenario(ReplayStage(kind = ReplayStageKind.STOP, seconds = 20)).frames()
        assertTrue(frames.all { it.coordinate == frames.first().coordinate && it.speed == 0f })
    }

    @Test fun `GPS missing and low accuracy advance virtual route but signal remains distinct`() {
        val frames = scenario(ReplayStage(seconds = 5), ReplayStage(kind = ReplayStageKind.GPS_GAP, seconds = 10),
            ReplayStage(kind = ReplayStageKind.LOW_ACCURACY, seconds = 8), ReplayStage(seconds = 5)).frames()
        assertEquals((6..15).toList(), frames.filterNot { it.emit }.map { it.second })
        assertEquals((16..23).toList(), frames.filter { it.accuracy == 60f }.map { it.second })
        assertTrue(Geo.distance(frames[5].coordinate, frames[16].coordinate) > 29)
        assertTrue(frames.last().emit && frames.last().accuracy == 5f)
    }

    @Test fun `one second and maximum total duration are allowed`() {
        assertEquals(2, scenario(ReplayStage(seconds = 1)).frames().size)
        val maximum = scenario(ReplayStage(seconds = ReplayLimits.MAX_SECONDS, paceSeconds = 1800))
        assertNull(maximum.validationMessage())
        assertEquals(ReplayLimits.MAX_SECONDS + 1, maximum.frames().size)
    }

    @Test fun `invalid and overflowing durations are rejected before frame generation`() {
        for (seconds in listOf(0, -1, ReplayLimits.MAX_SECONDS + 1, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { scenario(ReplayStage(seconds = seconds)).frames() }
        }
        val tooLong = scenario(ReplayStage(seconds = 7000), ReplayStage(seconds = 7000))
        assertEquals(14000L, tooLong.totalSeconds)
        assertThrows(IllegalArgumentException::class.java) { tooLong.frames() }
    }

    @Test fun `pace and target use common limits and stop does not need a movement pace`() {
        for (pace in listOf(120, 1800)) assertNull(scenario(ReplayStage(paceSeconds = pace)).copy(targetPaceSeconds = pace).validationMessage())
        for (pace in listOf(0, 119, 1801, Int.MAX_VALUE)) {
            assertNotNull(scenario(ReplayStage(paceSeconds = pace)).validationMessage())
            assertNotNull(scenario(ReplayStage()).copy(targetPaceSeconds = pace).validationMessage())
        }
        val stopped = scenario(ReplayStage(kind = ReplayStageKind.STOP, paceSeconds = 0))
        assertNull(stopped.validationMessage())
        assertEquals(0.0, stopped.inputMeters)
    }

    @Test fun `stage count duplicate identities and blank names are invalid`() {
        assertNotNull(scenario().validationMessage())
        assertNull(CustomReplayScenario(stages = List(30) { ReplayStage(seconds = 1) }).validationMessage())
        assertNotNull(CustomReplayScenario(stages = List(31) { ReplayStage(seconds = 1) }).validationMessage())
        val stage = ReplayStage()
        assertNotNull(scenario(stage, stage).validationMessage())
        assertNotNull(scenario(stage).copy(name = " ").validationMessage())
        assertNotNull(scenario(stage).copy(name = "a".repeat(41)).validationMessage())
    }

    @Test fun `library round trip preserves selected target stage order and identity`() {
        val plan = scenario(ReplayStage(seconds = 20, paceSeconds = 420), ReplayStage(kind = ReplayStageKind.STOP, seconds = 15))
            .copy(name = "정지와 변속", targetPaceSeconds = 390)
        val saved = ReplayLibrary().upsert(plan)
        val restored = ReplayLibrary.decode(codec.encodeToString(saved))
        assertEquals(saved, restored)
        assertEquals(plan.id, restored.selectedId)
        assertEquals(plan.frames(), restored.scenarios.single().frames())
    }

    @Test fun `editing upserts rather than duplicating and keeps other saved scenarios`() {
        val first = scenario(ReplayStage())
        val second = scenario(ReplayStage())
        val original = ReplayLibrary().upsert(first).upsert(second)
        val edited = original.upsert(first.copy(name = "  수정한 시나리오  ", targetPaceSeconds = 420))
        assertEquals(2, edited.scenarios.size)
        assertEquals("수정한 시나리오", edited.scenarios.first().name)
        assertEquals(second, edited.scenarios[1])
        assertEquals(first.id, edited.selectedId)
        assertEquals("내 시나리오", original.scenarios.first().name)
    }

    @Test fun `deleting selected scenario clears selection without changing others`() {
        val first = scenario(ReplayStage())
        val second = scenario(ReplayStage())
        val saved = ReplayLibrary().upsert(first).upsert(second)
        val removed = saved.remove(second.id)
        assertNull(removed.selectedId)
        assertEquals(listOf(first), removed.scenarios)
        assertEquals(saved, saved.remove("missing"))
    }

    @Test fun `reordering changes playback order while boundary actions preserve it`() {
        val first = ReplayStage(paceSeconds = 300)
        val second = ReplayStage(kind = ReplayStageKind.STOP)
        val plan = scenario(first, second)
        val reordered = plan.moveStage(second.id, -1)
        assertEquals(listOf(second, first), reordered.stages)
        assertTrue(reordered.frames().first().phase.contains("정지"))
        assertEquals(plan, plan.moveStage(first.id, -1))
        assertEquals(plan, plan.moveStage(second.id, 1))
        assertEquals(plan, plan.moveStage("missing", -1))
        assertEquals(plan, plan.moveStage(first.id, 3))
    }

    @Test fun `dragging across multiple rows preserves stage data and saved playback order`() {
        val first = ReplayStage(seconds = 11, paceSeconds = 300)
        val second = ReplayStage(kind = ReplayStageKind.STOP, seconds = 12, gpsJitter = true)
        val third = ReplayStage(kind = ReplayStageKind.GPS_GAP, seconds = 13, paceSeconds = 420)
        val fourth = ReplayStage(kind = ReplayStageKind.LOW_ACCURACY, seconds = 14, paceSeconds = 390)
        val plan = scenario(first, second, third, fourth)
        val moved = plan.moveStageTo(first.id, 3).moveStageTo(fourth.id, 0)
        assertEquals(listOf(fourth, second, third, first), moved.stages)
        assertEquals(plan.totalSeconds, moved.totalSeconds)
        assertEquals(plan.stages.associateBy { it.id }, moved.stages.associateBy { it.id })
        val saved = ReplayLibrary().upsert(plan)
        assertEquals(plan, saved.scenarios.single()) // 드래그 초안과 원래 저장값을 분리한다.
        val restored = ReplayLibrary.decode(codec.encodeToString(saved.upsert(moved)))
        assertEquals(moved.stages, restored.scenarios.single().stages)
        assertTrue(restored.scenarios.single().frames().first().phase.contains("정확도 저하"))
        assertEquals(plan, moved.moveStageTo(fourth.id, 3).moveStageTo(first.id, 0))
        assertEquals(plan, plan.moveStageTo(first.id, -1))
        assertEquals(plan, plan.moveStageTo(first.id, plan.stages.size))
        assertEquals(plan, plan.moveStageTo("missing", 0))
    }

    @Test fun `library capacity malformed data and future schema are rejected`() {
        val saved = ReplayLibrary(scenarios = List(20) { scenario(ReplayStage(seconds = 1)) }).validated()
        assertThrows(IllegalArgumentException::class.java) { saved.upsert(scenario(ReplayStage())) }
        assertThrows(Exception::class.java) { ReplayLibrary.decode("broken-json") }
        assertThrows(IllegalArgumentException::class.java) { ReplayLibrary.decode("{\"schemaVersion\":2}") }
        assertThrows(IllegalArgumentException::class.java) { saved.copy(selectedId = "missing").validated() }
        assertThrows(IllegalArgumentException::class.java) { saved.copy(scenarios = listOf(saved.scenarios.first(), saved.scenarios.first())).validated() }
        assertEquals(ReplayLibrary(), ReplayLibrary.decode(null))
    }

    @Test fun `custom movement is interpreted as intended pace by real engine`() {
        val plan = scenario(ReplayStage(seconds = 25, paceSeconds = 365), ReplayStage(seconds = 30, paceSeconds = 420))
        val engine = RunEngine(RunSummary(startedAt = 1_000_000L), Preferences(autoPause = false))
        plan.frames().forEach { frame ->
            val time = 1_000_000L + frame.second * 1000L
            val mono = 10_000L + frame.second * 1000L
            engine.tick(mono, time)
            engine.ingest(LocationFix(frame.coordinate, time, mono, frame.accuracy, speed = frame.speed))
        }
        assertEquals(420.0, engine.currentPace!!, .1)
        assertEquals(55_000L, engine.summary.activeMs)
        assertEquals(plan.inputMeters, engine.summary.meters, .1)
    }
}
