package com.runkeep.core

data class WorkoutSpeechRequest(val session: Long, val sequence: Long)

class WorkoutAudioSession {
    var cue: String? = null; private set
    private var session = 0L
    private var sequence = 0L
    private var active: WorkoutSpeechRequest? = null

    fun attach(runId: String, previous: LiveRun, newWorkout: Boolean) {
        session++
        active = null
        cue = previous.cue.takeIf { !newWorkout && previous.summary?.id == runId }
    }

    fun show(text: String) { cue = text }
    fun requested(): WorkoutSpeechRequest = WorkoutSpeechRequest(session, ++sequence).also { active = it }
    fun accepts(request: WorkoutSpeechRequest): Boolean = active == request
    fun completed() { active = null }
}
