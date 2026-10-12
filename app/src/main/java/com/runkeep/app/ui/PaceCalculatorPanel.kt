package com.runkeep.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.runkeep.core.*

@Composable internal fun PaceCalculatorInputs(value: PaceCalculatorDraft, enabled: Boolean,
                                              onChange: (PaceCalculatorDraft) -> Unit, onCancel: () -> Unit) {
    Surface(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("거리·시간·페이스 계산", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            DomainSelectionField("계산할 값", when (value.mode) {
                PaceCalculationMode.PACE -> "페이스"; PaceCalculationMode.DURATION -> "전체 시간"; PaceCalculationMode.DISTANCE -> "거리"
            }, PaceCalculationMode.entries.map { SelectionChoice(it.name, when (it) {
                PaceCalculationMode.PACE -> "페이스"; PaceCalculationMode.DURATION -> "전체 시간"; PaceCalculationMode.DISTANCE -> "거리"
            }) }, value.mode.name, { onChange(value.copy(mode = PaceCalculationMode.valueOf(it))) }, enabled)
            if (enabled) {
                if (value.mode != PaceCalculationMode.DISTANCE) MeasurementField("계산 거리", MeasurementKind.DISTANCE,
                    value.meters, { onChange(value.copy(meters = it)) }, onClear = { onChange(value.copy(meters = null)) })
                if (value.mode != PaceCalculationMode.DURATION) MeasurementField("전체시간 · 시/분/초", MeasurementKind.DURATION,
                    value.seconds, { onChange(value.copy(seconds = it)) }, onClear = { onChange(value.copy(seconds = null)) })
                if (value.mode != PaceCalculationMode.PACE) MeasurementField("페이스 · 분/초 per km", MeasurementKind.PACE,
                    value.paceSeconds, { onChange(value.copy(paceSeconds = it)) }, onClear = { onChange(value.copy(paceSeconds = null)) })
            }
            AdaptiveActions {
                TextButton(enabled = enabled, onClick = { onChange(value.copy(mode = PaceCalculationMode.PACE, meters = 10000.0, seconds = 3600.0)) }) { Text("10km · 1시간") }
                TextButton(enabled = enabled, onClick = { onChange(value.copy(mode = PaceCalculationMode.PACE, meters = 5000.0, seconds = 1825.0)) }) { Text("5km · 30분 25초") }
                TextButton(enabled = enabled, onClick = onCancel) { Text("계산 취소") }
            }
        }
    }
}

@Composable internal fun PaceCalculatorSummary(value: PaceCalculatorDraft, draft: PlanEditorDraft) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        val selected = value.selected(draft)
        val result = value.result
        val candidate = value.candidate(draft)
        Text(if (selected == null) "적용할 구간을 선택해주세요." else
            "선택 ${draft.rows.indexOf(selected) + 1}. ${selected.step.name} · ${stepCalculationText(selected.step)}")
        if (result == null) {
            Text(if (value.inputsValid) "계산 가능한 범위를 벗어났습니다." else "계산할 두 값을 입력해주세요.", color = MaterialTheme.colorScheme.error)
        } else {
            Text("계산 ${distanceInputText(result.meters, DistanceUnit.KILOMETERS)} km · ${calculatorTimeText(result.seconds)} · ${calculatorTimeText(result.paceSeconds, pace = true)}/km",
                style = MaterialTheme.typography.titleMedium)
            if (candidate != null) Text("적용 후 ${stepCalculationText(candidate)}")
            else if (selected != null) Text("계획 적용 범위 초과 · 거리 200km 이하, 시간 1초~200분, 페이스 2:00~30:00/km",
                color = MaterialTheme.colorScheme.error)
            Text("시간·페이스는 초 단위 반올림", style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun stepCalculationText(step: PlanStep): String =
    "${if (step.kind == StepKind.DISTANCE) "거리" else "시간"} ${measurementText(if (step.kind == StepKind.DISTANCE) MeasurementKind.DISTANCE else MeasurementKind.DURATION, step.amount)} · ${paceText(step.paceSeconds.toDouble())}/km"
