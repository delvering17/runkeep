package com.runkeep.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.runkeep.core.*
import kotlinx.serialization.encodeToString

@Composable fun PlanMobileEditor(draft: PlanEditorDraft, saving: Boolean, error: String?,
                                onChange: (PlanEditorDraft) -> Unit, onCancel: () -> Unit, onSave: () -> Unit) {
    val list = rememberLazyListState()
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var focusId by rememberSaveable { mutableStateOf<String?>(null) }
    var undoRaw by rememberSaveable { mutableStateOf<String?>(null) }
    var repeating by rememberSaveable { mutableStateOf(false) }
    var calculatorRaw by rememberSaveable { mutableStateOf<String?>(null) }
    val calculator = remember(calculatorRaw) { calculatorRaw?.let { codec.decodeFromString<PaceCalculatorDraft>(it) } }
    var revealCalculator by remember { mutableStateOf(false) }
    var dragOriginal by remember { mutableStateOf<PlanEditorDraft?>(null) }
    val latestDraft by rememberUpdatedState(draft)
    val change by rememberUpdatedState(onChange)
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val reorder = rememberRowReorder(list, draft.rows.map { it.id }, !saving,
        onMove = { id, target -> change(latestDraft.move(id, target)) },
        onStart = {
            dragOriginal = latestDraft; expandedId = null; focusId = null
            focusManager.clearFocus(); keyboard?.hide()
        },
        onEnd = { dragOriginal?.takeIf { it != latestDraft }?.let { undoRaw = codec.encodeToString(it) }; dragOriginal = null },
        onCancel = { dragOriginal?.let(change); dragOriginal = null })
    val enabled = !saving && reorder.draggingId == null
    fun edit(next: PlanEditorDraft) { undoRaw = null; onChange(next) }
    fun structural(next: PlanEditorDraft) {
        if (next != draft) { undoRaw = codec.encodeToString(draft); onChange(next) }
    }
    fun reveal(id: String) { expandedId = id; focusId = id }
    fun calculate(next: PaceCalculatorDraft) { calculatorRaw = codec.encodeToString(next) }
    LaunchedEffect(focusId) {
        val id = focusId ?: return@LaunchedEffect
        val index = draft.rows.indexOfFirst { it.id == id }
        if (index >= 0) list.scrollToItem(index + if (calculator != null) 2 else 1)
    }
    LaunchedEffect(revealCalculator) {
        if (revealCalculator) { list.scrollToItem(1); revealCalculator = false }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (draft.original.steps.isEmpty()) "계획 만들기" else "계획 수정", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).semantics { heading() })
            TextButton(enabled = !saving, onClick = onCancel) { Text("닫기") }
        }
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            val summaryMaximum = if (calculator != null) (maxHeight * .45f).coerceAtMost(240.dp) else (maxHeight * .25f).coerceAtMost(120.dp)
            Column(Modifier.fillMaxSize()) {
                LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    item(key = "plan-name") {
                        OutlinedTextField(draft.name, { edit(draft.copy(name = it.take(80))) }, label = { Text("계획 이름") },
                            modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = enabled,
                            isError = draft.name.isBlank() && draft.rows.isNotEmpty(),
                            supportingText = { if (draft.name.isBlank() && draft.rows.isNotEmpty()) Text("계획 이름을 입력해주세요.") })
                    }
                    if (calculator != null) item(key = "pace-calculator") {
                        PaceCalculatorInputs(calculator, enabled, ::calculate, onCancel = { calculatorRaw = null })
                    }
                    itemsIndexed(draft.rows, key = { _, row -> row.id }) { index, row ->
                        val dragging = reorder.draggingId == row.id
                        Surface(modifier = Modifier.fillMaxWidth().then(if (dragging) Modifier else Modifier.animateItem()).zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer { translationY = reorder.translation(row.id) }
                            .semantics {
                                customActions = buildList {
                                    if (enabled && index > 0) add(CustomAccessibilityAction("위로 이동") { structural(draft.move(row.id, index - 1)); true })
                                    if (enabled && index < draft.rows.lastIndex) add(CustomAccessibilityAction("아래로 이동") { structural(draft.move(row.id, index + 1)); true })
                                }
                            }, color = if (dragging || expandedId == row.id || calculator?.selectedRowId == row.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface) {
                            Column {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                RowReorderHandle(reorder, row.id, "${index + 1}번 구간 끌어서 순서 변경")
                                TextButton(enabled = enabled, modifier = Modifier.weight(1f), onClick = {
                                    calculator?.let { calculate(it.copy(selectedRowId = row.id)) }
                                    expandedId = if (expandedId == row.id) null else row.id
                                    focusId = null
                                }) {
                                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("${index + 1}. ${row.step.name.ifBlank { "이름 입력" }}", style = MaterialTheme.typography.titleMedium)
                                        Text("${if (row.step.kind == StepKind.DISTANCE) "거리" else "시간"} · ${measurementText(if (row.step.kind == StepKind.DISTANCE) MeasurementKind.DISTANCE else MeasurementKind.DURATION, row.step.amount)} · ${paceText(row.step.paceSeconds.toDouble())}/km")
                                    }
                                }
                            }
                            if (expandedId == row.id) {
                                Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    val requester = remember(row.id) { FocusRequester() }
                                    LaunchedEffect(focusId, row.id) {
                                        if (focusId == row.id) { withFrameNanos { }; requester.requestFocus(); focusId = null }
                                    }
                                    OutlinedTextField(row.step.name, { edit(draft.edit(row.id, row.step.copy(name = it.take(40)))) },
                                        label = { Text("구간 이름") }, modifier = Modifier.fillMaxWidth().focusRequester(requester), singleLine = true, enabled = enabled)
                                    DomainSelectionField("구간 기준", if (row.step.kind == StepKind.DISTANCE) "거리" else "시간",
                                        StepKind.entries.map { SelectionChoice(it.name, if (it == StepKind.DISTANCE) "거리" else "시간") }, row.step.kind.name,
                                        { name ->
                                            val kind = StepKind.valueOf(name)
                                            if (kind != row.step.kind) {
                                                val amount = if (kind == StepKind.DISTANCE) row.step.expectedMeters else row.step.expectedSeconds
                                                edit(draft.edit(row.id, row.step.copy(kind = kind, amount = amount.takeIf { it.isFinite() && it > 0 } ?: row.step.amount)))
                                            }
                                        }, enabled)
                                    if (enabled) {
                                        key(row.id, row.step.kind) {
                                            MeasurementField(if (row.step.kind == StepKind.DISTANCE) "구간 거리" else "구간 시간",
                                                if (row.step.kind == StepKind.DISTANCE) MeasurementKind.DISTANCE else MeasurementKind.DURATION, row.step.amount,
                                                { edit(latestDraft.edit(row.id, row.step.copy(amount = it))) })
                                        }
                                        MeasurementField("목표 페이스", MeasurementKind.PACE, row.step.paceSeconds.toDouble(),
                                            { edit(latestDraft.edit(row.id, row.step.copy(paceSeconds = it.toInt()))) })
                                    }
                                    AdaptiveActions {
                                        TextButton(enabled = enabled && draft.rows.size < PlanEditorDraft.MAX_ROWS, onClick = {
                                            val next = draft.clone(row.id)
                                            structural(next)
                                            reveal(next.rows[index + 1].id)
                                        }) { Text("복제") }
                                        TextButton(enabled = enabled, onClick = { structural(draft.remove(row.id)); expandedId = null }) { Text("구간 삭제") }
                                        TextButton(enabled = enabled, onClick = { expandedId = null }) { Text("접기") }
                                    }
                                }
                            }
                            if (row.step.name.isBlank() || !MeasurementLimits.step(row.step)) Text(
                                if (row.step.name.isBlank()) "구간 이름을 입력해주세요." else if (row.step.kind == StepKind.TIME) "운동 시간은 1초~200분으로 설정해주세요." else "거리는 0 초과~200km로 설정해주세요.",
                                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp).semantics { liveRegion = LiveRegionMode.Polite })
                            }
                        }
                    }
                    item(key = "add-row") {
                        TextButton(enabled = enabled && draft.rows.size < PlanEditorDraft.MAX_ROWS,
                            modifier = Modifier.heightIn(min = 48.dp), onClick = {
                                val next = draft.append()
                                structural(next)
                                reveal(next.rows.last().id)
                            }) { Text("구간 추가 (${draft.rows.size}/100)") }
                        AdaptiveActions {
                            TextButton(enabled = enabled && draft.rows.isNotEmpty() && draft.rows.size <= 50, onClick = { repeating = true }) { Text("전체 구간 반복") }
                            TextButton(enabled = enabled && undoRaw != null, onClick = {
                                undoRaw?.let { onChange(codec.decodeFromString<PlanEditorDraft>(it)) }
                                undoRaw = null; expandedId = null; focusId = null
                            }) { Text("실행 취소") }
                        }
                    }
                }
                Surface(tonalElevation = 3.dp) {
                    Column(Modifier.fillMaxWidth().heightIn(max = summaryMaximum).verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val plan = draft.plan
                        calculator?.let { PaceCalculatorSummary(it, draft) }
                        Text("${if (plan.steps.any { it.kind == StepKind.TIME }) "예상 거리" else "총 거리"} ${measurementText(MeasurementKind.DISTANCE, plan.meters)} · 예상 시간 ${durationText((plan.seconds * 1000).toLong())}",
                            modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite })
                        Text("거리 가중 목표 평균 ${paceText(plan.averagePace)}/km", style = MaterialTheme.typography.titleMedium)
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AdaptiveActions {
                    TextButton(enabled = enabled, modifier = Modifier.heightIn(min = 48.dp), onClick = {
                        if (calculator == null) calculate(PaceCalculatorDraft.forRow(draft.rows.firstOrNull { it.id == expandedId } ?: draft.rows.firstOrNull()))
                        revealCalculator = true
                        focusManager.clearFocus(); keyboard?.hide()
                    }) { Text(if (calculator == null) "계산기" else "계산 입력") }
                    if (calculator != null) Button(enabled = enabled && calculator.candidate(draft) != null,
                        modifier = Modifier.heightIn(min = 48.dp), onClick = {
                            calculator.apply(latestDraft)?.let { next ->
                                structural(next)
                                calculatorRaw = null
                                calculator.selectedRowId?.let { reveal(it) }
                                focusManager.clearFocus(); keyboard?.hide()
                            }
                        }) { Text("선택 구간에 적용") }
                }
                Button(enabled = enabled && draft.valid, onClick = onSave, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text(if (saving) "저장 중…" else "계획 저장")
                }
            }
        }
    }
    if (repeating) PlanRepeatSheet(draft.rows.size, onCancel = { repeating = false }, onConfirm = {
        structural(draft.repeat(it)); repeating = false
    })
}

@Composable private fun PlanRepeatSheet(rowCount: Int, onCancel: () -> Unit, onConfirm: (Int) -> Unit) {
    var count by rememberSaveable { mutableStateOf("2") }
    val parsed = count.toIntOrNull()?.takeIf { it in 2..20 && rowCount <= PlanEditorDraft.MAX_ROWS / it }
    TaskSheet("전체 구간 반복", onCancel, actions = {
        AdaptiveActions {
            TextButton(onClick = onCancel) { Text("취소") }
            Button(enabled = parsed != null, onClick = { parsed?.let(onConfirm) }) { Text("적용") }
        }
    }) {
        OutlinedTextField(count, { if (it.all { char -> char in '0'..'9' }) count = it.take(8) }, label = { Text("반복 횟수") },
            modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, isError = parsed == null)
        Text(if (parsed == null) "2~20회, 총 100구간까지 반복할 수 있습니다." else "${rowCount}구간 × ${parsed}회 = ${rowCount * parsed}구간",
            color = if (parsed == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
}
