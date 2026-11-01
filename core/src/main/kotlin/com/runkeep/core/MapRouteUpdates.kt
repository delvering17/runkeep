package com.runkeep.core

enum class MapRouteKind { PLANNED, TRACK }

interface MapRouteSink<H> {
    fun create(kind: MapRouteKind, points: List<Coordinate>): H
    fun update(kind: MapRouteKind, handle: H, points: List<Coordinate>)
    fun remove(handle: H)
}

class MapRouteUpdates<H>(private val sink: MapRouteSink<H>, private val chunkSize: Int = 256) {
    init { require(chunkSize >= 2) }

    private data class Key(val kind: MapRouteKind, val track: Int, val chunk: Int)
    private data class Entry<H>(val handle: H, val points: List<Coordinate>)
    private val entries = linkedMapOf<Key, Entry<H>>()

    fun reconcile(planned: List<Coordinate>, tracks: List<List<Coordinate>>, diagnostic: ((MapDiagnostic) -> Unit)? = null) {
        val counts = Counts()
        fun result(reason: DiagnosticReason) = mapDiagnostic(reason, planned, tracks).copy(
            createdRoutes = counts.created, updatedRoutes = counts.updated, removedRoutes = counts.removed)
        try {
            applyRoutes(planned, tracks, counts)
            reportMapDiagnostic(diagnostic, result(if (counts.created + counts.updated + counts.removed == 0)
                DiagnosticReason.UPDATE_UNCHANGED else DiagnosticReason.UPDATE_APPLIED))
        } catch (failure: Exception) {
            reportMapDiagnostic(diagnostic, result(DiagnosticReason.UPDATE_FAILED))
            throw failure
        }
    }

    private class Counts(var created: Int = 0, var updated: Int = 0, var removed: Int = 0)
    private fun applyRoutes(planned: List<Coordinate>, tracks: List<List<Coordinate>>, counts: Counts) {
        val retained = mutableSetOf<Key>()
        fun reconcilePart(kind: MapRouteKind, track: Int, points: List<Coordinate>) {
            var start = 0
            var chunk = 0
            while (start < points.size - 1) {
                val end = (start.toLong() + chunkSize).coerceAtMost(points.size.toLong()).toInt()
                val key = Key(kind, track, chunk)
                retained.add(key)
                val previous = entries[key]
                val unchanged = previous != null && previous.points.size == end - start &&
                    previous.points.indices.all { previous.points[it] == points[start + it] }
                if (!unchanged) {
                    val snapshot = points.subList(start, end).toList()
                    val handle = if (previous == null) sink.create(kind, snapshot).also { counts.created++ } else {
                        sink.update(kind, previous.handle, snapshot)
                        counts.updated++
                        previous.handle
                    }
                    entries[key] = Entry(handle, snapshot)
                }
                start = end - 1
                chunk++
            }
        }
        reconcilePart(MapRouteKind.PLANNED, 0, planned)
        tracks.forEachIndexed { index, part -> reconcilePart(MapRouteKind.TRACK, index, part) }
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key !in retained) {
                sink.remove(entry.value.handle)
                counts.removed++
                iterator.remove()
            }
        }
    }

    fun clear() {
        val iterator = entries.iterator()
        while (iterator.hasNext()) {
            sink.remove(iterator.next().value.handle)
            iterator.remove()
        }
    }

    fun forget() { entries.clear() }
}
