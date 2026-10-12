package com.runkeep.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioTrack
import android.media.AudioAttributes
import android.media.AudioFormat
import android.os.Bundle
import android.os.SystemClock
import com.runkeep.app.tracking.PaceAlarmPlayer
import com.runkeep.app.tracking.RunAudioFocus
import com.runkeep.app.tracking.createAlarmTrack
import com.runkeep.core.BeatSound
import com.runkeep.core.PaceDirection
import kotlin.math.PI
import kotlin.math.sin

/** Android 네이티브 오디오 상태를 검증한다. core의 JUnit 5 구성은 유지한다. */
class AudioRegressionInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }

    override fun onStart() {
        val results = Bundle()
        var activity: Activity? = null
        var resultCode = Activity.RESULT_OK
        try {
            activity = startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            waitForIdleSync()
            for (sound in listOf(BeatSound.FAST, BeatSound.SLOW)) verifyStaticPlayback(sound)
            verifyPreviewSwitch()
            for (sound in listOf(BeatSound.FAST, BeatSound.SLOW)) verifyConcurrentPlayback(sound)
            verifyExternalFocusLoss()
            results.putString("stream", "PASS: FAST/SLOW static playback and replay; preview switching; FAST/SLOW concurrent speech-content PCM playback and independent focus release; shared external focus loss/recovery (6 checks)\n")
        } catch (error: Throwable) {
            results.putString("stream", "FAIL: ${error.javaClass.simpleName}: ${error.message}\n")
            resultCode = Activity.RESULT_CANCELED
        } finally {
            activity?.let { runOnMainSync { it.finish() } }
        }
        finish(resultCode, results)
    }

    private fun verifyStaticPlayback(sound: BeatSound) {
        val track = createAlarmTrack(sound)
        val audio = targetContext.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(track.audioAttributes).build()
        try {
            check(track.state == AudioTrack.STATE_INITIALIZED)
            check(audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            repeat(2) {
                track.stop()
                check(track.reloadStaticData() == AudioTrack.SUCCESS)
                check(track.setVolume(.5f) == AudioTrack.SUCCESS)
                track.play()
                val deadline = SystemClock.elapsedRealtime() + 2000
                while (track.playbackHeadPosition < 3087 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
                check(track.playbackHeadPosition >= 3087) { "$sound did not consume the 70ms PCM buffer" }
            }
        } finally { track.release(); audio.abandonAudioFocusRequest(focus) }
    }

    private fun verifyPreviewSwitch() {
        lateinit var shared: RunAudioFocus
        lateinit var voice: RunAudioFocus.Client
        lateinit var player: PaceAlarmPlayer
        var losses = 0
        runOnMainSync {
            shared = RunAudioFocus(targetContext)
            voice = shared.client { if (it != AudioManager.AUDIOFOCUS_GAIN) losses++ }
            check(voice.acquire())
            player = PaceAlarmPlayer(targetContext, shared)
        }
        try {
            for (direction in listOf(PaceDirection.FAST, PaceDirection.SLOW)) {
                runOnMainSync {
                    player.allowResume()
                    player.update(direction, null, .5f, SystemClock.elapsedRealtime() + 1000)
                }
                SystemClock.sleep(450)
                runOnMainSync {
                    check(player.error.value == null) { player.error.value ?: "preview failure" }
                    check(losses == 0) { "Preview interrupted the active voice focus" }
                }
            }
        } finally { runOnMainSync { player.close(); voice.close(); shared.close() } }
    }

    /** OS 믹싱/포커스 검사. 실제 한국어 TTS 청취를 대신하지 않는다. */
    private fun verifyConcurrentPlayback(sound: BeatSound) {
        val rate = 44_100
        val samples = ShortArray(rate * 2) { (sin(2 * PI * 220 * it / rate) * 2000).toInt().toShort() }
        val speech = AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setTransferMode(AudioTrack.MODE_STATIC).setBufferSizeInBytes(samples.size * 2).build()
        val alarm = createAlarmTrack(sound)
        lateinit var shared: RunAudioFocus
        lateinit var voiceFocus: RunAudioFocus.Client
        lateinit var alarmFocus: RunAudioFocus.Client
        var losses = 0
        try {
            check(speech.write(samples, 0, samples.size) == samples.size)
            runOnMainSync {
                shared = RunAudioFocus(targetContext)
                voiceFocus = shared.client { if (it != AudioManager.AUDIOFOCUS_GAIN) losses++ }
                alarmFocus = shared.client { if (it != AudioManager.AUDIOFOCUS_GAIN) losses++ }
            }
            // 경보→음성, 음성→경보 시작 순서와 각각 먼저 반환하는 경우를 검증한다.
            repeat(2) { order ->
                runOnMainSync {
                    check((if (order == 0) alarmFocus else voiceFocus).acquire())
                    check((if (order == 0) voiceFocus else alarmFocus).acquire())
                }
                speech.stop(); check(speech.reloadStaticData() == AudioTrack.SUCCESS); speech.play()
                val deadline = SystemClock.elapsedRealtime() + 2000
                while (speech.playbackHeadPosition == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
                check(speech.playbackHeadPosition > 0)
                alarm.stop(); check(alarm.reloadStaticData() == AudioTrack.SUCCESS)
                val before = speech.playbackHeadPosition
                alarm.play()
                while (alarm.playbackHeadPosition < 3087 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(10)
                check(alarm.playbackHeadPosition >= 3087)
                check(speech.playbackHeadPosition > before) { "Speech PCM stopped while alarm played" }
                runOnMainSync {
                    (if (order == 0) alarmFocus else voiceFocus).release()
                    check((if (order == 0) voiceFocus else alarmFocus).acquire())
                    check(losses == 0) { "One player's lifecycle interrupted the other" }
                    voiceFocus.release(); alarmFocus.release()
                }
            }
        } finally {
            speech.release(); alarm.release()
            runOnMainSync { shared.close() }
        }
    }

    private fun verifyExternalFocusLoss() {
        val audio = targetContext.getSystemService(AudioManager::class.java)
        val external = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { }.build()
        lateinit var shared: RunAudioFocus
        lateinit var voice: RunAudioFocus.Client
        lateinit var alarm: RunAudioFocus.Client
        var voiceLoss = false
        var alarmLoss = false
        var restored = false
        runOnMainSync {
            shared = RunAudioFocus(targetContext)
            voice = shared.client { if (it != AudioManager.AUDIOFOCUS_GAIN) { voiceLoss = true; voice.release() } }
            alarm = shared.client { if (it == AudioManager.AUDIOFOCUS_GAIN) restored = true else alarmLoss = true }
            check(voice.acquire()); check(alarm.acquire())
        }
        try {
            check(audio.requestAudioFocus(external) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            SystemClock.sleep(200)
            runOnMainSync {
                check(voiceLoss && alarmLoss) { "External loss did not reach both players" }
                check(!alarm.acquire()) { "Shared focus was reacquired during transient loss" }
            }
            audio.abandonAudioFocusRequest(external)
            SystemClock.sleep(200)
            runOnMainSync { check(restored); check(alarm.acquire()); check(voice.acquire()) }
        } finally { audio.abandonAudioFocusRequest(external); runOnMainSync { shared.close() } }
    }
}
