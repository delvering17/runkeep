package com.runkeep.app.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.runkeep.core.FileExportRequest
import com.runkeep.core.FileExportState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ExportChoice(val label: String) {
    COURSE_GPX("GPX"), COURSE_JSON("JSON"), RUN_GPX("전체 기록 · GPX"),
    GOAL_GPX("목표까지 · GPX"), RUN_JSON("전체 기록 · JSON (심박수 포함)")
}

@Composable fun ExportSelectionSheet(options: List<ExportChoice>, onSelect: (ExportChoice) -> Unit, onCancel: () -> Unit,
    disabledOptions: Set<ExportChoice> = emptySet()) {
    var choosing by remember { mutableStateOf(false) }
    var selectedName by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = options.firstOrNull { it.name == selectedName && it !in disabledOptions }
    TaskSheet("내보내기", onCancel = onCancel, actions = {
        AdaptiveActions {
            TextButton(onClick = onCancel, enabled = !choosing) { Text("취소") }
            Button(enabled = !choosing && selected != null, onClick = {
                selected?.let { choosing = true; onSelect(it) }
            }) { Text("내보내기") }
        }
    }) {
        options.forEach { option ->
            SelectionOptionRow(SelectionChoice(option.name, option.label), selectedName == option.name,
                { selectedName = option.name }, enabled = !choosing && option !in disabledOptions)
        }
    }
}

class FileExporter(val busy: Boolean, val launch: (String, String) -> Unit)

class FileExportViewModel : ViewModel() {
    var state by mutableStateOf(FileExportState())
        private set

    fun start(request: FileExportRequest, open: (String) -> Unit, message: (String) -> Unit) {
        if (state.busy) return
        state = state.start(request)
        try {
            open(request.fileName)
        } catch (failure: Exception) {
            state = state.finish()
            message("내보내기 실패: ${failure.message}")
        }
    }

    fun result(uri: Uri?, resolver: ContentResolver, message: (String) -> Unit) {
        if (state.writing) return
        val request = state.request
        if (uri == null) { state = state.finish(); return }
        if (request == null) { message("내보내기를 다시 선택해주세요."); return }
        state = state.beginWriting()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = resolver.openOutputStream(uri, "wt") ?: error("파일을 열 수 없습니다")
                    output.bufferedWriter(Charsets.UTF_8).use { it.write(request.content) }
                }
                message("${request.fileName} 저장 완료")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                message("내보내기 실패: ${failure.message}")
            } finally {
                state = state.finish()
            }
        }
    }
}

@Composable fun rememberFileExporter(onMessage: (String) -> Unit): FileExporter {
    val resolver = LocalContext.current.contentResolver
    val message by rememberUpdatedState(onMessage)
    val model: FileExportViewModel = viewModel()
    val gpx = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri -> model.result(uri, resolver, message) }
    val json = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> model.result(uri, resolver, message) }
    return FileExporter(model.state.busy) { name, content ->
        model.start(FileExportRequest(name, content), { fileName ->
            if (fileName.endsWith(".gpx", ignoreCase = true)) gpx.launch(fileName) else json.launch(fileName)
        }, message)
    }
}
