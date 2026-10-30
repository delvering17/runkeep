package com.runkeep.app.ui

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.runkeep.app.tracking.*
import com.runkeep.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

@Serializable private data class ReplayValueDraft(
    val stageId: String? = null, val kind: ReplayStageKind = ReplayStageKind.RUN,
    val hours: String = "0", val minutes: String = "0", val seconds: String = "30",
    val paceMinutes: String = "6", val paceSeconds: String = "05", val gpsJitter: Boolean = false
) {
    val duration get() = parseElapsed(hours, minutes, seconds)
    val pace get() = parsePaceParts(paceMinutes, paceSeconds)
    val valid get() = if (stageId == null) pace != null else duration != null && (kind == ReplayStageKind.STOP || pace != null)
}

/** 테스트 초안은 저장할 때만 반영하고 닫기는 확인 없이 버린다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CustomReplayEditor(initial: CustomReplayScenario, saving: Boolean, error: String?,
                                   onCancel: () -> Unit, onSave: (CustomReplayScenario) -> Unit) {
    var draftRaw by rememberSaveable(initial.id) { mutableStateOf(codec.encodeToString(initial)) }
    var valueRaw by rememberSaveable(initial.id) { mutableStateOf<String?>(null) }
    val draft = codec.decodeFromString<CustomReplayScenario>(draftRaw)
    val value = valueRaw?.let { codec.decodeFromString<ReplayValueDraft>(it) }
    val latestDraft by rememberUpdatedState(draft)
    val list = rememberLazyListState()
    var draggingId by remember { mutableStateOf<String?>(null) }
    var dragTop by remember { mutableFloatStateOf(0f) }
    var dragHeight by remember { mutableIntStateOf(0) }
    var originalOrder by remember { mutableStateOf<List<ReplayStage>?>(null) }
    val edge = with(LocalDensity.current) { 48.dp.toPx() }
    fun update(next: CustomReplayScenario) { draftRaw = codec.encodeToString(next) }
    fun editValue(next: ReplayValueDraft) { valueRaw = codec.encodeToString(next) }
    fun reorderAtPointer() {
        val id = draggingId ?: return
        val stages = latestDraft.stages
        val from = stages.indexOfFirst { it.id == id }
        val center = dragTop + dragHeight / 2f
        val target = list.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key != id && item.key is String && stages.any { it.id == item.key } &&
                center >= item.offset && center <= item.offset + item.size &&
                ((item.index - 1 > from && center > item.offset + item.size / 2f) ||
                    (item.index - 1 < from && center < item.offset + item.size / 2f))
        } ?: return
        update(latestDraft.moveStageTo(id, stages.indexOfFirst { it.id == target.key }))
    }
    // 화면 가장자리에서 자동 스크롤하여 보이지 않는 구간까지 드래그한다.
    LaunchedEffect(draggingId) {
        while (draggingId != null) {
            withFrameNanos { }
            val info = list.layoutInfo
            val center = dragTop + dragHeight / 2f
            val scroll = when {
                center < info.viewportStartOffset + edge -> -edge / 5f
                center > info.viewportEndOffset - edge -> edge / 5f
                else -> 0f
            }
            if (scroll != 0f) { list.scrollBy(scroll); reorderAtPointer() }
        }
    }
    fun close() { if (!saving) onCancel() }
    val window = mobileWindow()
    // 저장 실패 후에도 초안을 유지하고 숨겨진 시트만 다시 생성한다.
    key(saving) {
        ModalBottomSheet(onDismissRequest = ::close,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            contentWindowInsets = { WindowInsets.safeDrawing }) {
            Column(Modifier.fillMaxWidth().heightIn(max = window.height * .9f).imePadding().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (value == null) "시나리오" else if (value.stageId == null) "목표 페이스" else "구간 설정",
                    style = MaterialTheme.typography.titleLarge)
                if (value != null) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) { ReplayValueFields(value, ::editValue) }
                } else {
                    LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        item(key = "header") {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(draft.name, { update(draft.copy(name = it.take(40))) },
                                    label = { Text("이름") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving)
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween) {
                                    TextButton(enabled = !saving, onClick = {
                                        editValue(ReplayValueDraft(paceMinutes = (draft.targetPaceSeconds / 60).toString(),
                                            paceSeconds = (draft.targetPaceSeconds % 60).toString().padStart(2, '0')))
                                    }) { Text("목표 ${paceText(draft.targetPaceSeconds.toDouble())}/km") }
                                    Text("전체 ${durationText(draft.totalSeconds * 1000)}", style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                        itemsIndexed(draft.stages, key = { _, stage -> stage.id }) { index, stage ->
                            val dragging = draggingId == stage.id
                            val translation = if (dragging) dragTop - (list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == stage.id }?.offset?.toFloat() ?: dragTop) else 0f
                            OutlinedCard(modifier = Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f)
                                .graphicsLayer { translationY = translation }
                                .semantics {
                                    customActions = buildList {
                                        if (!saving && index > 0) add(CustomAccessibilityAction("위로 이동") { update(draft.moveStage(stage.id, -1)); true })
                                        if (!saving && index < draft.stages.lastIndex) add(CustomAccessibilityAction("아래로 이동") { update(draft.moveStage(stage.id, 1)); true })
                                    }
                                }, colors = CardDefaults.outlinedCardColors(containerColor =
                                    if (dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface)) {
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(48.dp).pointerInput(stage.id, saving) {
                                        if (!saving) detectDragGesturesAfterLongPress(
                                            onDragStart = {
                                                list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == stage.id }?.let { item ->
                                                    originalOrder = latestDraft.stages
                                                    dragTop = item.offset.toFloat(); dragHeight = item.size; draggingId = stage.id
                                                }
                                            }, onDrag = { change, amount ->
                                                change.consume(); dragTop += amount.y; reorderAtPointer()
                                            }, onDragEnd = { draggingId = null; originalOrder = null },
                                            onDragCancel = {
                                                originalOrder?.let { update(latestDraft.copy(stages = it)) }
                                                draggingId = null; originalOrder = null
                                            })
                                    }, contentAlignment = Alignment.Center) {
                                        Icon(Icons.Default.DragHandle, "${index + 1}번 구간 길게 눌러 순서 변경")
                                    }
                                    TextButton(enabled = !saving && draggingId == null, modifier = Modifier.weight(1f), onClick = {
                                        editValue(ReplayValueDraft(stage.id, stage.kind, (stage.seconds / 3600).toString(),
                                            (stage.seconds / 60 % 60).toString(), (stage.seconds % 60).toString().padStart(2, '0'),
                                            (stage.paceSeconds / 60).toString(), (stage.paceSeconds % 60).toString().padStart(2, '0'), stage.gpsJitter))
                                    }) {
                                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                            Text("${index + 1}. ${stage.kind.title}" + if (stage.kind == ReplayStageKind.STOP && stage.gpsJitter) " · 흔들림" else "")
                                            Text(durationText(stage.seconds * 1000L) + if (stage.kind == ReplayStageKind.STOP) "" else " · ${paceText(stage.paceSeconds.toDouble())}/km",
                                                style = MaterialTheme.typography.bodyMedium)
                                        }
                                    }
                                    IconButton(enabled = !saving && draggingId == null, onClick = {
                                        update(draft.copy(stages = draft.stages.filterNot { it.id == stage.id }))
                                    }) { Icon(Icons.Default.DeleteOutline, "${index + 1}번 구간 삭제") }
                                }
                            }
                        }
                        item(key = "add") {
                            OutlinedButton(enabled = draft.stages.size < ReplayLimits.MAX_STAGES && !saving && draggingId == null,
                                modifier = Modifier.fillMaxWidth(), onClick = {
                                    val stage = ReplayStage(paceSeconds = draft.targetPaceSeconds)
                                    update(draft.copy(stages = draft.stages + stage))
                                    editValue(ReplayValueDraft(stage.id, paceMinutes = (stage.paceSeconds / 60).toString(),
                                        paceSeconds = (stage.paceSeconds % 60).toString().padStart(2, '0')))
                                }) { Text("구간 추가") }
                        }
                    }
                    draft.validationMessage()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(enabled = !saving, modifier = Modifier.weight(1f), onClick = {
                        if (value != null) valueRaw = null else close()
                    }) { Text("취소") }
                    Button(modifier = Modifier.weight(1f), enabled = !saving && draggingId == null &&
                        (value?.valid ?: (draft.validationMessage() == null)), onClick = {
                        if (value == null) onSave(draft)
                        else {
                            if (value.stageId == null) update(draft.copy(targetPaceSeconds = value.pace!!))
                            else update(draft.copy(stages = draft.stages.map { stage ->
                                if (stage.id == value.stageId) stage.copy(kind = value.kind, seconds = value.duration!!,
                                    paceSeconds = value.pace ?: stage.paceSeconds, gpsJitter = value.gpsJitter) else stage
                            }))
                            valueRaw = null
                        }
                    }) { Text(if (value != null) "적용" else if (saving) "저장 중…" else "저장") }
                }
                Spacer(Modifier.height(12.dp))
            }
        }
    }
}

@Composable private fun ReplayValueFields(value: ReplayValueDraft, onChange: (ReplayValueDraft) -> Unit) {
    if (value.stageId != null) {
        var kindOpen by rememberSaveable { mutableStateOf(false) }
        SelectionRow("구간 종류", value.kind.title, { kindOpen = !kindOpen })
        if (kindOpen) ReplayStageKind.entries.forEach { kind ->
            SelectionOptionRow(SelectionChoice(kind.name, kind.title), value.kind == kind, {
                onChange(value.copy(kind = kind)); kindOpen = false
            })
        }
        Text("지속 시간")
        AdaptiveActions {
            ReplayNumber("시", value.hours, { onChange(value.copy(hours = it)) })
            ReplayNumber("분", value.minutes, { onChange(value.copy(minutes = it)) })
            ReplayNumber("초", value.seconds, { onChange(value.copy(seconds = it)) })
        }
        AdaptiveActions { listOf(10, 30, 60, 120).forEach { seconds ->
            FilterChip(modifier = Modifier.heightIn(min = 48.dp), selected = value.duration == seconds,
                onClick = { onChange(value.copy(hours = "0", minutes = (seconds / 60).toString(), seconds = (seconds % 60).toString().padStart(2, '0'))) },
                label = { Text(if (seconds < 60) "${seconds}초" else "${seconds / 60}분") })
        } }
        if (value.duration == null) Text("시간은 1초~200분, 분·초는 0~59로 입력해주세요.", color = MaterialTheme.colorScheme.error)
    }
    if (value.stageId != null && value.kind == ReplayStageKind.STOP) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value.gpsJitter, role = Role.Switch) {
            onChange(value.copy(gpsJitter = it))
        }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("GPS 흔들림", modifier = Modifier.weight(1f))
            Switch(value.gpsJitter, onCheckedChange = null)
        }
    }
    if (value.stageId == null || value.kind != ReplayStageKind.STOP) {
        Text(if (value.stageId == null) "목표 페이스 /km" else "페이스 /km")
        AdaptiveActions {
            ReplayNumber("분", value.paceMinutes, { onChange(value.copy(paceMinutes = it)) })
            ReplayNumber("초", value.paceSeconds, { onChange(value.copy(paceSeconds = it)) })
        }
        AdaptiveActions { listOf(300, 365, 420).forEach { pace ->
            FilterChip(modifier = Modifier.heightIn(min = 48.dp), selected = value.pace == pace,
                onClick = { onChange(value.copy(paceMinutes = (pace / 60).toString(), paceSeconds = (pace % 60).toString().padStart(2, '0'))) },
                label = { Text("${paceText(pace.toDouble())}/km") })
        } }
        if (value.pace == null) Text("페이스는 2:00~30:00/km, 초는 0~59로 입력해주세요.", color = MaterialTheme.colorScheme.error)
    }
}

@Composable private fun ReplayNumber(label: String, value: String, onChange: (String) -> Unit) {
    val window = mobileWindow()
    val width = if (window.fontScale >= 1.5f || window.width < 320.dp) Modifier.fillMaxWidth() else Modifier.width(88.dp)
    OutlinedTextField(value, { next -> if (next.all(Char::isDigit)) onChange(next.take(5)) }, label = { Text(label) },
        modifier = width, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}
