package com.runkeep.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.runkeep.core.*
import kotlinx.serialization.encodeToString

@Composable fun CoursePaceSummary(course: Course) {
    val estimate = remember(course) { course.paceEstimate() }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Text("코스 ${measurementText(MeasurementKind.DISTANCE, estimate.totalMeters)}")
        Text("목표 평균 ${paceText(estimate.averagePace)}/km · 예상 시간 ${estimate.seconds?.let { durationText((it * 1000).toLong()) } ?: "미설정"}")
        if (!estimate.complete) Text("미설정 구간 있음", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable fun CoursePaceControls(course: Course, plans: List<WorkoutPlan>, selectedLeg: Int?, enabled: Boolean = true,
    onChange: (Course) -> Unit) {
    var calculatorRaw by rememberSaveable(course.id) { mutableStateOf<String?>(null) }
    val calculator = remember(calculatorRaw) { calculatorRaw?.let { codec.decodeFromString<PaceCalculatorDraft>(it) } }
    val current by rememberUpdatedState(course)
    val change by rememberUpdatedState(onChange)
    val index = selectedLeg?.takeIf { it in 0 until course.points.lastIndex }
    val edgeKey = index?.let { "${course.pointIds[it]}→${course.pointIds[it + 1]}" }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DomainSelectionField("설정", if (course.usePaceTargets) "코스 · 페이스" else "코스만",
            listOf(SelectionChoice("course", "코스만"), SelectionChoice("pace", "코스 · 페이스")),
            if (course.usePaceTargets) "pace" else "course", { onChange(course.copy(usePaceTargets = it == "pace")) }, enabled)
        if (course.usePaceTargets) {
            DomainSelectionField("목표 전환", if (course.paceTransition == CoursePaceTransition.WAYPOINT) "실제 지점 통과" else "계획 누적 거리",
                CoursePaceTransition.entries.map { SelectionChoice(it.name, if (it == CoursePaceTransition.WAYPOINT) "실제 지점 통과" else "계획 누적 거리") },
                course.paceTransition.name, { onChange(course.copy(paceTransition = CoursePaceTransition.valueOf(it))) }, enabled)
            if (enabled) {
                if (plans.isNotEmpty()) DomainSelectionField("계획 가져오기", "선택", plans.map { SelectionChoice(it.id, it.name) }, null, { id ->
                    plans.firstOrNull { it.id == id }?.let { change(current.withPlanPaces(it)) }
                })
                if (course.points.size >= 2) MeasurementField("전체 구간에 같은 목표 적용", MeasurementKind.PACE, null,
                    { change(current.withAllPaces(it.toInt())) }, onClear = { change(current.withAllPaces(null)) })
                if (index != null) key(edgeKey) {
                    MeasurementField("${index + 1} → ${index + 2} 목표 페이스", MeasurementKind.PACE, course.paceAt(index)?.toDouble(),
                        { pace ->
                            val target = current.pointIds.zipWithNext().indexOfFirst { (a, b) -> "$a→$b" == edgeKey }
                            change(current.withPace(target, pace.toInt()))
                        }, onClear = {
                            val target = current.pointIds.zipWithNext().indexOfFirst { (a, b) -> "$a→$b" == edgeKey }
                            change(current.withPace(target, null))
                        })
                    TextButton(onClick = {
                        val meters = Geo.distance(course.points[index], course.points[index + 1])
                        calculatorRaw = codec.encodeToString(PaceCalculatorDraft(selectedRowId = edgeKey, meters = meters,
                            seconds = null, paceSeconds = course.paceAt(index)?.toDouble() ?: 360.0))
                    }) { Text("이 구간 페이스 계산") }
                }
            }
            calculator?.let { value ->
                PaceCalculatorInputs(value, enabled, { calculatorRaw = codec.encodeToString(it) }, { calculatorRaw = null })
                val result = value.result
                val target = course.pointIds.zipWithNext().indexOfFirst { (a, b) -> "$a→$b" == value.selectedRowId }
                val pace = result?.takeIf { it.paceSeconds in 120.0..1800.0 }?.roundedPace?.toInt()
                Text("계산 목표 ${paceText(result?.paceSeconds)}/km", style = MaterialTheme.typography.titleMedium)
                if (target < 0) Text("계산 대상 구간이 바뀌었습니다. 구간을 다시 선택해주세요.", color = MaterialTheme.colorScheme.error)
                Button(enabled = enabled && target >= 0 && pace != null, onClick = {
                    if (pace != null) { onChange(course.withPace(target, pace)); calculatorRaw = null }
                }) { Text("${if (target >= 0) "${target + 1} → ${target + 2} " else ""}목표에 적용") }
            }
            CoursePaceSummary(course)
        }
    }
}
