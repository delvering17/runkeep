package com.runkeep.core

import kotlin.test.*

class AudioFocusSessionTest {
    private class Backend : AudioFocusBackend {
        var allowed = true
        var granted = true
        var requestFailure = false
        var onRequest: ((Handle) -> Unit)? = null
        var onAbandon: ((Handle) -> Unit)? = null
        val handles = mutableListOf<Handle>()
        override fun playbackAllowed() = allowed
        override fun create(policy: MusicFocusPolicy, onChange: (AudioFocusChange) -> Unit) =
            Handle(policy, onChange).also { handles += it }
        inner class Handle(val policy: MusicFocusPolicy, val callback: (AudioFocusChange) -> Unit) : AudioFocusRequestHandle {
            var requests = 0
            var abandons = 0
            override fun request(): Boolean {
                requests++
                onRequest?.invoke(this)
                if (requestFailure) error("platform request failed")
                return granted
            }
            override fun abandon() { abandons++; onAbandon?.invoke(this) }
            fun emit(change: AudioFocusChange) { callback(change) }
        }
    }

    private class BeatClient(session: AudioFocusSession) {
        val output = BeatOutputPolicy()
        val events = mutableListOf<AudioFocusChange>()
        val focus = session.client { change ->
            events += change
            when (change) {
                AudioFocusChange.GAIN -> output.focusGained()
                AudioFocusChange.LOSS -> { output.interrupt(permanent = true); release() }
                AudioFocusChange.LOSS_TRANSIENT, AudioFocusChange.LOSS_TRANSIENT_CAN_DUCK -> output.interrupt(permanent = false)
            }
        }
        fun release() { focus.release() }
        fun start() { output.allowResume(); output.update(0, null, 180, 1000, metronome = true) }
    }

    @Test fun `idle changes store only and initial policy is applied on first acquire`() {
        for (policy in MusicFocusPolicy.entries) {
            val backend = Backend()
            val session = AudioFocusSession(backend, policy)
            val client = session.client {}
            assertTrue(backend.handles.isEmpty())
            session.setPolicy(policy)
            assertTrue(backend.handles.isEmpty())
            assertTrue(client.acquire())
            assertEquals(policy, backend.handles.single().policy)
            repeat(10) { session.setPolicy(policy); assertTrue(client.acquire()) }
            assertEquals(1, backend.handles.single().requests)
            client.release()
            val other = if (policy == MusicFocusPolicy.DUCK) MusicFocusPolicy.PAUSE else MusicFocusPolicy.DUCK
            session.setPolicy(other)
            assertEquals(1, backend.handles.size)
            assertTrue(client.acquire())
            assertEquals(other, backend.handles.last().policy)
            session.close()
            assertTrue(backend.handles.all { it.abandons == 1 })
        }
    }

    @Test fun `two players share one request in both start and finish orders`() {
        for (policy in MusicFocusPolicy.entries) for (reverseStart in listOf(false, true)) for (reverseFinish in listOf(false, true)) {
            val backend = Backend()
            val session = AudioFocusSession(backend, policy)
            val voice = session.client { fail("own lifecycle cannot generate OS loss") }
            val beat = session.client { fail("own lifecycle cannot generate OS loss") }
            val start = if (reverseStart) listOf(beat, voice) else listOf(voice, beat)
            val finish = if (reverseFinish) listOf(beat, voice) else listOf(voice, beat)
            start.forEach { assertTrue(it.acquire()) }
            assertEquals(1, backend.handles.size)
            finish.first().release()
            assertEquals(0, backend.handles.single().abandons)
            assertTrue(finish.last().acquire())
            finish.last().release()
            assertEquals(1, backend.handles.single().abandons)
            session.close()
            assertEquals(1, backend.handles.single().abandons)
        }
    }

    @Test fun `frequent speech and beats do not churn focus while metronome owns it`() {
        val backend = Backend()
        val session = AudioFocusSession(backend)
        val voice = session.client {}
        val beat = session.client {}
        assertTrue(beat.acquire())
        repeat(100) { assertTrue(voice.acquire()); assertTrue(beat.acquire()); voice.release() }
        assertEquals(1, backend.handles.size)
        assertEquals(0, backend.handles.single().abandons)
        beat.release()
        assertEquals(1, backend.handles.single().abandons)
    }

