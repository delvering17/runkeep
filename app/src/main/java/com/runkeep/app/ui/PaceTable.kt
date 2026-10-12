package com.runkeep.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.runkeep.core.*

internal class PaceTableState(byCourse: Boolean = false, courseBasis: String? = null, sheet: String? = null) {
    var byCourse by mutableStateOf(byCourse)
    var courseBasis by mutableStateOf(courseBasis)
    var sheet by mutableStateOf(sheet)
}

@Composable internal fun rememberPaceTableState(runId: String): PaceTableState = rememberSaveable(runId,
    saver = listSaver<PaceTableState, Any?>(
        save = { listOf(it.byCourse, it.courseBasis, it.sheet) },
        restore = { PaceTableState(it[0] as Boolean, it[1] as String?, it[2] as String?) }
    )) { PaceTableState() }

@Composable private fun paceTableRows(run: RunSummary, points: List<TrackPoint>, state: PaceTableState): List<PaceProgressRow> =
    remember(run, points, state.byCourse, state.courseBasis) {
        val course = run.course
        when {
            run.status == RunStatus.FINISHED && run.meters <= 0 -> emptyList()
            !state.byCourse -> PaceProgress.kilometers(run, points)
            course == null || state.courseBasis == null -> emptyList()
            state.courseBasis == "pass" -> PaceProgress.coursePassages(course, run, points)
            else -> PaceProgress.courseDistances(course, run, points)
        }
    }

@Composable private fun PaceTableControls(state: PaceTableState) {
    SelectionRow("구간", if (state.byCourse) "코스 지점별" else "1km 단위", { state.sheet = "kind" })
    if (state.byCourse) SelectionRow("계산 기준", when (state.courseBasis) {
        "pass" -> "실제 지점 통과"; "distance" -> "누적 거리"; else -> "선택 필요"
    }, { state.sheet = "basis" })
}

@Composable private fun PaceTableEmpty(state: PaceTableState) {
    Text(if (state.byCourse && state.courseBasis == null) "계산 기준을 선택하세요." else "구간 기록 없음",
        style = MaterialTheme.typography.bodyMedium)
}

@Composable private fun PaceTableRow(row: PaceProgressRow) {
    val state = when (row.state) { ProgressState.COMPLETE -> "완료"; ProgressState.ACTIVE -> "진행 중"; ProgressState.PENDING -> "대기"; ProgressState.INCOMPLETE -> "미완료" }
    val actualMeters = row.actualMeters
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${row.label} · $state", style = MaterialTheme.typography.titleSmall)
        AdaptiveActions {
            Text(if (actualMeters == null) "예정 %.2f km".format(row.plannedMeters / 1000) else "%.2f km".format(actualMeters / 1000))
            Text(row.activeMs?.let(::durationText) ?: "시간 —")
            Text("${paceText(row.pace)}/km")
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** Inline content only; callers may bound the preview while owning their main scroll. */
@Composable fun PaceTable(run: RunSummary, points: List<TrackPoint>, maxRows: Int? = null) {
    val state = rememberPaceTableState(run.id)
    val rows = paceTableRows(run, points, state)
    Column(Modifier.fillMaxWidth()) {
        PaceTableControls(state)
        if (rows.isEmpty()) PaceTableEmpty(state)
        rows.take(maxRows ?: rows.size).forEach { PaceTableRow(it) }
        if (maxRows != null && rows.size > maxRows) Text("전체 ${rows.size}구간", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
    }
    PaceTableSelectionSheet(run, state)
}

/** The independent page owns one main LazyColumn; headers and each split are separate lazy items. */
@Composable internal fun PaceTablePage(run: RunSummary, points: List<TrackPoint>, state: PaceTableState,
                                      listState: LazyListState, modifier: Modifier = Modifier, loading: Boolean = false) {
    val rows = paceTableRows(run, points, state)
    val rowKind = if (state.byCourse) "course:${state.courseBasis}" else "km"
    LazyColumn(modifier, state = listState, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
        if (loading) item(key = "loading") { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        item(key = "controls") { Column { PaceTableControls(state) } }
        if (rows.isEmpty()) item(key = "empty") { PaceTableEmpty(state) }
        itemsIndexed(rows, key = { index, _ -> "$rowKind:$index" }, contentType = { _, _ -> "split" }) { _, row -> Column { PaceTableRow(row) } }
    }
    PaceTableSelectionSheet(run, state)
}

@Composable private fun PaceTableSelectionSheet(run: RunSummary, state: PaceTableState) {
    when (state.sheet) {
        "kind" -> DomainSelectionSheet("구간 단위", buildList {
            add(SelectionChoice("km", "1km 단위"))
            if (run.course != null) add(SelectionChoice("course", "코스 지점별"))
        }, if (state.byCourse) "course" else "km", onSelect = { state.byCourse = it == "course"; state.sheet = null }, onCancel = { state.sheet = null })
        "basis" -> DomainSelectionSheet("코스 구간 계산 기준", listOf(SelectionChoice("pass", "실제 지점 통과"), SelectionChoice("distance", "누적 거리")), state.courseBasis,
            onSelect = { state.courseBasis = it; state.sheet = null }, onCancel = { state.sheet = null })
    }
}
