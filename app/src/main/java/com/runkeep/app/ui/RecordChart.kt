package com.runkeep.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.unit.dp
import com.runkeep.core.*
import kotlin.math.abs

@Composable internal fun RecordChart(title: String, unit: String, samples: List<RecordChartSample>, gps: List<TrackPoint>,
                                    startedAt: Long, axis: RecordChartAxis, pace: Boolean = false, maxGapMs: Long = 30_000) {
    val series = remember(samples, gps, startedAt, axis, maxGapMs) { RecordChartPolicy.series(samples, gps, startedAt, axis, maxGapMs) }
    val values = remember(series) { series.points }
    var selected by remember(series) { mutableStateOf<Int?>(null) }
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val fontScale = mobileWindow().fontScale
    val yLabelWidth = (48 * fontScale.coerceAtMost(2f)).dp
    fun valueText(value: Double) = if (pace) paceText(value) else "%.0f".format(value)
    fun xText(x: Double) = if (series.axis == RecordChartAxis.TIME) durationText((x * 1000).toLong()) else "%.2f km".format(x)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section(title)
        if (values.isEmpty()) { Text("표시할 $title 자료가 없습니다."); return@Column }
        val ticks = RecordChartPolicy.ticks(values.minOf { it.value }, values.maxOf { it.value })
        val min = ticks.first(); val max = ticks.last()
        val maxX = values.maxOf { it.x }.coerceAtLeast(if (series.axis == RecordChartAxis.TIME) 1.0 else .01)
        val summary = "${values.size}개 표시 · 최소 ${valueText(values.minOf { it.value })} $unit · 최대 ${valueText(values.maxOf { it.value })} $unit"
        Text("$unit · ${if (series.axis == RecordChartAxis.TIME) "시작 후 경과 시간" else "누적 거리"}", style = MaterialTheme.typography.labelMedium)
        Text(summary, style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().height((200 * fontScale.coerceAtLeast(1f)).dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.width(yLabelWidth).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                (if (pace) ticks else ticks.reversed()).forEach { Text(valueText(it), style = MaterialTheme.typography.labelSmall) }
            }
            Canvas(Modifier.weight(1f).fillMaxHeight().pointerInput(series) {
                detectTapGestures { position ->
                    val x = (position.x / size.width).coerceIn(0f, 1f) * maxX
                    selected = values.indices.minByOrNull { abs(values[it].x - x) }
                }
            }.semantics {
                contentDescription = "$title 선 그래프. $summary. ${if (pace) "빠른 페이스가 위쪽입니다." else "큰 값이 위쪽입니다."}"
                customActions = listOf(
                    CustomAccessibilityAction("이전 표본 값") { selected = ((selected ?: values.size) - 1).coerceAtLeast(0); true },
                    CustomAccessibilityAction("다음 표본 값") { selected = ((selected ?: -1) + 1).coerceAtMost(values.lastIndex); true }
                )
            }) {
                fun at(point: RecordChartPoint): Offset {
                    val fraction = ((point.value - min) / (max - min)).toFloat()
                    return Offset((point.x / maxX * size.width).toFloat(), (if (pace) fraction else 1 - fraction) * size.height)
                }
                ticks.forEach { tick ->
                    val fraction = ((tick - min) / (max - min)).toFloat()
                    val y = (if (pace) fraction else 1 - fraction) * size.height
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
                for (part in series.parts) {
                    part.zipWithNext().forEach { (a, b) -> drawLine(color, at(a), at(b), strokeWidth = 2.dp.toPx()) }
                    if (part.size == 1) drawCircle(color, 3.dp.toPx(), at(part.single()))
                }
                selected?.let { index ->
                    val location = at(values[index])
                    drawLine(grid, Offset(location.x, 0f), Offset(location.x, size.height), 1.dp.toPx())
                    drawCircle(color, 5.dp.toPx(), location)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = yLabelWidth + 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(0.0, maxX / 2, maxX).forEachIndexed { index, tick -> Text(xText(tick), modifier = Modifier.weight(1f), textAlign = when (index) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End }, style = MaterialTheme.typography.labelSmall) }
        }
        selected?.let { index -> Text("${xText(values[index].x)} · ${valueText(values[index].value)} $unit", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (axis == RecordChartAxis.DISTANCE && series.axis == RecordChartAxis.TIME) Text("거리 자료 없음 · 시간 기준", style = MaterialTheme.typography.bodySmall)
        else if (series.axis == RecordChartAxis.DISTANCE && series.mappedCount < series.sourceCount)
            Text("거리 위치 확인 ${series.mappedCount}/${series.sourceCount}개", style = MaterialTheme.typography.bodySmall)
    }
}
