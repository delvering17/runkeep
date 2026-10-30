package com.runkeep.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.runkeep.core.Coordinate
import com.runkeep.core.RecordEndpointKind
import com.runkeep.core.recordMapEndpoints
import com.runkeep.core.RecordEndpointScreenPoint
import com.runkeep.core.recordEndpointAnchors
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.*

/** Explicitly a route diagram. It never supplies invented geographic coordinates. */
@Composable fun RunMap(planned: List<Coordinate>, modifier: Modifier = Modifier, tracks: List<List<Coordinate>> = emptyList(),
                       current: Coordinate? = null, onTap: ((Coordinate) -> Unit)? = null,
                       centerOnUser: Boolean = false, followUserInitially: Boolean = false,
                       selectedPoint: Int? = null, onPointSelected: ((Int) -> Unit)? = null,
                       onPointMoved: ((Int, Coordinate) -> Unit)? = null, showDistances: Boolean = false,
                       focusPoint: Coordinate? = null, useLocationProvider: Boolean = true,
                       selectedSegment: Int? = null, segmentLabels: List<String> = emptyList(), onSegmentSelected: ((Int) -> Unit)? = null, showRecordEndpoints: Boolean = false) {
    val endpoints = if (showRecordEndpoints) recordMapEndpoints(tracks) else emptyList()
    Surface(modifier.semantics { if (endpoints.isNotEmpty()) contentDescription = "기록 경로 · 출발 삼각형 · 종료 깃발" }, color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.large) {
        Column(Modifier.padding(16.dp)) {
            Text("경로 미리보기", style = MaterialTheme.typography.labelLarge)
            Text("지도 표시와 지도에서 지점 추가·이동은 지원하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            val all = planned + tracks.flatten()
            val color = MaterialTheme.colorScheme.primary
            val muted = MaterialTheme.colorScheme.outline
            Canvas(Modifier.fillMaxWidth().weight(1f).padding(12.dp)) {
                if (all.isNotEmpty()) {
                    val minX = all.minOf { it.lon }; val minY = all.minOf { it.lat }
                    val factor = cos(Math.toRadians(all.first().lat))
                    val width = ((all.maxOf { it.lon } - minX) * factor).coerceAtLeast(.0001)
                    val height = (all.maxOf { it.lat } - minY).coerceAtLeast(.0001)
                    val scale = min(size.width / width, size.height / height)
                    fun xy(c: Coordinate) = Offset(((c.lon - minX) * factor * scale + (size.width - width * scale) / 2).toFloat(),
                        (size.height - (c.lat - minY) * scale - (size.height - height * scale) / 2).toFloat())
                    fun line(points: List<Coordinate>, actual: Boolean) {
                        val path = Path(); points.forEachIndexed { i, c -> val p = xy(c); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                        drawPath(path, if (actual) color else muted, style = Stroke(if (actual) 6f else 3f))
                    }
                    line(planned, false); tracks.forEach { line(it, true) }
                    selectedSegment?.takeIf { it in 0 until planned.lastIndex }?.let { index ->
                        drawLine(color, xy(planned[index]), xy(planned[index + 1]), strokeWidth = 8f)
                    }
                    val radius = 12.dp.toPx()
                    val anchors = if (endpoints.size == 2) {
                        val start = xy(endpoints.first().coordinate); val end = xy(endpoints.last().coordinate)
                        recordEndpointAnchors(RecordEndpointScreenPoint(start.x, start.y),
                            RecordEndpointScreenPoint(end.x, end.y), radius * 2)
                    } else .5f to .5f
                    endpoints.forEachIndexed { index, endpoint ->
                        val point = xy(endpoint.coordinate)
                        val anchor = if (index == 0) anchors.first else anchors.second
                        val iconCenter = point + Offset((.5f - anchor) * radius * 2, 0f)
                        fun start(at: Offset) {
                            drawCircle(color, radius, at)
                            val triangle = Path().apply { moveTo(at.x - radius / 3, at.y - radius / 2); lineTo(at.x + radius / 2, at.y); lineTo(at.x - radius / 3, at.y + radius / 2); close() }
                            drawPath(triangle, androidx.compose.ui.graphics.Color.White)
                        }
                        fun end(at: Offset) {
                            drawRect(color, at - Offset(radius, radius), androidx.compose.ui.geometry.Size(radius * 2, radius * 2))
                            drawLine(androidx.compose.ui.graphics.Color.White, at + Offset(-radius / 2, -radius / 2), at + Offset(-radius / 2, radius / 2), 2.dp.toPx())
                            drawRect(androidx.compose.ui.graphics.Color.White, at + Offset(-radius / 2, -radius / 2), androidx.compose.ui.geometry.Size(radius, radius * .6f))
                        }
                        when (endpoint.kind) {
                            RecordEndpointKind.START -> start(iconCenter)
                            RecordEndpointKind.END -> end(iconCenter)
                            RecordEndpointKind.BOTH -> { start(point - Offset(radius, 0f)); end(point + Offset(radius, 0f)) }
                        }
                    }
                    current?.let { drawCircle(color, 8f, xy(it)) }
                }
            }
            selectedSegment?.let { segmentLabels.getOrNull(it) }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            MapCameraControls(fitEnabled = false, locationEnabled = false, following = false,
                onFit = {}, onLocation = {}, modifier = Modifier.align(Alignment.End))
        }
    }
}
