package com.runkeep.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import com.runkeep.app.BuildConfig
import com.runkeep.app.RunkeepApp
import com.runkeep.app.data.CourseRow
import com.runkeep.core.*
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun CourseEditor(app: RunkeepApp, initial: Course?, plans: List<WorkoutPlan>, startWithPace: Boolean = false, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    val scaffold = rememberBottomSheetScaffoldState()
    val window = mobileWindow()
    val mapEditing = BuildConfig.FLAVOR == "kakao"
    val density = LocalDensity.current
    val listState = rememberLazyListState()
    var controlsHeight by remember { mutableIntStateOf(0) }
    val name = remember(initial?.id) { initial?.name ?: "" }
    var draftName by rememberSaveable(initial?.id) { mutableStateOf(name) }
    val seed = remember(initial?.id) { initial ?: Course(name = "", points = emptyList(), usePaceTargets = startWithPace) }
    var encoded by rememberSavedCourseDraft(initial?.id) { codec.encodeToString(CourseEditorDraft(seed)) }
    val draft = remember(encoded) { codec.decodeFromString<CourseEditorDraft>(encoded) }
    val latestDraft by rememberUpdatedState(draft)
    var selected by rememberSaveable { mutableStateOf<Int?>(null) }
    var insertAfter by rememberSaveable { mutableStateOf<Int?>(null) }
    var undo by rememberSavedOptionalCourseDraft { null }
    var saveDialog by rememberSaveable { mutableStateOf(false) }
    var moreSheet by rememberSaveable { mutableStateOf(false) }
    var discardDialog by remember { mutableStateOf(false) }
    var deleteDialog by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var exportSheet by rememberSaveable { mutableStateOf(false) }
    val exporter = rememberFileExporter { app.error.value = it }
    val course = draft.course
    val points = course.points
    var focus by remember { mutableStateOf(points.getOrNull(selected ?: -1)) }
    val cumulative = remember(points) { points.zipWithNext().map { (a, b) -> Geo.distance(a, b) }.runningFold(0.0, Double::plus) }
    val total = cumulative.lastOrNull() ?: 0.0
    fun change(next: CourseEditorDraft) { if (next != draft) { undo = encoded; encoded = codec.encodeToString(next) } }
    fun remove(index: Int) { change(draft.remove(course.pointIds[index])); selected = null; insertAfter = null; focus = null }
    fun value(courseName: String = draftName) = course.copy(name = courseName.ifBlank { "나의 코스" }, version = (initial?.version ?: 0) + 1)
    fun dismissSaveDialog() { if (!saving) saveDialog = false }
    var dragOriginal by remember { mutableStateOf<CourseEditorDraft?>(null) }
    val reorder = rememberRowReorder(listState, course.pointIds, !saving && !deleting,
        onMove = { id, target -> encoded = codec.encodeToString(latestDraft.move(id, target, dragOriginal?.course?.paceTargets ?: latestDraft.course.paceTargets)); selected = target; insertAfter = null },
        onStart = { dragOriginal = draft },
        onEnd = { dragOriginal?.takeIf { it != latestDraft }?.let { undo = codec.encodeToString(it) }; dragOriginal = null },
        onCancel = { dragOriginal?.let { encoded = codec.encodeToString(it) }; dragOriginal = null; selected = null; focus = null })
    val selectedLeg = selected?.let { it.coerceAtMost(points.lastIndex - 1) }?.takeIf { it >= 0 }
    fun back() {
        if (saving || deleting) return
        when {
            exportSheet -> exportSheet = false
            moreSheet -> moreSheet = false
            saveDialog -> dismissSaveDialog()
            discardDialog -> discardDialog = false
            deleteDialog -> deleteDialog = false
            !window.useSupportingPane && scaffold.bottomSheetState.currentValue == SheetValue.Expanded -> scope.launch { scaffold.bottomSheetState.partialExpand() }
            draft.dirty || draftName != name -> discardDialog = true
            else -> onSaved()
        }
    }
    BackHandler { back() }
    LaunchedEffect(startWithPace) { if (startWithPace && !window.useSupportingPane) scaffold.bottomSheetState.expand() }
    LaunchedEffect(selected, points.size, reorder.draggingId) {
        if (reorder.draggingId == null) selected?.takeIf { it in points.indices }?.let { listState.animateScrollToItem(it + 1) }
    }
    fun selectPoint(index: Int) {
        if (index in points.indices) { selected = index; focus = points[index]; insertAfter = null }
    }
    Column(Modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::back) { Icon(Icons.Outlined.Close, "코스 편집 닫기") }
                Column(Modifier.weight(1f)) {
                    Text(initial?.name ?: "새 코스", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                    Text("%.2f km · %d개 지점".format(total / 1000, points.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = { undo?.let { encoded = it; undo = null; selected = null; insertAfter = null; focus = null } }, enabled = undo != null && !saving && !deleting) { Icon(Icons.AutoMirrored.Outlined.Undo, "실행 취소") }
                IconButton(onClick = { moreSheet = true }, enabled = !saving && !deleting) { Icon(Icons.Outlined.MoreVert, "코스 더보기") }
                Button(onClick = { saveDialog = true }, enabled = points.size >= 2 && !saving && !deleting && reorder.draggingId == null, modifier = Modifier.heightIn(min = 48.dp)) { Text("저장") }
            }
        }
        BoxWithConstraints(Modifier.weight(1f)) {
            val drawerHeight = if (window.useSupportingPane) maxHeight else maxHeight - minOf(144.dp, maxHeight * .5f)
            val minimumPeek = if (window.fontScale >= 1.5f) 160.dp else 108.dp
            val measuredPeek = with(density) { controlsHeight.toDp() } + 48.dp
            val peekHeight = maxOf(minimumPeek, measuredPeek).coerceAtMost(maxHeight * .6f)
            MapSupportingPanel(state = scaffold, peekHeight = peekHeight, panel = {
                Column(Modifier.fillMaxWidth().heightIn(max = drawerHeight).padding(horizontal = 20.dp)) {
                    Column(Modifier.fillMaxWidth().heightIn(max = if (window.useSupportingPane) drawerHeight * .6f else (peekHeight - 48.dp).coerceAtLeast(0.dp))
                        .verticalScroll(rememberScrollState())) {
                        Column(Modifier.fillMaxWidth().onSizeChanged { controlsHeight = it.height }) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
                                    val index = selected?.takeIf { it in points.indices }
                                    Text(index?.let { if (it == 0) "1번 · 출발점" else "${it + 1}번 지점" } ?: "코스 지점 ${points.size}개", style = MaterialTheme.typography.titleMedium)
                                    if (index != null && index > 0) Text("%.2f km · 구간 %.2f km".format(cumulative[index] / 1000,
                                        (cumulative[index] - cumulative[index - 1]) / 1000), style = MaterialTheme.typography.bodySmall)
                                }
                                if (!window.useSupportingPane) IconButton(onClick = { scope.launch {
                                    if (scaffold.bottomSheetState.currentValue == SheetValue.Expanded) scaffold.bottomSheetState.partialExpand() else scaffold.bottomSheetState.expand()
                                } }) {
                                    val expanded = scaffold.bottomSheetState.currentValue == SheetValue.Expanded
                                    Icon(if (expanded) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, if (expanded) "지점 목록 접기" else "지점 목록 펼치기")
                                }
                            }
                            selected?.takeIf { it in points.indices }?.let { index ->
                                AdaptiveActions {
                                    IconButton(onClick = { selectPoint(index - 1) }, enabled = index > 0) { Icon(Icons.Outlined.ChevronLeft, "이전 지점") }
                                    IconButton(onClick = { selectPoint(index + 1) }, enabled = index < points.lastIndex) { Icon(Icons.Outlined.ChevronRight, "다음 지점") }
                                    if (mapEditing) IconButton(onClick = { insertAfter = if (insertAfter != null) null else index }, modifier = Modifier.semantics {
                                        stateDescription = if (insertAfter != null) "삽입 중" else "삽입 안 함"
                                    }) { Icon(if (insertAfter != null) Icons.Outlined.Done else Icons.Outlined.AddLocationAlt, if (insertAfter != null) "삽입 완료" else "선택 지점 뒤에 추가") }
                                    IconButton(onClick = { remove(index) }) { Icon(Icons.Outlined.DeleteOutline, "${index + 1}번 지점 삭제", tint = MaterialTheme.colorScheme.error) }
                                    IconButton(onClick = { selected = null; insertAfter = null; focus = null }) { Icon(Icons.Outlined.Close, "선택 해제") }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                    if (selectedLeg != null) TextButton(enabled = !saving && !deleting, onClick = { scope.launch { listState.scrollToItem(0); if (!window.useSupportingPane) scaffold.bottomSheetState.expand() } }) {
                        Text("${selectedLeg + 1} → ${selectedLeg + 2} · ${course.paceAt(selectedLeg)?.let { paceText(it.toDouble()) + "/km" } ?: "목표 미설정"}")
                    }
                    LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false)) {
                        item(key = "course-pace-settings") {
                            CoursePaceControls(course, plans, selectedLeg, enabled = !saving && !deleting && reorder.draggingId == null,
                                onChange = { change(draft.copy(course = it)) })
                        }
                        items(points.size, key = { course.pointIds[it] }) { index ->
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = if (selected == index) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                                leadingContent = { RowReorderHandle(reorder, course.pointIds[index], "${index + 1}번 지점 끌어서 순서 변경") },
                                headlineContent = { Text(if (index == 0) "1번 지점" else "${index + 1}번 · %.2f km".format(cumulative[index] / 1000)) },
                                supportingContent = { Text(if (index == points.lastIndex) "도착점" else
                                    "${index + 1} → ${index + 2} · %.2f km · %s".format((cumulative[index + 1] - cumulative[index]) / 1000,
                                        if (course.usePaceTargets) course.paceAt(index)?.let { paceText(it.toDouble()) + "/km" } ?: "미설정" else "코스만")) },
                                modifier = Modifier.then(if (reorder.draggingId == course.pointIds[index]) Modifier else Modifier.animateItem()).heightIn(min = 56.dp).zIndex(if (reorder.draggingId == course.pointIds[index]) 1f else 0f)
                                    .graphicsLayer { translationY = reorder.translation(course.pointIds[index]) }.semantics {
                                        this.selected = selected == index
                                        customActions = buildList {
                                            if (!saving && !deleting && index > 0) add(CustomAccessibilityAction("위로 이동") { change(draft.move(course.pointIds[index], index - 1)); selected = index - 1; true })
                                            if (!saving && !deleting && index < points.lastIndex) add(CustomAccessibilityAction("아래로 이동") { change(draft.move(course.pointIds[index], index + 1)); selected = index + 1; true })
                                        }
                                    }.clickable(enabled = !saving && !deleting && reorder.draggingId == null, role = Role.Button, onClickLabel = "${index + 1}번 지점 선택") {
                                    selectPoint(index)
                                    if (!window.useSupportingPane) scope.launch { scaffold.bottomSheetState.partialExpand() }
                                }
                            )
                        }
                        if (mapEditing && points.size > 200) item { Text("지도에는 시작·끝·선택 지점만 표시합니다. 전체 지점은 목록에서 선택하세요.", style = MaterialTheme.typography.bodySmall) }
                        item { Spacer(Modifier.height(24.dp)) }
                    }
                }
            }) { padding ->
                RunMap(points, selectedSegment = selectedLeg,
                    segmentLabels = points.zipWithNext().indices.map { index -> "${index + 1}→${index + 2} · %.2f km".format(Geo.distance(points[index], points[index + 1]) / 1000) +
                        if (course.usePaceTargets) " · ${course.paceAt(index)?.let { paceText(it.toDouble()) + "/km" } ?: "미설정"}" else "" },
                    modifier = Modifier.fillMaxSize().padding(padding), centerOnUser = initial == null, showDistances = true, selectedPoint = selected, focusPoint = focus,
                    onTap = { coordinate ->
                        if (points.size >= 10_000) app.error.value = "지점은 최대 10,000개입니다"
                        else {
                            val index = insertAfter?.plus(1)?.coerceAtMost(points.size) ?: points.size
                            if (!saving && !deleting) change(latestDraft.insert(index, coordinate)); selected = index
                            if (insertAfter != null) insertAfter = index
                        }
                    },
                    onPointSelected = { selected = it; focus = points.getOrNull(it); insertAfter = null },
                    onSegmentSelected = { selectPoint(it) },
                    onPointMoved = { index, coordinate -> if (!saving && !deleting && index in points.indices) change(latestDraft.relocate(course.pointIds[index], coordinate)) }
                )
            }
        }
    }
    if (moreSheet) TaskSheet("코스 더보기", onCancel = { moreSheet = false }, actions = {
        TextButton(onClick = { moreSheet = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("닫기") }
    }) {
        OutlinedButton(onClick = { moreSheet = false; exportSheet = true }, enabled = points.size >= 2 && !exporter.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (exporter.busy) "내보내는 중…" else "내보내기") }
        if (initial != null) TextButton(onClick = { moreSheet = false; deleteDialog = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("코스 삭제", color = MaterialTheme.colorScheme.error) }
    }
    if (exportSheet) ExportSelectionSheet(options = listOf(ExportChoice.COURSE_GPX, ExportChoice.COURSE_JSON),
        disabledOptions = if (exporter.busy || points.size < 2) setOf(ExportChoice.COURSE_GPX, ExportChoice.COURSE_JSON) else emptySet(),
        onCancel = { exportSheet = false }, onSelect = { choice ->
            exportSheet = false
            runCatching {
                when (choice) {
                    ExportChoice.COURSE_GPX -> exporter.launch("runkeep-course.gpx", Reports.gpx(name.ifBlank { "나의 코스" }, points))
                    ExportChoice.COURSE_JSON -> exporter.launch("runkeep-course.json", codec.encodeToString(CourseFile(course = value())))
                    else -> Unit
                }
            }.onFailure { app.error.value = "내보내기 실패: ${it.message}" }
        })
    if (saveDialog) AlertDialog(onDismissRequest = ::dismissSaveDialog, title = { Text("코스 저장") },
        text = { OutlinedTextField(draftName, { draftName = it.take(80) }, label = { Text("코스 이름") }, singleLine = true, enabled = !saving, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { saving = true; scope.launch {
            runCatching { val course = value(draftName); app.db.dao().saveCourse(CourseRow(course.id, codec.encodeToString(course))); onSaved() }
                .onFailure { app.error.value = "저장 실패: ${it.message}" }
            saving = false
        } }, enabled = draftName.isNotBlank() && !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (saving) "저장 중…" else "저장") } }, dismissButton = { TextButton(onClick = ::dismissSaveDialog, enabled = !saving, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } })
    if (discardDialog) AlertDialog(onDismissRequest = { discardDialog = false }, title = { Text("편집을 마칠까요?") }, text = { Text("아직 저장하지 않은 변경이 있습니다.") },
        confirmButton = { TextButton(onClick = onSaved) { Text("변경 버리고 닫기") } }, dismissButton = { TextButton(onClick = { discardDialog = false }) { Text("계속 편집") } })
    if (deleteDialog) AlertDialog(onDismissRequest = { if (!deleting) deleteDialog = false }, title = { Text("코스를 삭제할까요?") },
        text = { Text(initial?.name.orEmpty()) },
        confirmButton = { TextButton(onClick = {
            if (!deleting) {
                deleting = true
                scope.launch {
                    runCatching { app.db.dao().deleteCourse(requireNotNull(initial).id); onSaved() }
                        .onFailure { app.error.value = "삭제 실패: ${it.message}" }
                    deleting = false
                }
            }
        }, enabled = !deleting, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (deleting) "삭제 중…" else "삭제") } },
        dismissButton = { TextButton(onClick = { deleteDialog = false }, enabled = !deleting, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } })
}
