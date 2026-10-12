package com.runkeep.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.runkeep.app.RunkeepApp
import com.runkeep.app.data.PlanRow
import com.runkeep.core.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

@Composable fun PlansScreen(app: RunkeepApp, plans: List<WorkoutPlan>) {
    val scope = rememberCoroutineScope()
    val exit = LocalDraftExit.current
    var draftRaw by rememberSaveable { mutableStateOf<String?>(null) }
    val draft = remember(draftRaw) { draftRaw?.let { codec.decodeFromString<PlanEditorDraft>(it) } }
    var saving by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var deletionId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    fun close() { draftRaw = null; error = null }
    fun open(plan: WorkoutPlan) { draftRaw = codec.encodeToString(PlanEditorDraft(plan)); error = null }
    GuardDraftExit(draft?.dirty == true, onDiscard = ::close, enabled = !saving)
    BackHandler(enabled = draft != null) { if (!saving) exit.request(::close) }
    if (draft != null) {
        key(draft.original.id) {
            PlanMobileEditor(draft, saving, error,
                onChange = { draftRaw = codec.encodeToString(it); error = null },
                onCancel = { exit.request(::close) },
                onSave = {
                    if (!saving && draft.valid) {
                        saving = true
                        val candidate = draft.plan
                        scope.launch {
                            runCatching {
                                require(draft.valid) { "구간 입력 범위를 확인해주세요." }
                                app.db.dao().savePlan(PlanRow(candidate.id, codec.encodeToString(candidate)))
                            }.onSuccess { close() }.onFailure { error = "계획 저장 실패: ${it.message}" }
                            saving = false
                        }
                    }
                })
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            item {
                Text("페이스 계획", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                IconButton(onClick = { open(WorkoutPlan(name = "", steps = emptyList())) }, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Add, "계획 만들기") }
            }
            if (plans.isEmpty()) item { Text("구간별 거리·시간과 목표 페이스를 정해보세요.") }
            items(plans, key = { it.id }) { plan ->
                Surface(onClick = { open(plan) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(plan.name, style = MaterialTheme.typography.titleMedium)
                        Text("${plan.steps.size}구간 · ${measurementText(MeasurementKind.DISTANCE, plan.meters)} · ${paceText(plan.averagePace)}/km")
                        AdaptiveActions {
                            TextButton(onClick = { open(plan) }) { Text("수정") }
                            TextButton(onClick = { deletionId = plan.id; error = null }) { Text("삭제") }
                        }
                    }
                }
            }
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        }
    }
    plans.firstOrNull { it.id == deletionId }?.let { plan ->
        AlertDialog(onDismissRequest = { if (!deleting) deletionId = null }, title = { Text("계획을 삭제할까요?") },
            text = { Text("과거 운동에 저장된 계획은 유지됩니다.") },
            confirmButton = { TextButton(enabled = !deleting, onClick = {
                deleting = true
                scope.launch {
                    runCatching { app.db.dao().deletePlan(plan.id) }.onSuccess { deletionId = null }
                        .onFailure { error = "계획 삭제 실패: ${it.message}"; deletionId = null }
                    deleting = false
                }
            }) { Text(if (deleting) "삭제 중…" else "삭제") } },
            dismissButton = { TextButton(enabled = !deleting, onClick = { deletionId = null }) { Text("취소") } })
    }
}
