package com.runkeep.app.ui

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.runkeep.app.RunkeepApp
import com.runkeep.app.diagnostics.DiagnosticShareFiles
import com.runkeep.core.*
import kotlinx.coroutines.*
import java.io.File

class DiagnosticExportViewModel(application: Application) : AndroidViewModel(application) {
    private val app get() = getApplication<RunkeepApp>()
    var data by mutableStateOf<DiagnosticFile?>(null); private set
    var loading by mutableStateOf(false); private set
    private var transfer by mutableStateOf(DiagnosticTransferState())
    val busy get() = transfer.busy
    val pickerNeeded get() = transfer.phase == DiagnosticTransferPhase.SAVE_READY
    var shareUri by mutableStateOf<Uri?>(null); private set
    private var content: String? = null
    private var sharedFile: File? = null
    fun load(id: String) {
        if (busy || loading) return
        loading = true; data = null
        viewModelScope.launch {
            try { data = app.diagnostics.snapshot(id) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { app.error.value = "진단 자료를 읽지 못했습니다. 저장 공간을 확인해주세요." }
            finally { loading = false }
        }
    }
    fun invalidate() { data = null }
    fun delete(id: String) {
        if (busy || loading) return
        loading = true; data = null
        viewModelScope.launch {
            try {
                app.diagnostics.delete(id)
                withContext(Dispatchers.IO) { DiagnosticShareFiles(File(app.cacheDir, "diagnostic-shares")).deleteRun(id) }
                data = null
                app.error.value = "진단 자료 삭제 완료"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { app.error.value = "진단 자료 삭제 실패 · 다시 시도해주세요." }
            finally { loading = false }
        }
    }
    fun prepare(id: String, coordinates: Boolean, share: Boolean) {
        val snapshot = data ?: return
        if (busy || loading) return
        transfer = transfer.start()
        viewModelScope.launch {
            try {
                val json = withContext(Dispatchers.Default) { snapshot.export(coordinates) }
                if (share) {
                    val file = withContext(Dispatchers.IO) { DiagnosticShareFiles(File(app.cacheDir, "diagnostic-shares")).create(id, json) }
                    sharedFile = file
                    shareUri = FileProvider.getUriForFile(app, "${app.packageName}.diagnostics", file)
                } else { content = json }
                transfer = transfer.prepared(share)
            } catch (cancelled: CancellationException) { reset(deleteShared = true); throw cancelled }
            catch (_: Exception) { reset(deleteShared = true); app.error.value = "진단 내보내기 준비 실패" }
        }
    }
    fun pickerLaunched() { transfer = transfer.launched() }
    fun saveResult(uri: Uri?) {
        if (transfer.phase == DiagnosticTransferPhase.WRITING) return
        val json = content
        if (transfer.phase != DiagnosticTransferPhase.SAVE_PICKER) {
            if (uri != null) app.error.value = "진단 내보내기를 다시 선택해주세요."
            return
        }
        transfer = transfer.documentResult(uri != null, json != null)
        if (uri == null) { reset(); app.error.value = "진단 저장 취소"; return }
        if (json == null) { reset(); app.error.value = "진단 내보내기를 다시 선택해주세요."; return }
        content = null
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    com.runkeep.app.diagnostics.DiagnosticDocumentWriter.write(json) { app.contentResolver.openOutputStream(uri, "wt") }
                }
                app.error.value = "진단 파일 저장 완료"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { app.error.value = "진단 저장 실패 · 선택한 파일에 일부 내용이 남을 수 있습니다." }
            finally { reset() }
        }
    }
    fun shareLaunched() { transfer = transfer.launched(); shareUri = null }
    fun shareReturned() {
        if (transfer.phase != DiagnosticTransferPhase.SHARE_PICKER) return
        reset()
        app.error.value = "공유 화면을 닫았습니다. 전달 완료 여부는 대상 앱에서 확인해주세요."
    }
    fun launchFailed() {
        reset(deleteShared = true)
        app.error.value = "진단 저장·공유 화면을 열지 못했습니다."
    }
    private fun reset(deleteShared: Boolean = false) {
        val file = sharedFile
        if (deleteShared && file != null) viewModelScope.launch(Dispatchers.IO) { file.delete() }
        sharedFile = null; content = null; shareUri = null; transfer = transfer.finish()
    }
}

