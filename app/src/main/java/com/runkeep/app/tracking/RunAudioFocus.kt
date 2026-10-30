package com.runkeep.app.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.runkeep.core.*
import java.io.Closeable

internal fun musicFocusGain(policy: MusicFocusPolicy): Int = when (policy) {
    MusicFocusPolicy.DUCK -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    MusicFocusPolicy.PAUSE -> AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
}

private fun AudioFocusChange.platformValue(): Int = when (this) {
    AudioFocusChange.GAIN -> AudioManager.AUDIOFOCUS_GAIN
    AudioFocusChange.LOSS -> AudioManager.AUDIOFOCUS_LOSS
    AudioFocusChange.LOSS_TRANSIENT -> AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    AudioFocusChange.LOSS_TRANSIENT_CAN_DUCK -> AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
}

private class AndroidAudioFocusBackend(context: Context) : AudioFocusBackend {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    override fun playbackAllowed() = audio.mode == AudioManager.MODE_NORMAL
    override fun create(policy: MusicFocusPolicy, onChange: (AudioFocusChange) -> Unit): AudioFocusRequestHandle {
        val request = AudioFocusRequest.Builder(musicFocusGain(policy))
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                val event = when (change) {
                    AudioManager.AUDIOFOCUS_GAIN -> AudioFocusChange.GAIN
                    AudioManager.AUDIOFOCUS_LOSS -> AudioFocusChange.LOSS
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusChange.LOSS_TRANSIENT
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusChange.LOSS_TRANSIENT_CAN_DUCK
                    else -> null
                }
                event?.let(onChange)
            }, handler).build()
        return object : AudioFocusRequestHandle {
            override fun request() = audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            override fun abandon() { audio.abandonAudioFocusRequest(request) }
        }
    }
}

/** 메인 스레드에서 사용한다. 운동의 음성·경보가 한 OS 포커스를 공유한다. */
class RunAudioFocus(context: Context, initialPolicy: MusicFocusPolicy = MusicFocusPolicy.DUCK) : Closeable {
    private val context = context.applicationContext
    private val session = AudioFocusSession(AndroidAudioFocusBackend(context), initialPolicy)
    private var closed = false
    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) session.routeLost()
        }
    }
    init {
        ContextCompat.registerReceiver(this.context, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun setPolicy(policy: MusicFocusPolicy) { session.setPolicy(policy) }
    fun client(onChange: (Int) -> Unit): Client = Client(session.client { onChange(it.platformValue()) })

    inner class Client internal constructor(private val delegate: AudioFocusSession.Client) : Closeable {
        fun acquire() = delegate.acquire()
        fun release() { delegate.release() }
        override fun close() { delegate.close() }
    }

    override fun close() {
        if (closed) return
        closed = true
        session.close()
        context.unregisterReceiver(noisy)
    }
}
