package com.runkeep.app.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.runkeep.app.RunkeepApp
import com.runkeep.app.data.HealthImport
import com.runkeep.app.data.HeartRow
import com.runkeep.core.healthimport.*
import com.runkeep.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.time.*
import java.time.format.DateTimeFormatter

@Composable fun HistoryScreen(runs: List<RunSummary>, open: (String) -> Unit) = HistoryScreen(runs, open, emptySet())

@Composable fun HistoryScreen(runs: List<RunSummary>, open: (String) -> Unit, heartRunIds: Set<String>) {
    var anchorText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var periodName by rememberSaveable { mutableStateOf(RecordPeriod.WEEK.name) }
    var viewName by rememberSaveable { mutableStateOf(RecordView.LIST.name) }
    var sortName by rememberSaveable { mutableStateOf(RecordSort.NEWEST.name) }
    var courseName by rememberSaveable { mutableStateOf(RecordCourseFilter.ALL.name) }
    var courseId by rememberSaveable { mutableStateOf<String?>(null) }
    var heartName by rememberSaveable { mutableStateOf(RecordHeartFilter.ALL.name) }
    var selectedDate by rememberSaveable { mutableStateOf<String?>(null) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    val scroll = rememberLazyListState()
    val filterSelection = rememberDomainSelectionState()
    val window = mobileWindow()
    val period = RecordPeriod.valueOf(periodName)
    val view = RecordView.valueOf(viewName)
    val anchor = LocalDate.parse(anchorText)
    val zone = ZoneId.systemDefault()
    val range = remember(anchor, period, zone) { RecordBrowserPolicy.range(anchor, period, zone) }
    val filters = RecordFilters(RecordCourseFilter.valueOf(courseName), courseId, RecordHeartFilter.valueOf(heartName))
    val periodRecords = remember(runs, range, filters, heartRunIds, sortName) {
        RecordBrowserPolicy.records(runs, range, filters, heartRunIds, RecordSort.valueOf(sortName))
    }
    val day = selectedDate?.let(LocalDate::parse)?.takeIf { it >= range.start && it < range.endExclusive }
    val records = if (view == RecordView.CALENDAR && day != null)
        periodRecords.filter { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() == day } else periodRecords
    val counts = periodRecords.groupingBy { Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate() }.eachCount()
    val courses = runs.mapNotNull { it.course }.distinctBy { it.id }.sortedBy { it.name }
    val filterValue = listOfNotNull(when (filters.course) { RecordCourseFilter.ALL -> null; RecordCourseFilter.LINKED -> "코스 있음"; RecordCourseFilter.UNLINKED -> "코스 없음" },
        courseId?.let { id -> courses.find { it.id == id }?.name ?: "선택 코스" },
        when (filters.heart) { RecordHeartFilter.ALL -> null; RecordHeartFilter.PRESENT -> "심박 있음"; RecordHeartFilter.ABSENT -> "심박 없음" }).joinToString(" · ").ifBlank { "전체" }
    fun move(direction: Long) { anchorText = RecordBrowserPolicy.move(anchor, period, direction).toString(); selectedDate = null }
    LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { move(-1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 ${if (period == RecordPeriod.WEEK) "주" else "월"}") }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (period == RecordPeriod.MONTH) "${anchor.year}년 ${anchor.monthValue}월" else "${range.start.year}년 ${range.start.monthValue}월 ${range.start.dayOfMonth}일 주",
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                    Text("${range.start.format(DateTimeFormatter.ofPattern("M.d"))} – ${range.endExclusive.minusDays(1).format(DateTimeFormatter.ofPattern("M.d"))}", style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { move(1) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 ${if (period == RecordPeriod.WEEK) "주" else "월"}") }
                IconButton(onClick = { sheet = "view" }) { Icon(Icons.Default.Tune, "기록 보기 설정") }
            }
            Text("${if (period == RecordPeriod.WEEK) "주" else "월"} · ${if (view == RecordView.LIST) "리스트" else "달력"} · ${periodRecords.size}회 · %.2f km".format(periodRecords.sumOf(RecordBrowserPolicy::distance) / 1000), style = MaterialTheme.typography.bodyMedium)
            SelectionRow("정렬", recordSortLabel(RecordSort.valueOf(sortName)), { sheet = "sort" })
            SelectionRow("필터", filterValue, { sheet = "filter" })
            HorizontalDivider()
        }
        if (view == RecordView.CALENDAR) item(key = "calendar") {
            val leading = if (period == RecordPeriod.MONTH) range.start.dayOfWeek.value - 1 else 0
            val dates = List<LocalDate?>(leading) { null } + range.dates
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val columns = if (maxWidth < 336.dp || window.fontScale >= 1.5f) 4 else 7
                Column {
                    if (columns == 7) Row(Modifier.fillMaxWidth()) { listOf("월", "화", "수", "목", "금", "토", "일").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelSmall) } }
                    dates.chunked(7).forEach { week ->
                        (week + List<LocalDate?>(7 - week.size) { null }).chunked(columns).forEach { cells ->
                            Row(Modifier.fillMaxWidth()) {
                                (cells + List<LocalDate?>(columns - cells.size) { null }).forEach { date ->
                                    if (date == null) Spacer(Modifier.weight(1f)) else TextButton(onClick = { selectedDate = if (day == date) null else date.toString() },
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                            contentDescription = "${date.monthValue}월 ${date.dayOfMonth}일, ${counts[date] ?: 0}회"
                                            selected = day == date
                                        }, contentPadding = PaddingValues(2.dp)) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            val label = if (columns == 7) "${date.dayOfMonth}" else date.format(DateTimeFormatter.ofPattern("d (E)"))
                                            Text(if (day == date) "[$label]" else label, style = MaterialTheme.typography.bodyMedium)
                                            if ((counts[date] ?: 0) > 0) Text("${counts[date]}회", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            day?.let { SelectionRow("${it.monthValue}월 ${it.dayOfMonth}일", "기간 전체 보기", { selectedDate = null }) }
            HorizontalDivider()
        }
        if (records.isEmpty()) item(key = "empty") { EmptyState(if (filters == RecordFilters() && day == null) "이 기간의 기록 없음" else "조건에 맞는 기록 없음") }
        items(records, key = { "run:${it.id}" }) { run ->
            Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { open(run.id) }.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val start = Instant.ofEpochMilli(run.startedAt).atZone(zone)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(start.format(DateTimeFormatter.ofPattern("M.d (E) HH:mm")), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Text("%.2f km".format(RecordBrowserPolicy.distance(run) / 1000), style = MaterialTheme.typography.titleMedium)
                }
                Text("${durationText(RecordBrowserPolicy.activeMs(run))} · ${paceText(RecordBrowserPolicy.pace(run))}/km", style = MaterialTheme.typography.bodyMedium)
                run.course?.let { Text(it.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                if (run.id in heartRunIds) Text("심박수 있음", style = MaterialTheme.typography.labelSmall)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    when (sheet) {
        "view" -> {
            var candidatePeriod by rememberSaveable { mutableStateOf(periodName) }
            var candidateView by rememberSaveable { mutableStateOf(viewName) }
            TaskSheet("기록 보기", onCancel = { sheet = null }, actions = { AdaptiveActions {
                TextButton(onClick = { sheet = null }) { Text("취소") }
                Button(onClick = { periodName = candidatePeriod; viewName = candidateView; selectedDate = null; sheet = null }) { Text("적용") }
            } }) {
                Section("기간")
                SelectionOptionRow(SelectionChoice(RecordPeriod.WEEK.name, "주"), candidatePeriod == RecordPeriod.WEEK.name, { candidatePeriod = RecordPeriod.WEEK.name })
                SelectionOptionRow(SelectionChoice(RecordPeriod.MONTH.name, "월"), candidatePeriod == RecordPeriod.MONTH.name, { candidatePeriod = RecordPeriod.MONTH.name })
                Section("보기 방식")
                SelectionOptionRow(SelectionChoice(RecordView.LIST.name, "리스트"), candidateView == RecordView.LIST.name, { candidateView = RecordView.LIST.name })
                SelectionOptionRow(SelectionChoice(RecordView.CALENDAR.name, "달력"), candidateView == RecordView.CALENDAR.name, { candidateView = RecordView.CALENDAR.name })
            }
        }
        "sort" -> DomainSelectionSheet("기록 정렬", RecordSort.entries.map { SelectionChoice(it.name, recordSortLabel(it)) }, sortName,
            onSelect = { sortName = it; sheet = null }, onCancel = { sheet = null })
        "filter" -> {
            var candidateCourse by rememberSaveable { mutableStateOf(courseName) }
            var candidateId by rememberSaveable { mutableStateOf(courseId) }
            var candidateHeart by rememberSaveable { mutableStateOf(heartName) }
            var query by filterSelection.query
            val shownCourses = remember(courses, query) { courses.filter { it.name.contains(query, ignoreCase = true) } }
            TaskSheet("기록 필터", onCancel = { sheet = null }, actions = { AdaptiveActions {
                TextButton(onClick = { candidateCourse = RecordCourseFilter.ALL.name; candidateId = null; candidateHeart = RecordHeartFilter.ALL.name }) { Text("초기화") }
                TextButton(onClick = { sheet = null }) { Text("취소") }
                Button(onClick = { courseName = candidateCourse; courseId = candidateId; heartName = candidateHeart; sheet = null }) { Text("적용") }
            } }, scrollable = false) {
                LazyColumn(state = filterSelection.list, modifier = Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup()) {
                    item(key = "course-header", contentType = "header") { Section("코스") }
                    items(RecordCourseFilter.entries, key = { "course-filter:${it.name}" }, contentType = { "option" }) { option ->
                        SelectionOptionRow(SelectionChoice(option.name, when (option) { RecordCourseFilter.ALL -> "전체"; RecordCourseFilter.LINKED -> "코스 있음"; RecordCourseFilter.UNLINKED -> "코스 없음" }),
                            candidateId == null && candidateCourse == option.name, { candidateCourse = option.name; candidateId = null })
                    }
                    if (courses.size >= 8) item(key = "course-search", contentType = "search") {
                        OutlinedTextField(query, { query = it },
                            label = { Text("코스 검색") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    }
                    items(shownCourses, key = { "course:${it.id}" }, contentType = { "option" }) { course ->
                        SelectionOptionRow(SelectionChoice(course.id, course.name), candidateId == course.id, { candidateId = course.id; candidateCourse = RecordCourseFilter.LINKED.name })
                    }
                    item(key = "heart-header", contentType = "header") { Section("심박수") }
                    items(RecordHeartFilter.entries, key = { "heart-filter:${it.name}" }, contentType = { "option" }) { option ->
                        SelectionOptionRow(SelectionChoice(option.name, when (option) { RecordHeartFilter.ALL -> "전체"; RecordHeartFilter.PRESENT -> "심박수 있음"; RecordHeartFilter.ABSENT -> "심박수 없음" }),
                            candidateHeart == option.name, { candidateHeart = option.name })
                    }
                }
            }
        }
    }
}

private fun recordSortLabel(sort: RecordSort): String = when (sort) {
    RecordSort.NEWEST -> "날짜 최신순"; RecordSort.OLDEST -> "날짜 오래된순"
    RecordSort.LONGEST -> "거리 긴순"; RecordSort.SHORTEST -> "거리 짧은순"
    RecordSort.FASTEST -> "평균 페이스 빠른순"; RecordSort.SLOWEST -> "평균 페이스 느린순"
}

private enum class HistoryAction { EXPORT, DELETE }
private enum class HistoryHealthState { IDLE, CHECKING, REQUESTING_PERMISSION, PERMISSION, UNAVAILABLE, CHECK_FAILED, IMPORTING, SAVING, DIAGNOSING, EMPTY, IMPORT_FAILED, COMPLETE }
private fun healthProgressLabel(state: HistoryHealthState): String = when (state) {
    HistoryHealthState.CHECKING -> "권한·운동 확인 중"
    HistoryHealthState.REQUESTING_PERMISSION -> "읽기 권한 요청 중"
    HistoryHealthState.IMPORTING -> "심박수 읽는 중"
    HistoryHealthState.SAVING -> "심박수 저장 중"
    HistoryHealthState.DIAGNOSING -> "가져오기 진단 중"
    else -> "읽기 권한 결과 대기 중"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun RunDetail(app: RunkeepApp, run: RunSummary, deleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    val window = mobileWindow()
    val mapScaffold = rememberBottomSheetScaffoldState()
    val exitHost = LocalDraftExit.current
    val context = LocalContext.current
    val dao = app.db.dao()
    var points by remember(run.id) { mutableStateOf<List<TrackPoint>>(emptyList()) }
    var loaded by remember(run.id) { mutableStateOf(false) }
    var includeExtra by rememberSaveable(run.id) { mutableStateOf(false) }
    var splitPage by rememberSaveable(run.id) { mutableStateOf(false) }
    var showScopeSelection by rememberSaveable(run.id) { mutableStateOf(false) }
    var showChartAxis by rememberSaveable(run.id) { mutableStateOf(false) }
    var chartAxisName by rememberSaveable(run.id) { mutableStateOf(RecordChartAxis.TIME.name) }
    val chartAxis = RecordChartAxis.valueOf(chartAxisName)
    val detailScroll = rememberLazyListState()
    val splitScroll = rememberLazyListState()
    val splitTableState = rememberPaceTableState(run.id)
    var expandedMap by rememberSaveable(run.id) { mutableStateOf(false) }
    var showMore by rememberSaveable(run.id) { mutableStateOf(false) }
    var showExportSelection by rememberSaveable(run.id) { mutableStateOf(false) }
    var nextAction by remember(run.id) { mutableStateOf<HistoryAction?>(null) }
    var deleteConfirm by rememberSaveable(run.id) { mutableStateOf(false) }
    var deleting by remember(run.id) { mutableStateOf(false) }
    val hearts by remember(dao, run.id) { dao.hearts(run.id) }.collectAsStateWithLifecycle(emptyList())
    val health = remember(context) { HealthImport(context) }
    var candidates by remember(run.id) { mutableStateOf<List<HealthSession>>(emptyList()) }
    var showCandidates by remember(run.id) { mutableStateOf(false) }
    var selectedCandidate by remember(run.id) { mutableStateOf<Int?>(null) }
    val candidateSelection = key(run.id) { rememberDomainSelectionState() }
    var healthState by remember(run.id) { mutableStateOf(HistoryHealthState.IDLE) }
    var healthMessage by remember(run.id) { mutableStateOf<String?>(null) }
    var healthJob by remember(run.id) { mutableStateOf<Job?>(null) }
    val healthGate = remember(run.id) { HistoryHealthGate() }
    var permissionToken by remember { mutableStateOf<HistoryHealthToken?>(null) }
    var importedSession by remember(run.id) { mutableStateOf<HealthSession?>(null) }
    var healthDiagnostics by remember(run.id) { mutableStateOf<HealthDiagnostics?>(null) }
    var canDiagnose by remember(run.id) { mutableStateOf(false) }
    var preparingExport by remember(run.id) { mutableStateOf(false) }
    val fileExporter = rememberFileExporter { app.error.value = it }
    val exporting = preparingExport || fileExporter.busy
    val busy = permissionToken != null || healthState in setOf(HistoryHealthState.CHECKING, HistoryHealthState.REQUESTING_PERMISSION, HistoryHealthState.IMPORTING, HistoryHealthState.SAVING, HistoryHealthState.DIAGNOSING)
    val achievedGoal = run.goalActiveMs != null && run.goalMeters != null
    val presentation = remember(run, points, includeExtra) { RecordPresentation.from(run, points, includeExtra) }
    val displayedRun = presentation.run
    val visible = presentation.points
    val scopeLabel = if (presentation.goalOnly) "목표까지" else "전체 기록"
    val until = presentation.heartUntil
    val visibleHearts = remember(hearts, until) {
        hearts.filter { until == null || it.epochMs <= until }.distinctBy { it.epochMs }
    }
    val splits = remember(visible, displayedRun) { Reports.splits(visible, displayedRun.meters, displayedRun.activeMs) }
    val paceSamples = remember(visible) {
        val values = Reports.paceSamples(visible).toMap()
        visible.map { RecordChartSample(it.epochMs, values[it.epochMs], it.segment) }
    }
    LaunchedEffect(run.id) {
        runCatching { withContext(Dispatchers.IO) { dao.points(run.id).map { codec.decodeFromString<TrackPoint>(it.payload) } } }
            .onSuccess { points = it; loaded = true }
            .onFailure { app.error.value = "기록을 읽지 못했습니다: ${it.message}" }
    }
    DisposableEffect(run.id) {
        onDispose { healthGate.close(); healthJob?.cancel() }
    }
    fun healthFailure(failure: HealthResult.Failure) {
        healthState = when (failure.reason) {
            HealthFailure.UNSUPPORTED, HealthFailure.UPDATE_REQUIRED -> HistoryHealthState.UNAVAILABLE
            HealthFailure.PERMISSION_REQUIRED, HealthFailure.ACCESS_RESTRICTED -> HistoryHealthState.PERMISSION
            else -> if (failure.stage == HealthStage.SESSION_READ || failure.stage == HealthStage.PERMISSION)
                HistoryHealthState.CHECK_FAILED else HistoryHealthState.IMPORT_FAILED
        }
        healthMessage = historyHealthFailure(failure)
    }
    fun clearHealthMessage() { healthMessage = null; healthDiagnostics = null; canDiagnose = false }
    fun chooseSession(token: HistoryHealthToken) {
        if (!healthGate.accepts(token)) return
        healthState = HistoryHealthState.CHECKING
        clearHealthMessage()
        healthJob = scope.launch {
            try {
                val result = health.candidates(run)
                if (!healthGate.accepts(token)) return@launch
                when (result) {
                    is HealthResult.Success -> {
                        candidates = result.value.sessions
                        selectedCandidate = null
                        showCandidates = true
                        healthState = HistoryHealthState.IDLE
                    }
                    is HealthResult.Failure -> healthFailure(result)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (healthGate.accepts(token)) healthFailure(HealthResult.Failure(HealthFailure.READ_FAILED, HealthStage.SESSION_READ))
            } finally { healthGate.finish(token) }
        }
    }
    val permission = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { allowed ->
        val token = permissionToken
        permissionToken = null
        if (healthGate.accepts(token)) {
            if (allowed.containsAll(HealthImport.permissions)) chooseSession(requireNotNull(token))
            else {
                healthState = HistoryHealthState.PERMISSION
                healthMessage = "심박수·운동 읽기 권한을 허용한 뒤 다시 시도하세요."
                healthGate.finish(requireNotNull(token))
            }
        }
    }
    fun requestHealth() {
        if (permissionToken != null) return
        val token = healthGate.begin() ?: return
        healthState = HistoryHealthState.CHECKING
        clearHealthMessage()
        healthJob = scope.launch {
            var awaitingPermission = false
            try {
                val result = health.candidates(run)
                if (!healthGate.accepts(token)) return@launch
                when (result) {
                    is HealthResult.Success -> {
                        candidates = result.value.sessions
                        selectedCandidate = null
                        showCandidates = true
                        healthState = HistoryHealthState.IDLE
                    }
                    is HealthResult.Failure -> {
                        healthFailure(result)
                        if (result.reason == HealthFailure.PERMISSION_REQUIRED) {
                            permissionToken = token
                            healthState = HistoryHealthState.REQUESTING_PERMISSION
                            healthMessage = null
                            permission.launch(HealthImport.permissions)
                            awaitingPermission = true
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (healthGate.accepts(token)) {
                    permissionToken = null
                    healthFailure(HealthResult.Failure(HealthFailure.READ_FAILED, HealthStage.SESSION_READ))
                }
            } finally { if (!awaitingPermission) healthGate.finish(token) }
        }
    }
    fun importHearts(session: HealthSession?) {
        val token = healthGate.begin() ?: return
        showCandidates = false
        healthState = HistoryHealthState.IMPORTING
        clearHealthMessage()
        importedSession = session
        healthJob = scope.launch {
            try {
                val result = health.samples(run, session)
                if (!healthGate.accepts(token)) return@launch
                when (result) {
                    is HealthResult.Failure -> healthFailure(result)
                    is HealthResult.Success -> {
                        val samples = result.value.samples
                        if (samples.isEmpty()) {
                            healthState = HistoryHealthState.EMPTY
                            healthMessage = historyHealthEmpty(result.value.diagnostics)
                            canDiagnose = true
                        } else {
                            healthState = HistoryHealthState.SAVING
                            try {
                                dao.saveHearts(samples.map { HeartRow(run.id, it.sourceId, it.epochMs, it.bpm) })
                                if (healthGate.accepts(token)) {
                                    healthState = HistoryHealthState.COMPLETE
                                    healthMessage = "심박수 ${samples.size}개 저장 완료"
                                }
                            } catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) {
                                if (healthGate.accepts(token)) {
                                    healthState = HistoryHealthState.IMPORT_FAILED
                                    healthMessage = "심박수를 저장하지 못했습니다. 다시 시도하세요."
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (healthGate.accepts(token)) healthFailure(HealthResult.Failure(HealthFailure.READ_FAILED, HealthStage.HEART_READ))
            } finally { healthGate.finish(token) }
        }
    }
    fun diagnoseHealth() {
        val token = healthGate.begin() ?: return
        healthState = HistoryHealthState.DIAGNOSING
        healthDiagnostics = null
        healthJob = scope.launch {
            try {
                val result = health.diagnose(run, importedSession)
                if (!healthGate.accepts(token)) return@launch
                when (result) {
                    is HealthResult.Failure -> healthFailure(result)
                    is HealthResult.Success -> {
                        healthState = HistoryHealthState.EMPTY
                        healthDiagnostics = result.value
                        healthMessage = historyHealthEmpty(result.value)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (healthGate.accepts(token)) healthFailure(HealthResult.Failure(HealthFailure.READ_FAILED, HealthStage.SOURCE_COMPARE))
            } finally { healthGate.finish(token) }
        }
    }
    fun export(choice: ExportChoice) {
        showExportSelection = false
        if (!loaded || deleting || preparingExport || fileExporter.busy) return
        val sourcePoints = points
        preparingExport = true
        scope.launch {
            try {
                val (fileName, content) = withContext(Dispatchers.IO) {
                    when (choice) {
                        ExportChoice.RUN_GPX -> {
                            require(sourcePoints.isNotEmpty()) { "내보낼 GPS 지점이 없습니다." }
                            "runkeep-${run.id}.gpx" to Reports.runGpx("Runkeep ${dateText(run.startedAt)}", sourcePoints)
                        }
                        ExportChoice.GOAL_GPX -> {
                            val goalPoints = Reports.clip(sourcePoints, requireNotNull(run.goalMeters))
                            require(achievedGoal && goalPoints.isNotEmpty()) { "목표까지 내보낼 GPS 지점이 없습니다." }
                            "runkeep-goal-${run.id}.gpx" to Reports.runGpx("Runkeep ${dateText(run.startedAt)}", goalPoints)
                        }
                        ExportChoice.RUN_JSON -> {
                            val fullHearts = dao.hearts(run.id).first().map { HeartSample(it.epochMs, it.bpm, it.sourceId) }
                            "runkeep-${run.id}.json" to codec.encodeToString(RunFile(run = run, points = sourcePoints, hearts = fullHearts))
                        }
                        else -> error("지원하지 않는 기록 내보내기 형식입니다.")
                    }
                }
                fileExporter.launch(fileName, content)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                app.error.value = "내보내기 실패: ${failure.message}"
            } finally {
                preparingExport = false
            }
        }
    }
    LaunchedEffect(nextAction, showMore) {
        if (!showMore) {
            val action = nextAction
            nextAction = null
            when (action) {
                HistoryAction.EXPORT -> showExportSelection = true
                HistoryAction.DELETE -> deleteConfirm = true
                null -> Unit
            }
        }
    }
    val closeExpandedMap by rememberUpdatedState<() -> Unit>({
        if (!window.useSupportingPane && mapScaffold.bottomSheetState.currentValue == SheetValue.Expanded) {
            scope.launch { mapScaffold.bottomSheetState.partialExpand() }
        } else expandedMap = false
    })
    DisposableEffect(expandedMap, splitPage, healthState, exitHost) {
        val interceptor: ((() -> Unit) -> Unit)? = if ((expandedMap || splitPage || healthState == HistoryHealthState.SAVING) && exitHost.interceptor == null) {
            { _ -> if (healthState != HistoryHealthState.SAVING) { if (splitPage) splitPage = false else closeExpandedMap() } }
        } else null
        if (interceptor != null) exitHost.interceptor = interceptor
        onDispose { if (interceptor != null && exitHost.interceptor === interceptor) exitHost.interceptor = null }
    }
    BackHandler(enabled = expandedMap || splitPage || healthState == HistoryHealthState.SAVING) { if (healthState != HistoryHealthState.SAVING) { if (splitPage) splitPage = false else closeExpandedMap() } }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (splitPage) "구간 기록" else dateText(run.startedAt), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    if (splitPage) Text(dateText(run.startedAt), style = MaterialTheme.typography.bodySmall)
                }
                if (splitPage || expandedMap) TextButton(onClick = { splitPage = false; expandedMap = false }) { Text("상세로") }
                IconButton(onClick = { showMore = true }) { Icon(Icons.Default.MoreVert, "기록 더보기") }
            }
            if (achievedGoal) SelectionRow("표시 범위", scopeLabel, { showScopeSelection = true })
            HorizontalDivider()
        }
        if (splitPage) {
            PaceTablePage(displayedRun, visible, splitTableState, splitScroll, Modifier.weight(1f), loading = !loaded)
        } else if (expandedMap) {
            MapSupportingPanel(mapScaffold, panel = {
                Column(Modifier.fillMaxWidth().heightIn(max = if (window.useSupportingPane) window.height else window.height * .65f).padding(horizontal = 20.dp)) {
                    Section("$scopeLabel · 구간", trailing = { TextButton(onClick = { expandedMap = false; splitPage = true }) { Text("전체 구간") } })
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        HistorySummaryMetrics(displayedRun)
                        PaceTable(displayedRun, visible, maxRows = 3)
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }) { padding ->
                RunMap(run.course?.points ?: emptyList(), tracks = visible.groupBy { it.segment }.values.map { part -> part.map { it.coordinate } },
                    modifier = Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding()), showRecordEndpoints = true)
            }
        } else {
            LazyColumn(Modifier.weight(1f), state = detailScroll, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (!loaded) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                item(key = "summary") {
                    HistorySummaryMetrics(displayedRun)
                    run.course?.let { Text(it.name, color = MaterialTheme.colorScheme.primary) }
                }
                item(key = "map-title") { Section("기록 지도", trailing = { TextButton(onClick = { expandedMap = true }) { Text("확대") } }) }
                item(key = "map") { RunMap(run.course?.points ?: emptyList(), tracks = visible.groupBy { it.segment }.values.map { part -> part.map { it.coordinate } },
                    modifier = Modifier.fillMaxWidth().height(240.dp), showRecordEndpoints = true) }
                item(key = "additional") { HistoryAdditionalMetrics(displayedRun, visible) }
                item(key = "split-title") { Section("구간 기록", trailing = { TextButton(onClick = { splitPage = true }) { Text("전체 구간") } }) }
                if (splits.isEmpty()) item { Text("구간 기록 없음") }
                else items(splits.take(3), key = { "split:${it.kilometer}" }) { HistorySplit(it) }
                item(key = "axis") { SelectionRow("그래프 기준", if (chartAxisName == RecordChartAxis.TIME.name) "경과 시간" else "거리", { showChartAxis = true }) }
                item(key = "pace") { RecordChart("페이스 · 20초 이동 구간", "분:초/km", paceSamples, visible, run.startedAt, chartAxis, pace = true, maxGapMs = 15_000) }
                item(key = "cadence") { RecordChart("측정 케이던스", "spm", visible.map { RecordChartSample(it.epochMs, it.cadence, it.segment) }, visible, run.startedAt, chartAxis, maxGapMs = 15_000) }
                item(key = "altitude") { RecordChart("GPS 고도 · 추정", "m", visible.map { RecordChartSample(it.epochMs, it.altitude, it.segment) }, visible, run.startedAt, chartAxis, maxGapMs = 15_000) }
                if (visibleHearts.isNotEmpty()) {
                    item(key = "heart") { RecordChart("심박수", "bpm", visibleHearts.map { RecordChartSample(it.epochMs, it.bpm.toDouble().takeIf { bpm -> bpm > 0 }) }, visible, run.startedAt, chartAxis) }
                    item { AdaptiveActions {
                        Metric("평균 심박수", "%.0f bpm".format(visibleHearts.map { it.bpm }.average()))
                        Metric("최대", "${visibleHearts.maxOf { it.bpm }} bpm")
                    } }
                }
                item(key = "import") {
                    AdaptiveActions {
                        TextButton(onClick = ::requestHealth, enabled = !busy && run.status == RunStatus.FINISHED, modifier = Modifier.heightIn(min = 48.dp)) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.FileDownload, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (busy) healthProgressLabel(healthState) else if (hearts.isEmpty()) "심박수 가져오기" else "심박수 다시 가져오기")
                        }
                        if (busy && permissionToken == null && healthState != HistoryHealthState.SAVING && healthState != HistoryHealthState.REQUESTING_PERMISSION)
                            TextButton(onClick = { healthGate.cancel(); healthJob?.cancel(); healthState = HistoryHealthState.IDLE; healthMessage = "가져오기 취소됨" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") }
                    }
                    if (busy) Text(healthProgressLabel(healthState), style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    healthMessage?.let { Text(it, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                    if (canDiagnose) TextButton(onClick = ::diagnoseHealth, enabled = !busy) { Text("가져오기 진단") }
                }
                healthDiagnostics?.let { diagnostic -> item(key = "diagnostic") {
                    val selection = importedSession?.window
                    val from = maxOf(run.startedAt, selection?.start?.toEpochMilli() ?: run.startedAt)
                    val to = minOf(requireNotNull(run.endedAt), selection?.end?.toEpochMilli() ?: requireNotNull(run.endedAt))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("런킵 ${historyTimeRange(run.startedAt, requireNotNull(run.endedAt))}")
                        Text("선택 ${historyTimeRange(from, to)}")
                        Text("시간대 · ${ZoneId.systemDefault().id}")
                        Text("삼성 헬스 · 레코드 ${diagnostic.samsung.records}개 / 선택 구간 ${diagnostic.samsung.overlappingRecords}개")
                        Text("삼성 샘플 · 전체 ${diagnostic.samsung.samplesInRun}개 / 선택 ${diagnostic.samsung.samplesInSelection}개")
                        Text("다른 출처 · 레코드 ${diagnostic.other.records}개 / 선택 샘플 ${diagnostic.other.samplesInSelection}개")
                        Text("조회 ${diagnostic.pages}페이지 · 중복 레코드 ${diagnostic.duplicateRecords}개 / 샘플 ${diagnostic.duplicateSamples}개")
                        Text("삼성 원본 구간·값 밖 샘플 ${diagnostic.samsung.rejectedSamples}개")
                    }
                } }
            }
        }
    }
    if (showScopeSelection) DomainSelectionSheet("기록 표시 범위", listOf(SelectionChoice("goal", "목표까지"), SelectionChoice("all", "전체 기록")), if (includeExtra) "all" else "goal",
        onSelect = { includeExtra = it == "all"; showScopeSelection = false }, onCancel = { showScopeSelection = false })
    if (showChartAxis) DomainSelectionSheet("그래프 기준", listOf(SelectionChoice(RecordChartAxis.TIME.name, "시작 후 경과 시간"), SelectionChoice(RecordChartAxis.DISTANCE.name, "누적 거리")), chartAxisName,
        onSelect = { chartAxisName = it; showChartAxis = false }, onCancel = { showChartAxis = false })
    if (showMore) TaskSheet("기록 더보기", onCancel = { showMore = false }, actions = {
        TextButton(onClick = { showMore = false }, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
    }) {
        TextButton(onClick = { showMore = false; nextAction = HistoryAction.EXPORT }, enabled = loaded && !deleting && !exporting,
            modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.FileUpload, null); Spacer(Modifier.width(6.dp)); Text(if (preparingExport) "내보내기 준비 중…" else if (fileExporter.busy) "내보내는 중…" else "내보내기") }
        if (run.status == RunStatus.FINISHED) TextButton(onClick = { showMore = false; nextAction = HistoryAction.DELETE },
            enabled = !deleting && !busy && !exporting, modifier = Modifier.fillMaxWidth()) { Text("기록 삭제", color = MaterialTheme.colorScheme.error) }
    }
    if (showExportSelection) ExportSelectionSheet(
        options = buildList {
            add(ExportChoice.RUN_GPX)
            if (achievedGoal) add(ExportChoice.GOAL_GPX)
            add(ExportChoice.RUN_JSON)
        },
        disabledOptions = buildSet {
            if (!loaded || deleting || exporting) {
                add(ExportChoice.RUN_GPX)
                add(ExportChoice.GOAL_GPX)
                add(ExportChoice.RUN_JSON)
            } else {
                if (points.isEmpty()) add(ExportChoice.RUN_GPX)
                if (achievedGoal && Reports.clip(points, requireNotNull(run.goalMeters)).isEmpty()) add(ExportChoice.GOAL_GPX)
            }
        },
        onSelect = ::export,
        onCancel = { showExportSelection = false }
    )
    if (showCandidates) {
        var query by candidateSelection.query
        val shownCandidates = remember(candidates, query) {
            candidates.withIndex().filter { (index, session) ->
                query.isBlank() || (session.title ?: "삼성 헬스 운동 ${index + 1}").contains(query, ignoreCase = true)
            }
        }
        TaskSheet("심박수 가져올 운동 선택", onCancel = { showCandidates = false }, actions = {
            Button(onClick = { selectedCandidate?.let { importHearts(if (it < 0) null else candidates[it]) } },
                enabled = selectedCandidate != null && !busy, modifier = Modifier.fillMaxWidth()) { Text("선택한 시간대로 가져오기") }
            TextButton(onClick = { showCandidates = false }, modifier = Modifier.fillMaxWidth()) { Text("취소") }
        }, scrollable = false) {
            LazyColumn(state = candidateSelection.list, modifier = Modifier.fillMaxWidth().weight(1f, fill = false).selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item(key = "session-description", contentType = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("선택한 운동과 런킵의 공통 시간대 심박수를 가져옵니다.")
                        if (candidates.isEmpty()) Text("겹치는 삼성 헬스 운동 없음 · 런킵 운동 시간으로 가져올 수 있습니다.")
                        else Text("겹치는 운동 ${candidates.size}개", style = MaterialTheme.typography.bodySmall)
                        Text("시간대 · ${ZoneId.systemDefault().id}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (candidates.size >= 8) item(key = "session-search", contentType = "search") {
                    OutlinedTextField(query, { query = it },
                        label = { Text("운동 검색") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                items(shownCandidates, key = { "session:${it.value.id}" }, contentType = { "session" }) { (index, session) ->
                    HistoryCandidate(selected = selectedCandidate == index, onClick = { selectedCandidate = index },
                        title = session.title ?: "삼성 헬스 운동 ${index + 1}",
                        time = historyTimeRange(session.window.start.toEpochMilli(), session.window.end.toEpochMilli()),
                        source = "출처 · ${session.origin}")
                }
                item(key = "run-window", contentType = "session") {
                    HistoryCandidate(selected = selectedCandidate == -1, onClick = { selectedCandidate = -1 }, title = "런킵 운동 시간으로 가져오기",
                        time = historyTimeRange(run.startedAt, run.endedAt ?: run.startedAt), source = "삼성 헬스 심박수 · 운동 세션 선택 없이 조회")
                }
            }
        }
    }
    if (deleteConfirm) AlertDialog(onDismissRequest = { if (!deleting) deleteConfirm = false }, title = { Text("기록을 삭제할까요?") },
        text = { Text("전체 위치와 가져온 심박수가 함께 삭제됩니다. 되돌릴 수 없습니다.") },
        confirmButton = { TextButton(onClick = {
            deleting = true
            scope.launch {
                runCatching { dao.deleteRun(run.id); deleteConfirm = false; deleted() }
                    .onFailure { app.error.value = "삭제 실패: ${it.message}" }
                deleting = false
            }
        }, enabled = !deleting) { Text(if (deleting) "삭제 중…" else "삭제", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { deleteConfirm = false }, enabled = !deleting) { Text("취소") } })
}

@Composable private fun HistorySummaryMetrics(run: RunSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Metric("거리", "%.2f km".format(run.meters / 1000))
        AdaptiveActions {
            Metric("운동 시간", durationText(run.activeMs))
            Metric("평균 페이스", paceText(run.averagePace), suffix = "/km")
        }
    }
}

@Composable private fun HistoryAdditionalMetrics(run: RunSummary, points: List<TrackPoint>) {
    val cadence = points.mapNotNull { it.cadence }.takeIf { it.isNotEmpty() }?.average()
    val ascent = Reports.ascent(points)
    if (cadence != null || ascent != null || run.weightKg != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AdaptiveActions {
            cadence?.let { Text("평균 케이던스 %.0f spm".format(it)) }
            ascent?.let { Text("상승 고도 약 %.0f m".format(it)) }
        }
        run.weightKg?.let { weight -> Text("칼로리 추정 %.0f kcal".format(weight * run.meters / 1000), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable private fun HistorySplit(split: Split) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {}) {
        Text(if (split.meters < 999.9) "마지막 %.2f km".format(split.meters / 1000) else "${split.kilometer} km", style = MaterialTheme.typography.titleSmall)
        Text("${durationText(split.activeMs)} · ${paceText(split.pace)}/km", style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}

@Composable private fun HistoryCandidate(selected: Boolean, onClick: () -> Unit, title: String, time: String, source: String) {
    SelectionOptionRow(SelectionChoice(title, title, "$time · $source"), selected, onClick)
}

private fun historyTimeRange(start: Long, end: Long): String {
    val formatter = DateTimeFormatter.ofPattern("yyyy년 M월 d일 HH:mm:ss")
    val zone = ZoneId.systemDefault()
    return "${Instant.ofEpochMilli(start).atZone(zone).format(formatter)} – ${Instant.ofEpochMilli(end).atZone(zone).format(formatter)}"
}
