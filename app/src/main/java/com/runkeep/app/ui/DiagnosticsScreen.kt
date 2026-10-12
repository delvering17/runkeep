package com.runkeep.app.ui

import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.runkeep.app.BuildConfig
import com.runkeep.app.RunkeepApp
import com.runkeep.app.tracking.PaceAlarmPlayer
import com.runkeep.app.tracking.PositionPhase
import com.runkeep.app.tracking.SpeechPlayback
import com.runkeep.app.tracking.SpeechPlayer
import com.runkeep.core.*
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable fun DiagnosticsScreen(app: RunkeepApp, prefs: Preferences) {
    val context = LocalContext.current
    var testing by rememberSaveable { mutableStateOf(false) }
    val position = rememberPosition(app, enabled = testing)
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(testing) {
        while (testing) { now = SystemClock.elapsedRealtime(); delay(1000) }
    }
    val fix = position.fix?.takeIf { testing && position.phase == PositionPhase.RECEIVING && (position.ageMs(now) ?: Long.MAX_VALUE) <= 10_000 }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (testing && BuildConfig.FLAVOR == "kakao") {
                RunMap(emptyList(), Modifier.fillMaxSize(), current = fix?.let { Coordinate(it.latitude, it.longitude) },
                    followUserInitially = true, useLocationProvider = false)
            } else {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainer) {
                    Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.Center) {
                        Text(if (!testing) "현재 위치 확인" else "이 빌드에서는 지도를 표시하지 않습니다", style = MaterialTheme.typography.titleLarge)
                        Text(if (!testing) "위치 테스트를 시작하세요." else "위치 수신 상태와 정확도는 아래에서 확인할 수 있습니다.")
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().heightIn(max = mobileWindow().height * .55f).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (testing) {
                Text(when (position.phase) {
                    PositionPhase.IDLE -> "위치 확인 대기"
                    PositionPhase.PERMISSION -> "정확한 위치 권한 필요"
                    PositionPhase.DISABLED -> "휴대폰 위치 꺼짐"
                    PositionPhase.WAITING -> "현재 위치 찾는 중"
                    PositionPhase.ERROR -> position.detail ?: "위치 연결 실패"
                    PositionPhase.RECEIVING -> if (fix == null) "위치 갱신 대기" else if (fix.accuracy > 25) "위치 수신 · 정확도 낮음" else "위치 수신 중"
                }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                fix?.let { Text("정확도 ±${it.accuracy.roundToInt()}m") }
                if (fix == null) TextButton(onClick = { app.location.restart() }) { Text("다시 찾기") }
            }
            AdaptiveActions {
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("위치 설정") }
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())) }) { Text("앱 권한") }
            }
            Text("테스트 위치는 기록에 저장하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            Button(onClick = { testing = !testing }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (testing) "위치 테스트 중지" else "위치 테스트 시작")
            }
        }
    }
}

