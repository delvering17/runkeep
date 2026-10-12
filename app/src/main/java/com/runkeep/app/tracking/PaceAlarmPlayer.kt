package com.runkeep.app.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.*
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.runkeep.core.*
import kotlinx.coroutines.*
import java.io.Closeable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class PaceAlarmPlayer(context: Context, sharedFocus: RunAudioFocus? = null) : Closeable {
    private val context = context.applicationContext
    private val audio = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val output = BeatOutputPolicy()
    private val tracks = mutableMapOf<BeatSound, AudioTrack>()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private val mutablePlaying = MutableStateFlow(false)
    val playing = mutablePlaying.asStateFlow()
    private var retryAt = 0L
    private var closed = false
    private var volume = .5f
    private var job: Job? = null
    private val ownsFocus = sharedFocus == null
    private val sessionFocus = sharedFocus ?: RunAudioFocus(context)
    private val focus = sessionFocus.client { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> output.focusGained()
            AudioManager.AUDIOFOCUS_LOSS -> { output.interrupt(permanent = true); silence(); releaseFocus() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                output.interrupt(permanent = false); silence()
            }
        }
    }
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                output.interrupt(permanent = true); silence(); releaseFocus()
            }
        }
    }
    init {
        ContextCompat.registerReceiver(this.context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun allowResume() { releaseFocus(); output.allowResume(); retryAt = 0L }

    fun update(direction: PaceDirection?, cadenceSpm: Int?, gain: Float, expiresAt: Long, metronome: Boolean = false) {
        if (closed) return
        mutableError.value = null
        volume = gain.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: .5f
        output.update(SystemClock.elapsedRealtime(), direction, cadenceSpm, expiresAt, metronome)
        if (!output.hasOutput(SystemClock.elapsedRealtime()) || volume == 0f) {
            if ((direction != null || metronome) && volume == 0f) mutableError.value = "박자 음량이 0%입니다. 음량을 올려주세요."
            stop(); return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                if (!output.hasOutput(now)) { stop(); break }
                if (output.isSuspended || audio.mode != AudioManager.MODE_NORMAL) {
                    output.resetPhase()
                    silence()
                    if (!output.transientLoss) releaseFocus()
                    delay(50); continue
                }
                if (now < retryAt) { delay(50); continue }
                if (!focus.acquire()) {
                    mutableError.value = "다른 오디오가 사용 중입니다. 잠시 뒤 다시 들어보세요."
                    retryAt = now + 1000; delay(50); continue
                }
                output.poll(now)?.let { sound ->
                    runCatching { play(sound) }.onSuccess { mutableError.value = null }.onFailure {
                        mutableError.value = "박자음을 재생하지 못했습니다. 다시 시도해주세요."
                        stop()
                    }
                }
                delay(((output.nextAt ?: now + 50) - SystemClock.elapsedRealtime()).coerceIn(1, 50))
            }
        }
    }

    private fun play(sound: BeatSound) {
        val track = tracks.getOrPut(sound) { createAlarmTrack(sound) }
        tracks.values.forEach { it.stop() }
        check(track.reloadStaticData() == AudioTrack.SUCCESS)
        check(track.setVolume(volume) == AudioTrack.SUCCESS)
        track.play()
        mutablePlaying.value = true
    }
    private fun silence() { mutablePlaying.value = false; tracks.values.forEach { runCatching { it.pause() } } }
    private fun releaseFocus() { focus.release() }
    fun stop() {
        output.stop(); job?.cancel(); job = null; silence(); releaseFocus(); retryAt = 0L
        tracks.values.forEach { runCatching { it.release() } }; tracks.clear()
    }
    override fun close() {
        if (closed) return
        closed = true; stop(); scope.cancel(); context.unregisterReceiver(noisy)
        focus.close(); if (ownsFocus) sessionFocus.close()
    }
}
