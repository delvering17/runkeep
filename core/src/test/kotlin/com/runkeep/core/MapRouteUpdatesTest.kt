package com.runkeep.core

import kotlin.test.*

class MapRouteUpdatesTest {
    private data class Handle(val id: Int, val kind: MapRouteKind, var points: List<Coordinate>)
    private data class Operation(val name: String, val id: Int, val points: List<Coordinate> = emptyList())
    private class Sink : MapRouteSink<Handle> {
        val live = linkedMapOf<Int, Handle>()
        val operations = mutableListOf<Operation>()
        var nextId = 0
        var failNext = false
        private fun checkFailure() {
            if (failNext) { failNext = false; error("synthetic failure") }
        }
        override fun create(kind: MapRouteKind, points: List<Coordinate>): Handle {
            checkFailure()
            val handle = Handle(nextId++, kind, points)
            live[handle.id] = handle
            operations.add(Operation("create", handle.id, points))
            return handle
        }
        override fun update(kind: MapRouteKind, handle: Handle, points: List<Coordinate>) {
            checkFailure()
            assertSame(handle, live[handle.id])
            assertEquals(kind, handle.kind)
            handle.points = points
            operations.add(Operation("update", handle.id, points))
        }
        override fun remove(handle: Handle) {
            assertSame(handle, live.remove(handle.id))
            operations.add(Operation("remove", handle.id))
        }
        fun routes(kind: MapRouteKind) = live.values.filter { it.kind == kind }
    }
    private fun point(index: Int) = Coordinate(10.0 + index * .00001, 20.0 + index * .00001)
    private fun path(start: Int, size: Int) = List(size) { point(start + it) }
    private fun edges(sink: Sink, kind: MapRouteKind = MapRouteKind.TRACK) =
        sink.routes(kind).flatMap { it.points.zipWithNext() }

    @Test fun `empty and singleton segments never create a connecting line`() {
        val sink = Sink(); val updates = MapRouteUpdates(sink)
        updates.reconcile(listOf(point(0)), listOf(emptyList(), listOf(point(10)), listOf(point(20))))
        assertTrue(sink.operations.isEmpty())
        assertFailsWith<IllegalArgumentException> { MapRouteUpdates(sink, chunkSize = 1) }
    }

    @Test fun `one hundred GPS additions keep planned and completed handles and update only the growing track`() {
        val sink = Sink(); val updates = MapRouteUpdates(sink)
        val planned = path(1000, 3); val completed = path(2000, 3)
        updates.reconcile(planned, listOf(completed, path(0, 2)))
        val plannedHandle = sink.routes(MapRouteKind.PLANNED).single()
        val completedHandle = sink.routes(MapRouteKind.TRACK).first()
        val growingHandle = sink.routes(MapRouteKind.TRACK).last()
        sink.operations.clear()
        repeat(100) { updates.reconcile(planned, listOf(completed, path(0, it + 3))) }
        assertEquals(100, sink.operations.size)
        assertTrue(sink.operations.all { it.name == "update" && it.id == growingHandle.id })
        assertEquals(path(0, 102), growingHandle.points)
        assertSame(plannedHandle, sink.routes(MapRouteKind.PLANNED).single())
        assertSame(completedHandle, sink.routes(MapRouteKind.TRACK).first())
        assertEquals(planned, plannedHandle.points)
        assertEquals(completed, completedHandle.points)
    }

    @Test fun `equal copied inputs and location or UI only refreshes issue no route commands`() {
        val sink = Sink(); val updates = MapRouteUpdates(sink)
        val planned = path(100, 5); val tracks = listOf(path(0, 5), path(20, 3))
        updates.reconcile(planned, tracks)
        sink.operations.clear()
        repeat(100) { updates.reconcile(planned.toList(), tracks.map { it.toList() }) }
        assertTrue(sink.operations.isEmpty())
        assertEquals(3, sink.live.size)
    }