@Composable fun DiagnosticExportHost(runId: String, open: Boolean, onDismiss: () -> Unit,
    model: DiagnosticExportViewModel, canDelete: Boolean) {
    var confirmDelete by rememberSaveable(runId, open) { mutableStateOf(false) }
    var coordinates by rememberSaveable(runId, open) { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json"), model::saveResult)
    val share = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { model.shareReturned() }
    LaunchedEffect(open, runId) { if (open) model.load(runId) }
    LaunchedEffect(model.pickerNeeded) {
        if (model.pickerNeeded) {
            model.pickerLaunched()
            try { save.launch("runkeep-diagnostic-v1.json") } catch (_: Exception) { model.launchFailed() }
        }
    }
    LaunchedEffect(model.shareUri) {
        model.shareUri?.let { uri ->
            model.shareLaunched()
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newRawUri("Runkeep diagnostic", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try { share.launch(Intent.createChooser(intent, "진단 파일 공유")) }
            catch (_: Exception) { model.launchFailed() }
        }
    }
    if (!open) return
    val data = model.data
    TaskSheet("진단 자료 내보내기", onCancel = { if (!model.busy) onDismiss() }, actions = {
        AdaptiveActions {
            TextButton(onClick = onDismiss, enabled = !model.busy) { Text("닫기") }
            OutlinedButton(onClick = { model.prepare(runId, coordinates, false) }, enabled = data != null && !model.loading && !model.busy) { Text("파일 저장") }
            Button(onClick = { model.prepare(runId, coordinates, true) }, enabled = data != null && !model.loading && !model.busy) { Text("공유") }
        }
    }) {
        if (model.loading) Text("진단 자료 확인 중…")
        else if (data == null) {
            Text("수집한 진단 자료가 없습니다. 진단을 켜기 전 운동이나 삭제·보관 기한이 지난 자료는 복원하지 않습니다.")
            if (canDelete) TextButton(onClick = { confirmDelete = true }, enabled = !model.busy && !model.loading) { Text("진단·공유 임시 사본 정리") }
        }
        else {
            val sessions = data.events.filter { it.kind == DiagnosticKind.COLLECTION_START }
            Text("진단 형식 v1 · 사건 ${data.events.size}개 · 수집 ${sessions.size}회")
            sessions.forEach { start ->
                val end = data.events.lastOrNull { it.session == start.session && it.kind == DiagnosticKind.COLLECTION_STOP }
                Text("운동 시작 후 ${durationText(start.runOffsetMs)} → ${end?.let { durationText(it.runOffsetMs) } ?: "종료 확인 없음"}")
            }
            Text("켜진 구간의 앱·Android 버전, 측정 설정, 상대 시각, 위치 품질·처리 사유, 경로 분리, 정지·재개, 지도 전달 개수·갱신 결과를 포함합니다. 미수집 구간은 복원하지 않습니다.")
            Text("지도가 열리지 않거나 준비되지 않은 경우 성공으로 처리하지 않습니다. SDK 호출 결과와 실제 화면 표시는 구분됩니다.")
            if (data.loss.incomplete) Text("일부 자료 유실 · 큐 누락 ${data.loss.queueDropped}개 · 저장 실패 ${data.loss.storageFailed} · 용량 한도 ${data.loss.capacityReached} · 파일 손상 ${data.loss.truncated} · 종료 미확인 ${data.loss.uncleanSession}", color = MaterialTheme.colorScheme.error)
            ToggleRow("좌표 포함", coordinates, "켜면 수집 중 받은 위치 샘플만 포함합니다. 코스·과거 경로는 포함하지 않습니다.") { if (!model.busy) coordinates = it }
            if (canDelete) TextButton(onClick = { confirmDelete = true }, enabled = !model.busy && !model.loading) { Text("진단 자료만 삭제") }
            Text("기본값은 모든 좌표 제외입니다. 심박수·체중·계정·기기 식별자·키·개인 경로는 항상 제외합니다.")
            Text("운동당 4 MiB·전체 32 MiB·14일 보관. 운동 삭제 시 진단도 삭제됩니다. 공유 사본은 최대 2개, 최대 1시간 후 앱의 다음 정리 시 삭제됩니다. 외부로 저장·전달한 파일은 대상 앱에서 관리하세요.")
        }
        if (model.busy) Text("저장·공유 진행 중…")
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("진단 자료를 삭제할까요?") },
        text = { Text("운동 기록은 유지하고 진단과 공유 임시 사본을 삭제합니다. 외부에 전달한 파일은 대상 앱에서 삭제해주세요.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; model.delete(runId) }) { Text("삭제") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("취소") } })
}