    @Test fun `active policy swaps once and ignores old events including reentrant abandon callback`() {
        for (from in MusicFocusPolicy.entries) {
            val to = if (from == MusicFocusPolicy.DUCK) MusicFocusPolicy.PAUSE else MusicFocusPolicy.DUCK
            val backend = Backend()
            val session = AudioFocusSession(backend, from)
            val events = mutableListOf<AudioFocusChange>()
            val voice = session.client { events += it }
            val beat = session.client { events += it }
            assertTrue(voice.acquire()); assertTrue(beat.acquire())
            val old = backend.handles.single()
            backend.onAbandon = { it.emit(AudioFocusChange.LOSS); it.emit(AudioFocusChange.GAIN) }
            session.setPolicy(to)
            repeat(10) { session.setPolicy(to) }
            assertEquals(listOf(from, to), backend.handles.map { it.policy })
            assertEquals(1, old.abandons)
            old.emit(AudioFocusChange.LOSS_TRANSIENT)
            assertTrue(events.isEmpty())
            assertTrue(voice.acquire()); assertTrue(beat.acquire())
            assertEquals(2, backend.handles.size)
            voice.release(); beat.release()
            assertEquals(1, backend.handles.last().abandons)
        }
    }

    @Test fun `transient loss retains beat owner and pending policy waits for actual gain`() {
        for (loss in listOf(AudioFocusChange.LOSS_TRANSIENT, AudioFocusChange.LOSS_TRANSIENT_CAN_DUCK)) {
            val backend = Backend()
            val session = AudioFocusSession(backend)
            val voiceEvents = mutableListOf<AudioFocusChange>()
            lateinit var voice: AudioFocusSession.Client
            voice = session.client { voiceEvents += it; if (it != AudioFocusChange.GAIN) voice.release() }
            val beat = BeatClient(session).also { it.start() }
            assertTrue(voice.acquire()); assertTrue(beat.focus.acquire())
            val old = backend.handles.single()
            old.emit(loss)
            assertTrue(beat.output.transientLoss)
            assertFalse(voice.acquire()); assertFalse(beat.focus.acquire())
            session.setPolicy(MusicFocusPolicy.PAUSE)
            assertEquals(1, backend.handles.size)
            assertEquals(0, old.abandons)
            old.emit(AudioFocusChange.GAIN)
            assertEquals(2, backend.handles.size)
            assertEquals(MusicFocusPolicy.PAUSE, backend.handles.last().policy)
            assertEquals(1, old.abandons)
            assertFalse(beat.output.isSuspended)
            assertEquals(listOf(loss), voiceEvents)
            assertTrue(voice.acquire()); assertTrue(beat.focus.acquire())
            assertEquals(2, backend.handles.size)
            session.close()
        }
    }

    @Test fun `pending policy failure on gain stops owners without resuming beats`() {
        val backend = Backend()
        val session = AudioFocusSession(backend)
        val beat = BeatClient(session).also { it.start() }
        assertTrue(beat.focus.acquire())
        val old = backend.handles.single()
        old.emit(AudioFocusChange.LOSS_TRANSIENT)
        session.setPolicy(MusicFocusPolicy.PAUSE)
        backend.granted = false
        old.emit(AudioFocusChange.GAIN)
        assertEquals(listOf(AudioFocusChange.LOSS_TRANSIENT, AudioFocusChange.LOSS), beat.events)
        assertTrue(beat.output.requiresResume)
        assertNull(beat.output.poll(1000))
        assertTrue(backend.handles.all { it.abandons == 1 })
        backend.handles.forEach { it.emit(AudioFocusChange.GAIN) }
        assertTrue(beat.output.requiresResume)
    }

    @Test fun `failed active replacement stops both players and does not release a later request`() {
        val backend = Backend()
        val session = AudioFocusSession(backend)
        lateinit var voice: AudioFocusSession.Client
        var voiceStopped = false
        voice = session.client { if (it == AudioFocusChange.LOSS) { voiceStopped = true; voice.release() } }
        val beat = BeatClient(session).also { it.start() }
        assertTrue(voice.acquire()); assertTrue(beat.focus.acquire())
        backend.granted = false
        session.setPolicy(MusicFocusPolicy.PAUSE)
        assertTrue(voiceStopped)
        assertTrue(beat.output.requiresResume)
        assertTrue(backend.handles.all { it.abandons == 1 })
        val expired = backend.handles.toList()
        backend.granted = true
        assertTrue(voice.acquire())
        expired.forEach { it.emit(AudioFocusChange.LOSS); it.emit(AudioFocusChange.GAIN) }
        assertTrue(voice.acquire())
        assertEquals(0, backend.handles.last().abandons)
        assertEquals(3, backend.handles.size)
        voice.release()
    }

    @Test fun `failed first request can retry and delayed grant from failed handle is ignored`() {
        for (throws in listOf(false, true)) {
            val backend = Backend().apply { granted = false; requestFailure = throws }
            val session = AudioFocusSession(backend)
            val events = mutableListOf<AudioFocusChange>()
            val client = session.client { events += it }
            assertFalse(client.acquire())
            val failed = backend.handles.single()
            backend.granted = true; backend.requestFailure = false
            assertTrue(client.acquire())
            failed.emit(AudioFocusChange.GAIN); failed.emit(AudioFocusChange.LOSS)
            assertTrue(events.isEmpty())
            assertEquals(1, failed.abandons)
            assertEquals(0, backend.handles.last().abandons)
            session.close()
        }
    }

