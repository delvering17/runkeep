package com.runkeep.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import android.util.Xml
import com.runkeep.app.RunkeepApp
import com.runkeep.app.data.CourseRow
import com.runkeep.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import org.xmlpull.v1.XmlPullParser

@Composable fun CoursesScreen(app: RunkeepApp, courses: List<Course>, edit: (String, Boolean) -> Unit, onPlans: () -> Unit, runs: List<RunSummary> = emptyList()) {
    val context = LocalContext.current; val scope = rememberCoroutineScope()
    var sortKey by rememberSaveable { mutableStateOf(CourseListSort.REGISTERED.name) }
    var ranOnly by rememberSaveable { mutableStateOf(true) }
    var showSort by rememberSaveable { mutableStateOf(false) }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    val sort = CourseListSort.entries.firstOrNull { it.name == sortKey } ?: CourseListSort.REGISTERED
    val entries = remember(courses, runs) { courseListEntries(courses, runs) }
    val visible = remember(entries, sort, ranOnly) { visibleCourseEntries(entries, sort, ranOnly) }
    var deleteTargetId by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteTargetName by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf(false) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        require(output.size() + count <= MAX_COURSE_FILE_BYTES) { "코스 파일은 최대 8MB입니다" }
                        output.write(buffer, 0, count)
                    }
                    output.toString("UTF-8")
                } }
                val course = if (text.trimStart().startsWith("{")) {
                    readCourseFile(text).copy(id = newId())
                } else {
                    require(text.toByteArray(Charsets.UTF_8).size <= MAX_LEGACY_COURSE_FILE_BYTES) { "GPX는 2MB 이하여야 합니다" }
                    require(!text.contains("<!DOCTYPE", true) && !text.contains("<!ENTITY", true)) { "지원하지 않는 XML 형식입니다" }
                    val parser = Xml.newPullParser(); parser.setInput(text.reader())
                    val points = mutableListOf<Coordinate>(); var segments = 0
                    while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                        if (parser.eventType == XmlPullParser.START_TAG && parser.name in listOf("trkseg", "rte")) {
                            segments++; require(segments <= 1) { "여러 구간으로 끊어진 GPX입니다. 하나의 연속 코스 파일을 선택해주세요" }
                        }
                        if (parser.eventType == XmlPullParser.START_TAG && parser.name in listOf("trkpt", "rtept")) {
                            points += Coordinate(parser.getAttributeValue(null, "lat").toDouble(), parser.getAttributeValue(null, "lon").toDouble())
                        }
                        parser.next()
                    }
                    Course(name = "가져온 GPX 코스", points = points)
                }
                require(course.points.size in 2..10_000) { "코스에는 2~10,000개 지점이 필요합니다" }
                app.db.dao().saveCourse(CourseRow(course.id, codec.encodeToString(course))); edit(course.id, course.usePaceTargets)
            }.onFailure { app.error.value = "코스를 가져오지 못했습니다: ${it.message}" }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SelectionRow("정렬", sort.label, { showSort = true }, modifier = Modifier.weight(1f), enabled = !deleting)
                Row(Modifier.weight(.7f).heightIn(min = 48.dp)
                    .toggleable(ranOnly, enabled = !deleting, role = Role.Checkbox, onValueChange = { ranOnly = it })
                    .padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(ranOnly, onCheckedChange = null, enabled = !deleting)
                    Text("달린 기록", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    AdaptiveActions {
                        TextButton(onClick = onPlans, enabled = !deleting) { Text("페이스 계획") }
                        TextButton(onClick = { importer.launch(arrayOf("*/*")) }, enabled = !deleting) {
                            Icon(Icons.Outlined.FileDownload, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("가져오기")
                        }
                    }
                }
                IconButton(onClick = { showAdd = true }, enabled = !deleting,
                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(Icons.Outlined.Add, "코스 추가")
                }
            }
            HorizontalDivider()
        }
        if (courses.isEmpty()) item { EmptyState("코스 없음") }
        else if (visible.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("달린 기록이 있는 코스 없음", style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { ranOnly = false }) { Text("달린 기록 필터 끄기 · 전체 코스 보기") }
            }
        }
        items(visible, key = { it.course.id }) { entry ->
            val course = entry.course
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clickable(enabled = !deleting, role = Role.Button) {
                    edit(course.id, course.usePaceTargets)
                }.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(course.name, style = MaterialTheme.typography.titleMedium)
                    Text("%.2f km · %d개 지점 · 달린 횟수 %d회".format(entry.meters / 1000, course.points.size, entry.runCount),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(when {
                        entry.targetPace != null -> "목표 ${paceText(entry.targetPace)}/km · 거리 가중 평균"
                        entry.partialTarget -> "목표 일부 미설정"
                        else -> "목표 미설정"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    deleteTargetId = course.id
                    deleteTargetName = course.name
                    deleteError = null
                }, enabled = !deleting, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                    Icon(Icons.Outlined.Delete, "${course.name} 코스 삭제")
                }
            }
            HorizontalDivider()
        }
    }
    if (showSort) DomainSelectionSheet(
        title = "코스 정렬",
        choices = CourseListSort.entries.map { order -> SelectionChoice(order.name, order.label, when (order) {
            CourseListSort.REGISTERED -> "최신 등록 우선"
            CourseListSort.TARGET_FAST, CourseListSort.TARGET_SLOW -> "모든 구간 목표의 거리 가중 예상 평균 · 미설정은 마지막"
            CourseListSort.RUNS_MANY, CourseListSort.RUNS_FEW -> "종료한 달리기 기록 기준"
            else -> null
        }) },
        selectedKey = sort.name,
        onSelect = { sortKey = it; showSort = false },
        onCancel = { showSort = false }
    )
    if (showAdd) DomainSelectionSheet(
        title = "코스 추가",
        choices = listOf(SelectionChoice("course", "코스만"), SelectionChoice("coursepace", "코스 · 페이스")),
        selectedKey = null,
        onSelect = { type -> showAdd = false; edit("new", type == "coursepace") },
        onCancel = { showAdd = false }
    )
    deleteTargetId?.let { courseId ->
        AlertDialog(
            onDismissRequest = { if (!deleting) { deleteTargetId = null; deleteError = null } },
            properties = DialogProperties(dismissOnBackPress = !deleting, dismissOnClickOutside = !deleting),
            title = { Text("코스 삭제") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("\"$deleteTargetName\" 코스를 삭제할까요? 저장된 운동 기록은 유지됩니다.")
                    deleteError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                }
            },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    if (!deleting) {
                        deleting = true
                        deleteError = null
                        scope.launch {
                            try {
                                app.db.dao().deleteCourse(courseId)
                                deleteTargetId = null
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                deleteError = "삭제하지 못했습니다. 다시 시도해주세요."
                            } finally {
                                deleting = false
                            }
                        }
                    }
                }) { Text(if (deleting) "삭제 중…" else "삭제") }
            },
            dismissButton = { TextButton(enabled = !deleting, onClick = { deleteTargetId = null; deleteError = null }) { Text("취소") } }
        )
    }
}

private val CourseListSort.label: String get() = when (this) {
    CourseListSort.REGISTERED -> "최신 등록 순"
    CourseListSort.DISTANCE_LONG -> "거리 긴 순"
    CourseListSort.DISTANCE_SHORT -> "거리 짧은 순"
    CourseListSort.TARGET_FAST -> "목표 페이스 빠른 순"
    CourseListSort.TARGET_SLOW -> "목표 페이스 느린 순"
    CourseListSort.RUNS_MANY -> "달린 횟수 많은 순"
    CourseListSort.RUNS_FEW -> "달린 횟수 적은 순"
}
