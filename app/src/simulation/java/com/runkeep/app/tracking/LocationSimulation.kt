package com.runkeep.app.tracking

import android.location.Location
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ReplayPhase { OFF, READY, RUNNING, PAUSED, COMPLETE, STOPPED }
data class ReplayState(val phase: ReplayPhase = ReplayPhase.OFF, val second: Int = 0,
                       val total: Int = 0, val detail: String = "시나리오를 선택하고 시작하세요.")

/** App-local source only. Does not change Android's location provider or other apps. */
class LocationSimulation(private val onLocation: (Location) -> Unit, private val onSourceChanged: () -> Unit) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(ReplayState())
    val state = mutable.asStateFlow()
    var enabled = false
        private set
    var leased = false
        private set
    private var frames = emptyList<ReplayFrame>()
    private var next = 0
    private var playback: Job? = null

    fun prepare(scenario: ReplayScenario) {
        prepareFrames(scenario.frames())
    }

    fun prepare(scenario: CustomReplayScenario) {
        prepareFrames(scenario.frames())
    }

    private fun prepareFrames(prepared: List<ReplayFrame>) {
        check(!leased) { "기존 위치 테스트나 운동을 먼저 종료해주세요." }
        playback?.cancel()
        frames = prepared; next = 0; enabled = true
        mutable.value = ReplayState(ReplayPhase.READY, total = frames.last().second, detail = "기록 서비스 시작 대기")
        onSourceChanged()
    }

    fun onAcquire() {
        leased = true
        if (mutable.value.phase == ReplayPhase.READY) play()
    }

    fun onRelease() {
        leased = false; playback?.cancel(); playback = null
        if (enabled) mutable.value = if (mutable.value.phase == ReplayPhase.COMPLETE)
            mutable.value.copy(detail = "재생 완료 · 운동이 종료되었습니다.")
        else mutable.value.copy(phase = ReplayPhase.STOPPED, detail = "위치 구독 종료 · 재생 중지")
    }

    fun pause() {
        if (mutable.value.phase != ReplayPhase.RUNNING) return
        playback?.cancel(); playback = null
        mutable.value = mutable.value.copy(phase = ReplayPhase.PAUSED, detail = "GPS 재생만 쉬는 중 · 운동 시간은 계속 흐릅니다")
    }

    fun play() {
        if (!enabled || !leased || playback?.isActive == true || next >= frames.size) return
        mutable.value = mutable.value.copy(phase = ReplayPhase.RUNNING)
        playback = scope.launch {
            while (next < frames.size) {
                val frame = frames[next++]
                if (frame.emit) {
                    val fix = Location("runkeep-simulation").apply {
                        latitude = frame.coordinate.lat; longitude = frame.coordinate.lon
                        accuracy = frame.accuracy; speed = frame.speed
                        time = System.currentTimeMillis(); elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    }
                    onLocation(fix)
                }
                mutable.value = ReplayState(ReplayPhase.RUNNING, frame.second, frames.last().second, frame.phase)
                if (next < frames.size) delay(1000)
            }
            mutable.value = mutable.value.copy(phase = ReplayPhase.COMPLETE, detail = "재생 완료 · 테스트 종료를 눌러 기록을 마치세요.")
        }
    }

    fun disable() {
        check(!leased) { "진행 중인 운동이나 위치 테스트를 먼저 종료해주세요." }
        playback?.cancel(); playback = null; enabled = false; frames = emptyList(); next = 0
        mutable.value = ReplayState(); onSourceChanged()
    }
}
