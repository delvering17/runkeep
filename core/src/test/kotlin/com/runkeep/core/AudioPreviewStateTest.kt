package com.runkeep.core

import org.junit.jupiter.api.Test
import kotlin.test.*

class AudioPreviewStateTest {
    @Test fun `completion restores button and keeps only user started preview context`() {
        assertFalse(AudioPreviewState().canReplay(null))
        val completed = AudioPreviewState().start(AudioSample.ROUTE).completed()
        assertFalse(completed.active)
        assertTrue(completed.canReplay(null))
        assertEquals(AudioSample.ROUTE, completed.sample)
        assertFalse(completed.stopped().canReplay(null))
        assertNull(completed.stopped().sample)
    }

    @Test fun `stop failure and running transition prevent automatic replay`() {
        val playing = AudioPreviewState().start(AudioSample.PACE)
        assertFalse(playing.canReplay(RunStatus.RUNNING))
        assertFalse(playing.canReplay(RunStatus.AUTO_PAUSED))
        assertFalse(playing.canReplay(RunStatus.PAUSED))
        assertFalse(playing.canReplay(RunStatus.INTERRUPTED))
        assertTrue(playing.canReplay(RunStatus.FINISHED))
        assertFalse(playing.failed().active)
        assertFalse(playing.failed().canReplay(null))
        assertFalse(playing.stopped().canReplay(RunStatus.PAUSED))
    }

    @Test fun `sample switch invalidates old delayed request and uses correct parameters`() {
        val voice = AudioPreviewState().start(AudioSample.PACE)
        val alarm = voice.start(AudioSample.FAST)
        assertTrue(alarm.request > voice.request)
        assertEquals(AudioSample.FAST, alarm.sample)
        val base = Preferences()
        assertTrue(voice.changed(base, base.copy(voiceSpeed = 1.2f)))
        assertFalse(alarm.changed(base, base.copy(voiceSpeed = 1.2f)))
        assertTrue(alarm.changed(base, base.copy(paceAlarmVolume = .8f)))
        assertFalse(voice.changed(base, base.copy(paceAlarmVolume = .8f)))
        assertTrue(alarm.changed(base, base.copy(cadenceTargetSpm = 160)))
        assertFalse(voice.changed(base, base.copy(darkTheme = true)))
    }

    @Test fun `metronome samples use their selected cadence and stop context on failure or active exercise`() {
        val base = Preferences()
        val selected = AudioPreviewState().start(AudioSample.METRONOME)
        assertTrue(selected.changed(base, base.copy(cadenceTargetSpm = 185)))
        assertFalse(selected.changed(base, base.copy(readPaceStatus = false)))
        for (sample in listOf(AudioSample.METRONOME_180, AudioSample.METRONOME_185)) {
            val fixed = AudioPreviewState().start(sample)
            assertFalse(fixed.changed(base, base.copy(cadenceTargetSpm = 185)))
            assertTrue(fixed.changed(base, base.copy(paceAlarmVolume = .7f)))
            assertFalse(fixed.failed().canReplay(null))
            assertFalse(fixed.canReplay(RunStatus.RUNNING))
        }
        assertTrue(AudioPreviewState().start(AudioSample.PACE).changed(base, base.copy(readPaceStatus = false)))
    }
}