    @Test fun `permanent loss stops both through reentrant release and changing policy preserves beat latch`() {
        val backend = Backend()
        val session = AudioFocusSession(backend)
        lateinit var voice: AudioFocusSession.Client
        var voiceLosses = 0
        voice = session.client { if (it == AudioFocusChange.LOSS) { voiceLosses++; voice.release() } }
        val beat = BeatClient(session).also { it.start() }
        assertTrue(voice.acquire()); assertTrue(beat.focus.acquire())
        val old = backend.handles.single()
        old.emit(AudioFocusChange.LOSS)
        assertEquals(1, voiceLosses)
        assertTrue(beat.output.requiresResume)
        session.setPolicy(MusicFocusPolicy.PAUSE)
        old.emit(AudioFocusChange.GAIN)
        beat.output.update(2000, null, 180, 3000, metronome = true)
        assertNull(beat.output.poll(2000))
        assertEquals(1, backend.handles.size)
        beat.start()
        assertTrue(beat.focus.acquire())
        assertEquals(MusicFocusPolicy.PAUSE, backend.handles.last().policy)
    }

    @Test fun `unreleased permanent owner cannot reacquire or reset by policy toggles or old gain`() {
        val backend = Backend()
        val session = AudioFocusSession(backend)
        val events = mutableListOf<AudioFocusChange>()
        val client = session.client { events += it }
        assertTrue(client.acquire())
        val old = backend.handles.single()
        old.emit(AudioFocusChange.LOSS)
        session.setPolicy(MusicFocusPolicy.PAUSE)
        old.emit(AudioFocusChange.GAIN)
        assertFalse(client.acquire())
        assertEquals(listOf(AudioFocusChange.LOSS), events)
        assertEquals(1, backend.handles.size)
        client.release()
        assertTrue(client.acquire())
        assertEquals(MusicFocusPolicy.PAUSE, backend.handles.last().policy)
    }

    @Test fun `route loss reaches active voice and waiting beat without clearing resume latch`() {
        val backend = Backend()
        val session = AudioFocusSession(backend)
        lateinit var voice: AudioFocusSession.Client
        var stopped = false
        voice = session.client { if (it == AudioFocusChange.LOSS) { stopped = true; voice.release() } }
        val beat = BeatClient(session).also { it.start() }
        assertTrue(voice.acquire())
        val old = backend.handles.single()
        session.routeLost()
        assertTrue(stopped)
        assertTrue(beat.output.requiresResume)
        session.setPolicy(MusicFocusPolicy.PAUSE)
        old.emit(AudioFocusChange.GAIN)
        assertNull(beat.output.poll(500))
        assertEquals(1, backend.handles.size)
        beat.start()
        assertTrue(beat.focus.acquire())
        assertEquals(MusicFocusPolicy.PAUSE, backend.handles.last().policy)
    }

    @Test fun `phone blocks acquisition policy swapping and transient gain until normal mode`() {
        val backend = Backend().apply { allowed = false }
        val session = AudioFocusSession(backend)
        val beat = BeatClient(session).also { it.start() }
        assertFalse(beat.focus.acquire())
        assertTrue(backend.handles.isEmpty())
        backend.allowed = true
        assertTrue(beat.focus.acquire())
        val old = backend.handles.single()
        old.emit(AudioFocusChange.LOSS_TRANSIENT)
        backend.allowed = false
        session.setPolicy(MusicFocusPolicy.PAUSE)
        old.emit(AudioFocusChange.GAIN)
        assertTrue(beat.output.transientLoss)
        assertFalse(beat.focus.acquire())
        assertEquals(1, backend.handles.size)
        backend.allowed = true
        old.emit(AudioFocusChange.GAIN)
        assertFalse(beat.output.isSuspended)
        assertEquals(2, backend.handles.size)
        assertEquals(MusicFocusPolicy.PAUSE, backend.handles.last().policy)
    }

    @Test fun `phone deferred policy also applies on next acquire without an OS callback`() {
        val backend = Backend()
        val session = AudioFocusSession(backend)
        val client = session.client {}
        assertTrue(client.acquire())
        backend.allowed = false
        session.setPolicy(MusicFocusPolicy.PAUSE)
        assertFalse(client.acquire())
        assertEquals(1, backend.handles.size)
        backend.allowed = true
        assertTrue(client.acquire())
        assertEquals(listOf(MusicFocusPolicy.DUCK, MusicFocusPolicy.PAUSE), backend.handles.map { it.policy })
    }

