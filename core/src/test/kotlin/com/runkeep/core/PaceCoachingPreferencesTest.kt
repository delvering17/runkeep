package com.runkeep.core

import kotlin.test.*

class PaceCoachingPreferencesTest {
    @Test fun `legacy settings preserve instant coaching and disable independent time voice`() {
        val value = codec.decodeFromString<Preferences>("""{"paceTolerance":5,"readCurrentPace":true,"distancePaceVoice":true}""").normalized()
        assertEquals(PaceCoachingMode.IMMEDIATE, value.paceCoachingMode)
        assertFalse(value.periodicPaceVoice)
        assertEquals(30, value.periodicPaceSeconds)
        assertTrue(value.readCurrentPace); assertTrue(value.distancePaceVoice)
    }

    @Test fun `time voice roundtrips independently and retains interval while disabled`() {
        for (seconds in listOf(10, 30, 60, 120, 300)) {
            val original = Preferences(voice = false, paceVoice = false, periodicPaceVoice = true,
                periodicPaceSeconds = seconds, paceCoachingMode = PaceCoachingMode.STABLE)
            val saved = codec.decodeFromString<Preferences>(codec.encodeToString(Preferences.serializer(), original)).normalized()
            assertEquals(original, saved)
            assertEquals(seconds, saved.copy(periodicPaceVoice = false).normalized().periodicPaceSeconds)
        }
        for (seconds in listOf(Int.MIN_VALUE, -1, 0, 9, 301, Int.MAX_VALUE))
            assertEquals(30, Preferences(periodicPaceSeconds = seconds).normalized().periodicPaceSeconds)
    }
}
