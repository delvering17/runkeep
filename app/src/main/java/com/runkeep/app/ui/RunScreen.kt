package com.runkeep.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.runkeep.app.tracking.RunService
import com.runkeep.core.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RunScreen(live: LiveRun, unfinished: RunSummary?, courses: List<Course>, plans: List<WorkoutPlan>, prefs: Preferences,
                         onStart: (RunSummary) -> Unit, onPlans: () -> Unit, onDetail: (String) -> Unit, onCommand: (String) -> Unit,
                         recentRun: RunSummary? = null, onSettings: () -> Unit = {}) {
    var courseId by rememberSaveable { mutableStateOf<String?>(null) }
    var planId by rememberSaveable { mutableStateOf<String?>(null) }
    var goalMeters by rememberSaveable { mutableStateOf<Double?>(null) }
    var steadyPace by rememberSaveable { mutableStateOf<Double?>(null) }
    var finishRunId by rememberSaveable { mutableStateOf<String?>(null) }
    var pending by rememberSaveable { mutableStateOf(false) }
    val current = live.summary?.takeIf { it.status != RunStatus.FINISHED }
    val recovery = unfinished?.takeIf { current == null }
    val active = current ?: recovery
    val preparing = active == null
    val course = courses.find { it.id == courseId }
    val plan = plans.find { it.id == planId }
    val courseChoices = remember(courses) { courses.map { it.id to runPreparationCourseLabel(it) } }
    var courseDraftRaw by rememberSavedOptionalCourseDraft(course?.id, course?.version) {
        course?.let { codec.encodeToString(if (plan != null) it.withPlanPaces(plan) else it) }
    }
    val configuredCourse = remember(courseDraftRaw) { courseDraftRaw?.let { codec.decodeFromString<Course>(it) } }
    var selectedCoursePointId by rememberSaveable(course?.id) { mutableStateOf<String?>(null) }
    val selectedCourseLeg = configuredCourse?.let { selected ->
        selected.pointIds.indexOfFirst { it == selectedCoursePointId }.takeIf { it in 0 until selected.points.lastIndex } ?: 0
    }
    val selection = RunPreparationSelection(configuredCourse, plan, goalMeters, steadyPace)
    val coursePace = selection.coursePace
    var courseControlsOpen by rememberSaveable(course?.id) { mutableStateOf(false) }
    val panel = rememberBottomSheetScaffoldState()
    val window = mobileWindow()
    val density = LocalDensity.current
    var summaryHeight by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun togglePanel() { scope.launch { if (panel.bottomSheetState.currentValue == SheetValue.Expanded) panel.bottomSheetState.partialExpand() else panel.bottomSheetState.expand() } }
    LaunchedEffect(live.summary, live.preparation) {
        pending = false
        if (finishRunId != null && finishRunId != active?.id) finishRunId = null
    }
    LaunchedEffect(pending) { if (pending) { kotlinx.coroutines.delay(5000); pending = false } }
    live.preparation?.let { preparation ->
        BackHandler { onCommand(RunService.forRun(RunService.CANCEL_START, preparation.requestId)) }
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("출발까지", style = MaterialTheme.typography.titleLarge)
                Text("${preparation.remainingSeconds}초", style = MaterialTheme.typography.displayLarge,
                    modifier = Modifier.semantics(mergeDescendants = true) {})
            }
            OutlinedButton(onClick = { onCommand(RunService.forRun(RunService.CANCEL_START, preparation.requestId)) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("취소") }
        }
        return
    }
    if (preparing) {
        Column(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("오늘 달리기", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    IconButton(onClick = onPlans, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(Icons.Outlined.Add, "코스 · 페이스 계획 관리")
                    }
                    IconButton(onClick = onSettings, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                        Icon(Icons.Outlined.Settings, "달리기 설정")
                    }
                }
                SelectionMenu("코스", courseChoices, courseId, "없음 · 페이스만") { courseId = it }
                HorizontalDivider()
                SelectionMenu("페이스 계획", plans.map { it.id to "${it.name} · ${paceText(it.averagePace)}/km" }, planId, "없음") { id ->
                    planId = id
                    plans.firstOrNull { it.id == id }?.let { selectedPlan ->
                        configuredCourse?.let { courseDraftRaw = codec.encodeToString(it.withPlanPaces(selectedPlan)) }
                    }
                }
                HorizontalDivider()
                if (configuredCourse != null) {
                    SelectionRow("코스 목표", if (coursePace) {
                        val estimate = configuredCourse.paceEstimate()
                        "${estimate.averagePace?.let { paceText(it) + "/km" } ?: "구간별 목표"} · ${if (courseControlsOpen) "접기" else "설정"}"
                    } else "코스만 · ${if (courseControlsOpen) "접기" else "설정"}", { courseControlsOpen = !courseControlsOpen })
                    if (courseControlsOpen) {
                        if (coursePace) SelectionMenu("편집할 코스 구간", configuredCourse.pointIds.dropLast(1).mapIndexed { index, id ->
                            id to "${index + 1} → ${index + 2} · %.2f km · ${configuredCourse.paceAt(index)?.let { paceText(it.toDouble()) + "/km" } ?: "미설정"}".format(
                                Geo.distance(configuredCourse.points[index], configuredCourse.points[index + 1]) / 1000)
                        }, configuredCourse.pointIds.getOrNull(selectedCourseLeg ?: 0), "선택") { selectedCoursePointId = it }
                        CoursePaceControls(configuredCourse, plans = emptyList(), selectedLeg = selectedCourseLeg,
                            onChange = { courseDraftRaw = codec.encodeToString(it); if (!it.usePaceTargets) planId = null })
                    }
                    HorizontalDivider()
                }
                if (!coursePace && plan != null) {
                    PreparationSummaryRow("예상 거리", measurementText(MeasurementKind.DISTANCE, plan.meters))
                    PreparationSummaryRow("목표 평균", "${paceText(plan.averagePace)}/km")
                    PreparationSummaryRow("예상 시간", durationText((plan.seconds * 1000).toLong()))
                } else if (!coursePace) {
                    MeasurementField("목표 거리", MeasurementKind.DISTANCE, goalMeters, { goalMeters = it }, minimumMeters = 100.0,
                        onClear = { goalMeters = null; steadyPace = null })
                    if (goalMeters != null) MeasurementField("목표 페이스", MeasurementKind.PACE, steadyPace, { steadyPace = it }, onClear = { steadyPace = null })
                } else {
                    val estimate = requireNotNull(configuredCourse).paceEstimate()
                    PreparationSummaryRow("목표 거리", measurementText(MeasurementKind.DISTANCE, estimate.totalMeters))
                    PreparationSummaryRow("예상 시간", estimate.seconds?.let { durationText((it * 1000).toLong()) } ?: "미설정 구간 있음")
                }
                if (!selection.valid) Text("목표 거리와 페이스를 확인해주세요.", color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Text("출발 대기 ${if (prefs.startDelaySeconds == 0) "꺼짐" else "${prefs.startDelaySeconds}초"} · 음성 ${if (prefs.voice) "켬" else "끔"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                listOfNotNull(recentRun, live.summary).filter { it.status == RunStatus.FINISHED }.maxByOrNull { it.startedAt }?.let { finished ->
                    HorizontalDivider(Modifier.padding(top = 12.dp))
                    SelectionRow("최근 달리기", "%.2f km · %s · %s/km".format(finished.meters / 1000,
                        durationText(finished.activeMs), paceText(finished.averagePace)), { onDetail(finished.id) })
                }
            }
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Button(onClick = {
                        pending = true
                        onStart(selection.startRequest(prefs.weightKg))
                    }, enabled = selection.valid && !pending, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(20.dp)) {
                        Icon(Icons.Outlined.PlayArrow, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (pending) "준비 중…" else "달리기 시작", style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(when {
                    recovery != null -> "중단된 기록"
                    current?.status == RunStatus.RUNNING -> "달리는 중"
                    current?.status == RunStatus.AUTO_PAUSED -> "자동 일시정지"
                    current?.status == RunStatus.INTERRUPTED -> "복구된 기록"
                    else -> "일시정지"
                }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
                Text(if (recovery != null || current?.status == RunStatus.INTERRUPTED) "중단 시간과 거리는 기록에서 제외됩니다." else live.gpsMessage,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val panelHeight = if (window.useSupportingPane) maxHeight else maxHeight - minOf(144.dp, maxHeight * .5f)
            val defaultPeek = if (window.fontScale >= 1.5f) 160.dp else 108.dp
            val peekHeight = maxOf(defaultPeek, with(density) { summaryHeight.toDp() } + 48.dp).coerceAtMost(maxHeight * .6f)
            MapSupportingPanel(state = panel, peekHeight = peekHeight, panel = {
                Column(Modifier.fillMaxWidth().heightIn(max = panelHeight).padding(horizontal = 20.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(max = panelHeight * .6f).onSizeChanged { summaryHeight = it.height }
                        .verticalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                            Text("현재 페이스", style = MaterialTheme.typography.labelMedium)
                            Text("${paceText(live.pace)} /km", style = MaterialTheme.typography.headlineMedium)
                            Text(active?.let { "%.2f km · %s".format(it.meters / 1000, durationText(it.activeMs)) }.orEmpty(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (!window.useSupportingPane) IconButton(onClick = ::togglePanel) {
                            val expanded = panel.bottomSheetState.currentValue == SheetValue.Expanded
                            Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, if (expanded) "진행 패널 접기" else "진행 상세 펼치기")
                        }
                    }
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val progress = requireNotNull(active)
                        AdaptiveActions {
                            Metric("거리", "%.2f".format(progress.meters / 1000), suffix = " km")
                            Metric("운동 시간", durationText(progress.activeMs))
                            Metric("평균 페이스", paceText(progress.averagePace), suffix = " /km")
                        }
                        progress.goalMeters?.let { target ->
                            LinearProgressIndicator(progress = { (progress.meters / target).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                            Text(if (progress.goalActiveMs != null) "목표 달성 · 추가 기록 중" else "목표 %.2f km · 남은 거리 %.2f km".format(target / 1000, (target - progress.meters).coerceAtLeast(0.0) / 1000))
                        }
                        if (progress.coursePaceEnabled) {
                            Text(when {
                                progress.coursePaceComplete -> "코스 페이스 계획 완료"
                                progress.courseLegIndex < 0 -> "출발 지점 통과 대기"
                                else -> "${progress.courseLegIndex + 1} → ${progress.courseLegIndex + 2} · ${progress.targetPace?.let { paceText(it.toDouble()) + "/km" } ?: "목표 미설정"}"
                            }, style = MaterialTheme.typography.titleMedium)
                        }
                        progress.plan?.takeUnless { progress.coursePaceEnabled }?.let { selectedPlan ->
                            val step = selectedPlan.steps.getOrNull(progress.planIndex)
                            Text(step?.let { "${progress.planIndex + 1}/${selectedPlan.steps.size} · ${it.name} · 목표 ${paceText(it.paceSeconds.toDouble())}/km" } ?: "페이스 계획 완료", style = MaterialTheme.typography.titleMedium)
                        }
                        live.cue?.let { Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp)) { Text(it, Modifier.fillMaxWidth().padding(16.dp)) } }
                        if (current != null) PaceTable(current, live.points)
                        Metric("케이던스", live.cadence?.let { "%.0f spm".format(it) } ?: "—")
                    }
                }
            }) { padding ->
                key(active?.id) {
                    RunMap(active?.course?.points ?: emptyList(),
                        tracks = if (current != null) live.points.groupBy { it.segment }.values.map { part -> part.map { it.coordinate } } else emptyList(),
                        selectedSegment = active?.takeIf { it.coursePaceEnabled && !it.coursePaceComplete }?.courseLegIndex?.takeIf { it >= 0 },
                        current = live.position, centerOnUser = true, followUserInitially = true,
                        modifier = Modifier.fillMaxSize().padding(padding))
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                AdaptiveActions {
                    val running = current?.status == RunStatus.RUNNING
                    Button(onClick = { active?.id?.let { onCommand(RunService.forRun(if (running) RunService.PAUSE else RunService.RESUME, it)) } }, modifier = Modifier.heightIn(min = 56.dp)) { Text(if (running) "일시정지" else if (recovery != null) "기록 재개" else "재개") }
                    OutlinedButton(onClick = { finishRunId = active?.id }, modifier = Modifier.heightIn(min = 56.dp)) { Text("종료") }
                }
            }
        }
    }
    finishRunId?.let { runId ->
        AlertDialog(onDismissRequest = { finishRunId = null }, title = { Text("달리기를 마칠까요?") },
            text = { Text("지금까지의 전체 기록을 저장합니다.") },
            confirmButton = { TextButton(onClick = { finishRunId = null; onCommand(RunService.forRun(RunService.FINISH, runId)) }) { Text("저장하고 종료") } },
            dismissButton = { TextButton(onClick = { finishRunId = null }) { Text("계속 달리기") } })
    }
}

@Composable private fun PreparationSummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable fun SelectionMenu(label: String, options: List<Pair<String, String>>, selected: String?, none: String, onSelect: (String?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val state = rememberDomainSelectionState()
    val choices = listOf(SelectionChoice("none", none)) + options.map { (id, title) -> SelectionChoice("value:$id", title) }
    SelectionRow(label, options.find { it.first == selected }?.second ?: none, { open = true })
    if (open) DomainSelectionSheet(label, choices, selected?.takeIf { id -> options.any { it.first == id } }?.let { "value:$it" } ?: "none",
        onSelect = { key -> onSelect(options.firstOrNull { "value:${it.first}" == key }?.first); open = false },
        onCancel = { open = false }, state = state)
}
