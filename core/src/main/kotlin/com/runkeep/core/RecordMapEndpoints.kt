package com.runkeep.core

import kotlin.math.abs

enum class RecordEndpointKind { START, END, BOTH }
data class RecordMapEndpoint(val coordinate: Coordinate, val kind: RecordEndpointKind, val anchorX: Float = .5f)
data class RecordEndpointScreenPoint(val x: Float, val y: Float)

/** The caller supplies the displayed record range; empty segments never create a synthetic endpoint. */
fun recordMapEndpoints(tracks: List<List<Coordinate>>): List<RecordMapEndpoint> {
    val start = tracks.firstOrNull { it.isNotEmpty() }?.first() ?: return emptyList()
    val end = tracks.lastOrNull { it.isNotEmpty() }?.last() ?: return emptyList()
    if (start == end) return listOf(RecordMapEndpoint(start, RecordEndpointKind.BOTH))
    return listOf(RecordMapEndpoint(start, RecordEndpointKind.START), RecordMapEndpoint(end, RecordEndpointKind.END))
}

/** Keep original coordinates; only overlapping screen boxes move outward by their icon anchors. */
fun recordEndpointAnchors(start: RecordEndpointScreenPoint, end: RecordEndpointScreenPoint, iconSize: Float): Pair<Float, Float> {
    require(iconSize.isFinite() && iconSize > 0)
    require(start.x.isFinite() && start.y.isFinite() && end.x.isFinite() && end.y.isFinite())
    if (abs(start.x - end.x) >= iconSize || abs(start.y - end.y) >= iconSize) return .5f to .5f
    return if (start.x <= end.x) 1f to 0f else 0f to 1f
}
