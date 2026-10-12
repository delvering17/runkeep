package com.runkeep.core

enum class AudioSample { ROUTE, PACE, FAST, SLOW, METRONOME, METRONOME_180, METRONOME_185;
    val speech get() = this == ROUTE || this == PACE
}

data class AudioPreviewState(
    val sample: AudioSample? = null,
    val active: Boolean = false,
    val armed: Boolean = false,
    val request: Long = 0
) {
    fun start(sample: AudioSample) = AudioPreviewState(sample, true, true, request + 1)
    fun completed() = copy(active = false)
    fun failed() = copy(active = false, armed = false)
    fun stopped() = AudioPreviewState(request = request + 1)
    fun canReplay(status: RunStatus?) = armed && sample != null && audioPreviewAllowed(status)
    fun changed(before: Preferences, after: Preferences): Boolean = when (sample) {
        AudioSample.ROUTE -> before.voiceVolume != after.voiceVolume ||
            before.voiceSpeed != after.voiceSpeed || before.voicePitch != after.voicePitch
        AudioSample.PACE -> before.voiceVolume != after.voiceVolume || before.voiceSpeed != after.voiceSpeed ||
            before.voicePitch != after.voicePitch || before.readPaceStatus != after.readPaceStatus ||
            before.readCurrentPace != after.readCurrentPace || before.readTargetPace != after.readTargetPace
        AudioSample.FAST, AudioSample.SLOW, AudioSample.METRONOME -> before.paceAlarmVolume != after.paceAlarmVolume ||
            before.cadenceTargetSpm != after.cadenceTargetSpm
        AudioSample.METRONOME_180, AudioSample.METRONOME_185 -> before.paceAlarmVolume != after.paceAlarmVolume
        null -> false
    }
}

fun audioPreviewAllowed(status: RunStatus?) = status == null || status == RunStatus.FINISHED
