package com.runkeep.app.tracking

import android.content.Context
import android.media.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.runkeep.core.Preferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.Locale

enum class SpeechPlayback { PREPARING, READY, SPEAKING, ERROR, STOPPED, CLOSED }

class SpeechPlayer(context: Context, sharedFocus: RunAudioFocus? = null) : Closeable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val ownsFocus = sharedFocus == null
    private val sessionFocus = sharedFocus ?: RunAudioFocus(context)
    private val focus = sessionFocus.client { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) stop()
    }
    private val mutable = MutableStateFlow("한국어 음성 준비 중")
    val status = mutable.asStateFlow()
    var ready = false; private set
    private var closed = false
    private var tts: TextToSpeech? = null
    private var utterance = 0L
    private var activeUtterance: String? = null
    private var startedCallback: (() -> Unit)? = null
    private var finishedCallback: ((SpeechPlayback) -> Unit)? = null
    private val mutablePlayback = MutableStateFlow(SpeechPlayback.PREPARING)
    val playback = mutablePlayback.asStateFlow()
    val isSpeaking get() = mutablePlayback.value == SpeechPlayback.SPEAKING
    init {
        tts = TextToSpeech(context.applicationContext) { result ->
            handler.post {
                if (closed) return@post
                ready = result == TextToSpeech.SUCCESS && (tts?.setLanguage(Locale.KOREAN) ?: -1) >= 0
                mutablePlayback.value = if (ready) SpeechPlayback.READY else SpeechPlayback.ERROR
                mutable.value = if (ready) "한국어 음성 사용 가능" else "한국어 음성을 설치하거나 TTS 엔진 설정을 확인해주세요"
                tts?.setAudioAttributes(attributes)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { updatePlayback(utteranceId, SpeechPlayback.SPEAKING) }
                    override fun onDone(utteranceId: String?) { updatePlayback(utteranceId, SpeechPlayback.READY) }
                    @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { updatePlayback(utteranceId, SpeechPlayback.ERROR) }
                    override fun onError(utteranceId: String?, errorCode: Int) { updatePlayback(utteranceId, SpeechPlayback.ERROR) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { updatePlayback(utteranceId, SpeechPlayback.STOPPED) }
                })
            }
        }
    }
    // Engine callbacks can arrive on different threads or refer to a replaced utterance.
    private fun updatePlayback(id: String?, state: SpeechPlayback) { handler.post {
        if (closed || id == null || id != activeUtterance) return@post
        mutablePlayback.value = state
        if (state == SpeechPlayback.SPEAKING) { startedCallback?.invoke(); startedCallback = null }
        if (state != SpeechPlayback.SPEAKING) {
            val finished = finishedCallback
            finishedCallback = null
            activeUtterance = null; startedCallback = null
            focus.release()
            if (state == SpeechPlayback.ERROR) mutable.value = "음성 재생에 실패했습니다"
            finished?.invoke(state)
        }
    } }
    fun speak(text: String, preferences: Preferences, onFinished: ((SpeechPlayback) -> Unit)? = null,
              onStarted: (() -> Unit)? = null): Boolean {
        if (text.isBlank() || !ready || closed || isSpeaking || audio.mode != AudioManager.MODE_NORMAL) return false
        if (!focus.acquire()) { mutable.value = "다른 오디오가 사용 중입니다. 잠시 뒤 다시 시도해주세요"; return false }
        tts?.setSpeechRate(preferences.voiceSpeed.coerceIn(.5f, 1.5f))
        tts?.setPitch(preferences.voicePitch.coerceIn(.7f, 1.3f))
        val parameters = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, preferences.voiceVolume.coerceIn(0f, 1f)) }
        startedCallback = onStarted
        finishedCallback = onFinished
        activeUtterance = (++utterance).toString()
        // Mark busy before onStart to protect the gap while the engine prepares audio.
        mutablePlayback.value = SpeechPlayback.SPEAKING
        val result = tts?.speak(text, TextToSpeech.QUEUE_ADD, parameters, activeUtterance)
        if (result != TextToSpeech.SUCCESS) { activeUtterance = null; startedCallback = null; finishedCallback = null; mutablePlayback.value = SpeechPlayback.ERROR; focus.release(); mutable.value = "음성 재생에 실패했습니다"; return false }
        mutable.value = "한국어 음성 사용 가능"
        return true
    }
    fun stop() {
        val finished = finishedCallback
        finishedCallback = null; activeUtterance = null; startedCallback = null
        tts?.stop(); focus.release(); mutablePlayback.value = if (closed) SpeechPlayback.CLOSED else SpeechPlayback.STOPPED
        finished?.invoke(mutablePlayback.value)
    }
    override fun close() { closed = true; stop(); tts?.shutdown(); tts = null; focus.close(); if (ownsFocus) sessionFocus.close() }
}
