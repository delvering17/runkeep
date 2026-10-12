package com.runkeep.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlinx.serialization.Serializable

@Serializable enum class PaceCalculationMode { PACE, DURATION, DISTANCE }

data class PaceCalculation(val meters: Double, val seconds: Double, val paceSeconds: Double) {
    val roundedPace get() = rounded(paceSeconds)
    val roundedDuration get() = rounded(seconds)

    fun stepFor(step: PlanStep): PlanStep? {
        if (!meters.isFinite() || meters <= 0 || !seconds.isFinite() || seconds <= 0) return null
        if (!paceSeconds.isFinite() || paceSeconds < MeasurementLimits.MIN_PACE_SECONDS ||
            paceSeconds > MeasurementLimits.MAX_PACE_SECONDS) return null
        val pace = roundedPace.toInt()
        val amount = if (step.kind == StepKind.DISTANCE) meters else roundedDuration.toDouble()
        if (!MeasurementLimits.pace(pace) || !MeasurementLimits.amount(step.kind, amount)) return null
        if (step.kind == StepKind.TIME && seconds !in 1.0..MeasurementLimits.MAX_STEP_SECONDS.toDouble()) return null
        return step.copy(amount = amount, paceSeconds = pace)
    }

    companion object {
        fun rounded(value: Double): BigDecimal = BigDecimal.valueOf(value).setScale(0, RoundingMode.HALF_EVEN)
    }
}

object PaceCalculator {
    fun calculate(mode: PaceCalculationMode, meters: Double?, seconds: Double?, paceSeconds: Double?): PaceCalculation? {
        fun decimal(value: Double?) = value?.takeIf { it.isFinite() && it > 0 }?.let(BigDecimal::valueOf)
        val thousand = BigDecimal(1000)
        val context = MathContext.DECIMAL128
        val values = when (mode) {
            PaceCalculationMode.PACE -> {
                val distance = decimal(meters) ?: return null
                val duration = decimal(seconds) ?: return null
                Triple(distance, duration, duration.multiply(thousand).divide(distance, context))
            }
            PaceCalculationMode.DURATION -> {
                val distance = decimal(meters) ?: return null
                val pace = decimal(paceSeconds) ?: return null
                Triple(distance, distance.multiply(pace).divide(thousand, context), pace)
            }
            PaceCalculationMode.DISTANCE -> {
                val duration = decimal(seconds) ?: return null
                val pace = decimal(paceSeconds) ?: return null
                Triple(duration.multiply(thousand).divide(pace, context), duration, pace)
            }
        }
        val result = PaceCalculation(values.first.toDouble(), values.second.toDouble(), values.third.toDouble())
        return result.takeIf { listOf(it.meters, it.seconds, it.paceSeconds).all { value -> value.isFinite() && value > 0 } }
    }
}

fun calculatorTimeText(seconds: Double, pace: Boolean = false): String {
    if (!seconds.isFinite() || seconds <= 0) return "—"
    val whole = PaceCalculation.rounded(seconds)
    val sixty = BigDecimal(60)
    val minutes = whole.divideToIntegralValue(sixty)
    val second = whole.remainder(sixty).toPlainString().padStart(2, '0')
    if (pace || whole < BigDecimal(3600)) return "${minutes.toPlainString()}:$second"
    return "${whole.divideToIntegralValue(BigDecimal(3600)).toPlainString()}:${minutes.remainder(sixty).toPlainString().padStart(2, '0')}:$second"
}

@Serializable data class PaceCalculatorDraft(
    val selectedRowId: String?,
    val mode: PaceCalculationMode = PaceCalculationMode.PACE,
    val meters: Double? = 10_000.0,
    val seconds: Double? = 3600.0,
    val paceSeconds: Double? = 360.0
) {
    val result get() = if (inputsValid) PaceCalculator.calculate(mode, meters, seconds, paceSeconds) else null
    val inputsValid get() = when (mode) {
        PaceCalculationMode.PACE -> distanceValid && durationValid
        PaceCalculationMode.DURATION -> distanceValid && paceValid
        PaceCalculationMode.DISTANCE -> durationValid && paceValid
    }
    private val distanceValid get() = meters?.let { MeasurementLimits.distance(it) } == true
    private val durationValid get() = seconds?.let { it.isFinite() && it in 1.0..MeasurementLimits.MAX_STEP_SECONDS.toDouble() && it % 1.0 == 0.0 } == true
    private val paceValid get() = paceSeconds?.let { it.isFinite() && it in MeasurementLimits.MIN_PACE_SECONDS.toDouble()..MeasurementLimits.MAX_PACE_SECONDS.toDouble() && it % 1.0 == 0.0 } == true

    fun selected(draft: PlanEditorDraft) = draft.rows.singleOrNull { it.id == selectedRowId }
    fun candidate(draft: PlanEditorDraft) = selected(draft)?.step?.let { result?.stepFor(it) }
    fun apply(draft: PlanEditorDraft): PlanEditorDraft? {
        val id = selectedRowId ?: return null
        val step = candidate(draft) ?: return null
        return draft.edit(id, step)
    }

    companion object {
        fun forRow(row: PlanEditorRow?) = if (row == null) PaceCalculatorDraft(null) else PaceCalculatorDraft(
            selectedRowId = row.id,
            meters = row.step.expectedMeters.takeIf { MeasurementLimits.distance(it) },
            seconds = row.step.expectedSeconds.takeIf { it.isFinite() && it > 0 }?.let { PaceCalculation.rounded(it).toDouble() }
                ?.takeIf { it in 1.0..MeasurementLimits.MAX_STEP_SECONDS.toDouble() },
            paceSeconds = row.step.paceSeconds.toDouble()
        )
    }
}
