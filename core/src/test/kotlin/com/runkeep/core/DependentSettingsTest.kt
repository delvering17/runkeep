package com.runkeep.core

import kotlin.test.*

class DependentSettingsTest {
    private fun stored(p: Preferences) = codec.decodeFromString<Preferences>(codec.encodeToString(Preferences.serializer(), p)).normalized()

    @Test fun `disabled conditional settings retain exact prior values after storage`() {
        val initial = Preferences(startDelaySeconds = 37, cadenceTargetSpm = 193)
        val disabled = stored(initial.copy(startDelaySeconds = 0, startDelayPresetSeconds = initial.startDelaySeconds,
            cadenceTargetSpm = null, cadencePresetSpm = initial.cadenceTargetSpm!!))
        assertEquals(0, disabled.startDelaySeconds); assertNull(disabled.cadenceTargetSpm)
        assertEquals(37, disabled.startDelayPresetSeconds); assertEquals(193, disabled.cadencePresetSpm)
        val enabled = stored(disabled.copy(startDelaySeconds = disabled.startDelayPresetSeconds, cadenceTargetSpm = disabled.cadencePresetSpm))
        assertEquals(37, enabled.startDelaySeconds); assertEquals(193, enabled.cadenceTargetSpm)
    }
    @Test fun `legacy and invalid retained values have independent safe defaults`() {
        val legacy = codec.decodeFromString<Preferences>("""{"startDelaySeconds":13,"cadenceTargetSpm":185}""").normalized()
        assertEquals(13, legacy.startDelaySeconds); assertEquals(185, legacy.cadenceTargetSpm)
        assertEquals(5, legacy.startDelayPresetSeconds); assertEquals(180, legacy.cadencePresetSpm)
        val invalid = Preferences(startDelayPresetSeconds = Int.MAX_VALUE, cadencePresetSpm = -1).normalized()
        assertEquals(5, invalid.startDelayPresetSeconds); assertEquals(180, invalid.cadencePresetSpm)
    }
}
