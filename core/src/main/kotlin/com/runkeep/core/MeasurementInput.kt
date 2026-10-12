package com.runkeep.core

/** Values at UI / persistence boundaries use metres, elapsed seconds and seconds/km. */
object MeasurementLimits {
    const val MIN_PACE_SECONDS = 120
    const val MAX_PACE_SECONDS = 1800
    const val MAX_DISTANCE_METERS = 200_000.0
    const val MAX_STEP_SECONDS = 12_000
    fun pace(seconds: Int) = seconds in MIN_PACE_SECONDS..MAX_PACE_SECONDS
    fun distance(meters: Double, minimum: Double = Double.MIN_VALUE) =
        meters.isFinite() && meters > 0 && meters >= minimum && meters <= MAX_DISTANCE_METERS
    fun duration(seconds: Int, maximum: Int = MAX_STEP_SECONDS) = seconds in 1..maximum
    fun amount(kind: StepKind, amount: Double) = when (kind) {
        StepKind.DISTANCE -> distance(amount)
        StepKind.TIME -> amount.isFinite() && amount > 0 && amount <= MAX_STEP_SECONDS
    }
    fun step(step: PlanStep) = pace(step.paceSeconds) && amount(step.kind, step.amount)
}

enum class DistanceUnit(val meters: Double, val label: String) { KILOMETERS(1000.0, "km"), METERS(1.0, "m") }

/** Accept a decimal comma from locale keyboards; reject exponent / infinity / signs. */
fun parseDistance(text: String, unit: DistanceUnit, minimum: Double = Double.MIN_VALUE): Double? {
    val number = text.trim().replace(',', '.')
    if (!number.matches(Regex("(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)"))) return null
    return number.toBigDecimalOrNull()?.movePointRight(if (unit == DistanceUnit.KILOMETERS) 3 else 0)
        ?.toDouble()?.takeIf { MeasurementLimits.distance(it, minimum) }
}

/** Elapsed time has no clock / AM-PM semantics. No overflow or second rollover. */
fun parseElapsed(hours: String, minutes: String, seconds: String, maximum: Int = MeasurementLimits.MAX_STEP_SECONDS): Int? {
    fun part(text: String): Long? = text.takeIf { it.matches(Regex("[0-9]+")) }?.toLongOrNull()
    val h = part(hours) ?: return null
    val m = part(minutes) ?: return null
    val s = part(seconds) ?: return null
    if (h > maximum / 3600 || m !in 0..59 || s !in 0..59) return null
    return (h * 3600 + m * 60 + s).takeIf { it in 1..maximum.toLong() }?.toInt()
}

fun parsePaceParts(minutes: String, seconds: String): Int? {
    val m = minutes.takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull() ?: return null
    val s = seconds.takeIf { it.matches(Regex("[0-9]+")) }?.toIntOrNull() ?: return null
    if (m !in 0..30 || s !in 0..59) return null
    return (m * 60 + s).takeIf(MeasurementLimits::pace)
}

fun parsePace(text: String): Int? {
    val parts = text.trim().split(':')
    return if (parts.size == 2) parsePaceParts(parts[0], parts[1]) else null
}

/** Immutable transaction: editing / cancelling never mutates the caller's applied value. */
data class InputDraft<T>(val original: T, val candidate: T = original) {
    fun edit(value: T) = copy(candidate = value)
    fun cancel(): T = original
    fun confirm(isValid: (T) -> Boolean): T? = candidate.takeIf(isValid)
}