    @Test fun `new separated segment appears only at two points without bridging the previous segment`() {
        val sink = Sink(); val updates = MapRouteUpdates(sink)
        val completed = path(0, 3)
        updates.reconcile(emptyList(), listOf(completed))
        val original = sink.routes(MapRouteKind.TRACK).single()
        sink.operations.clear()
        updates.reconcile(emptyList(), listOf(completed, listOf(point(100))))
        assertTrue(sink.operations.isEmpty())
        updates.reconcile(emptyList(), listOf(completed, path(100, 2)))
        assertEquals(listOf("create"), sink.operations.map { it.name })
        assertSame(original, sink.routes(MapRouteKind.TRACK).first())
        assertEquals(completed.zipWithNext() + path(100, 2).zipWithNext(), edges(sink))
        assertFalse((point(2) to point(100)) in edges(sink))
    }

    @Test fun `course edit changes only its line and preserves track content`() {
        val sink = Sink(); val updates = MapRouteUpdates(sink)
        val track = path(0, 4)
        updates.reconcile(path(100, 4), listOf(track))
        val plannedHandle = sink.routes(MapRouteKind.PLANNED).single()
        val trackHandle = sink.routes(MapRouteKind.TRACK).single()
        sink.operations.clear()
        updates.reconcile(path(200, 3), listOf(track))
        assertEquals(listOf(Operation("update", plannedHandle.id, path(200, 3))), sink.operations)
        assertSame(trackHandle, sink.routes(MapRouteKind.TRACK).single())
        assertEquals(track, trackHandle.points)
    }

    @Test fun `workout and course replacement remove obsolete lines and leave exactly the requested paths`() {
        val sink = Sink(); val updates = MapRouteUpdates(sink, chunkSize = 4)
        updates.reconcile(path(100, 8), listOf(path(0, 8), path(20, 5)))
        updates.reconcile(path(200, 2), listOf(path(50, 2)))
        assertEquals(2, sink.live.size)
        assertEquals(path(200, 2).zipWithNext(), edges(sink, MapRouteKind.PLANNED))
        assertEquals(path(50, 2).zipWithNext(), edges(sink))
        sink.operations.clear()
        updates.reconcile(path(200, 2), listOf(path(50, 2)))
        assertTrue(sink.operations.isEmpty())
    }

    @Test fun `shrinking and restoring a record neither duplicate edges nor retain hidden tail chunks`() {
        val sink = Sink(); val updates = MapRouteUpdates(sink, chunkSize = 4)
        val full = path(0, 11)
        updates.reconcile(emptyList(), listOf(full))
        updates.reconcile(emptyList(), listOf(full.take(5)))
        assertEquals(2, sink.live.size)
        assertEquals(full.take(5).zipWithNext(), edges(sink))
        updates.reconcile(emptyList(), listOf(full))
        assertEquals(4, sink.live.size)
        assertEquals(full.zipWithNext(), edges(sink))
        sink.operations.clear()
        updates.reconcile(emptyList(), listOf(full))
        assertTrue(sink.operations.isEmpty())
    }

    @Test fun `chunk boundary preserves the full previous chunk and starts the next at its endpoint`() {
        val sink = Sink(); val updates = MapRouteUpdates(sink)
        updates.reconcile(emptyList(), listOf(path(0, 256)))
        val first = sink.routes(MapRouteKind.TRACK).single()
        sink.operations.clear()
        updates.reconcile(emptyList(), listOf(path(0, 257)))
        assertEquals(listOf("create"), sink.operations.map { it.name })
        assertSame(first, sink.routes(MapRouteKind.TRACK).first())
        assertEquals(path(255, 2), sink.routes(MapRouteKind.TRACK).last().points)
        assertEquals(path(0, 257).zipWithNext(), edges(sink))
        updates.reconcile(emptyList(), listOf(path(0, 511)))
        sink.operations.clear()
        updates.reconcile(emptyList(), listOf(path(0, 512)))
        assertEquals(listOf("create"), sink.operations.map { it.name })
        assertEquals(path(0, 512).zipWithNext(), edges(sink))
    }

