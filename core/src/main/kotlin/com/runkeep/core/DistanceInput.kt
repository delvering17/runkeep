package com.runkeep.core

import java.math.BigDecimal

private val distanceDraftPattern = Regex("[0-9]*(?:[.,][0-9]*)?")

/** Reject the entire edit instead of turning signed, exponential or unit text into another number. */
fun acceptDistanceEdit(current: String, proposed: String): String =
    if (proposed.matches(distanceDraftPattern)) proposed else current

/** Decimal text is kept exact across unit changes, including values outside the allowed range. */
fun convertDistanceText(text: String, from: DistanceUnit, to: DistanceUnit): String {
    if (from == to || !text.matches(distanceDraftPattern)) return text
    val decimal = text.replace(',', '.').toBigDecimalOrNull() ?: return text
    return decimal.movePointRight(if (from == DistanceUnit.KILOMETERS) 3 else -3)
        .stripTrailingZeros().toPlainString()
}

/** Applied values and presets are stored in metres. Never divide their Double before formatting. */
fun distanceInputText(meters: Double, unit: DistanceUnit): String =
    BigDecimal.valueOf(meters).movePointLeft(if (unit == DistanceUnit.KILOMETERS) 3 else 0)
        .stripTrailingZeros().toPlainString()

enum class DistancePreset(val meters: Double) {
    ONE_KILOMETER(1_000.0), FIVE_KILOMETERS(5_000.0), TEN_KILOMETERS(10_000.0),
    TWENTY_KILOMETERS(20_000.0), HALF_MARATHON(21_097.5), MARATHON(42_195.0)
}
