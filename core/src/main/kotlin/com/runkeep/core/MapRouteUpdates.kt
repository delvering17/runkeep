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

    fun reconcile(planned: List<Coordinate>, tracks: List<List<Coordinate>>) {
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
                    val handle = if (previous == null) sink.create(kind, snapshot) else {
                        sink.update(kind, previous.handle, snapshot)
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
