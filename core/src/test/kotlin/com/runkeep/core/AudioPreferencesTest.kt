package com.runkeep.core

import kotlin.test.*

class AudioPreferencesTest {
    @Test fun `legacy preferences retain voice selections and new outputs remain off`() {
        val old = codec.decodeFromString<Preferences>("""{"voice":false,"paceVoice":false,"readCurrentPace":true,"readTargetPace":true,"kilometerVoice":false,"cadenceTargetSpm":185}""").normalized()
        assertTrue(old.readPaceStatus)
        assertFalse(old.voice); assertFalse(old.paceVoice); assertFalse(old.kilometerVoice)
        assertTrue(old.readCurrentPace); assertTrue(old.readTargetPace)
        assertFalse(old.metronome); assertFalse(old.distancePaceVoice)
        assertEquals(100, old.distancePaceMeters); assertEquals(185, old.cadenceTargetSpm)
    }

    @Test fun `eight selections persist with whole switch off without altering other outputs`() {
        for (status in listOf(false, true)) for (current in listOf(false, true)) for (target in listOf(false, true)) {
            val original = Preferences(paceVoice = false, readPaceStatus = status, readCurrentPace = current,
                readTargetPace = target, paceAlarm = true, metronome = true, distancePaceVoice = true,
                distancePaceMeters = 20, cadenceTargetSpm = 185)
            val saved = codec.decodeFromString<Preferences>(codec.encodeToString(Preferences.serializer(), original)).normalized()
            assertEquals(original, saved)
            val enabled = saved.copy(paceVoice = true)
            assertEquals(status, enabled.readPaceStatus); assertEquals(current, enabled.readCurrentPace)
            assertEquals(target, enabled.readTargetPace)
            assertTrue(enabled.paceAlarm); assertTrue(enabled.metronome); assertTrue(enabled.distancePaceVoice)
        }
    }

    @Test fun `distance storage and presets use the same limits as boundary policy`() {
        for (meters in listOf(10, 20, 100, 200, 1000, 10_000))
            assertEquals(meters, Preferences(distancePaceMeters = meters).normalized().distancePaceMeters)
        for (meters in listOf(Int.MIN_VALUE, -1, 0, 9, 10_001, Int.MAX_VALUE))
            assertEquals(100, Preferences(distancePaceMeters = meters).normalized().distancePaceMeters)
    }
}
