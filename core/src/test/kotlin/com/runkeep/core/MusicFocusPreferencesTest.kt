package com.runkeep.core

import kotlin.test.*

class MusicFocusPreferencesTest {
    @Test fun `legacy saved preferences default to duck and preserve existing audio selections`() {
        val saved = codec.decodeFromString<Preferences>("""{"voice":false,"paceAlarm":true,"metronome":true,"voiceVolume":0.7,"paceAlarmVolume":0.4}""").normalized()
        assertEquals(MusicFocusPolicy.DUCK, saved.musicFocusPolicy)
        assertFalse(saved.voice); assertTrue(saved.paceAlarm); assertTrue(saved.metronome)
        assertEquals(.7f, saved.voiceVolume); assertEquals(.4f, saved.paceAlarmVolume)
    }

    @Test fun `both policies persist without changing output switches or own volumes`() {
        for (policy in MusicFocusPolicy.entries) {
            val original = Preferences(musicFocusPolicy = policy, voice = false, paceVoice = false,
                paceAlarm = true, metronome = true, voiceVolume = .3f, paceAlarmVolume = .7f)
            val saved = codec.decodeFromString<Preferences>(codec.encodeToString(Preferences.serializer(), original)).normalized()
            assertEquals(original, saved)
        }
    }
}
