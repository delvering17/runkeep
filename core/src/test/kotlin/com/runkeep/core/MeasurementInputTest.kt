package com.runkeep.core

import org.junit.jupiter.api.Test
import kotlin.test.*

class MeasurementInputTest {
    @Test fun `pace parts reject rollover overflow and values beyond coaching limits`() {
        assertEquals(120, parsePaceParts("2", "00"))
        assertEquals(390, parsePaceParts("6", "30"))
        assertEquals(1800, parsePace("30:00"))
        listOf("1:59", "30:01", "6:60", "-1:00", "71582794:00", "6:3:0", "6:").forEach { assertNull(parsePace(it), it) }
    }
    @Test fun `distance units and decimal keyboard locales share boundary validation`() {
        assertEquals(100.0, parseDistance("0,1", DistanceUnit.KILOMETERS, 100.0))
        assertEquals(100.0, parseDistance("100", DistanceUnit.METERS, 100.0))
        assertEquals(200_000.0, parseDistance("200", DistanceUnit.KILOMETERS))
        listOf("0", "-1", "NaN", "Infinity", "1e3", "1.2.3", "").forEach { assertNull(parseDistance(it, DistanceUnit.METERS), it) }
        assertNull(parseDistance("99", DistanceUnit.METERS, 100.0))
        assertNull(parseDistance("200001", DistanceUnit.METERS))
    }
    @Test fun `elapsed input is duration rather than clock time`() {
        assertEquals(1, parseElapsed("0", "0", "1"))
        assertEquals(12_000, parseElapsed("3", "20", "0"))
        assertEquals(90_000, parseElapsed("25", "0", "0", 100_000))
        assertNull(parseElapsed("3", "20", "1"))
        assertNull(parseElapsed("0", "0", "0"))
        assertNull(parseElapsed("0", "60", "0"))
        assertNull(parseElapsed("0", "1", "60"))
        assertNull(parseElapsed("9223372036854775807", "0", "0"))
    }
    @Test fun `draft cancellation preserves applied value and confirmation rejects invalid input`() {
        val original = InputDraft(390)
        val changed = original.edit(400)
        assertEquals(390, changed.cancel())
        assertEquals(400, changed.confirm(MeasurementLimits::pace))
        assertNull(changed.edit(1801).confirm(MeasurementLimits::pace))
        assertEquals(390, original.candidate)
    }
    @Test fun `save boundary rechecks imported or restored steps`() {
        assertTrue(MeasurementLimits.step(PlanStep(kind = StepKind.DISTANCE, amount = 200_000.0, paceSeconds = 120)))
        assertTrue(MeasurementLimits.step(PlanStep(kind = StepKind.TIME, amount = 12_000.0, paceSeconds = 1800)))
        assertFalse(MeasurementLimits.step(PlanStep(kind = StepKind.DISTANCE, amount = 200_001.0, paceSeconds = 390)))
        assertFalse(MeasurementLimits.step(PlanStep(kind = StepKind.TIME, amount = 12_001.0, paceSeconds = 390)))
    }
}