    @Test fun `close and disposed clients ignore late callbacks and abandon exact request once`() {
        val backend = Backend()
        val session = AudioFocusSession(backend)
        val events = mutableListOf<AudioFocusChange>()
        val voice = session.client { events += it }
        val beat = session.client { events += it }
        assertTrue(voice.acquire()); assertTrue(beat.acquire())
        voice.close()
        assertEquals(0, backend.handles.single().abandons)
        assertFalse(voice.acquire())
        session.close(); session.close(); beat.close()
        backend.handles.single().emit(AudioFocusChange.GAIN)
        backend.handles.single().emit(AudioFocusChange.LOSS)
        assertTrue(events.isEmpty())
        assertFalse(beat.acquire())
        assertEquals(1, backend.handles.single().abandons)
        assertFailsWith<IllegalStateException> { session.client {} }
        session.setPolicy(MusicFocusPolicy.PAUSE)
        assertEquals(1, backend.handles.size)
    }

    @Test fun `closing a client inside loss callback preserves the other owner and later callbacks`() {
        val backend = Backend()
        val session = AudioFocusSession(backend)
        lateinit var voice: AudioFocusSession.Client
        val voiceEvents = mutableListOf<AudioFocusChange>()
        voice = session.client { voiceEvents += it; voice.close() }
        val beatEvents = mutableListOf<AudioFocusChange>()
        val beat = session.client { beatEvents += it }
        assertTrue(voice.acquire()); assertTrue(beat.acquire())
        val handle = backend.handles.single()
        handle.emit(AudioFocusChange.LOSS_TRANSIENT)
        assertEquals(0, handle.abandons)
        handle.emit(AudioFocusChange.GAIN)
        assertEquals(listOf(AudioFocusChange.LOSS_TRANSIENT), voiceEvents)
        assertEquals(listOf(AudioFocusChange.LOSS_TRANSIENT, AudioFocusChange.GAIN), beatEvents)
        assertFalse(voice.acquire()); assertTrue(beat.acquire())
        beat.close()
        assertEquals(1, handle.abandons)
    }

    @Test fun `synchronous loss during request is never treated as granted playback`() {
        val backend = Backend().apply { onRequest = { it.emit(AudioFocusChange.LOSS) } }
        val session = AudioFocusSession(backend)
        val client = session.client {}
        assertFalse(client.acquire())
        assertEquals(1, backend.handles.single().abandons)
        backend.onRequest = null
        assertTrue(client.acquire())
        assertEquals(2, backend.handles.size)
    }

    @Test fun `focus interruption disarms preview and invalidates delayed sample completion`() {
        for (loss in listOf(AudioFocusChange.LOSS, AudioFocusChange.LOSS_TRANSIENT, AudioFocusChange.LOSS_TRANSIENT_CAN_DUCK)) {
            val backend = Backend()
            val session = AudioFocusSession(backend)
            var preview = AudioPreviewState().start(AudioSample.FAST)
            val originalRequest = preview.request
            lateinit var client: AudioFocusSession.Client
            client = session.client { if (it != AudioFocusChange.GAIN) { preview = preview.stopped(); client.release() } }
            assertTrue(client.acquire())
            backend.handles.single().emit(loss)
            assertFalse(preview.active); assertFalse(preview.armed)
            assertTrue(preview.request > originalRequest)
            assertFalse(preview.canReplay(null))
            // The old 1.1s completion task is no longer the current sample request.
            if (preview.request == originalRequest) preview = preview.completed()
            session.setPolicy(MusicFocusPolicy.PAUSE)
            assertFalse(preview.canReplay(null))
            assertEquals(1, backend.handles.size)
        }
    }

    @Test fun `active policy replacement failure disarms preview before subsequent settings changes`() {
        val backend = Backend()
        val session = AudioFocusSession(backend)
        var preview = AudioPreviewState().start(AudioSample.METRONOME)
        lateinit var client: AudioFocusSession.Client
        client = session.client { if (it != AudioFocusChange.GAIN) { preview = preview.stopped(); client.release() } }
        assertTrue(client.acquire())
        backend.granted = false
        session.setPolicy(MusicFocusPolicy.PAUSE)
        val base = Preferences()
        assertFalse(preview.canReplay(null))
        assertFalse(preview.changed(base, base.copy(paceAlarmVolume = .8f)))
        assertFalse(preview.changed(base, base.copy(cadenceTargetSpm = 185)))
        assertEquals(2, backend.handles.size)
    }

    @Test fun `policy selection alone never triggers any completed preview to replay`() {
        val base = Preferences()
        for (sample in AudioSample.entries) {
            val preview = AudioPreviewState().start(sample).completed()
            assertTrue(preview.canReplay(null))
            assertFalse(preview.changed(base, base.copy(musicFocusPolicy = MusicFocusPolicy.PAUSE)))
        }
    }
}
