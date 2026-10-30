package com.runkeep.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.runkeep.app.RunkeepApp
import com.runkeep.app.tracking.*
import com.runkeep.core.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

@Composable fun GpsSimulatorPanel(app: RunkeepApp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val simulator = app.location.simulation
    val replay by simulator.state.collectAsState()
    val live by app.live.collectAsState()
    val store = remember(context) { ReplayLibraryStore(context) }
    val loaded = remember(store) { store.load() }
    var library by remember(store) { mutableStateOf(loaded.getOrElse { ReplayLibrary() }) }
    var libraryError by remember(store) { mutableStateOf(loaded.exceptionOrNull()?.let { it.message ?: "저장 내용을 확인해주세요." }) }
    var saving by remember { mutableStateOf(false) }
    var writeError by remember { mutableStateOf<String?>(null) }
    var customId by rememberSaveable { mutableStateOf(library.selectedId) }
    var selected by rememberSaveable { mutableStateOf(ReplayScenario.STATIONARY.name) }
    var editingRaw by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteId by rememberSaveable { mutableStateOf<String?>(null) }
    var resetLibrary by rememberSaveable { mutableStateOf(false) }
    var pending by rememberSaveable { mutableStateOf(false) }
    val scenario = ReplayScenario.valueOf(selected)
    val custom = library.scenarios.firstOrNull { it.id == customId }
    val title = custom?.name ?: scenario.title
    val total = custom?.totalSeconds?.toInt() ?: scenario.seconds
    val active = live.summary?.status?.let { it != RunStatus.FINISHED } == true
    val canConfigure = !active && !pending && !saving
    fun resetReplay() { if (simulator.enabled && !simulator.leased) simulator.disable() }
    fun persist(next: ReplayLibrary, onSaved: () -> Unit = {}) {
        saving = true; writeError = null
        scope.launch {
            try {
                library = store.save(next); customId = library.selectedId; libraryError = null
                onSaved()
            } catch (e: Exception) {
                writeError = e.message ?: "시나리오를 저장하지 못했습니다."
            } finally { saving = false }
        }
    }
    fun begin() {
        scope.launch {
            try {
                check(app.db.dao().unfinished() == null) { "미완료 운동을 달리기 화면에서 먼저 종료해주세요." }
                if (custom == null) simulator.prepare(scenario) else simulator.prepare(custom)
                val targetPace = custom?.targetPaceSeconds ?: 365
                val plan = WorkoutPlan(name = "GPS 테스트 · 목표 ${paceText(targetPace.toDouble())}/km", steps = listOf(
                    PlanStep(kind = StepKind.DISTANCE, amount = maxOf(10_000.0, (custom?.inputMeters ?: 0.0) + 1000), paceSeconds = targetPace)))
                val course = Course(name = "GPS 테스트 경로", points = listOf(0.0, 200.0, 400.0, 800.0, 1400.0).map(::coordinateAt))
                RunService.send(context, RunService.START, RunSummary(startedAt = System.currentTimeMillis(), course = course, plan = plan))
            } catch (e: Exception) { pending = false; app.error.value = e.message ?: "테스트를 시작하지 못했습니다." }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) begin()
        else { pending = false; app.error.value = "기록 서비스 테스트에는 정확한 위치 권한이 필요합니다." }
    }
    LaunchedEffect(pending, active, replay.phase) {
        if (active) pending = false
        else if (pending && replay.phase == ReplayPhase.READY) {
            kotlinx.coroutines.delay(5000)
            if (!simulator.leased) {
                simulator.disable(); pending = false
                app.error.value = "기록 서비스가 시작되지 않았습니다. 위치 권한을 확인하고 다시 시도해주세요."
            }
        }
    }
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("GPS 테스트", style = MaterialTheme.typography.titleMedium)
            DomainSelectionField("시나리오", title,
                ReplayScenario.entries.map { SelectionChoice("builtin:" + it.name, "기본 · ${it.title}") } +
                    library.scenarios.map { SelectionChoice("custom:" + it.id, "내 시나리오 · ${it.name}", durationText(it.totalSeconds * 1000)) },
                custom?.let { "custom:" + it.id } ?: ("builtin:" + selected), { key ->
                    resetReplay()
                    if (key.startsWith("builtin:")) {
                        selected = key.removePrefix("builtin:"); customId = null
                        if (libraryError == null) persist(library.copy(selectedId = null))
                    } else persist(library.copy(selectedId = key.removePrefix("custom:")))
                }, canConfigure)
            AdaptiveActions {
                OutlinedButton(enabled = canConfigure && libraryError == null && library.scenarios.size < ReplayLimits.MAX_SCENARIOS,
                    onClick = { writeError = null; editingRaw = codec.encodeToString(CustomReplayScenario()) }) { Text("새 시나리오") }
                if (custom != null) {
                    TextButton(enabled = canConfigure, onClick = { writeError = null; editingRaw = codec.encodeToString(custom) }) { Text("편집") }
                    TextButton(enabled = canConfigure, onClick = { deleteId = custom.id }) { Text("삭제") }
                }
            }
            if (libraryError != null) {
                Text("저장한 시나리오를 읽지 못했습니다. 원본을 보존했습니다. $libraryError", color = MaterialTheme.colorScheme.error)
                TextButton(enabled = canConfigure, onClick = { resetLibrary = true }) { Text("저장 시나리오 초기화") }
            }
            if (editingRaw == null) writeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Text(custom?.let { "${it.stages.size}구간 · 목표 ${paceText(it.targetPaceSeconds.toDouble())}/km · ${durationText(it.totalSeconds * 1000)}" }
                ?: durationText(total * 1000L), style = MaterialTheme.typography.bodyMedium)
            if (simulator.enabled) {
                LinearProgressIndicator(progress = { if (replay.total > 0) (replay.second.toFloat() / replay.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                Text("${durationText(replay.second * 1000L)} / ${durationText((if (replay.total > 0) replay.total else total) * 1000L)} · " + when (replay.phase) {
                    ReplayPhase.RUNNING -> "재생 중"
                    ReplayPhase.PAUSED -> "GPS 입력 쉬는 중"
                    ReplayPhase.COMPLETE -> "재생 완료"
                    else -> "대기"
                }, style = MaterialTheme.typography.bodyMedium)
                Text(replay.detail, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = {
                pending = true
                val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                if (Build.VERSION.SDK_INT >= 33) required += Manifest.permission.POST_NOTIFICATIONS
                permissions.launch(required.toTypedArray())
            }, enabled = canConfigure && !simulator.leased, modifier = Modifier.fillMaxWidth()) { Text(if (pending) "시작 준비 중…" else "테스트 시작") }
            if (simulator.enabled && active) {
                OutlinedButton(onClick = {
                    if (replay.phase == ReplayPhase.PAUSED) simulator.play() else simulator.pause()
                }, enabled = replay.phase in listOf(ReplayPhase.RUNNING, ReplayPhase.PAUSED), modifier = Modifier.fillMaxWidth()) {
                    Text(if (replay.phase == ReplayPhase.PAUSED) "GPS 입력 계속" else "GPS 입력 쉬기")
                }
                OutlinedButton(onClick = { RunService.send(context, RunService.FINISH) }, modifier = Modifier.fillMaxWidth()) { Text("테스트 종료") }
            }
            if (simulator.enabled && !active && !simulator.leased) TextButton(onClick = {
                scope.launch {
                    if (app.db.dao().unfinished() != null) app.error.value = "미완료 운동을 먼저 종료해주세요."
                    else simulator.disable()
                }
            }) { Text("시뮬레이터 해제") }
        }
    }
    editingRaw?.let { raw ->
        CustomReplayEditor(codec.decodeFromString(raw), saving, writeError,
            onCancel = { editingRaw = null; writeError = null }, onSave = { candidate ->
                try {
                    persist(library.upsert(candidate)) { resetReplay(); editingRaw = null }
                } catch (e: Exception) { writeError = e.message }
            })
    }
    deleteId?.let { id ->
        AlertDialog(onDismissRequest = { if (!saving) deleteId = null }, title = { Text("시나리오 삭제") },
            text = { Text("이 시나리오를 삭제할까요? 기존 테스트 운동 기록은 유지됩니다.") },
            confirmButton = { TextButton(enabled = !saving, onClick = {
                persist(library.remove(id)) { resetReplay(); deleteId = null }
            }) { Text("삭제") } }, dismissButton = { TextButton(enabled = !saving, onClick = { deleteId = null }) { Text("취소") } })
    }
    if (resetLibrary) AlertDialog(onDismissRequest = { if (!saving) resetLibrary = false }, title = { Text("저장 시나리오 초기화") },
        text = { Text("읽지 못한 저장 시나리오를 지우고 새로 시작할까요? 기본 시나리오와 운동 기록은 유지됩니다.") },
        confirmButton = { TextButton(enabled = !saving, onClick = { persist(ReplayLibrary()) { resetReplay(); resetLibrary = false } }) { Text("초기화") } },
        dismissButton = { TextButton(enabled = !saving, onClick = { resetLibrary = false }) { Text("취소") } })
}
