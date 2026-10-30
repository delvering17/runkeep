package com.runkeep.core

import java.math.BigDecimal
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Test
import kotlin.test.*

class PaceCalculatorTest {
    private fun calculate(mode: PaceCalculationMode, distance: Double? = null, time: Double? = null, pace: Double? = null) =
        PaceCalculator.calculate(mode, distance, time, pace)
    private val distanceStep = PlanStep("거리", StepKind.DISTANCE, 1000.0, 390)
    private val timeStep = PlanStep("시간", StepKind.TIME, 600.0, 420)
    private fun plan() = PlanEditorDraft(WorkoutPlan(id = "saved", name = "훈련", steps = listOf(distanceStep, timeStep, distanceStep)))

    @Test fun `ten kilometers in one hour and five kilometers in thirty minutes twenty five seconds`() {
        assertEquals(360.0, calculate(PaceCalculationMode.PACE, 10000.0, 3600.0)!!.paceSeconds)
        assertEquals(365.0, calculate(PaceCalculationMode.PACE, 5000.0, 1825.0)!!.paceSeconds)
    }

    @Test fun `three directions share meters elapsed seconds and seconds per kilometer`() {
        val expected = PaceCalculation(5000.0, 1825.0, 365.0)
        assertEquals(expected, calculate(PaceCalculationMode.PACE, 5000.0, 1825.0))
        assertEquals(expected, calculate(PaceCalculationMode.DURATION, 5000.0, pace = 365.0))
        assertEquals(expected, calculate(PaceCalculationMode.DISTANCE, time = 1825.0, pace = 365.0))
    }

    @Test fun `decimal marathon distance keeps quantity precision across directions`() {
        val result = calculate(PaceCalculationMode.DURATION, 42195.0, pace = 360.0)!!
        assertEquals(15190.2, result.seconds)
        assertEquals(42195.0, calculate(PaceCalculationMode.DISTANCE, time = result.seconds, pace = 360.0)!!.meters)
        assertEquals(360.0, calculate(PaceCalculationMode.PACE, result.meters, result.seconds)!!.paceSeconds)
        assertEquals(21097.5, parseDistance("21.0975", DistanceUnit.KILOMETERS))
    }

    @Test fun `missing zero negative nan infinity inputs are rejected in all consumed positions`() {
        listOf(null, 0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY).forEach { invalid ->
            assertNull(calculate(PaceCalculationMode.PACE, invalid, 3600.0))
            assertNull(calculate(PaceCalculationMode.PACE, 10000.0, invalid))
            assertNull(calculate(PaceCalculationMode.DURATION, invalid, pace = 360.0))
            assertNull(calculate(PaceCalculationMode.DURATION, 10000.0, pace = invalid))
            assertNull(calculate(PaceCalculationMode.DISTANCE, time = invalid, pace = 360.0))
            assertNull(calculate(PaceCalculationMode.DISTANCE, time = 3600.0, pace = invalid))
        }
    }

    @Test fun `unused third input does not alter a two input calculation`() {
        assertEquals(360.0, calculate(PaceCalculationMode.PACE, 10000.0, 3600.0, Double.NaN)!!.paceSeconds)
        assertEquals(3600.0, calculate(PaceCalculationMode.DURATION, 10000.0, Double.NaN, 360.0)!!.seconds)
        assertEquals(10000.0, calculate(PaceCalculationMode.DISTANCE, Double.NaN, 3600.0, 360.0)!!.meters)
    }

    @Test fun `decimal math avoids intermediate overflow but rejects unrepresentable output and underflow`() {
        assertEquals(1000.0, calculate(PaceCalculationMode.PACE, Double.MAX_VALUE, Double.MAX_VALUE)!!.paceSeconds)
        assertEquals(Double.MAX_VALUE, calculate(PaceCalculationMode.DURATION, Double.MAX_VALUE, pace = 1000.0)!!.seconds)
        assertNull(calculate(PaceCalculationMode.PACE, Double.MIN_VALUE, Double.MAX_VALUE))
        assertNull(calculate(PaceCalculationMode.DURATION, Double.MAX_VALUE, pace = Double.MAX_VALUE))
        assertNull(calculate(PaceCalculationMode.DISTANCE, time = Double.MAX_VALUE, pace = Double.MIN_VALUE))
        assertNull(calculate(PaceCalculationMode.DURATION, Double.MIN_VALUE, pace = Double.MIN_VALUE))
    }

    @Test fun `second boundary and ties use same even rounding as existing pace display`() {
        listOf(359.49 to 359, 359.5 to 360, 360.5 to 360, 361.5 to 362).forEach { (seconds, rounded) ->
            val result = calculate(PaceCalculationMode.PACE, 1000.0, seconds)!!
            assertEquals(BigDecimal(rounded), result.roundedPace)
            assertEquals(rounded, result.stepFor(distanceStep)!!.paceSeconds)
        }
        assertEquals("6:00", paceText(359.5))
        assertEquals(BigDecimal(3600), calculate(PaceCalculationMode.DURATION, 9998.75, pace = 360.0)!!.roundedDuration)
    }

