package com.runkeep.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.runkeep.app.BuildConfig
import com.runkeep.app.RunkeepApp
import com.runkeep.core.*
import kotlin.math.roundToInt

@Composable fun SettingsScreen(app: RunkeepApp, prefs: Preferences, onDiagnostics: () -> Unit,
    onVoice: () -> Unit, onSimulation: () -> Unit, onPrivacy: () -> Unit) {
    var panel by rememberSaveable { mutableStateOf<String?>(null) }
    var weight by rememberSaveable { mutableStateOf("") }
    var weightError by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        ToggleRow("출발 대기", prefs.startDelaySeconds > 0) { enabled ->
            app.updateSettings(prefs.copy(startDelaySeconds = if (enabled) prefs.startDelayPresetSeconds else 0,
                startDelayPresetSeconds = prefs.startDelaySeconds.takeIf { it > 0 } ?: prefs.startDelayPresetSeconds))
        }
        if (prefs.startDelaySeconds > 0) IntegerSettingRow("대기 시간", prefs.startDelaySeconds, 1..60, "초", {
            app.updateSettings(prefs.copy(startDelaySeconds = it, startDelayPresetSeconds = it))
        }, presets = listOf(1, 3, 5, 10, 30, 60))
        SettingsEntry("페이스와 길 안내", "허용 범위 ±${prefs.paceTolerance}초/km · 회전 ${prefs.turnLeadMeters}m 전") { panel = "pace" }
        SettingsEntry("음성 · 경보 · 케이던스", "음성 ${if (prefs.voice) "켜짐" else "꺼짐"} · 경보 ${if (prefs.paceAlarm) "켜짐" else "꺼짐"} · 메트로놈 ${if (prefs.metronome) "켜짐" else "꺼짐"}", onVoice)
        SettingsEntry("측정 · 위치", "자동 일시정지 ${if (prefs.autoPause) "켜짐" else "꺼짐"}") { panel = "location" }
        SettingsEntry("개인 · 화면", "${if (prefs.darkTheme) "다크 모드" else "밝은 모드"} · 체중 ${prefs.weightKg?.let { "%.1fkg".format(it) } ?: "미설정"}") { panel = "personal" }
        SettingsEntry("데이터 · 권한", "저장과 위치·심박수 권한", onPrivacy)
        if (BuildConfig.BUILD_TYPE == "simulation") SettingsEntry("GPS 테스트", "시나리오 선택 · 편집 · 재생", onSimulation)
        Text("런킵 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp))
    }
    when (panel) {
        "pace" -> TaskSheet("페이스와 길 안내", onCancel = { panel = null }, actions = {
            TextButton(onClick = { panel = null }) { Text("닫기") }
        }) {
            Text("허용 범위 ±${prefs.paceTolerance}초/km")
            Slider(prefs.paceTolerance.toFloat(), { app.updateSettings(prefs.copy(paceTolerance = it.roundToInt())) }, valueRange = 5f..60f, steps = 10)
            Text("회전 ${prefs.turnLeadMeters}m 전 안내")
            Slider(prefs.turnLeadMeters.toFloat(), { app.updateSettings(prefs.copy(turnLeadMeters = it.roundToInt())) }, valueRange = 10f..100f, steps = 8)
            ToggleRow("계획 완료 시 페이스 알림 끄기", prefs.stopCoachAtEnd,
                "끄면 마지막 구간의 목표 페이스로 안내합니다.") { app.updateSettings(prefs.copy(stopCoachAtEnd = it)) }
            Text("페이스 설정은 다음 평가부터, 회전 거리는 다음 운동부터 적용됩니다.", style = MaterialTheme.typography.bodySmall)
        }
        "location" -> TaskSheet("측정 · 위치", onCancel = { panel = null }, actions = {
            TextButton(onClick = { panel = null }) { Text("닫기") }
        }) {
            ToggleRow("자동 일시정지", prefs.autoPause) { app.updateSettings(prefs.copy(autoPause = it)) }
            Text("자동 일시정지는 다음 운동부터 적용됩니다.", style = MaterialTheme.typography.bodySmall)
            SelectionRow("현재 위치", "확인", { panel = null; onDiagnostics() })
        }
        "personal" -> TaskSheet("개인 · 화면", onCancel = { panel = null }, actions = {
            TextButton(onClick = { panel = null }) { Text("닫기") }
        }) {
            ToggleRow("다크 모드", prefs.darkTheme) { app.updateSettings(prefs.copy(darkTheme = it)) }
            SelectionRow("체중", prefs.weightKg?.let { "%.1fkg".format(it) } ?: "미설정", {
                weight = prefs.weightKg?.toString() ?: ""; weightError = null; panel = "weight"
            })
        }
        "weight" -> {
            val parsed = weight.replace(',', '.').toDoubleOrNull()
            val valid = weight.isBlank() || parsed != null && parsed.isFinite() && parsed in 20.0..300.0
            TaskSheet("체중", onCancel = { panel = null }, actions = {
                AdaptiveActions {
                    TextButton(onClick = { panel = null }) { Text("취소") }
                    Button(enabled = valid, onClick = {
                        runCatching { app.updateSettings(prefs.copy(weightKg = parsed)) }.onSuccess { panel = null }
                            .onFailure { weightError = "체중을 저장하지 못했습니다. 다시 시도해주세요." }
                    }) { Text("저장") }
                }
            }) {
                OutlinedTextField(weight, { next ->
                    if (next.length <= 10 && next.all { it.isDigit() || it == '.' || it == ',' }) weight = next
                }, label = { Text("kg") }, singleLine = true, isError = !valid, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                if (!valid) Text("20~300kg 사이로 입력해주세요.", color = MaterialTheme.colorScheme.error)
                weightError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text("칼로리 추정에 사용합니다. 비워서 저장하면 해제됩니다.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable private fun SettingsEntry(title: String, summary: String, open: () -> Unit) {
    SelectionRow(title, summary, open)
}

@Composable fun PrivacyScreen() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("운동 기록, 코스, 페이스 계획과 가져온 심박수는 이 휴대폰에 저장됩니다. 런킵 서버나 분석 서비스로 전송하지 않습니다.")
        Text("위치는 사용자가 달리기를 시작하면 기록하며 운동 중에는 알림을 표시합니다. 걸음 센서는 케이던스 추정에 사용합니다. 종료하면 위치·걸음 수집을 중단합니다.")
        Text("Health Connect에서는 사용자가 선택한 운동 시간대의 삼성헬스 운동·심박수 데이터만 가져옵니다. 기존 삼성헬스 기록을 수정하거나 삭제하지 않습니다. 설정에서 권한을 언제든 철회할 수 있습니다.")
        Text("지도 표시에는 카카오 서비스와 인터넷 연결을 사용합니다. 지도 요청에는 화면에 표시할 지역 등의 정보가 포함됩니다. 앱에서 직접 만든 코스나 운동 기록을 파일로 내보내는 것은 사용자가 선택할 때만 수행합니다.")
        Text("기록 화면에서 운동을 삭제할 수 있습니다. 앱 삭제 시 로컬 데이터도 삭제됩니다. 필요한 기록은 GPX로, 코스는 JSON으로 내보내 보관하세요.")
    }
}