@Composable fun AudioSettingsScreen(app: RunkeepApp, prefs: Preferences) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val speech = remember(context) { SpeechPlayer(context) }
    val alarm = remember(context) { PaceAlarmPlayer(context) }
    val speechPlayback by speech.playback.collectAsStateWithLifecycle()
    val speechStatus by speech.status.collectAsStateWithLifecycle()
    val alarmPlaying by alarm.playing.collectAsStateWithLifecycle()
    val alarmError by alarm.error.collectAsStateWithLifecycle()
    val live by app.live.collectAsStateWithLifecycle()
    val allowed = audioPreviewAllowed(live.summary?.status)
    var preview by remember { mutableStateOf(AudioPreviewState()) }
    var playedPrefs by remember { mutableStateOf(prefs) }
    var failure by remember { mutableStateOf<String?>(null) }
    var editDistance by rememberSaveable { mutableStateOf(false) }
    var distanceText by rememberSaveable { mutableStateOf("") }
    fun paceSample(): String? = paceWarningAnnouncement(
        PaceFeedbackState(PaceDirection.SLOW, currentPace = 380.0, targetPace = 360),
        prefs.copy(voice = true, paceVoice = true), 0)?.speechText()
    fun stop() { speech.stop(); alarm.stop(); preview = preview.stopped(); failure = null }
    fun play(sample: AudioSample) {
        if (!audioPreviewAllowed(app.live.value.summary?.status)) { stop(); return }
        speech.stop(); alarm.stop(); failure = null
        preview = preview.start(sample); playedPrefs = prefs
        if (sample.speech) {
            val text = if (sample == AudioSample.ROUTE) "앞에서 오른쪽으로 이동하세요." else paceSample()
            if (text == null) { preview = preview.stopped(); return }
            if (!speech.speak(text, prefs)) { preview = preview.failed(); failure = speech.status.value }
        } else {
            alarm.allowResume()
            val direction = when (sample) { AudioSample.FAST -> PaceDirection.FAST; AudioSample.SLOW -> PaceDirection.SLOW; else -> null }
            val target = when (sample) { AudioSample.METRONOME_180 -> 180; AudioSample.METRONOME_185 -> 185; else -> prefs.cadenceTargetSpm }
            alarm.update(direction, target, prefs.paceAlarmVolume, SystemClock.elapsedRealtime() + 1000,
                metronome = direction == null)
            alarm.error.value?.let { alarm.stop(); preview = preview.failed(); failure = it }
        }
    }
    val stopLatest by rememberUpdatedState(::stop)
    DisposableEffect(owner, speech, alarm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_PAUSE) stopLatest() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); speech.close(); alarm.close() }
    }
    LaunchedEffect(allowed) { if (!allowed) stop() }
    LaunchedEffect(speechPlayback, preview.request) {
        if (preview.sample?.speech == true && preview.active) {
            when (speech.playback.value) {
                SpeechPlayback.READY -> preview = preview.completed()
                SpeechPlayback.ERROR -> { preview = preview.failed(); failure = speech.status.value }
                SpeechPlayback.STOPPED, SpeechPlayback.CLOSED -> preview = preview.stopped()
                else -> Unit
            }
        }
    }
    LaunchedEffect(preview.request) {
        if (preview.sample?.speech == false && preview.active) {
            delay(1100)
            if (alarm.error.value != null) { preview = preview.failed(); failure = alarm.error.value }
            else preview = preview.completed()
            alarm.stop()
        }
    }
    LaunchedEffect(alarmError) {
        val currentError = alarm.error.value
        if (currentError != null && preview.sample?.speech == false && preview.active) {
            alarm.stop(); preview = preview.failed(); failure = currentError
        }
    }
    LaunchedEffect(prefs.voiceVolume, prefs.voiceSpeed, prefs.voicePitch, prefs.paceAlarmVolume, prefs.cadenceTargetSpm,
        prefs.readPaceStatus, prefs.readCurrentPace, prefs.readTargetPace,
        preview.armed, preview.sample, preview.request, allowed) {
        if (preview.canReplay(live.summary?.status) && preview.changed(playedPrefs, prefs)) {
            delay(450)
            preview.sample?.let { play(it) }
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("음성 안내")
        ToggleRow("앱 전체 음성 안내", prefs.voice) { app.updateSettings(prefs.copy(voice = it)) }
        Section("목표 이탈 음성")
        ToggleRow("페이스 음성 안내", prefs.paceVoice, "길 안내·거리 안내·경보음·메트로놈과 별개입니다.") { app.updateSettings(prefs.copy(paceVoice = it)) }
        if (prefs.voice && prefs.paceVoice) {
        PaceVoiceOption("빠름 · 느림 상태 읽기", prefs.readPaceStatus, prefs.paceVoice) { app.updateSettings(prefs.copy(readPaceStatus = it)) }
        PaceVoiceOption("현재 페이스 읽기", prefs.readCurrentPace, prefs.paceVoice) { app.updateSettings(prefs.copy(readCurrentPace = it)) }
        PaceVoiceOption("목표 페이스 읽기", prefs.readTargetPace, prefs.paceVoice) { app.updateSettings(prefs.copy(readTargetPace = it)) }
        IntegerSettingRow("재안내", prefs.paceRepeatSeconds, 10..120, "초", {
            app.updateSettings(prefs.copy(paceRepeatSeconds = it))
        }, presets = listOf(10, 30, 60, 120))
        }
        Section("거리 음성")
        ToggleRow("거리마다 현재 페이스", prefs.distancePaceVoice) { app.updateSettings(prefs.copy(distancePaceVoice = it)) }
        if (prefs.voice && prefs.distancePaceVoice) SelectionRow("현재 페이스 안내 간격", "${prefs.distancePaceMeters}m", {
            distanceText = prefs.distancePaceMeters.toString(); editDistance = true
        })
        ToggleRow("1km마다 누적 평균 페이스", prefs.kilometerVoice) { app.updateSettings(prefs.copy(kilometerVoice = it)) }
        if (prefs.voice) VoiceControls(app, prefs)
        AudioSample.entries.filter { it.speech }.forEach { sample ->
            val active = preview.sample == sample && preview.active
            OutlinedButton(onClick = { if (active) stop() else play(sample) }, enabled = allowed && (speech.ready || active) &&
                (sample != AudioSample.PACE || paceSample() != null || active), modifier = Modifier.heightIn(min = 48.dp)) {
                Text((if (sample == AudioSample.ROUTE) "길 안내" else "페이스 안내") + if (active) " 중지" else " 들어보기")
            }
        }
        when (speechPlayback) {
            SpeechPlayback.PREPARING -> Text("음성 준비 중")
            SpeechPlayback.ERROR -> Text(speechStatus, color = MaterialTheme.colorScheme.error)
            else -> Unit
        }
        HorizontalDivider()
        Section("경보음 · 케이던스")
        ToggleRow("페이스 경보음", prefs.paceAlarm, "음성과 별도로 재생합니다.") { app.updateSettings(prefs.copy(paceAlarm = it)) }
        ToggleRow("지속 메트로놈", prefs.metronome, "정상 페이스·GPS 대기 중에도 목표 박자를 유지합니다. 일시정지하면 멈춥니다.") { app.updateSettings(prefs.copy(metronome = it)) }
        ToggleRow("목표 케이던스 직접 설정", prefs.cadenceTargetSpm != null) {
            app.updateSettings(prefs.copy(cadenceTargetSpm = if (it) prefs.cadencePresetSpm else null,
                cadencePresetSpm = prefs.cadenceTargetSpm ?: prefs.cadencePresetSpm))
        }
        if (prefs.paceAlarm || prefs.metronome) prefs.cadenceTargetSpm?.let { target ->
            IntegerSettingRow("목표 박자", target, 120..220, "spm", {
                app.updateSettings(prefs.copy(cadenceTargetSpm = it, cadencePresetSpm = it))
            }, presets = listOf(180, 185))
        }
        Text("경보 · 메트로놈 음량 ${(prefs.paceAlarmVolume * 100).roundToInt()}%")
        Slider(prefs.paceAlarmVolume, { app.updateSettings(prefs.copy(paceAlarmVolume = it)) })
        AudioSample.entries.filter { !it.speech }.forEach { sample ->
            val active = preview.sample == sample && preview.active
            OutlinedButton(onClick = { if (active) stop() else play(sample) }, enabled = allowed, modifier = Modifier.heightIn(min = 48.dp)) {
                val label = when (sample) {
                    AudioSample.FAST -> "빠름"; AudioSample.SLOW -> "느림"
                    AudioSample.METRONOME_180 -> "180spm 메트로놈"; AudioSample.METRONOME_185 -> "185spm 메트로놈"
                    else -> "목표 박자 메트로놈"
                }
                Text(label + if (active && alarmPlaying) " 중지" else if (active) " 취소" else " 들어보기")
            }
        }
        failure?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
        if (!allowed) Text("진행 중인 운동을 종료하면 미리 들을 수 있습니다.", style = MaterialTheme.typography.bodySmall)
        Text("조절값은 다음 안내부터 적용됩니다. 테스트를 시작한 뒤 조절하면 같은 예문을 다시 재생합니다.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            stop()
            runCatching { context.startActivity(Intent("com.android.settings.TTS_SETTINGS")) }.onFailure {
                app.error.value = "휴대폰 설정에서 ‘텍스트 읽어주기’를 검색해주세요."
            }
        }) { Text("휴대폰 음성 설정") }
    }
    if (editDistance) {
        val value = distanceText.toIntOrNull()
        val valid = value != null && value in DistancePacePolicy.RANGE
        TaskSheet("현재 페이스 안내 간격", onCancel = { editDistance = false }, actions = {
            AdaptiveActions {
                TextButton(onClick = { editDistance = false }) { Text("취소") }
                Button(enabled = valid, onClick = {
                    app.updateSettings(prefs.copy(distancePaceMeters = requireNotNull(value))); editDistance = false
                }) { Text("적용") }
            }
        }) {
            OutlinedTextField(distanceText, { next ->
                if (next.length <= 5 && next.all { it in '0'..'9' }) distanceText = next
            }, label = { Text("거리 (m)") }, singleLine = true, isError = !valid,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            AdaptiveActions {
                listOf(10, 20, 100, 200, 1000).forEach { meters ->
                    OutlinedButton(onClick = { distanceText = meters.toString() }) { Text("${meters}m") }
                }
            }
            Text("10~10000m 사이로 입력해주세요.", color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
        }
    }
}

@Composable private fun PaceVoiceOption(title: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, modifier = Modifier.weight(1f), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(checked, onChange, enabled = enabled)
    }
}

@Composable fun VoiceControls(app: RunkeepApp, prefs: Preferences) {
    Text("안내 음량 ${(prefs.voiceVolume * 100).roundToInt()}%")
    Slider(prefs.voiceVolume, { app.updateSettings(prefs.copy(voiceVolume = it)) }, valueRange = 0f..1f)
    Text("말하기 속도 %.1f배".format(prefs.voiceSpeed))
    Slider(prefs.voiceSpeed, { app.updateSettings(prefs.copy(voiceSpeed = it)) }, valueRange = .5f..1.5f, steps = 9)
    Text("목소리 높낮이 %.1f배".format(prefs.voicePitch))
    Slider(prefs.voicePitch, { app.updateSettings(prefs.copy(voicePitch = it)) }, valueRange = .7f..1.3f, steps = 5)
}
