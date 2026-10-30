package com.runkeep.core

import org.junit.jupiter.api.Test
import kotlin.test.*

class DistanceInputTest {
    @Test fun `invalid keyboard edits and pasted text keep the previous draft without coercion`() {
        val current = "21.0975"
        listOf("a", "한글", "5km", "-1", "+1", "1e3", "12a34", "1 2", "1.2.3", "1,2.3", "１２", "NaN", "Infinity")
            .forEach { proposed -> assertEquals(current, acceptDistanceEdit(current, proposed), proposed) }
        assertEquals("", acceptDistanceEdit("", "5km"))
        assertEquals("12", acceptDistanceEdit("12", "123a"))
    }

    @Test fun `empty and intermediate decimal edits can be completed but cannot apply early`() {
        listOf("", ".", ",", "1.", "1,", ".1", "0,1", "21.0975").forEach { proposed ->
            assertEquals(proposed, acceptDistanceEdit("1", proposed), proposed)
        }
        listOf("", ".", ",", "1.", "1,").forEach { assertNull(parseDistance(it, DistanceUnit.KILOMETERS), it) }
        assertEquals(100.0, parseDistance(acceptDistanceEdit("0,", "0,1"), DistanceUnit.KILOMETERS, 100.0))
        assertEquals(100.0, parseDistance(".1", DistanceUnit.KILOMETERS, 100.0))
        assertEquals(100.0, parseDistance(",1", DistanceUnit.KILOMETERS, 100.0))
    }

    @Test fun `unit switches preserve exact decimal distance including outside the valid range`() {
        val cases = listOf("21.0975" to "21097.5", "42.195" to "42195", "200.001" to "200001",
            "12.345678901234567891" to "12345.678901234567891", "0.000000000000000000001" to "0.000000000000000001")
        cases.forEach { (km, meters) ->
            assertEquals(meters, convertDistanceText(km, DistanceUnit.KILOMETERS, DistanceUnit.METERS))
            var restored = meters
            repeat(10) {
                restored = convertDistanceText(restored, DistanceUnit.METERS, DistanceUnit.KILOMETERS)
                assertEquals(km, restored)
                restored = convertDistanceText(restored, DistanceUnit.KILOMETERS, DistanceUnit.METERS)
                assertEquals(meters, restored)
            }
        }
        assertEquals("21097.5", convertDistanceText("21,0975", DistanceUnit.KILOMETERS, DistanceUnit.METERS))
        assertEquals("1000", convertDistanceText("1.", DistanceUnit.KILOMETERS, DistanceUnit.METERS))
        assertEquals("", convertDistanceText("", DistanceUnit.KILOMETERS, DistanceUnit.METERS))
        assertEquals(".", convertDistanceText(".", DistanceUnit.KILOMETERS, DistanceUnit.METERS))
        assertNull(parseDistance("200.001", DistanceUnit.KILOMETERS))
        assertNull(parseDistance("200001", DistanceUnit.METERS))
    }

    @Test fun `presets and application preserve marathon values across both units`() {
        assertEquals(listOf(1000.0, 5000.0, 10000.0, 20000.0, 21097.5, 42195.0), DistancePreset.entries.map { it.meters })
        DistancePreset.entries.forEach { preset ->
            DistanceUnit.entries.forEach { unit ->
                assertEquals(preset.meters, parseDistance(distanceInputText(preset.meters, unit), unit, 100.0))
            }
        }
        assertEquals("21.0975", distanceInputText(DistancePreset.HALF_MARATHON.meters, DistanceUnit.KILOMETERS))
        assertEquals("42195", distanceInputText(DistancePreset.MARATHON.meters, DistanceUnit.METERS))
        val km = "12.345678901234567891"
        val meters = convertDistanceText(km, DistanceUnit.KILOMETERS, DistanceUnit.METERS)
        assertEquals(parseDistance(km, DistanceUnit.KILOMETERS), parseDistance(meters, DistanceUnit.METERS))
        val applied = InputDraft(5000.0, parseDistance("21.0975", DistanceUnit.KILOMETERS))
        assertEquals(5000.0, applied.cancel())
        assertEquals(21097.5, applied.confirm { it != null })
    }
}