    @Test fun `long route additions bound SDK payloads and preserve completed chunks`() {
        val sink = Sink(); val updates = MapRouteUpdates(sink)
        updates.reconcile(path(10000, 10000), listOf(path(0, 4000)))
        val existing = sink.live.values.toList()
        val completed = sink.routes(MapRouteKind.TRACK).dropLast(1).map { it.id to it.points }
        sink.operations.clear()
        repeat(50) { updates.reconcile(path(10000, 10000), listOf(path(0, 4001 + it))) }
        assertEquals(50, sink.operations.size)
        assertTrue(sink.operations.all { it.name == "update" && it.points.size <= 256 })
        assertTrue(sink.operations.none { it.name == "remove" })
        existing.forEach { assertSame(it, sink.live[it.id]) }
        completed.forEach { (id, points) -> assertSame(points, sink.live.getValue(id).points) }
        assertEquals(path(0, 4050).zipWithNext(), edges(sink))
        assertEquals(path(10000, 10000).zipWithNext(), edges(sink, MapRouteKind.PLANNED))
    }

    @Test fun `changed middle point updates its affected chunk and preserves every original edge`() {
        val sink = Sink(); val updates = MapRouteUpdates(sink, chunkSize = 4)
        val original = path(0, 9)
        updates.reconcile(emptyList(), listOf(original))
        val changed = original.toMutableList().apply { this[3] = point(100) }
        val unaffected = sink.routes(MapRouteKind.TRACK).last()
        sink.operations.clear()
        updates.reconcile(emptyList(), listOf(changed))
        assertEquals(2, sink.operations.count { it.name == "update" })
        assertSame(unaffected, sink.routes(MapRouteKind.TRACK).last())
        assertEquals(changed.zipWithNext(), edges(sink))
    }

    @Test fun `snapshots detect reused mutable inputs and preserve repeated coordinates`() {
        val sink = Sink(); val updates = MapRouteUpdates(sink)
        val input = path(0, 3).toMutableList()
        updates.reconcile(emptyList(), listOf(input))
        val before = sink.operations.single().points
        input[1] = point(100)
        updates.reconcile(emptyList(), listOf(input))
        assertEquals(path(0, 3), before)
        assertEquals(input, sink.routes(MapRouteKind.TRACK).single().points)
        input.add(input.last())
        updates.reconcile(emptyList(), listOf(input))
        assertEquals(input.zipWithNext(), edges(sink))
    }

    @Test fun `clear releases each owned handle once and a returning map rebuilds all paths`() {
        val sink = Sink(); val updates = MapRouteUpdates(sink)
        val planned = path(100, 3); val tracks = listOf(path(0, 4), path(20, 4))
        updates.reconcile(planned, tracks)
        val oldIds = sink.live.keys.toSet()
        sink.operations.clear()
        updates.clear(); updates.clear()
        assertEquals(3, sink.operations.size)
        assertTrue(sink.operations.all { it.name == "remove" })
        assertTrue(sink.live.isEmpty())
        updates.reconcile(planned, tracks)
        assertEquals(3, sink.live.size)
        assertTrue(sink.live.keys.none { it in oldIds })
        assertEquals(tracks.flatMap { it.zipWithNext() }, edges(sink))
    }

    @Test fun `destroyed SDK objects are forgotten without calls and a new map gets fresh handles`() {
        val sink = Sink(); val updates = MapRouteUpdates(sink)
        val planned = path(100, 3); val tracks = listOf(path(0, 4))
        updates.reconcile(planned, tracks)
        val oldIds = sink.live.keys.toSet()
        sink.live.clear(); sink.operations.clear()
        updates.forget(); updates.clear()
        assertTrue(sink.operations.isEmpty())
        updates.reconcile(planned, tracks)
        assertEquals(2, sink.live.size)
        assertTrue(sink.live.keys.none { it in oldIds })
        assertEquals(tracks.single().zipWithNext(), edges(sink))
    }

    @Test fun `empty workout removes all lines and restarting does not reuse retired objects`() {
        val sink = Sink(); val updates = MapRouteUpdates(sink)
        updates.reconcile(path(100, 3), listOf(path(0, 3)))
        val oldIds = sink.live.keys.toSet()
        updates.reconcile(emptyList(), emptyList())
        assertTrue(sink.live.isEmpty())
        updates.reconcile(emptyList(), listOf(path(50, 3)))
        assertEquals(1, sink.live.size)
        assertTrue(sink.live.keys.none { it in oldIds })
        assertEquals(path(50, 3).zipWithNext(), edges(sink))
    }

