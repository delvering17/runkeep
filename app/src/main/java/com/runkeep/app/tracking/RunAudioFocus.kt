package com.runkeep.app.tracking

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import java.io.Closeable

/** 메인 스레드에서 사용한다. 운동의 음성·경보가 한 OS 포커스를 공유한다. */
class RunAudioFocus(context: Context) : Closeable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val clients = mutableSetOf<Client>()
    private val owners = mutableSetOf<Client>()
    private var focused = false
    private var registered = false
    private var suspended = false
    private var closed = false
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener({ change ->
            if (owners.isNotEmpty()) {
                focused = change == AudioManager.AUDIOFOCUS_GAIN
                suspended = change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ||
                    change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                // 콜백에서 반환/해제할 수 있으므로 스냅샷을 순회한다.
                owners.toList().forEach { it.onChange(change) }
            }
        }, Handler(Looper.getMainLooper())).build()

    fun client(onChange: (Int) -> Unit): Client {
        check(!closed)
        return Client(onChange).also { clients += it }
    }

    inner class Client internal constructor(internal val onChange: (Int) -> Unit) : Closeable {
        private var disposed = false

        fun acquire(): Boolean {
            if (closed || disposed || suspended || audio.mode != AudioManager.MODE_NORMAL) return false
            if (!focused) {
                if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
                focused = true; registered = true
            }
            owners += this
            return true
        }

        fun release() {
            owners -= this
            if (owners.isEmpty()) {
                if (registered) audio.abandonAudioFocusRequest(request)
                focused = false; suspended = false; registered = false
            }
        }

        override fun close() {
            if (disposed) return
            disposed = true; release(); clients -= this
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        clients.toList().forEach { it.close() }
    }
}