    @Test fun `calculated pace outside plan range remains visible and cannot be rounded into range`() {
        listOf(119.99, 1800.01, 100000.0).forEach { pace ->
            val result = calculate(PaceCalculationMode.PACE, 1000.0, pace)!!
            assertEquals(pace, result.paceSeconds)
            assertNull(result.stepFor(distanceStep))
            assertNull(result.stepFor(timeStep))
        }
        listOf(120.0, 1800.0).forEach { assertNotNull(calculate(PaceCalculationMode.PACE, 1000.0, it)!!.stepFor(distanceStep)) }
    }

    @Test fun `display carries seconds minutes hours and shows results beyond supported plan pace`() {
        assertEquals("6:05", calculatorTimeText(365.0, pace = true))
        assertEquals("1:00", calculatorTimeText(59.5))
        assertEquals("1:00:00", calculatorTimeText(3599.5))
        assertEquals("3:20:00", calculatorTimeText(12000.0))
        assertEquals("60:00", calculatorTimeText(3600.0, pace = true))
        assertEquals("1666:40", calculatorTimeText(100000.0, pace = true))
        assertTrue(calculatorTimeText(Double.MAX_VALUE).contains(':'))
        assertEquals("—", calculatorTimeText(Double.NaN))
    }

    @Test fun `distance and time amount limits are checked for the selected kind only`() {
        val longDistance = calculate(PaceCalculationMode.DISTANCE, time = 12000.0, pace = 120.0)!!
        assertEquals(100000.0, longDistance.stepFor(distanceStep)!!.amount)
        assertEquals(12000.0, longDistance.stepFor(timeStep)!!.amount)
        val overTime = calculate(PaceCalculationMode.DURATION, 200000.0, pace = 360.0)!!
        assertEquals(72000.0, overTime.seconds)
        assertEquals(200000.0, overTime.stepFor(distanceStep)!!.amount)
        assertNull(overTime.stepFor(timeStep))
        val overDistance = calculate(PaceCalculationMode.DURATION, 200001.0, pace = 360.0)!!
        assertNull(overDistance.stepFor(distanceStep))
        val tiny = calculate(PaceCalculationMode.DURATION, 0.1, pace = 360.0)!!
        assertNotNull(tiny.stepFor(distanceStep))
        assertNull(tiny.stepFor(timeStep))
    }

    @Test fun `duration rounds after raw boundary validation without clamping`() {
        assertEquals(60.0, calculate(PaceCalculationMode.DURATION, 151.25, pace = 400.0)!!.stepFor(timeStep)!!.amount)
        assertEquals(61.0, calculate(PaceCalculationMode.DURATION, 168.1, pace = 360.0)!!.stepFor(timeStep)!!.amount)
        assertNull(calculate(PaceCalculationMode.DURATION, 33333.34, pace = 360.0)!!.stepFor(timeStep))
        assertNull(calculate(PaceCalculationMode.DURATION, 2.7, pace = 360.0)!!.stepFor(timeStep))
    }

