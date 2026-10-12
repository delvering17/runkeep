package com.runkeep.app.tracking

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.runkeep.core.BeatSound
import kotlin.math.PI
import kotlin.math.sin

internal fun createAlarmTrack(sound: BeatSound): AudioTrack {
    val rate = 44_100
    val durationMs = if (sound == BeatSound.CLICK) 20 else 70
    val hz = when (sound) {
        BeatSound.CLICK -> 2000.0
        BeatSound.FAST -> 1200.0
        BeatSound.SLOW -> 600.0
    }
    val sampleCount = rate * durationMs / 1000
    val samples = ShortArray(sampleCount) { i ->
        val envelope = minOf(1.0, i / 200.0, (sampleCount - i) / 200.0)
        (sin(2 * PI * hz * i / rate) * envelope * Short.MAX_VALUE * .6).toInt().toShort()
    }
    val attributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    val track = AudioTrack.Builder().setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
        .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
    try {
        check(track.state != AudioTrack.STATE_UNINITIALIZED) { "경보 출력 초기화 실패" }
        check(track.write(samples, 0, samples.size) == samples.size) { "경보 데이터 쓰기 실패" }
        check(track.state == AudioTrack.STATE_INITIALIZED) { "경보 재생 준비 실패" }
        return track
    } catch (error: Throwable) {
        track.release()
        throw error
    }
}
