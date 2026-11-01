package com.runkeep.core

import kotlin.test.*
import kotlinx.serialization.json.*

class DiagnosticTest {
    private fun fix(seconds: Int, meters: Double = seconds * 3.0, accuracy: Float = 3f) =
        LocationFix(Coordinate(meters / 111195.08, 0.0), seconds * 1000L, seconds * 1000L, accuracy, speed = 3f)
    @Test fun `engine reports actual rejection branches and segment causes`() {
        val events = mutableListOf<EngineDiagnostic>()
        val engine = RunEngine(RunSummary(startedAt = 0), Preferences(autoPause = false), diagnostic = events::add)
        engine.tick(0, 0)
        engine.ingest(fix(0)); engine.ingest(fix(1, 1000.0)); engine.ingest(fix(2, accuracy = 90f))
        engine.ingest(fix(3)); engine.ingest(fix(20)); engine.ingest(fix(19))
        engine.ingest(fix(21), receivedAtMs = 40_000)
        engine.ingest(fix(41), receivedAtMs = 40_000)
        engine.pause(); engine.ingest(fix(42)); engine.resume(45_000); engine.ingest(fix(44)); engine.ingest(fix(45))
        engine.finish(46_000)
        val decisions = events.filter { it.kind == DiagnosticKind.LOCATION_DECISION }.map { it.reason }
        assertTrue(decisions.containsAll(listOf(DiagnosticReason.ACCEPTED, DiagnosticReason.POSITION_JUMP,
            DiagnosticReason.ACCURACY, DiagnosticReason.OUT_OF_ORDER, DiagnosticReason.STALE, DiagnosticReason.FUTURE_SAMPLE,
            DiagnosticReason.INACTIVE, DiagnosticReason.BEFORE_RESUME)))
        val splits = events.filter { it.kind == DiagnosticKind.SEGMENT }.map { it.reason }
        assertTrue(splits.containsAll(listOf(DiagnosticReason.FIRST_FIX, DiagnosticReason.ACCURACY,
            DiagnosticReason.GAP_OVER_15_SECONDS, DiagnosticReason.MANUAL_RESUME)))
        assertTrue(events.any { it.reason == DiagnosticReason.FINISHED })
        assertTrue(events.any { it.pointIndex == 0 })
    }
    @Test fun `service exclusion keeps its actual precedence separate from engine decisions`() {
        assertEquals(DiagnosticReason.STALE, serviceLocationExclusion(20_001, 10_000_000_000, 15_000_000_000))
        assertEquals(DiagnosticReason.BEFORE_RESUME, serviceLocationExclusion(20_000, 10_000_000_000, 15_000_000_000))
        assertNull(serviceLocationExclusion(20_000, 15_000_000_000, 15_000_000_000))
    }
    @Test fun `automatic transitions are distinct from manual transitions`() {
        val events = mutableListOf<EngineDiagnostic>()
        val e = RunEngine(RunSummary(startedAt = 0), Preferences(), diagnostic = events::add)
        e.tick(0, 0)
        for (s in 0..30) e.ingest(fix(s, 0.0).copy(speed = 0f))
        assertEquals(RunStatus.AUTO_PAUSED, e.summary.status)
        for (s in 31..45) e.ingest(fix(s, (s - 30) * 3.0))
        assertEquals(RunStatus.RUNNING, e.summary.status)
        assertTrue(events.any { it.kind == DiagnosticKind.STATE && it.reason == DiagnosticReason.AUTO_PAUSE })
        assertTrue(events.any { it.kind == DiagnosticKind.STATE && it.reason == DiagnosticReason.AUTO_RESUME })
        assertTrue(events.none { it.reason == DiagnosticReason.MANUAL_PAUSE || it.reason == DiagnosticReason.MANUAL_RESUME })
    }
    @Test fun `diagnostic failures cannot change engine output`() {
        val plain = RunEngine(RunSummary(id = "synthetic", startedAt = 0), Preferences(autoPause = false))
        val broken = RunEngine(plain.summary, plain.preferences, diagnostic = { error("synthetic failure") })
        for (s in 0..30) { plain.ingest(fix(s)); broken.ingest(fix(s)) }
        assertEquals(plain.summary, broken.summary)
        assertEquals(plain.points, broken.points)
    }
    @Test fun `no receipt and poor received quality have independent recovery`() {
        val monitor = DiagnosticSignalMonitor(0)
        assertNull(monitor.tick(10_000))
        assertEquals(DiagnosticReason.NO_RECEIPT_10_SECONDS, monitor.tick(10_001))
        assertNull(monitor.tick(20_000))
        assertEquals(DiagnosticReason.RECEIPT_RETURNED, monitor.receipt(20_001))
        assertEquals(DiagnosticReason.ACCURACY, monitor.quality(false))
        assertNull(monitor.tick(25_000))
        assertEquals(DiagnosticReason.QUALITY_RECOVERED, monitor.quality(true))
    }
    @Test fun `default export recursively contains no coordinates or unrelated sensitive fields`() {
        assertFalse(Preferences().diagnosticRecording)
        assertFalse(codec.decodeFromString<Preferences>("{}").diagnosticRecording)
        val original = DiagnosticFile(events = listOf(DiagnosticEvent(kind = DiagnosticKind.LOCATION_RECEIVED,
            coordinate = Coordinate(12.345678, 45.678912), speedMps = null)))
        val without = original.export(false)
        fun assertAllowed(element: JsonElement) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) ->
                    assertTrue(key !in setOf("coordinate", "lat", "lon", "course", "weightKg", "hearts", "bpm", "sourceId", "run", "preferences", "deviceId", "path", "key", "account"), key)
                    assertAllowed(value)
                }
                is JsonArray -> element.forEach(::assertAllowed)
                else -> Unit
            }
        }
        assertAllowed(diagnosticCodec.parseToJsonElement(without))
        assertFalse(without.contains("12.345678"))
        assertFalse(without.contains("speedMps"))
        val with = diagnosticCodec.decodeFromString<DiagnosticFile>(original.export(true))
        assertEquals(original.events.single().coordinate, with.events.single().coordinate)
        assertEquals(1, with.schemaVersion)
        assertTrue(with.coordinatesIncluded)
    }
    @Test fun `map callback distinguishes sink success failure and observer failure`() {
        var fail = false
        val sink = object : MapRouteSink<Int> {
            override fun create(kind: MapRouteKind, points: List<Coordinate>): Int { check(!fail); return 1 }
            override fun update(kind: MapRouteKind, handle: Int, points: List<Coordinate>) { check(!fail) }
            override fun remove(handle: Int) { check(!fail) }
        }
        val updates = MapRouteUpdates(sink)
        val results = mutableListOf<MapDiagnostic>()
        val points = listOf(Coordinate(0.0, 0.0), Coordinate(0.0, .001))
        updates.reconcile(emptyList(), listOf(points), results::add)
        assertEquals(MapDiagnostic(DiagnosticReason.UPDATE_APPLIED, 1, 2, 0, 1, 0, 0), results.single())
        updates.reconcile(emptyList(), listOf(points), results::add)
        assertEquals(DiagnosticReason.UPDATE_UNCHANGED, results.last().reason)
        fail = true
        assertFails { updates.reconcile(points, listOf(points), results::add) }
        assertEquals(DiagnosticReason.UPDATE_FAILED, results.last().reason)
        fail = false
        updates.reconcile(points, listOf(points)) { error("observer failure") }
        assertNotEquals(DiagnosticReason.UPDATE_APPLIED, DiagnosticReason.SDK_NOT_READY)
    }
}