    @Test fun `invalid result cannot become a valid candidate or throw during rounding`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, -1.0).forEach { invalid ->
            listOf(PaceCalculation(invalid, 3600.0, 360.0), PaceCalculation(10000.0, invalid, 360.0),
                PaceCalculation(10000.0, 3600.0, invalid)).forEach {
                assertNull(it.stepFor(distanceStep))
                assertNull(it.stepFor(timeStep))
            }
        }
    }

    @Test fun `common parsers enforce empty values clock parts overflow and decimal distance boundaries`() {
        assertEquals(3600, parseElapsed("1", "0", "0"))
        assertEquals(12000, parseElapsed("3", "20", "0"))
        listOf("", "-1", "NaN", "Infinity", "99999999999999999999999").forEach {
            assertNull(parseElapsed(it, "0", "1"))
            assertNull(parsePaceParts(it, "0"))
            assertNull(parseDistance(it, DistanceUnit.KILOMETERS))
        }
        assertNull(parseElapsed("0", "60", "0"))
        assertNull(parseElapsed("1", "0", "60"))
        assertNull(parseElapsed("3", "20", "1"))
        assertNull(parsePaceParts("6", "60"))
        assertEquals(200000.0, parseDistance("200", DistanceUnit.KILOMETERS))
        assertNull(parseDistance("200.0001", DistanceUnit.KILOMETERS))
    }

    @Test fun `calculator restored input uses shared limits and integer time pace`() {
        val valid = PaceCalculatorDraft(null)
        assertNotNull(valid.result)
        listOf(null, 0.0, -1.0, 200000.1, Double.NaN, Double.POSITIVE_INFINITY).forEach { assertNull(valid.copy(meters = it).result) }
        listOf(null, 0.0, -1.0, 1.5, 12001.0).forEach { assertNull(valid.copy(seconds = it).result) }
        listOf(null, 119.0, 1801.0, 360.5).forEach { assertNull(valid.copy(mode = PaceCalculationMode.DISTANCE, paceSeconds = it).result) }
        assertNotNull(valid.copy(mode = PaceCalculationMode.DURATION, seconds = null).result)
    }

    @Test fun `opening from fractional saved step does not change original and rejects out of input range`() {
        val original = plan()
        val session = PaceCalculatorDraft.forRow(original.rows[1])
        assertEquals(600.0, session.seconds)
        assertEquals(timeStep, original.rows[1].step)
        val long = PlanEditorRow(step = distanceStep.copy(amount = 200000.0))
        assertNull(PaceCalculatorDraft.forRow(long).seconds)
        val malformed = PlanEditorRow(step = distanceStep.copy(amount = Double.MAX_VALUE))
        assertNull(PaceCalculatorDraft.forRow(malformed).seconds)
    }

    @Test fun `apply only selected stable id after moving cloning repeating and other edits`() {
        val original = plan()
        val id = original.rows[0].id
        val session = PaceCalculatorDraft(id, meters = 5000.0, seconds = 1825.0)
        val reordered = original.clone(id).repeat(2).move(id, 7).copy(name = "새 이름")
        val applied = session.apply(reordered)!!
        assertEquals(PlanStep("거리", StepKind.DISTANCE, 5000.0, 365), applied.rows.single { it.id == id }.step)
        assertEquals(reordered.rows.filterNot { it.id == id }, applied.rows.filterNot { it.id == id })
        assertEquals("새 이름", applied.name)
        assertEquals(original.original, applied.original)
        assertEquals(distanceStep, original.rows[0].step)
    }

    @Test fun `deleted selection is not retargeted to next row or clone`() {
        val original = plan()
        val id = original.rows[0].id
        val session = PaceCalculatorDraft(id)
        val removed = original.clone(id).remove(id)
        assertNull(session.selected(removed))
        assertNull(session.candidate(removed))
        assertNull(session.apply(removed))
        assertNull(session.copy(selectedRowId = null).apply(original))
        assertNull(session.apply(original.copy(rows = listOf(original.rows[0], original.rows[0]))))
    }

    @Test fun `current kind and name are used when selected row changes during calculation`() {
        val original = plan()
        val row = original.rows[0]
        val session = PaceCalculatorDraft(row.id, meters = 5000.0, seconds = 1825.0)
        val changed = original.edit(row.id, row.step.copy(name = "시간으로 전환", kind = StepKind.TIME, amount = 600.0))
        assertEquals(PlanStep("시간으로 전환", StepKind.TIME, 1825.0, 365), session.candidate(changed))
        assertEquals(changed.rows[1], session.apply(changed)!!.rows[1])
    }

    @Test fun `cancel calculation preserves draft and application undo restores all rows`() {
        val original = plan().copy(name = "미저장 이름")
        val snapshot = codec.encodeToString(original)
        val session = PaceCalculatorDraft(original.rows[1].id)
        session.copy(seconds = 1825.0, meters = 5000.0).result
        assertEquals(snapshot, codec.encodeToString(original))
        val applied = session.apply(original)!!
        assertTrue(applied.dirty)
        assertEquals(original, codec.decodeFromString<PlanEditorDraft>(snapshot))
        assertEquals(original.original, applied.original)
    }

    @Test fun `applied quantities and rounded pace agree with plan estimates and weighted totals`() {
        val original = plan()
        val session = PaceCalculatorDraft(original.rows[1].id, meters = 5000.0, seconds = 1825.0)
        val applied = session.apply(original)!!
        assertEquals(5000.0, applied.rows[1].step.expectedMeters)
        assertEquals(1825.0, applied.rows[1].step.expectedSeconds)
        assertEquals(7000.0, applied.plan.meters)
        assertEquals(2605.0, applied.plan.seconds)
        assertEquals(2605.0 / 7, applied.plan.averagePace!!)
        val fractional = session.copy(meters = 5000.0, seconds = 1827.0)
        val rounded = fractional.apply(original)!!.rows[1].step
        assertEquals(365, rounded.paceSeconds)
        assertEquals(1827.0 / 365 * 1000, rounded.expectedMeters)
        assertEquals(1827.0, rounded.expectedSeconds)
    }

    @Test fun `session and edited plan survive serialization save and reopening`() {
        val original = plan()
        val session = PaceCalculatorDraft(original.rows[0].id, mode = PaceCalculationMode.DURATION, meters = 21097.5, seconds = null, paceSeconds = 360.0)
        val restored = codec.decodeFromString<PaceCalculatorDraft>(codec.encodeToString(session))
        assertEquals(session, restored)
        val applied = restored.apply(original)!!
        val draftRestored = codec.decodeFromString<PlanEditorDraft>(codec.encodeToString(applied))
        assertEquals(applied, draftRestored)
        val saved = codec.decodeFromString<WorkoutPlan>(codec.encodeToString(draftRestored.plan))
        assertEquals(21097.5, PlanEditorDraft(saved).rows[0].step.amount)
        assertEquals(360, saved.steps[0].paceSeconds)
        assertFalse(PlanEditorDraft(saved).dirty)
        assertEquals(original.original, original.plan)
        assertTrue(applied.valid)
    }
}