    @Test fun `failed SDK create or update is retried instead of being treated as already rendered`() {
        val sink = Sink(); val updates = MapRouteUpdates(sink)
        sink.failNext = true
        assertFailsWith<IllegalStateException> { updates.reconcile(emptyList(), listOf(path(0, 2))) }
        updates.reconcile(emptyList(), listOf(path(0, 2)))
        val handle = sink.routes(MapRouteKind.TRACK).single()
        sink.failNext = true
        assertFailsWith<IllegalStateException> { updates.reconcile(emptyList(), listOf(path(0, 3))) }
        assertEquals(path(0, 2), handle.points)
        updates.reconcile(emptyList(), listOf(path(0, 3)))
        assertSame(handle, sink.routes(MapRouteKind.TRACK).single())
        assertEquals(path(0, 3), handle.points)
        assertEquals(listOf("create", "update"), sink.operations.map { it.name })
    }

    @Test fun `real engine manual pause resume and GPS gap stay separate in map updates`() {
        val sink = Sink(); val updates = MapRouteUpdates(sink)
        val epoch = 1700000000000L
        val engine = RunEngine(RunSummary(startedAt = epoch), Preferences(autoPause = false)).also { it.tick(0, epoch) }
        fun fix(seconds: Int, meters: Double) = LocationFix(Coordinate(meters / 111195.0802335329, 20.0),
            epoch + seconds * 1000L, seconds * 1000L, 3f, speed = 3f)
        fun render() {
            updates.reconcile(emptyList(), engine.points.groupBy { it.segment }.values.map { part -> part.map { it.coordinate } })
        }
        engine.ingest(fix(0, 0.0)); engine.ingest(fix(10, 30.0)); render()
        val completed = sink.routes(MapRouteKind.TRACK).single()
        engine.pause(); engine.tick(60000, epoch + 60000); engine.ingest(fix(61, 500.0)); render()
        engine.resume(62000); engine.ingest(fix(62, 500.0)); render()
        assertEquals(1, sink.live.size)
        engine.ingest(fix(72, 530.0)); render()
        engine.ingest(fix(100, 600.0)); render()
        assertEquals(2, sink.live.size)
        engine.ingest(fix(101, 603.0)); render()
        assertSame(completed, sink.routes(MapRouteKind.TRACK).first())
        val expected = engine.points.groupBy { it.segment }.values.flatMap { part -> part.map { it.coordinate }.zipWithNext() }
        assertEquals(expected, edges(sink))
        assertEquals(3, sink.live.size)
        assertEquals(63.0, engine.summary.meters, .01)
    }

    @Test fun `recovered engine rebuilds existing tracks once and resumes with an unconnected segment`() {
        val epoch = 1700000000000L
        fun fix(seconds: Int, meters: Double) = LocationFix(Coordinate(meters / 111195.0802335329, 20.0),
            epoch + seconds * 1000L, seconds * 1000L, 3f, speed = 3f)
        val original = RunEngine(RunSummary(startedAt = epoch), Preferences(autoPause = false)).also { it.tick(0, epoch) }
        original.ingest(fix(0, 0.0)); original.ingest(fix(10, 30.0))
        val recovered = RunEngine(original.summary.copy(status = RunStatus.INTERRUPTED), Preferences(autoPause = false), original.points)
        val sink = Sink(); val updates = MapRouteUpdates(sink)
        fun render() = updates.reconcile(emptyList(), recovered.points.groupBy { it.segment }.values.map { part -> part.map { it.coordinate } })
        render()
        val existing = sink.routes(MapRouteKind.TRACK).single()
        sink.operations.clear(); render()
        assertTrue(sink.operations.isEmpty())
        recovered.tick(100000, epoch + 100000); recovered.resume(101000)
        recovered.ingest(fix(101, 300.0)); render()
        recovered.ingest(fix(111, 330.0)); render()
        assertSame(existing, sink.routes(MapRouteKind.TRACK).first())
        assertEquals(listOf("create"), sink.operations.map { it.name })
        val expected = recovered.points.groupBy { it.segment }.values.flatMap { part -> part.map { it.coordinate }.zipWithNext() }
        assertEquals(expected, edges(sink))
        assertEquals(60.0, recovered.summary.meters, .01)
    }
}
