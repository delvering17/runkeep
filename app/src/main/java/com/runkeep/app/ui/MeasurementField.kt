package com.runkeep.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.runkeep.core.*
import java.math.BigDecimal

/** A clock time must use a separate clock picker. These values are quantities. */
enum class MeasurementKind { DISTANCE, DURATION, PACE }

fun measurementText(kind: MeasurementKind, value: Double?): String = if (value == null) "없음" else if (!value.isFinite()) "입력 확인 필요" else when (kind) {
    MeasurementKind.DISTANCE -> "${decimalText(value / 1000)} km"
    MeasurementKind.DURATION -> durationText((value * 1000).toLong())
    MeasurementKind.PACE -> "${paceText(value)} /km"
}
private fun decimalText(value: Double) = BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

/** Caller owns applied value; the sheet owns an isolated, saveable draft until confirm. */
@Composable fun MeasurementField(label: String, kind: MeasurementKind, value: Double?, onConfirm: (Double) -> Unit,
                                 minimumMeters: Double = Double.MIN_VALUE, onClear: (() -> Unit)? = null) {
    var open by rememberSaveable { mutableStateOf(false) }
    SelectionRow(label, measurementText(kind, value), onClick = { open = true })
    if (open) MeasurementSheet(label, kind, value, minimumMeters, onCancel = { open = false },
        onConfirm = { candidate -> onConfirm(candidate); open = false }, onClear = onClear?.let { { it(); open = false } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun MeasurementSheet(title: String, kind: MeasurementKind, value: Double?, minimumMeters: Double,
                                        onCancel: () -> Unit, onConfirm: (Double) -> Unit, onClear: (() -> Unit)?) {
    val initial = value?.takeIf { it.isFinite() } ?: when (kind) { MeasurementKind.DISTANCE -> 1000.0; MeasurementKind.DURATION -> 600.0; MeasurementKind.PACE -> 390.0 }
    var unitName by rememberSaveable { mutableStateOf(DistanceUnit.KILOMETERS.name) }
    val unit = DistanceUnit.valueOf(unitName)
    var distance by rememberSaveable { mutableStateOf(distanceInputText(initial, DistanceUnit.KILOMETERS)) }
    var hours by rememberSaveable { mutableStateOf((initial.toInt() / 3600).toString()) }
    var minutes by rememberSaveable { mutableStateOf((if (kind == MeasurementKind.PACE) initial.toInt() / 60 else initial.toInt() / 60 % 60).toString()) }
    var seconds by rememberSaveable { mutableStateOf((initial.toInt() % 60).toString().padStart(2, '0')) }
    val parsed = when (kind) {
        MeasurementKind.DISTANCE -> parseDistance(distance, unit, minimumMeters)
        MeasurementKind.DURATION -> parseElapsed(hours, minutes, seconds)?.toDouble()
        MeasurementKind.PACE -> parsePaceParts(minutes, seconds)?.toDouble()
    }
    val transaction = InputDraft(value, parsed)
    val range = when (kind) {
        MeasurementKind.DISTANCE -> if (minimumMeters == 100.0) "거리 0.1~200 km" else "거리 0 초과~200 km"
        MeasurementKind.DURATION -> "운동 시간 1초~3시간 20분 · 분·초 0~59"
        MeasurementKind.PACE -> "목표 페이스 2:00~30:00 /km · 초 0~59"
    }
    fun choose(next: Double) {
        when (kind) {
            MeasurementKind.DISTANCE -> distance = distanceInputText(next, unit)
            MeasurementKind.DURATION -> { hours = (next.toInt() / 3600).toString(); minutes = (next.toInt() / 60 % 60).toString(); seconds = (next.toInt() % 60).toString().padStart(2, '0') }
            MeasurementKind.PACE -> { minutes = (next.toInt() / 60).toString(); seconds = (next.toInt() % 60).toString().padStart(2, '0') }
        }
    }
    TaskSheet(title, onCancel, actions = {
        AdaptiveActions {
            TextButton(onClick = onCancel) { Text("취소") }
            if (onClear != null) TextButton(onClick = onClear) { Text("해제") }
            Button(enabled = parsed != null, onClick = { transaction.confirm { it != null }?.let(onConfirm) }) { Text("적용") }
        }
    }) {
        if (parsed != null && kind != MeasurementKind.DISTANCE) Text(measurementText(kind, parsed), style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        if (kind == MeasurementKind.DISTANCE) {
            DistanceEditor(distance, unit, parsed == null, onTextChange = { distance = it }, onUnitChange = { next ->
                distance = convertDistanceText(distance, unit, next)
                unitName = next.name
            })
        } else {
            BoxWithConstraints {
                val columns = if (maxWidth < 320.dp || mobileWindow().fontScale >= 1.5f) 1 else 3
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = columns) {
                    if (kind == MeasurementKind.DURATION) NumberPart("시", hours, { hours = it }, Modifier.weight(1f), parsed == null)
                    NumberPart("분", minutes, { minutes = it }, Modifier.weight(1f), parsed == null)
                    NumberPart("초", seconds, { seconds = it }, Modifier.weight(1f), parsed == null)
                }
            }
        }
        if (parsed == null) Text("${range} 범위의 숫자를 입력해주세요.", color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        AdaptiveActions {
            if (kind == MeasurementKind.DISTANCE) {
                DistancePreset.entries.forEach { preset ->
                    FilterChip(modifier = Modifier.heightIn(min = 48.dp), selected = parsed == preset.meters,
                        onClick = { choose(preset.meters) }, label = { Text(distancePresetLabel(preset)) })
                }
            } else {
                val presets = if (kind == MeasurementKind.DURATION) listOf(300.0, 600.0, 1800.0) else listOf(300.0, 360.0, 390.0, 420.0)
                presets.forEach { preset -> FilterChip(modifier = Modifier.heightIn(min = 48.dp), selected = parsed == preset, onClick = { choose(preset) }, label = { Text(measurementText(kind, preset)) }) }
            }
        }
        val increment = when (kind) { MeasurementKind.DISTANCE -> 100.0; MeasurementKind.DURATION -> 60.0; MeasurementKind.PACE -> 5.0 }
        fun allowed(next: Double) = when (kind) { MeasurementKind.DISTANCE -> MeasurementLimits.distance(next, minimumMeters); MeasurementKind.DURATION -> MeasurementLimits.duration(next.toInt()); MeasurementKind.PACE -> MeasurementLimits.pace(next.toInt()) }
        AdaptiveActions {
            val unitLabel = when (kind) { MeasurementKind.DISTANCE -> "100 m"; MeasurementKind.DURATION -> "1분"; MeasurementKind.PACE -> "5초/km" }
            OutlinedButton(enabled = parsed?.let { allowed(it - increment) } == true, onClick = { parsed?.let { choose(it - increment) } }) { Text("− $unitLabel") }
            OutlinedButton(enabled = parsed?.let { allowed(it + increment) } == true, onClick = { parsed?.let { choose(it + increment) } }) { Text("+ $unitLabel") }
        }
    }
}

@Composable private fun NumberPart(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier, isError: Boolean) {
    OutlinedTextField(value, { onChange(it.take(8)) }, label = { Text(label) }, modifier = modifier, singleLine = true, isError = isError,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next))
}
